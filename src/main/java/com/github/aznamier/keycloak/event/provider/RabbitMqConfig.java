package com.github.aznamier.keycloak.event.provider;


import org.jboss.logging.Logger;
import org.keycloak.Config.Scope;
import org.keycloak.events.Event;
import org.keycloak.events.admin.AdminEvent;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.util.JsonSerialization;

import com.rabbitmq.client.ConnectionFactory;

import java.io.FileInputStream;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

import java.security.KeyStore;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;


public class RabbitMqConfig {

	private static final Logger log = Logger.getLogger(RabbitMqConfig.class);
	public static final String ROUTING_KEY_PREFIX = "KK.EVENT";
	public static final String REALM_ATTRIBUTE_PREFIX = "kk.to.rmq.";
	public static final String ALLOW_REALM_RMQ_CREDENTIALS_ENV = "ALLOW_REALM_RMQ_CREDENTIALS";
	private static final Pattern SPECIAL_CHARACTERS = Pattern.compile("[^*#a-zA-Z0-9 _.-]");
	private static final Pattern SPACE = Pattern.compile(" ");
	private static final Pattern DOT = Pattern.compile("\\.");
	private static final Pattern ENV_REALM_NAME = Pattern.compile("[^A-Z0-9]");

	private String hostUrl;
	private Integer port;
	private String username;
	private String password;
	private String vhost;
	private Boolean useTls;

	// SSL context settings
	private String trustStore;
	private String trustStorePass;
	private String keyStore;
	private String keyStorePass;
	//


	private String exchange;
	private Integer channelPoolSize;
	private Boolean allowRealmRmqCredentials;

	public RabbitMqConfig() {
	}

	private RabbitMqConfig(RabbitMqConfig source) {
		this.hostUrl = source.hostUrl;
		this.port = source.port;
		this.username = source.username;
		this.password = source.password;
		this.vhost = source.vhost;
		this.useTls = source.useTls;
		this.trustStore = source.trustStore;
		this.trustStorePass = source.trustStorePass;
		this.keyStore = source.keyStore;
		this.keyStorePass = source.keyStorePass;
		this.exchange = source.exchange;
		this.channelPoolSize = source.channelPoolSize;
		this.allowRealmRmqCredentials = source.allowRealmRmqCredentials;
	}
	
	public static String calculateRoutingKey(AdminEvent adminEvent, KeycloakSession session) {
		//KK.EVENT.ADMIN.<REALM>.<RESULT>.<RESOURCE_TYPE>.<OPERATION>
		String realmName = resolveRealmName(session, adminEvent.getRealmId());
		String routingKey = ROUTING_KEY_PREFIX
				+ ".ADMIN"
				+ "." + removeDots(realmName)
				+ "." + (adminEvent.getError() != null ? "ERROR" : "SUCCESS")
				+ "." + adminEvent.getResourceTypeAsString()
				+ "." + adminEvent.getOperationType().toString()
				
				;
		return normalizeKey(routingKey);
	}
	
	public static String calculateRoutingKey(Event event, KeycloakSession session) {
		//KK.EVENT.CLIENT.<REALM>.<RESULT>.<CLIENT>.<EVENT_TYPE>
		String realmName = resolveRealmName(session, event.getRealmId());
		String routingKey = ROUTING_KEY_PREFIX
					+ ".CLIENT"
					+ "." + removeDots(realmName)
					+ "." + (event.getError() != null ? "ERROR" : "SUCCESS")
					+ "." + removeDots(event.getClientId())
					+ "." + event.getType();
		
		return normalizeKey(routingKey);
	}

	//Remove all characters apart a-z, A-Z, 0-9, space, underscore, replace all spaces and hyphens with underscore
	public static String normalizeKey(CharSequence stringToNormalize) {
		return SPACE.matcher(SPECIAL_CHARACTERS.matcher(stringToNormalize).replaceAll(""))
				.replaceAll("_");
	}
	
