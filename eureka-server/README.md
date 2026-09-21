# Eureka server

Java 25 / Spring Boot 4.1.1 / Spring Cloud 2025.1.3 / Gradle 9.3.0.

Build: `bash ./gradlew clean build` (Windows: `gradlew.bat clean build`).
Output: `build/libs/eureka-server.war`.

Set `EUREKA_PASSWORD`, then run `java -jar build/libs/eureka-server.war`.
Default dashboard: <http://127.0.0.1:8761/eureka-server/>;
username: `eureka` (override with `EUREKA_USERNAME`).

Oracle is optional: activate profile `oracle` and set `EUREKA_DB_PASSWORD`.
The account is `EUREKA_DB`; the default database address is
`jdbc:oracle:thin:@//127.0.0.1:11521/FREEPDB1` (override `EUREKA_DB_URL`).
This datasource does not persist the Eureka registry.

For external deployment use Tomcat 11 / Java 25 and preserve the WAR name.
Never run the application as SYS or give it SYSDBA privileges.

See [the complete two-service guide](../JAVA_SERVICES.md) for database setup,
security, external Tomcat deployment, and the initial-service client.
