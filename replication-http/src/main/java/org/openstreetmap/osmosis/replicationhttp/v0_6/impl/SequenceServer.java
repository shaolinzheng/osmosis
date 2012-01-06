// This software is released into the Public Domain.  See copying.txt for details.
package org.openstreetmap.osmosis.replicationhttp.v0_6.impl;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.jboss.netty.bootstrap.ServerBootstrap;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFactory;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.group.ChannelGroup;
import org.jboss.netty.channel.group.DefaultChannelGroup;
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory;
import org.openstreetmap.osmosis.core.OsmosisRuntimeException;


/**
 * This class creates a HTTP server that sends updated replication sequences to
 * clients. Once started it is notified of updated sequence numbers as they
 * occur and will pass the sequence data to listening clients. The sequence data
 * is implementation dependent.
 * 
 * @author Brett Henderson
 */
public class SequenceServer implements SequenceServerControl {

	private int port;
	private SequenceServerChannelPipelineFactory channelPipelineFactory;
	/**
	 * Limits shared data access to one thread at a time.
	 */
	private Lock sharedLock;
	/**
	 * A flag used to remember if the server has been started or not.
	 */
	private boolean serverStarted;
	private long sequenceNumber;
	private ChannelFactory factory;
	private ChannelGroup allChannels;
	private List<Channel> waitingChannels;


	/**
	 * Creates a new instance.
	 * 
	 * @param port
	 *            The port number to listen on.
	 * @param channelPipelineFactory
	 *            The factory for creating channel pipelines for new client
	 *            connections.
	 */
	public SequenceServer(int port, SequenceServerChannelPipelineFactory channelPipelineFactory) {
		this.port = port;
		this.channelPipelineFactory = channelPipelineFactory;

		// Provide handlers with access to control functions.
		channelPipelineFactory.setControl(this);

		// Create the thread synchronisation primitives.
		sharedLock = new ReentrantLock();

		// Create the list of channels waiting to be notified about a new
		// sequence.
		waitingChannels = new ArrayList<Channel>();
	}


	/**
	 * Starts the server.
	 * 
	 * @param initialSequenceNumber
	 *            The initial sequence number.
	 */
	public void start(long initialSequenceNumber) {
		sharedLock.lock();

		try {
			if (serverStarted) {
				throw new OsmosisRuntimeException("The server has already been started");
			}

			sequenceNumber = initialSequenceNumber;

			// Create a channel group to hold all channels for use during
			// shutdown.
			allChannels = new DefaultChannelGroup("sequence-server");

			// Create the processing thread pools.
			factory = new NioServerSocketChannelFactory(Executors.newCachedThreadPool(),
					Executors.newCachedThreadPool());

			// Launch the server.
			ServerBootstrap bootstrap = new ServerBootstrap(factory);
			bootstrap.setPipelineFactory(channelPipelineFactory);
			bootstrap.setOption("child.tcpNoDelay", true);
			bootstrap.setOption("child.keepAlive", true);
			allChannels.add(bootstrap.bind(new InetSocketAddress(port)));

			// Server startup has succeeded.
			serverStarted = true;

		} finally {
			sharedLock.unlock();
		}
	}


	/**
	 * Notifies that server of a new sequence number.
	 * 
	 * @param newSequenceNumber
	 *            The new sequence number.
	 */
	public void update(long newSequenceNumber) {
		sharedLock.lock();
		try {
			if (!serverStarted) {
				throw new OsmosisRuntimeException("The server has not been started");
			}

			this.sequenceNumber = newSequenceNumber;

			/*
			 * Create a new waiting channels list and process from the original.
			 * This is necessary because some channels may get added back in
			 * during processing causing a concurrent modification exception.
			 * Due to the Netty implementation, if a write operation completes
			 * before we get a chance to register the completion listener, the
			 * listener will run within this thread and that will mean the
			 * channel will need to be added to the waiting list before we
			 * complete sending messages to all the other channels.
			 */
			List<Channel> existingWaitingChannels = waitingChannels;
			waitingChannels = new ArrayList<Channel>();
			for (Channel channel : existingWaitingChannels) {
				sendSequence(channel, sequenceNumber, true);
			}

		} finally {
			sharedLock.unlock();
		}
	}


