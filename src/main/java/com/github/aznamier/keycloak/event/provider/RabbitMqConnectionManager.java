package com.github.aznamier.keycloak.event.provider;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.rabbitmq.client.AMQP.BasicProperties;

public class RabbitMqConnectionManager {

	private final Map<String, RabbitMqChannelPool> pools = new ConcurrentHashMap<>();

	public void publish(RabbitMqConfig config, String routingKey, BasicProperties properties, byte[] body) throws IOException {
		RabbitMqChannelPool pool = pools.computeIfAbsent(config.getConnectionCacheKey(), key -> new RabbitMqChannelPool(config));
		pool.publish(config.getExchange(), routingKey, properties, body);
	}

	public void close() {
		for (RabbitMqChannelPool pool : pools.values()) {
			pool.close();
		}
		pools.clear();
	}
}