	public static String removeDots(String stringToNormalize) {
		if(stringToNormalize != null) {
			return DOT.matcher(stringToNormalize).replaceAll("");
		}
		return stringToNormalize;
	}
	
	public static String writeAsJson(Object object, boolean isPretty) {
		try {
			if(isPretty) {
				return JsonSerialization.writeValueAsPrettyString(object);
			}
			return JsonSerialization.writeValueAsString(object);

		} catch (Exception e) {
			log.error("Could not serialize to JSON", e);
		}
		return "unparseable";
	}
	
	
	public static RabbitMqConfig createFromScope(Scope config) {
		RabbitMqConfig cfg = new RabbitMqConfig();
		
		cfg.hostUrl = resolveConfigVar(config, "url", "localhost");
		cfg.port = Integer.valueOf(resolveConfigVar(config, "port", "5672"));
		cfg.username = resolveConfigVar(config, "username", "admin");
		cfg.password = resolveConfigVar(config, "password", "admin");
		cfg.vhost = resolveConfigVar(config, "vhost", "");
		cfg.useTls = Boolean.valueOf(resolveConfigVar(config, "use_tls", "false"));

		// SSL context settings
		cfg.trustStore = resolveConfigVar(config, "trust_store", "");
		cfg.trustStorePass = resolveConfigVar(config, "trust_store_pass", "");
		cfg.keyStore = resolveConfigVar(config, "key_store", "");
		cfg.keyStorePass = resolveConfigVar(config, "key_store_pass", "");
		//

		cfg.exchange = resolveConfigVar(config, "exchange", "amq.topic");
		cfg.channelPoolSize = Integer.valueOf(resolveConfigVar(config, "channel_pool_size", "16"));
		cfg.allowRealmRmqCredentials = resolveAllowRealmRmqCredentials(config);
		return cfg;
		
	}

	public static RabbitMqConfig resolveForRealm(RabbitMqConfig defaults, RealmModel realm) {
		RabbitMqConfig cfg = new RabbitMqConfig(defaults);
		if (realm == null || !Boolean.TRUE.equals(defaults.getAllowRealmRmqCredentials())) {
			return cfg;
		}

		String realmName = realm.getName();
		cfg.hostUrl = resolveRealmOverride(realm, realmName, "url", cfg.hostUrl);
		cfg.port = Integer.valueOf(resolveRealmOverride(realm, realmName, "port", String.valueOf(cfg.port)));
		cfg.username = resolveRealmOverride(realm, realmName, "username", cfg.username);
		cfg.password = resolveRealmOverride(realm, realmName, "password", cfg.password);
		cfg.vhost = resolveRealmOverride(realm, realmName, "vhost", cfg.vhost);
		cfg.useTls = Boolean.valueOf(resolveRealmOverride(realm, realmName, "use_tls", String.valueOf(cfg.useTls)));
		cfg.trustStore = resolveRealmOverride(realm, realmName, "trust_store", cfg.trustStore);
		cfg.trustStorePass = resolveRealmOverride(realm, realmName, "trust_store_pass", cfg.trustStorePass);
		cfg.keyStore = resolveRealmOverride(realm, realmName, "key_store", cfg.keyStore);
		cfg.keyStorePass = resolveRealmOverride(realm, realmName, "key_store_pass", cfg.keyStorePass);
		cfg.exchange = resolveRealmOverride(realm, realmName, "exchange", cfg.exchange);
		cfg.channelPoolSize = Integer.valueOf(resolveRealmOverride(realm, realmName, "channel_pool_size", String.valueOf(cfg.channelPoolSize)));
		return cfg;
	}

	public ConnectionFactory createConnectionFactory() {
		ConnectionFactory connectionFactory = new ConnectionFactory();
		connectionFactory.setUsername(username);
		connectionFactory.setPassword(password);
		connectionFactory.setVirtualHost(vhost);
		connectionFactory.setHost(hostUrl);
		connectionFactory.setPort(port);
		connectionFactory.setAutomaticRecoveryEnabled(true);

		if (Boolean.TRUE.equals(useTls)) {
			configureTls(connectionFactory);
		}

		return connectionFactory;
	}

