# Validation — 2026-09-22

Verified with Java 25.0.4.1, Gradle 9.3.0, Spring Boot 4.1.1 and Spring Cloud 2025.1.3.

`gradle build` completed successfully: **100 tests, 0 failures, 0 errors, 0 skipped**.

| Test suite | Tests |
| --- | ---: |
| Real HTTP API + Spring Security + H2 Oracle-mode JDBC | 42 |
| Spring bootstrap / automatic processor registration | 1 |
| Database-port property precedence and configuration | 18 |
| Database owner/schema/PDB readiness checks | 11 |
| Oracle port reader validation, error safety and cleanup | 28 |

The HTTP tests cover registration, input validation, BCrypt cost 12, duplicate
identities, credential verification, disabled users, self/admin authorization,
pagination, and protection against password/hash disclosure and role assignment.
The port tests check both YAML mode and database mode without contacting Oracle.

Archive verification passed:

- `build/libs/userInfoServices.war`: executable WAR; application configuration and
  classes included; embedded Tomcat libraries in `WEB-INF/lib-provided`; no SQL.
- `build/libs/userInfoServices-db.zip`: SQL only; preserves `DBA/` and
  `USER_INFO_SCHEMA/` paths and every source SQL file.

No live Oracle connection, schema creation, SQL execution, Eureka registration,
or deployment to an external Tomcat was performed. H2 tests are not proof of
Oracle DDL compatibility; review and execute the Oracle scripts explicitly as
documented in README.md before a real startup. Test databases were disposable
and tests used mock JDBC boundaries for Oracle-specific startup checks.

No real credentials are included. Existing Eureka and initial-service source
files were not changed for this task.
