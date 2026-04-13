package com.github.aznamier.keycloak.event.provider;

import org.keycloak.Config.Scope;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.EventListenerProviderFactory;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;

public class RabbitMqEventListenerProviderFactory implements EventListenerProviderFactory {

    private RabbitMqConfig cfg;
    private RabbitMqConnectionManager connectionManager;

    @Override
    public EventListenerProvider create(KeycloakSession session) {
        return new RabbitMqEventListenerProvider(connectionManager, session, cfg);
    }

    @Override
    public void init(Scope config) {
        cfg = RabbitMqConfig.createFromScope(config);
        connectionManager = new RabbitMqConnectionManager();
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {

    }

    @Override
    public void close() {
        if (connectionManager != null) {
            connectionManager.close();
        }
    }

    @Override
    public String getId() {
        return "keycloak-to-rabbitmq";
    }

}
