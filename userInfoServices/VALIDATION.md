# Validation — 2026-09-22

Verified with Java 25.0.4.1, Gradle 9.3.0, Spring Boot 4.1.1 and Spring Cloud
2025.1.3. `gradle build --offline --no-daemon --console=plain` succeeded:
**182 tests, 0 failures, 0 errors, 0 skipped**.

| Test suite | Tests |
| --- | ---: |
| Real HTTP API + Spring Security + H2 Oracle-mode JDBC | 42 |
| Own database-port Spring bootstrap | 1 |
| Own database-port property precedence and configuration | 18 |
| Database owner/schema/PDB readiness checks | 11 |
| Shared Eureka port Spring bootstrap and real client configuration binding | 2 |
| Eureka endpoint property precedence, URI handling and configuration | 44 |
| Shared Oracle Eureka-port validation, error safety and cleanup | 36 |
| Own Oracle port reader validation, error safety and cleanup | 28 |

The HTTP tests cover registration, input validation, BCrypt cost 12, duplicate
identities, credential verification, disabled users, self/admin authorization,
pagination, and protection against password/hash disclosure and role assignment.
The port tests check both YAML mode and database mode without contacting Oracle.

The new tests verify automatic startup processor registration, this service's
port `8770` independently of Eureka's port, and binding the resolved endpoint
to the real `EurekaClientConfigBean`. They cover property aliases and precedence,
HTTPS/IPv6/raw paths, disabled discovery/manual URL mode, missing or duplicate
rows, invalid ports, account/PDB validation, timeouts, resource cleanup, and
sanitized errors. Oracle-specific startup tests use mocked JDBC boundaries;
no live Oracle or Eureka connection is made.

Archive verification passed:

- `build/libs/userInfoServices.war`: executable WAR; application configuration and
  classes and startup processors included; embedded Tomcat libraries in
  `WEB-INF/lib-provided`; no SQL.
- `build/libs/userInfoServices-db.zip`: SQL only; all three scripts are grouped
  under the target schema `USER_INFO_SCHEMA/`, preserving every source SQL file.
  Scripts `001` through `003` run as `USER_INFO_SCHEMA`. Account creation,
  credentials and grants are provisioned externally and are not in the SQL ZIP.
  Script `003_seed_port.sql` inserts or updates the exact user-service
  `server.port` row to `8770`.

The default Eureka lookup reads the existing
`EUREKA_DB.PROPERTIES[eureka-server / jdbc / jdbc / server.port]` row using
`USER_INFO_SCHEMA` credentials. The external SELECT grant is documented in
README.md and was not applied by this task. The existing local database-password
fallback was preserved; deployment should remove it and supply the secret
externally.

No live Oracle connection, schema creation, SQL execution, Eureka registration,
or external Tomcat deployment was performed. H2 and mocked JDBC tests are not
proof of Oracle DDL compatibility or successful live registration. Inspect and
execute the Oracle scripts explicitly as documented before real startup.
Restart services to apply changed listener/discovery ports; there is no runtime
polling of the properties table.

Existing Eureka and initial-service source files were not changed.
