# Initial service

Java 25 / Spring Boot 4.1.1 / Spring Cloud 2025.1.3 / Gradle 9.3.0.

Build: `bash ./gradlew clean build` (Windows: `gradlew.bat clean build`).
Output: `build/libs/initial-service.war`.

Required environment: `INITIAL_DB_PASSWORD` and `EUREKA_DEFAULT_ZONE`.
The Oracle account is `INITIAL_DB`; default JDBC URL:
`jdbc:oracle:thin:@//127.0.0.1:11521/FREEPDB1` (override `INITIAL_DB_URL`).
Eureka URL includes HTTP Basic credentials, for example
`http://eureka:<URL_ENCODED_PASSWORD>@127.0.0.1:8761/eureka-server/eureka/`.

Run: `java -jar build/libs/initial-service.war`.

- GET <http://127.0.0.1:8081/initial-service/api/hi>
- GET <http://127.0.0.1:8081/initial-service/api/hello>

Each request reads the Oracle session user and active schema from DUAL. Both
must be `INITIAL_DB`. No tables are needed and no application DDL/DML is executed.
Errors return HTTP 503 with a generic JSON message. Tests mock the database.

For external Tomcat 11 / Java 25, set `INITIAL_ADVERTISED_PORT` to the connector
port and keep the WAR name `initial-service.war`. Keep network access private.
See [the complete two-service guide](../JAVA_SERVICES.md).