	public String getConnectionCacheKey() {
		return String.join("|",
				Objects.toString(hostUrl, ""),
				String.valueOf(port),
				Objects.toString(username, ""),
				Objects.toString(password, ""),
				Objects.toString(vhost, ""),
				String.valueOf(useTls),
				Objects.toString(trustStore, ""),
				Objects.toString(trustStorePass, ""),
				Objects.toString(keyStore, ""),
				Objects.toString(keyStorePass, ""),
				String.valueOf(channelPoolSize));
	}
	
	private static String resolveConfigVar(Scope config, String variableName, String defaultValue) {
		
		String value = defaultValue;
		if(config != null && config.get(variableName) != null) {
			value = config.get(variableName);
		} else {
			//try from env variables eg: KK_TO_RMQ_URL:
			String envVariableName = "KK_TO_RMQ_" + variableName.toUpperCase(Locale.ENGLISH);
			String env = System.getenv(envVariableName);
			if(env != null) {
				value = env;
			}
		}
		if (!"password".equals(variableName)) {
			log.infof("keycloak-to-rabbitmq configuration: %s=%s%n", variableName, value);
		}
		return value;
		
	}

	private static Boolean resolveAllowRealmRmqCredentials(Scope config) {
		String value = "false";
		if (config != null && config.get("allow_realm_rmq_credentials") != null) {
			value = config.get("allow_realm_rmq_credentials");
		} else {
			String env = System.getenv(ALLOW_REALM_RMQ_CREDENTIALS_ENV);
			if (env != null) {
				value = env;
			}
		}

		log.infof("keycloak-to-rabbitmq configuration: %s=%s%n", ALLOW_REALM_RMQ_CREDENTIALS_ENV, value);
		return Boolean.valueOf(value);
	}

	private static String resolveRealmOverride(RealmModel realm, String realmName, String variableName, String defaultValue) {
		String realmAttributeValue = readRealmAttribute(realm, REALM_ATTRIBUTE_PREFIX + variableName);
		if (realmAttributeValue != null && !realmAttributeValue.isBlank()) {
			return realmAttributeValue;
		}

		String envOverride = System.getenv(resolveRealmEnvVariableName(realmName, variableName));
		if (envOverride != null && !envOverride.isBlank()) {
			return envOverride;
		}

		return defaultValue;
	}

	private static String readRealmAttribute(RealmModel realm, String attributeName) {
		if (realm == null) {
			return null;
		}

		try {
			Object value = realm.getClass().getMethod("getAttribute", String.class).invoke(realm, attributeName);
			if (value instanceof String) {
				return (String) value;
			}
		} catch (ReflectiveOperationException ignored) {
			// Older/newer Keycloak variants expose either getAttribute or getFirstAttribute.
		}

		try {
			Object value = realm.getClass().getMethod("getFirstAttribute", String.class).invoke(realm, attributeName);
			if (value instanceof String) {
				return (String) value;
			}
		} catch (ReflectiveOperationException ignored) {
			return null;
		}

		return null;
	}

	private static String resolveRealmEnvVariableName(String realmName, String variableName) {
		if (realmName == null || realmName.isBlank()) {
			return "KK_TO_RMQ_REALM__" + variableName.toUpperCase(Locale.ENGLISH);
		}
		String normalizedRealmName = ENV_REALM_NAME.matcher(realmName.toUpperCase(Locale.ENGLISH)).replaceAll("_");
		return "KK_TO_RMQ_REALM_" + normalizedRealmName + "_" + variableName.toUpperCase(Locale.ENGLISH);
	}

