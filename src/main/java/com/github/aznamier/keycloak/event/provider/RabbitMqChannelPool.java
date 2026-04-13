package com.github.aznamier.keycloak.event.provider;

import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeoutException;

import org.jboss.logging.Logger;

import com.rabbitmq.client.AMQP.BasicProperties;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;

public class RabbitMqChannelPool {

	private static final Logger log = Logger.getLogger(RabbitMqChannelPool.class);

	private final RabbitMqConfig config;
	private final ConnectionFactory connectionFactory;
	private final BlockingQueue<Channel> idleChannels;
	private final Semaphore permits;
	private final Object connectionLock = new Object();

	private volatile Connection connection;

	public RabbitMqChannelPool(RabbitMqConfig config) {
		this.config = config;
		this.connectionFactory = config.createConnectionFactory();
		this.idleChannels = new LinkedBlockingQueue<>();
		this.permits = new Semaphore(Math.max(1, config.getChannelPoolSize()));
	}

	public void publish(String exchange, String routingKey, BasicProperties properties, byte[] body) throws IOException {
		Channel channel = null;
		boolean reusable = false;
		try {
			channel = borrowChannel();
			channel.basicPublish(exchange, routingKey, properties, body);
			reusable = true;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while waiting for a RabbitMQ channel", e);
		} catch (TimeoutException e) {
			throw new IOException("Failed to create a RabbitMQ channel", e);
		} finally {
			releaseChannel(channel, reusable);
		}
	}

	public void close() {
		Channel channel = idleChannels.poll();
		while (channel != null) {
			closeQuietly(channel);
			channel = idleChannels.poll();
		}

		Connection currentConnection = connection;
		connection = null;
		if (currentConnection != null) {
			try {
				currentConnection.close();
			} catch (IOException e) {
				log.error("keycloak-to-rabbitmq ERROR on close", e);
			}
		}
	}

	private Channel borrowChannel() throws IOException, TimeoutException, InterruptedException {
		permits.acquire();
		boolean borrowed = false;
		try {
			Channel channel = idleChannels.poll();
			while (channel != null && !channel.isOpen()) {
				closeQuietly(channel);
				channel = idleChannels.poll();
			}

			if (channel == null) {
				channel = getOrCreateConnection().createChannel();
			}

			borrowed = true;
			return channel;
		} finally {
			if (!borrowed) {
				permits.release();
			}
		}
	}

	private Connection getOrCreateConnection() throws IOException, TimeoutException {
		Connection currentConnection = connection;
		if (currentConnection != null && currentConnection.isOpen()) {
			return currentConnection;
		}

		synchronized (connectionLock) {
			currentConnection = connection;
			if (currentConnection == null || !currentConnection.isOpen()) {
				connection = connectionFactory.newConnection();
				log.infof(
						"keycloak-to-rabbitmq opened connection pool for host=%s port=%d vhost=%s user=%s maxChannels=%d",
						config.getHostUrl(),
						config.getPort(),
						config.getVhost(),
						config.getUsername(),
						config.getChannelPoolSize());
			}
			return connection;
		}
	}

	private void releaseChannel(Channel channel, boolean reusable) {
		if (channel == null) {
			return;
		}

		try {
			if (reusable && channel.isOpen()) {
				idleChannels.offer(channel);
			} else {
				closeQuietly(channel);
			}
		} finally {
			permits.release();
		}
	}

	private void closeQuietly(Channel channel) {
		if (channel == null) {
			return;
		}

		try {
			if (channel.isOpen()) {
				channel.close();
			}
		} catch (Exception e) {
			log.debug("Ignoring channel close error", e);
		}
	}
}