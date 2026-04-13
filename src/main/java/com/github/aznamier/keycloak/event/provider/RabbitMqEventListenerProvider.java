package com.github.aznamier.keycloak.event.provider;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import org.jboss.logging.Logger;
import org.keycloak.events.Event;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.EventListenerTransaction;
import org.keycloak.events.admin.AdminEvent;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.AMQP.BasicProperties;
import com.rabbitmq.client.AMQP.BasicProperties.Builder;

public class RabbitMqEventListenerProvider implements EventListenerProvider {

	private static final Logger log = Logger.getLogger(RabbitMqEventListenerProvider.class);
	
	private final RabbitMqConfig cfg;
	private final RabbitMqConnectionManager connectionManager;

	private final KeycloakSession session;

	private final EventListenerTransaction tx = new EventListenerTransaction(this::publishAdminEvent, this::publishEvent);

	public RabbitMqEventListenerProvider(RabbitMqConnectionManager connectionManager, KeycloakSession session, RabbitMqConfig cfg) {
		this.cfg = cfg;
		this.connectionManager = connectionManager;
		this.session = session;
		session.getTransactionManager().enlistAfterCompletion(tx);
	}

	@Override
	public void close() {

	}

	@Override
	public void onEvent(Event event) {
		tx.addEvent(event.clone());
	}

	@Override
	public void onEvent(AdminEvent adminEvent, boolean includeRepresentation) {
		tx.addAdminEvent(adminEvent, includeRepresentation);
	}
	
	private void publishEvent(Event event) {
		RabbitMqConfig realmConfig = resolveRealmConfig(event.getRealmId());
		EventClientNotificationMqMsg msg = EventClientNotificationMqMsg.create(event);
		String routingKey = RabbitMqConfig.calculateRoutingKey(event, session);
		String messageString = RabbitMqConfig.writeAsJson(msg, true);
		
		BasicProperties msgProps = RabbitMqEventListenerProvider.getMessageProps(EventClientNotificationMqMsg.class.getName());
		this.publishNotification(realmConfig, messageString, msgProps, routingKey);
	}
	
	private void publishAdminEvent(AdminEvent adminEvent, boolean includeRepresentation) {
		RabbitMqConfig realmConfig = resolveRealmConfig(adminEvent.getRealmId());
		EventAdminNotificationMqMsg msg = EventAdminNotificationMqMsg.create(adminEvent);
		String routingKey = RabbitMqConfig.calculateRoutingKey(adminEvent, session);
		String messageString = RabbitMqConfig.writeAsJson(msg, true);

		BasicProperties msgProps = RabbitMqEventListenerProvider.getMessageProps(EventAdminNotificationMqMsg.class.getName());
		this.publishNotification(realmConfig, messageString,msgProps, routingKey);
	}
	
	private static BasicProperties getMessageProps(String className) {
		
		Map<String,Object> headers = new HashMap<>();
		headers.put("__TypeId__", className);
		
		Builder propsBuilder = new AMQP.BasicProperties.Builder()
				.appId("Keycloak")
				.headers(headers)
				.contentType("application/json")
				.contentEncoding("UTF-8");
		return propsBuilder.build();
	}

	private void publishNotification(RabbitMqConfig realmConfig, String messageString, BasicProperties props, String routingKey) {
		try {
			connectionManager.publish(realmConfig, routingKey, props, messageString.getBytes(StandardCharsets.UTF_8));
			log.tracef("keycloak-to-rabbitmq SUCCESS sending message: %s%n", routingKey);
		} catch (Exception ex) {
			log.errorf(ex, "keycloak-to-rabbitmq ERROR sending message: %s%n", routingKey);
		}
	}

	private RabbitMqConfig resolveRealmConfig(String realmId) {
		RealmModel realm = null;
		if (realmId != null) {
			realm = session.realms().getRealm(realmId);
		}
		if (realm == null) {
			realm = session.getContext().getRealm();
		}
		return RabbitMqConfig.resolveForRealm(cfg, realm);
	}

}