	private void configureTls(ConnectionFactory connectionFactory) {
		try {
			boolean customContext = false;
			SSLContext sslContext = SSLContext.getInstance("TLSv1.2");

			TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance("SunX509");
			if (!trustStore.isEmpty()) {
				char[] trustPassphrase = trustStorePass.toCharArray();
				KeyStore trustKeyStore = KeyStore.getInstance("JKS");
				try (FileInputStream trustStoreStream = new FileInputStream(trustStore)) {
					trustKeyStore.load(trustStoreStream, trustPassphrase);
				}

				trustManagerFactory.init(trustKeyStore);
				sslContext.init(null, trustManagerFactory.getTrustManagers(), null);
				customContext = true;
			}

			KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance("SunX509");
			if (!keyStore.isEmpty()) {
				char[] keyPassphrase = keyStorePass.toCharArray();
				KeyStore keyKeyStore = KeyStore.getInstance("PKCS12");
				try (FileInputStream keyStoreStream = new FileInputStream(keyStore)) {
					keyKeyStore.load(keyStoreStream, keyPassphrase);
				}

				keyManagerFactory.init(keyKeyStore, keyPassphrase);
				if (customContext) {
					sslContext.init(keyManagerFactory.getKeyManagers(), trustManagerFactory.getTrustManagers(), null);
				} else {
					sslContext.init(keyManagerFactory.getKeyManagers(), null, null);
					customContext = true;
				}
			}

			if (customContext) {
				connectionFactory.useSslProtocol(sslContext);
			} else {
				connectionFactory.useSslProtocol();
			}
		} catch (Exception e) {
			log.error("Could not use SSL protocol", e);
		}
	}

	private static String resolveRealmName(KeycloakSession session, String realmId) {
		if (realmId != null) {
			RealmModel realm = session.realms().getRealm(realmId);
			if (realm != null && realm.getName() != null) {
				return realm.getName();
			}
		}

		RealmModel contextRealm = session.getContext().getRealm();
		if (contextRealm != null && contextRealm.getName() != null) {
			return contextRealm.getName();
		}

		return "unknown";
	}
	
	
	public String getHostUrl() {
		return hostUrl;
	}
	public void setHostUrl(String hostUrl) {
		this.hostUrl = hostUrl;
	}
	public Integer getPort() {
		return port;
	}
	public void setPort(Integer port) {
		this.port = port;
	}
	public String getUsername() {
		return username;
	}
	public void setUsername(String username) {
		this.username = username;
	}
	public String getPassword() {
		return password;
	}
	public void setPassword(String password) {
		this.password = password;
	}
	public String getVhost() {
		return vhost;
	}
	public void setVhost(String vhost) {
		this.vhost = vhost;
	}
	public Boolean getUseTls() {
		return useTls;
	}
	public void setUseTls(Boolean useTls) {
		this.useTls = useTls;
	}

	// setters and getters SSL context setting
	public void setTrustStore(String trustStore) {
		this.trustStore = trustStore;
	}
	public void setTrustStorePass(String trustStorePass) {
		this.trustStorePass = trustStorePass;
	}
	public void setKeyStore(String keyStore) {
		this.keyStore = keyStore;
	}
	public void setKeyStorePass(String keyStorePass) {
		this.keyStorePass = keyStorePass;
	}
	public String getTrustStore() {
		return trustStore;
	}
	public String getTrustStorePass() {
		return trustStorePass;
	}
	public String getKeyStore() {
		return keyStore;
	}
	public String getKeyStorePass() {
		return keyStorePass;
	}
	public String getKeytStorePass() {
		return keyStorePass;
	}
	//

	public String getExchange() {
		return exchange;
	}
	public void setExchange(String exchange) {
		this.exchange = exchange;
	}
	public Integer getChannelPoolSize() {
		return channelPoolSize;
	}
	public void setChannelPoolSize(Integer channelPoolSize) {
		this.channelPoolSize = channelPoolSize;
	}
	public Boolean getAllowRealmRmqCredentials() {
		return allowRealmRmqCredentials;
	}
	public void setAllowRealmRmqCredentials(Boolean allowRealmRmqCredentials) {
		this.allowRealmRmqCredentials = allowRealmRmqCredentials;
	}

}
