# user_validation_service

Spring REST service packaged as a standard WAR for an external Jakarta Servlet
6.1 container. It contains no embedded servlet container, Kafka libraries, JDBC
driver, test framework dependencies, executable WAR launcher, or schema DDL.
The project contains application code, configuration, SQL sources, and a Gradle
build. It is independent of the parent repository's remote-command UI project.

## Separate artifacts

- WAR: `build/libs/user_validation_service.war`
- SQL ZIP: `build/libs/user_validation_service_sql.zip`

The SQL ZIP contains only `install.sql`, `01_tables.sql`, and `02_indexes.sql`.
It creates tables, constraints, indexes, and column comments in the existing
connected schema. It does not create schemas/users, grant privileges, insert
sample accounts, or delete existing records. SQL is not bundled in the WAR and
the application never initializes or changes the schema at startup.

Build both artifacts with JDK 17 or 21 using the included Gradle wrapper:

```bash
./gradlew clean build
```

On Windows:

```powershell
.\gradlew.bat clean build
```

Run from this service's directory. Set `JAVA_HOME` to your JDK. On Linux, run
`chmod +x gradlew` once if the executable bit was not retained after checkout.
The wrapper uses
Gradle 8.14.3 and downloads its distribution on first use; dependencies come from
Maven Central. With a compatible Gradle installation you can also use
`gradle clean build`. No external ZIP utility or database access is needed.

Gradle `build` compiles the service, creates the WAR, packages the SQL sources
with the `sqlZip` task, and checks both archives. There are exactly two packaged
outputs in `build/libs`; no executable JAR or SQL files inside the WAR.
The checks do not execute the SQL, start the service, or deploy anything.

The SQL sources are stored in the project's `sql/` directory. The Gradle wrapper
JAR is a build tool and is not packaged in the application WAR.

## Database tables

| Table | Contents |
| --- | --- |
| `UV_USER_CREDENTIALS` | Generated user ID, unique username, password hash, ACTIVE/CLOSED status, creation/update/last successful login timestamps |
| `UV_USER_DETAILS` | User ID, full name, address, ACTIVE/CLOSED status, creation/update timestamps |
| `UV_LOGIN_STATISTICS` | One row per processed login attempt: user ID, attempted username, SUCCESS/FAILED outcome, timestamp, processing duration |

Connect as the existing `UI_DATA_SCHEMA` account to `FREEPDB1`, then execute:

```sql
@install.sql
```

Run once in a schema where these three table names are unused. Oracle DDL commits
implicitly; the scripts deliberately fail on errors rather than silently
skipping conflicting objects. The existing schema must already have CREATE TABLE
permission and tablespace quota. Credentials and schema provisioning are managed
separately from the SQL ZIP.

## Container-managed JDBC configuration

The application resolves exactly `java:comp/env/jdbc/UserValidationDS`. Its
ResourceLink and resource-ref are included in the WAR. Add the following Resource
inside the existing `GlobalNamingResources` in your container's `conf/server.xml`:

```xml
<Resource name="jdbc/UserValidationDS" auth="Container" type="javax.sql.DataSource"
          factory="org.apache.tomcat.dbcp.dbcp2.BasicDataSourceFactory"
          driverClassName="oracle.jdbc.OracleDriver"
          url="${UV_DB_JDBC_URL}" username="UI_DATA_SCHEMA" password="${UV_DB_PASSWORD}"
          initialSize="0" minIdle="0" maxIdle="5" maxTotal="20" maxWaitMillis="10000"
          validationQuery="SELECT 1 FROM DUAL" validationQueryTimeout="5"
          testOnBorrow="true" defaultAutoCommit="true"
          connectionProperties="oracle.net.CONNECT_TIMEOUT=10000;oracle.jdbc.ReadTimeout=20000"/>
```

Put the Oracle JDBC driver in the container's `lib` directory, not in the WAR.
The references contain `ojdbc11-21.11.0.0.jar`, which can be reused.

Through the supplied SSH tunnel, the JDBC URL is:

```text
jdbc:oracle:thin:@//127.0.0.1:11521/FREEPDB1
```

The tunnel must run on the computer where the external container runs:

