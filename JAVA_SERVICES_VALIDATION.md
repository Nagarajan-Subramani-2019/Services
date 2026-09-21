# Validation results

Verified locally on 21 September 2026 using Java 25.0.4.1, Gradle 9.3.0,
Spring Boot 4.1.1, and Spring Cloud 2025.1.3.

## Automated checks

| Project | Tests | Failures | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: |
| eureka-server | 21 | 0 | 0 | 0 |
| initial-service | 36 | 0 | 0 | 0 |

Both `check` tasks passed, including WAR dependency-layout verification.
Tests cover greeting responses, schema isolation, database-error handling,
required credentials, Eureka authentication, and its registration lifecycle.

## External Tomcat smoke test

Both final WARs were deployed together to an isolated local Tomcat 11.0.26
running Java 25, bound to loopback port 18880. Verified:

- Both application liveness endpoints returned HTTP 200.
- Eureka registry access without credentials returned HTTP 401.
- Authenticated Eureka registry access returned HTTP 200.
- The greeting endpoint returned HTTP 503 with a generic message when Oracle
  was deliberately unreachable; no connection details were exposed.
- `INITIAL-SERVICE` registered with Eureka, advertising the actual Tomcat
  connector port and its `/initial-service/actuator/health` URL.

The isolated Tomcat process was stopped after the checks. Only dummy credentials
and an intentionally unused local database port were used. No cloud VM,
firewall, SSH tunnel, live schema, or database credential was changed.

## Not verified

Live Oracle authentication and database-backed successful greetings require
the user's `INITIAL_DB` account/password and active tunnel. Eureka's optional
Oracle profile similarly requires `EUREKA_DB`. These were not supplied or
tested against the live database. The successful greeting tests use mocked
Oracle query results. No cloud deployment or production load test was performed.

## Built WAR SHA-256 checksums

```text
C0F08BCFC17914AEB225FC7730BBDF02BE3C5C35260842E98461C8B31C602CDA  eureka-server.war
51B440C9A344EB6D4BC77431A36FD1DBDFC3F339A07795D039BECAD10018428F  initial-service.war
```

See [the setup guide](JAVA_SERVICES.md) for account preparation and deployment.
