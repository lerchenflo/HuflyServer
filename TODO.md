# TODO

Open work only; finished items live in git history. Client-side product TODOs live in the Hufly app repo (`../Hufly/TODO.md`). Requirements: `../Hufly/REQUIREMENTS.md`. Data model: `docs/domain-model.html`.

THIS WAS ADDED BY HAND AND NEEDS TO BE CONVERTED TO A TODO:
We need the website to have a delete function for the ställe. also we need to update the website task view to look like the in app task view.

ANOTHER FIX: FIX THE UNNECESSARY OUTPUT LOGGING; also copy the schneaggchatv3servers logging with colored outputs and output if apns and firebase have been loaded successfully
schneaggchat@schneaggchat:~/hufly$ docker compose logs -f hufly_server
hufly_server-1  |
hufly_server-1  |   .   ____          _            __ _ _
hufly_server-1  |  /\\ / ___'_ __ _ _(_)_ __  __ _ \ \ \ \
hufly_server-1  | ( ( )\___ | '_ | '_| | '_ \/ _` | \ \ \ \
hufly_server-1  |  \\/  ___)| |_)| | | | | || (_| |  ) ) ) )
hufly_server-1  |   '  |____| .__|_| |_|_| |_\__, | / / / /
hufly_server-1  |  =========|_|==============|___/=/_/_/_/
hufly_server-1  |
hufly_server-1  |  :: Spring Boot ::                (v4.1.1)
hufly_server-1  |
hufly_server-1  | 14:09:23 INFO com.lerchenflo.hufly.server.HuflyServerApplicationKt - Starting HuflyServerApplicationKt v0.0.1-SNAPSHOT using Java 25.0.4.1 with PID 1 (/app/app.jar started by spring in /app)
hufly_server-1  | 14:09:23 INFO com.lerchenflo.hufly.server.HuflyServerApplicationKt - The following 1 profile is active: "prod"
hufly_server-1  | 14:09:23 INFO org.springframework.data.repository.config.RepositoryConfigurationDelegate - Bootstrapping Spring Data MongoDB repositories in DEFAULT mode.
hufly_server-1  | 14:09:23 INFO org.springframework.data.repository.config.RepositoryConfigurationDelegate - Finished Spring Data repository scanning in 46 ms. Found 21 MongoDB repository interfaces.
hufly_server-1  | 14:09:24 INFO org.springframework.boot.tomcat.TomcatWebServer - Tomcat initialized with port 8080 (http)
hufly_server-1  | 14:09:24 INFO org.apache.catalina.core.StandardService - Starting service [Tomcat]
hufly_server-1  | 14:09:24 INFO org.apache.catalina.core.StandardEngine - Starting Servlet engine: [Apache Tomcat/11.0.24]
hufly_server-1  | 14:09:24 INFO org.springframework.boot.web.context.servlet.WebApplicationContextInitializer - Root WebApplicationContext: initialization completed in 1354 ms
hufly_server-1  | 14:09:24 INFO org.mongodb.driver.client - MongoClient with metadata {"driver": {"name": "mongo-java-driver|spring-boot|sync", "version": "5.8.1"}, "os": {"type": "Linux", "name": "Linux", "architecture": "amd64", "version": "7.0.0-38-generic"}, "platform": "Java/Eclipse Adoptium/25.0.4.1+1-LTS", "env": {"container": {"runtime": "docker"}}} created with settings MongoClientSettings{readPreference=primary, writeConcern=WriteConcern{w=null, wTimeout=null ms, journal=null}, retryWrites=true, retryReads=true, readConcern=ReadConcern{level=null}, credential=MongoCredential{mechanism=null, userName='hufly', source='admin', password=<hidden>, mechanismProperties=<hidden>}, transportSettings=null, commandListeners=[], codecRegistry=ProvidersCodecRegistry{codecProviders=[ValueCodecProvider{}, BsonValueCodecProvider{}, DBRefCodecProvider{}, DBObjectCodecProvider{}, DocumentCodecProvider{}, CollectionCodecProvider{}, IterableCodecProvider{}, MapCodecProvider{}, GeoJsonCodecProvider{}, GridFSFileCodecProvider{}, Jsr310CodecProvider{}, JsonObjectCodecProvider{}, BsonCodecProvider{}, com.mongodb.client.model.mql.ExpressionCodecProvider@27ec0d06, com.mongodb.Jep395RecordCodecProvider@2676d96a, com.mongodb.KotlinCodecProvider@12266084, EnumCodecProvider{}]}, loggerSettings=LoggerSettings{maxDocumentLength=1000}, clusterSettings={hosts=[hufly_db:27017], srvServiceName=mongodb, mode=SINGLE, requiredClusterType=UNKNOWN, requiredReplicaSetName='null', serverSelector='null', clusterListeners='[]', serverSelectionTimeout='30000 ms', localThreshold='15 ms'}, socketSettings=SocketSettings{connectTimeoutMS=10000, readTimeoutMS=0, receiveBufferSize=0, proxySettings=ProxySettings{host=null, port=null, username=null, password=null}}, heartbeatSocketSettings=SocketSettings{connectTimeoutMS=10000, readTimeoutMS=10000, receiveBufferSize=0, proxySettings=ProxySettings{host=null, port=null, username=null, password=null}}, connectionPoolSettings=ConnectionPoolSettings{maxSize=100, minSize=0, maxWaitTimeMS=120000, maxConnectionLifeTimeMS=0, maxConnectionIdleTimeMS=0, maintenanceInitialDelayMS=0, maintenanceFrequencyMS=60000, connectionPoolListeners=[], maxConnecting=2}, serverSettings=ServerSettings{heartbeatFrequencyMS=10000, minHeartbeatFrequencyMS=500, serverMonitoringMode=AUTO, serverListeners='[]', serverMonitorListeners='[]'}, sslSettings=SslSettings{enabled=false, invalidHostNameAllowed=false, context=null}, applicationName='null', compressorList=[], uuidRepresentation=UNSPECIFIED, serverApi=null, autoEncryptionSettings=null, dnsClient=null, inetAddressResolver=null, contextProvider=null, timeoutMS=null}
hufly_server-1  | 14:09:24 INFO org.mongodb.driver.cluster - Monitor thread successfully connected to server with description ServerDescription{address=hufly_db:27017, type=STANDALONE, cryptd=false, state=CONNECTED, ok=true, minWireVersion=0, maxWireVersion=27, maxDocumentSize=16777216, logicalSessionTimeoutMinutes=30, roundTripTimeNanos=20367986, minRoundTripTimeNanos=0}
hufly_server-1  | 14:09:25 INFO com.eatthepath.pushy.apns.ApnsClientBuilder - Native SSL provider not available; will use JDK SSL provider.
hufly_server-1  | 14:09:26 INFO org.springframework.security.config.annotation.authentication.configuration.InitializeUserDetailsBeanManagerConfigurer$InitializeUserDetailsManagerConfigurer - Global AuthenticationManager configured with UserDetailsService bean with name userDetailsService
hufly_server-1  | 14:09:26 INFO org.springframework.boot.webmvc.autoconfigure.WelcomePageHandlerMapping - Adding welcome page: class path resource [static/index.html]
hufly_server-1  | 14:09:26 INFO org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler - Starting...
hufly_server-1  | 14:09:26 INFO org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler - BrokerAvailabilityEvent[available=true, SimpleBrokerMessageHandler [org.springframework.messaging.simp.broker.DefaultSubscriptionRegistry@34dda1b4]]
hufly_server-1  | 14:09:26 INFO org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler - Started.
hufly_server-1  | 14:09:26 INFO org.springframework.boot.tomcat.TomcatWebServer - Tomcat started on port 8080 (http) with context path '/'
hufly_server-1  | 14:09:26 INFO com.lerchenflo.hufly.server.HuflyServerApplicationKt - Started HuflyServerApplicationKt in 3.927 seconds (process running for 4.426)
hufly_server-1  | 14:10:26 INFO org.springframework.web.socket.config.WebSocketMessageBrokerStats - WebSocketSession[0 current WS(0)-HttpStream(0)-HttpPoll(0), 0 total, 0 closed abnormally (0 connect failure, 0 send limit, 0 transport error)], stompSubProtocol[processed CONNECT(0)-CONNECTED(0)-DISCONNECT(0)], stompBrokerRelay[null], inboundChannel[pool size = 0, active threads = 0, queued tasks = 0, completed tasks = 0], outboundChannel[pool size = 0, active threads = 0, queued tasks = 0, completed tasks = 0], sockJsScheduler[pool size = 3, active threads = 1, queued tasks = 2, completed tasks = 0]

## Push notifications
- [ ] Configure for real: Firebase project + service account JSON, APNs key (.p8), team id, key id, bundle id (`.env`, `push-secrets/`). Until then both senders log "off" and drop pushes.
- [ ] Not built (user chose instant + answers + digest): due reminders for tasks and horse care (needs a scheduler and a sent-once record), per-type muting.
- [ ] Anyone who knows another install's push token could register it on their own session (the token moves). Tokens are not public, so accepted for now.

## Open decisions
- [ ] Website content before going live: real contact address (`index.html` uses the placeholder `kontakt@hufly.app`), full Impressum (ECG § 5, MedienG § 25) and privacy policy (DSGVO), prices once the payment model is decided.
- [ ] Payment model and subscription expiry (BIZ-6).

## Deferred features
- [ ] LOW (security check 2026-10-04, series): `isOccurrence` scans an open-ended series up to the requested date (at most ~360 000 dates for DAILY up to year 3000), and every valid date of an open series can get its own `EventOccurrence`/`TaskOccurrence` row, so restamping grows with them. Bound the key to e.g. 10 years after the first date and cap rows per series if abuse shows up.
- [ ] LOW: STOMP sessions stay open after the access token expires, the session is logged out or the user is deleted. They only receive collection names (no data). Close them on logout/deletion, or require reconnecting with a fresh token.
- [ ] General API rate limiting per user (only password logins are limited so far). Picture uploads decode up to 40 MP (~160 MB heap each) and need a tight per-user limit. Move limiter and version-counter state to a shared store (Redis) before running more than one server instance.
- [ ] Emailing generated passwords (USR-3).