	/**
	 * Stops the server.
	 */
	public void stop() {
		sharedLock.lock();

		try {
			if (serverStarted) {
				allChannels.close().awaitUninterruptibly();
				factory.releaseExternalResources();

				// Clear our control flag.
				serverStarted = false;
			}
		} finally {
			sharedLock.unlock();
		}
	}


	/**
	 * Sends the specified sequence to the channel. If follow is specified, the
	 * channel will be held open and follow up calls will be made to
	 * determineNextChannelAction with this channel and sequence number when the
	 * operation completes. If follow is not specified, the channel will be
	 * closed when the operation completes.
	 * 
	 * @param channel
	 *            The channel.
	 * @param currentSequenceNumber
	 *            The sequence to be sent.
	 * @param follow
	 *            If true, the channel will be held open and updated sequences
	 *            sent as they are arrive.
	 */
	private void sendSequence(final Channel channel, final long currentSequenceNumber, final boolean follow) {
		// Write the sequence number to the channel.
		ChannelFuture future = channel.write(currentSequenceNumber);

		if (follow) {
			// Upon completion of this write, check to see whether a new
			// sequence must be sent or whether we should wait for further
			// updates.
			future.addListener(new ChannelFutureListener() {
				@Override
				public void operationComplete(ChannelFuture future) throws Exception {
					// Only send more data if the write was successful.
					if (future.isSuccess()) {
						determineNextChannelAction(channel, currentSequenceNumber + 1, follow);
					}
				}
			});
		} else {
			// Upon completion of this write, close the channel.
			future.addListener(new ChannelFutureListener() {
				@Override
				public void operationComplete(ChannelFuture future) throws Exception {
					channel.close();
				}
			});
		}
	}


	/**
	 * Allows a Netty handler to notify the controller that the channel is ready
	 * for more data. If the controller has new sequence information available
	 * it will send it, otherwise it will add the channel to the waiting list.
	 * 
	 * @param channel
	 *            The client channel.
	 * @param nextSequenceNumber
	 *            The sequence number that the client needs to be sent next.
	 * @param follow
	 *            If true, the channel will be held open and updated sequences
	 *            sent as they arrive.
	 */
	public void determineNextChannelAction(Channel channel, long nextSequenceNumber, boolean follow) {
		long currentSequenceNumber;
		boolean sequenceAvailable;

		// We can only access the master sequence number and waiting channels
		// while we have the lock
		sharedLock.lock();
		try {
			currentSequenceNumber = sequenceNumber;

			// Check if the next sequence number is available yet.
			sequenceAvailable = nextSequenceNumber <= currentSequenceNumber;
			
			// If the sequence is not available, make sure that the client
			// hasn't requested a sequence number more than one past current.
			if (!sequenceAvailable) {
				if ((nextSequenceNumber - currentSequenceNumber) > 1) {
					channel.close();
					throw new OsmosisRuntimeException("Requested sequence number " + nextSequenceNumber
							+ " is more than 1 past current number " + currentSequenceNumber);
				}
			}
			
			// If the sequence isn't available we add the channel to the list waiting for a new sequence notification.
			if (!sequenceAvailable) {
				waitingChannels.add(channel);
			}
		} finally {
			sharedLock.unlock();
		}

		// Send the sequence if it is available.
		if (sequenceAvailable) {
			sendSequence(channel, nextSequenceNumber, follow);
		}
	}


	@Override
	public long getLatestSequenceNumber() {
		// Get the current sequence number within the lock.
		sharedLock.lock();
		try {
			return sequenceNumber;
		} finally {
			sharedLock.unlock();
		}
	}


	@Override
	public void registerChannel(Channel channel) {
		allChannels.add(channel);
	}
}