```bash
ssh -N -o IdentitiesOnly=yes -o ExitOnForwardFailure=yes \
  -L 127.0.0.1:11521:127.0.0.1:1521 nagarajan@192.168.0.123
```

If the container runs on the same Linux host as Oracle, use
`jdbc:oracle:thin:@//127.0.0.1:1521/FREEPDB1` directly instead.

Provide the following environment settings when running the external container:

- `UV_DB_JDBC_URL`: connection URL
- `UV_DB_PASSWORD`: existing schema password, supplied outside the artifacts
- `SERVICE_API_KEY`: a random key of at least 32 characters for management APIs
- `SERVICE_LOG_DIR`: optional directory writable by the container

For Tomcat to resolve environment placeholders in server.xml, include
`org.apache.tomcat.util.digester.EnvironmentPropertySource` in its
`org.apache.tomcat.util.digester.PROPERTY_SOURCE` setting. Preserve any existing
property sources when adding it. The code uses the container-managed datasource;
it does not construct a separate connection pool.

The DB username is `UI_DATA_SCHEMA`. No DB password or API key is embedded in the
WAR, SQL, or source configuration.

## REST API

Base URL after external deployment:
`http://HOST:PORT/user_validation_service/api/v1`

Use JSON for POST bodies. All user/profile and statistics APIs require
`X-API-Key: <SERVICE_API_KEY>`; `/login` and `/health` are public. The key must be
configured before startup. Transport security is provided by the external server
or its proxy. `/login` validates credentials; it does not issue sessions or tokens.

| Method | Path | Behavior |
| --- | --- | --- |
| GET | `/users/ids?status=ALL` | All user IDs, ascending |
| GET | `/users?page=0&size=50&status=ALL` | Paginated user/profile records; size 1-100 |
| GET | `/users/{userId}` | One user/profile by ID |
| POST | `/users` | Create credentials and profile atomically; status ACTIVE |
| POST | `/users/{userId}/update` | Update supplied fields on an ACTIVE record |
| POST | `/users/{userId}/delete` | Close both records; no physical deletion |
| POST | `/users/{userId}/close` | Equivalent close action; repeated calls are idempotent |
| POST | `/login` | Validate username/password and persist the login attempt |
| GET | `/users/{userId}/statistics` | Individual counters, average duration, last login, idle days |
| GET | `/statistics` | Overall login counters, distinct logged-in users, active/closed user totals |
| GET | `/health` | Check JDBC connectivity |

Create request:

```json
{
  "username": "nagarajan",
  "password": "ReplaceWithAStrongPassword",
  "name": "Nagarajan",
  "address": "User address"
}
```

Update example: `{"address":"Updated address"}`. At least one of username,
password, name, or address must be supplied. Closed records remain readable and
their login history remains available, but they cannot log in or be updated.

Login body: `{"username":"nagarajan","password":"ReplaceWithAStrongPassword"}`.
Successful logins return 200; invalid credentials, unknown usernames, and closed
users return the same 401 response. Failed attempts are committed to statistics
before returning 401. Unknown-username failures count toward the overall totals.
Malformed/invalid request bodies return 400 and do not count as processed attempts.

Usernames are case-insensitive and stored lowercase, using 3-100 ASCII letters,
digits, or `._@-`. New passwords require 12-128 characters. Passwords use salted
PBKDF2-HMAC-SHA256 with 600,000 iterations and are never returned or logged.

`totalLoggedIn` means successful login attempts, not current sessions.
`totalFailedLogin` means failed attempts. `averageLoginTimeMs` is average server
processing duration across successful and failed attempts, excluding transport
and transaction commit. `idleLoginDays` means completed days since the last
successful login; it is null until the first success. All timestamps are UTC.

## Logging

Console and rotating file logging are enabled. The default file is
`$CATALINA_BASE/logs/user_validation_service.log`, overridable by
`SERVICE_LOG_DIR`. Logs include generated request IDs, request method/path,
response status, timing, and committed lifecycle actions. They exclude request
bodies, credentials, password hashes, API keys, names, and addresses. Kafka is
intentionally omitted for the current delivery.
