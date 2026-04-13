# keycloak-event-listener-rabbitmq

##### A Keycloak SPI plugin that publishes events to a RabbitMq server.  

| Plugin | min Keycloak ver |
| -- | ---- |
| 1.x | 10.x |
| 2.x | 13.x |
| 3.x | 16.x |

For example here is the notification of the user updated by administrator

* routing key: `KK.EVENT.ADMIN.MYREALM.SUCCESS.USER.UPDATE`  
* published to exchange: `amq.topic`
* content: 


```
{
  "@class" : "com.github.aznamier.keycloak.event.provider.EventAdminNotificationMqMsg",
  "time" : 1596951200408,
  "realmId" : "MYREALM",
  "authDetails" : {
    "realmId" : "master",
    "clientId" : "********-****-****-****-**********",
    "userId" : "********-****-****-****-**********",
    "ipAddress" : "192.168.1.1"
  },
  "resourceType" : "USER",
  "operationType" : "UPDATE",
  "resourcePath" : "users/********-****-****-****-**********",
  "representation" : "representation details here....",
  "error" : null,
  "resourceTypeAsString" : "USER"
}
```

The routing key is calculated as follows:
* admin events: `KK.EVENT.ADMIN.<REALM>.<RESULT>.<RESOURCE_TYPE>.<OPERATION>`
* client events: `KK.EVENT.CLIENT.<REALM>.<RESULT>.<CLIENT>.<EVENT_TYPE>`

And because the recommended exchange is a **TOPIC (amq.topic)**,  
therefore its easy for Rabbit client to subscribe to selective combinations eg:
* all events: `KK.EVENT.#`
* all events from my realm: `KK.EVENT.*.MYREALM.#`
* all error events from my realm: `KK.EVENT.*.MYREALM.ERROR.#`
* all user events from my-relam and my-client: `KK.EVENT.*.MY-REALM.*.MY-CLIENT.USER`


