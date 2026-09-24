# Validation — 2026-09-23

## Result

Gradle build successful on Java 25.0.4 / Gradle 9.3.0 using cached dependencies.
**240 tests passed, zero failures/errors.**

- 63 transaction API cases: real local HTTP with isolated H2 Oracle mode.
- 177 configuration/readiness/Eureka bootstrap cases: mocked JDBC connections
  and environment bootstrapping, with no live Oracle or Eureka requests.

API coverage includes create/store, fetch, filtered/paged lists, updates,
optimistic-lock conflict, simultaneous updates (one winner), exact decimal
storage, month normalization, month counts greater than 12, field bounds,
malformed JSON, unknown properties, forbidden deletion, missing records,
public access without a user schema, no-store headers and sanitized DB errors.

Configuration coverage includes independent CRUD/config credentials, correct
EUREKA_DB property keys, timeout settings, connection/resource cleanup, secret
redaction, missing/invalid/duplicate ports, disabled lookup flags, database-port
precedence, URI host/path preservation, advertised connector URLs, owner checks,
table/sequence readiness and no sequence consumption during startup checks.

## Artifacts

- `build/libs/userTransactServices.war`
- `build/libs/userTransactServices-db.zip`

Build checks verify Oracle driver and bootstrap configuration in the WAR,
embedded/external Tomcat dependency placement, no SQL in the WAR, and exactly
the schema-organized SQL files in the ZIP. No schema/user creation, grants,
DROP or TRUNCATE statements are included in the deployable SQL ZIP.

## Boundaries / not executed

- No live database SQL was executed; Oracle DDL requires manual execution by
  the appropriate existing schema owner.
- H2 Oracle mode is not a replacement for deployment validation on Oracle.
- Actual Eureka registration and external Tomcat deployment were not tested
  against the user's running environment.
- No application instance was left running and no real transaction/user
  records were created.
- No existing userInfoServices, Eureka or UI source files were changed.
- No user-existence validation, UI integration or entitlement was added.

## Local Windows build environment

The build used a command-local `JAVA_TOOL_OPTIONS` value setting
`jdk.net.unixdomain.tmpdir` to an existing short writable task directory. This
avoids a Windows IPC problem on this machine. No global Java, Git or Gradle
configuration was changed. If Gradle reports an IPC/Unix-domain socket error,
set this property to your own existing writable directory for that terminal.
