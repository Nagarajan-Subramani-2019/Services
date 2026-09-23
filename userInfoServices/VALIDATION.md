# Validation — 2026-09-23

## Current change: public user-detail reads

At the user's request, `GET /userdetails` and `GET /userdetails/{id}` no longer
require authentication, an ADMIN role or record ownership. Their dedicated
first security chain does not install HTTP Basic, so stale or malformed Basic
headers do not cause an otherwise-public read to fail. The controller and
service no longer take an authentication object for an individual-user lookup.

Password hashing, safe response DTOs, registration field validation, positive
ID/pagination checks, missing-record 404 responses and `POST /authuserdetails`
credential verification remain. `GET /actuator/info` remains ADMIN-only.
Unrelated routes and unsupported write methods remain protected. Read responses
now intentionally expose profile fields, including email and phone number, to
anyone who can reach these endpoints; restrict network access until the future
entitlement service is actually implemented. Passwords/hashes remain excluded.

The isolated staged build completed with **233 tests, 0 failures, 0 errors,
0 skipped**, including 51 real-HTTP/H2 API tests. Tests cover anonymous reads
and pagination, empty results, invalid/missing IDs, disabled-record visibility,
ignored stale/malformed/unknown/disabled Basic credentials on public GETs,
unchanged credential-check behavior, restricted management/unrelated routes,
and no password/hash disclosure. WAR/SQL-ZIP artifact verification also passed.

The local Windows Java runtime initially failed creating its loopback selector
socket. For this build only, `JAVA_TOOL_OPTIONS` set
`-Djdk.net.unixdomain.tmpdir=C:\Users\NagarajanSubramani\Desktop\main_final\jvm-ipc`.
No application source, persistent Java settings or production runtime options
were changed for that workaround. The build used Java 25.0.4 and Gradle 9.3.0,
offline, with no live Oracle or Eureka connection.

The existing `application.yml` and both SQL source files were verified unchanged.
No schema, table, port row or database account was changed. The already-running
service was not stopped or restarted. Use the newly built WAR after stopping the
old process to activate the change; an old process still uses its old classes.

## Earlier verification record (before public reads)

The sections below record the previous dual-database change and its 225-test
build. References to self/admin authorization describe that previous build;
the public-read rules and 233-test result above supersede those details.

## Separate database credentials

Both port lookups read the existing `EUREKA_DB.PROPERTIES` table, filtered
by their respective application names, using a dedicated `EUREKA_DB` login.
`user-info.properties-datasource` supplies its independent URL, fixed username
and password through `EUREKA_DB_URL` and `EUREKA_DB_PASSWORD`. Missing properties
credentials never fall back to the CRUD datasource. These are short-lived JDBC
connections opened before the Spring context starts, not a second Hikari pool.

The application Hikari pool remains `spring.datasource`, using
`USER_INFO_DB_URL` / `USER_INFO_DB_PASSWORD` and `USER_INFO_SCHEMA` for user
records. Both configurations default to the same FREEPDB1 tunnel endpoint but
accept independent URLs. No cross-schema SELECT grant is needed for owner reads;
no existing grants were changed. No application-local PROPERTIES table is used.

The combined build passed. Bootstrap tests capture the actual JDBC credentials
and confirm that only EUREKA_DB is used for port reads while the CRUD settings
stay unchanged. A real HTTP/Hikari test confirms that supplying the separate
properties settings does not replace the application's user-data pool.

## Existing SQL layout and guard removal

Removed the session-user/current-schema/container PL/SQL guard and its enclosing
`BEGIN ... END; /` block from this service's SQL scripts at the user's request.
The ZIP now contains only `USER_INFO_SCHEMA/002_create_user_details.sql` and
`EUREKA_DB/003_seed_port.sql`. Execute each under its indicated owner; the
scripts do not enforce the session identity. The port MERGE sets only the
`userInfoServices / jdbc / jdbc / server.port` row to `8770` in the shared
Eureka table. Java identity checks remain enabled: EUREKA_DB for port lookups,
USER_INFO_SCHEMA for user data, and FREEPDB1 for both. User-data definitions are unchanged.
No live database table was dropped or migrated, and no SQL was executed.

The updated build passed. Both packaged SQL files were also compared to their
source files and checked for leftover guards, orphaned PL/SQL delimiters, and
references to the former application-local properties table.

## Application build

Verified with Java 25.0.4.1, Gradle 9.3.0, Spring Boot 4.1.1 and Spring Cloud
2025.1.3. `gradle build --offline --no-daemon --console=plain` succeeded:
**225 tests, 0 failures, 0 errors, 0 skipped**.

| Test suite | Tests |
| --- | ---: |
| Real HTTP API + Spring Security + H2 Oracle-mode JDBC | 43 |
| Own database-port Spring bootstrap | 1 |
| Own database-port property precedence and configuration | 27 |
| Database owner/schema/PDB readiness checks | 11 |
| Shared Eureka port Spring bootstrap and real client configuration binding | 2 |
| Eureka endpoint property precedence, URI handling and configuration | 53 |
| Shared Oracle Eureka-port validation, error safety and cleanup | 38 |
| Own Oracle port reader validation, error safety and cleanup | 40 |
| Separate properties-database settings and credential isolation | 10 |

The HTTP tests cover registration, input validation, BCrypt cost 12, duplicate
identities, credential verification, disabled users, self/admin authorization,
pagination, and protection against password/hash disclosure and role assignment.
The port tests check both YAML mode and database mode without contacting Oracle.

The new tests verify automatic startup processor registration, this service's
port `8770` independently of Eureka's port, and binding the resolved endpoint
to the real `EurekaClientConfigBean`. They cover property aliases and precedence,
HTTPS/IPv6/raw paths, disabled discovery/manual URL mode, missing or duplicate
rows, invalid ports, account/PDB validation, timeouts, resource cleanup, and
sanitized errors. Shared-table regression tests check that both lookups use
`EUREKA_DB.PROPERTIES` with different bound APPLICATION values and identical
`EUREKA_DB` credentials. Oracle-specific startup tests use mocked JDBC boundaries;
no live Oracle or Eureka connection is made.

Archive verification passed:

- `build/libs/userInfoServices.war`: executable WAR; application configuration and
  classes and startup processors included; embedded Tomcat libraries in
  `WEB-INF/lib-provided`; no SQL.
- `build/libs/userInfoServices-db.zip`: SQL only, grouped by target schema.
  `USER_INFO_SCHEMA/002_create_user_details.sql` creates only user data;
  `EUREKA_DB/003_seed_port.sql` inserts or updates the exact user-service
  `server.port` row to `8770` in the existing shared table. Account creation,
  credentials and grants are external and are not in the SQL ZIP.

The default Eureka lookup reads the existing
`EUREKA_DB.PROPERTIES[eureka-server / jdbc / jdbc / server.port]` row using
`EUREKA_DB` credentials. Its password has no fallback and must be supplied
externally. The existing local USER_INFO_DB_PASSWORD fallback was preserved;
deployment should remove it and supply that secret externally as well.

No live Oracle connection, schema creation, SQL execution, Eureka registration,
or external Tomcat deployment was performed. H2 and mocked JDBC tests are not
proof of Oracle DDL compatibility or successful live registration. Inspect and
execute the Oracle scripts explicitly as documented before real startup.
Restart services to apply changed listener/discovery ports; there is no runtime
polling of the properties table.

Existing Eureka and initial-service source files were not changed.