## USAGE:
1. [Download the latest jar](https://github.com/aznamier/keycloak-event-listener-rabbitmq/blob/target/keycloak-to-rabbit-3.0.5.jar?raw=true) or build from source: ``mvn clean install``
2. Copy jar into your Keycloak 
    1. Keycloak version 17+ (Quarkus) `/opt/keycloak/providers/keycloak-to-rabbit-3.0.5.jar` 
    2. Keycloak version 16 and older `/opt/jboss/keycloak/standalone/deployments/keycloak-to-rabbit-3.0.5.jar`
3. Configure as described below (option 1 or 2 or 3)
4. Restart the Keycloak server
5. Enable logging in Keycloak UI by adding **keycloak-to-rabbitmq**  
 `Manage > Events > Config > Events Config > Event Listeners`

#### Configuration 
###### Recommended: OPTION 1: just configure **ENVIRONMENT VARIABLES**
  - `ALLOW_REALM_RMQ_CREDENTIALS` - default: *false*
  - `KK_TO_RMQ_URL` - default: *localhost*
  - `KK_TO_RMQ_PORT` - default: *5672*
  - `KK_TO_RMQ_VHOST` - default: *empty*
  - `KK_TO_RMQ_EXCHANGE` - default: *amq.topic*
  - `KK_TO_RMQ_CHANNEL_POOL_SIZE` - default: *16*
  - `KK_TO_RMQ_USERNAME` - default: *admin*
  - `KK_TO_RMQ_PASSWORD` - default: *admin*
  - `KK_TO_RMQ_USE_TLS` - default: *false*
  - `KK_TO_RMQ_KEY_STORE` - default: *empty*
  - `KK_TO_RMQ_KEY_STORE_PASS` - default: *empty*
  - `KK_TO_RMQ_TRUST_STORE` - default: *empty*
  - `KK_TO_RMQ_TRUST_STORE_PASS` - default: *empty*

###### Realm-specific overrides

By default, realm-specific RabbitMQ credentials are disabled. If `ALLOW_REALM_RMQ_CREDENTIALS` is not set or is `false`, the provider works like before and uses only the global RabbitMQ settings.

To enable per-realm RabbitMQ credentials, set:

```bash
export ALLOW_REALM_RMQ_CREDENTIALS=true
```

You can override any RabbitMQ setting per realm. The provider resolves settings in this order:
1. Realm attribute `kk.to.rmq.<setting>`
2. Realm-specific environment variable `KK_TO_RMQ_REALM_<REALM>_<SETTING>`
3. Global provider/environment setting

Supported per-realm settings are:
* `url`
* `port`
* `vhost`
* `exchange`
* `channel_pool_size`
* `username`
* `password`
* `use_tls`
* `key_store`
* `key_store_pass`
* `trust_store`
* `trust_store_pass`

Examples:
* realm attribute `kk.to.rmq.username=orders-user`
* realm attribute `kk.to.rmq.password=secret`
* environment variable `KK_TO_RMQ_REALM_MY_REALM_USERNAME=orders-user`
* environment variable `KK_TO_RMQ_REALM_MY_REALM_PASSWORD=secret`

Quick usage examples:

Global-only mode, old behavior:

```bash
export KK_TO_RMQ_URL=rabbitmq.internal
export KK_TO_RMQ_USERNAME=global-user
export KK_TO_RMQ_PASSWORD=global-pass
unset ALLOW_REALM_RMQ_CREDENTIALS
```

Per-realm mode with env vars:

```bash
export ALLOW_REALM_RMQ_CREDENTIALS=true
export KK_TO_RMQ_URL=rabbitmq.internal
export KK_TO_RMQ_USERNAME=global-user
export KK_TO_RMQ_PASSWORD=global-pass

export KK_TO_RMQ_REALM_MASTER_USERNAME=master-user
export KK_TO_RMQ_REALM_MASTER_PASSWORD=master-pass

export KK_TO_RMQ_REALM_CUSTOMERS_EU_USERNAME=customers-user
export KK_TO_RMQ_REALM_CUSTOMERS_EU_PASSWORD=customers-pass
```

Per-realm mode with realm attributes:

```text
kk.to.rmq.username=realm-user
kk.to.rmq.password=realm-pass
kk.to.rmq.url=rabbitmq-per-realm.internal
kk.to.rmq.vhost=/realm-vhost
```

Realm names are normalized in environment variables by converting them to upper case and replacing non-alphanumeric characters with `_`. For example, realm `my-realm.eu` becomes `MY_REALM_EU`.

Connections are cached by the effective RabbitMQ connection settings, and channels are reused from a pool. Realms that resolve to the same connection settings will share the same connection pool.

###### Deprecated OPTION 2: edit Keycloak subsystem of WildFly (Keycloak 16 and older) standalone.xml or standalone-ha.xml:

```xml
<spi name="eventsListener">
    <provider name="keycloak-to-rabbitmq" enabled="true">
        <properties>
            <property name="url" value="${env.KK_TO_RMQ_URL:localhost}"/>
            <property name="port" value="${env.KK_TO_RMQ_PORT:5672}"/>
            <property name="vhost" value="${env.KK_TO_RMQ_VHOST:}"/>
            <property name="exchange" value="${env.KK_TO_RMQ_EXCHANGE:amq.topic}"/>
            <property name="channel_pool_size" value="${env.KK_TO_RMQ_CHANNEL_POOL_SIZE:16}"/>
            <property name="allow_realm_rmq_credentials" value="${env.ALLOW_REALM_RMQ_CREDENTIALS:false}"/>
            <property name="use_tls" value="${env.KK_TO_RMQ_USE_TLS:false}"/>
            <property name="key_store" value="${env.KK_TO_RMQ_KEY_STORE:}"/>
            <property name="key_store_pass" value="${env.KK_TO_RMQ_KEY_STORE_PASS:}"/> 
            <property name="trust_store" value="${env.KK_TO_RMQ_TRUST_STORE:}"/>
            <property name="trust_store_pass" value="${env.KK_TO_RMQ_TRUST_STORE_PASS:}"/>           
            <property name="username" value="${env.KK_TO_RMQ_USERNAME:guest}"/>
            <property name="password" value="${env.KK_TO_RMQ_PASSWORD:guest}"/>
        </properties>
    </provider>
</spi>
```
###### Deprecated OPTION 3 same effect as OPTION 2 but programatically WildFly (Keycloak 16 and older):
```
echo "yes" | $KEYCLOAK_HOME/bin/jboss-cli.sh --file=$KEYCLOAK_HOME/KEYCLOAK_TO_RABBIT.cli
```


