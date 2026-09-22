# userInfoServices

Java 25, Spring Boot 4.1.1, Spring Cloud 2025.1.3 and Gradle 9.3.0 service for
registering users, retrieving authorized user details and checking credentials.
It uses the dedicated Oracle account `USER_INFO_SCHEMA` in `FREEPDB1`, listens
on port `8770` by default, and registers with the existing Eureka server. At
startup it reads Eureka's port from `EUREKA_DB.PROPERTIES` using its existing
`USER_INFO_SCHEMA` connection. No database changes happen during the build or
automatically at application startup.

## Build and artifacts

From this project directory with Java 25 installed:

```powershell
.\gradlew.bat clean build
```

Git Bash/Linux: `./gradlew clean build`.
The build produces `build/libs/userInfoServices.war` and
`build/libs/userInfoServices-db.zip`. The ZIP contains SQL grouped by target
schema; the WAR contains the application. Oracle is not needed for the automated
tests. SQL is packaged separately following the reference pricing service and
Eureka layout:

```text
db/
  USER_INFO_SCHEMA/
    001_create_properties.sql
    002_create_user_details.sql
    003_seed_port.sql
```

All three scripts belong to the existing target schema `USER_INFO_SCHEMA`
and run while connected as that owner. Schema creation, credentials and grants
are handled externally; no schema-provisioning script is included in this
project's database folder or SQL ZIP. Folder names do not create users or switch
database sessions. Neither Gradle nor the application executes these files.

## Prepare Oracle once

Keep the existing Oracle tunnel listening on `127.0.0.1:11521` on the application
host. Back up existing application data and inspect objects before running DDL.
The scripts require `FREEPDB1`; they stop on a wrong container or owner. Oracle
DDL commits implicitly, so `ROLLBACK` does not undo successful table
creation. The create scripts deliberately fail if their target already exists.
Do not replace an existing schema or table to make setup pass.

Before running these scripts, have your external provisioning process prepare
`USER_INFO_SCHEMA` in `FREEPDB1`, with working credentials, `CREATE SESSION`,
`CREATE TABLE` for setup, and an appropriate bounded tablespace quota. This
service does not create accounts, set database passwords or grant privileges.
Never run it as SYS, SYSTEM or with a DBA role. After setup, the DBA can remove
`CREATE TABLE` if future schema changes will be administered separately;
normal API operations use owned tables.

The default Eureka port lookup also needs read access to the existing
`EUREKA_DB.PROPERTIES` table. While connected to `FREEPDB1`, the table owner
`EUREKA_DB` or an authorized administrator must apply this grant manually:

```sql
SHOW CON_NAME
-- Confirm FREEPDB1 before applying this one-time grant.
GRANT SELECT ON EUREKA_DB.PROPERTIES TO USER_INFO_SCHEMA;
```

This is an external provisioning step documented here only. It is not included
in the three-script DB ZIP and is never executed by the service. The lookup uses
the same `USER_INFO_DB_URL` and `USER_INFO_DB_PASSWORD` as the user-data
connection; no `EUREKA_DB_PASSWORD` is required. The Eureka table and its
`eureka-server / jdbc / jdbc / server.port` row must already exist.

Connect as the application owner, entering the database password only at the
SQL*Plus prompt:

```powershell
sqlplus -L "USER_INFO_SCHEMA@//127.0.0.1:11521/FREEPDB1"
```

Inspect first:

```sql
SHOW CON_NAME
SELECT USER, SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA') AS CURRENT_SCHEMA FROM DUAL;
SELECT TABLE_NAME FROM USER_TABLES
WHERE TABLE_NAME IN ('PROPERTIES', 'USER_DETAILS');
```

Run each create script only if its corresponding table is absent. If a table
already exists, compare its columns and constraints to the SQL file and arrange
an explicit migration for differences. For a fresh schema, execute in order:

```sql
@db/USER_INFO_SCHEMA/001_create_properties.sql
@db/USER_INFO_SCHEMA/002_create_user_details.sql
@db/USER_INFO_SCHEMA/003_seed_port.sql

SELECT APPLICATION, PROFILE, LABEL, "KEY", "VALUE"
FROM USER_INFO_SCHEMA.PROPERTIES
WHERE APPLICATION = 'userInfoServices'
  AND PROFILE = 'jdbc' AND LABEL = 'jdbc' AND "KEY" = 'server.port';
EXIT
```

When using the extracted DB ZIP, run the paths from its root without the `db/`
prefix. Script `003_seed_port.sql` sets this service's port to `8770`: its MERGE
updates an existing matching row or inserts a missing row. Re-running it
overwrites the value only for `APPLICATION=userInfoServices`, `PROFILE=jdbc`,
`LABEL=jdbc`, `KEY=server.port` in `USER_INFO_SCHEMA.PROPERTIES`. Review that
exact row before running the script. It does not update Eureka's configuration
or any other property. No default user, administrator or application password
is seeded.

## Start the service

The database is required for registration, authentication and the default Eureka
port lookup. Supply the `USER_INFO_SCHEMA` password externally. The existing
local password fallback in application configuration has been preserved; remove
that fallback for production and supply a deployment secret instead. In PowerShell:

```powershell
$userInfoDbSecret = Read-Host 'USER_INFO_SCHEMA password' -AsSecureString
$env:USER_INFO_DB_PASSWORD = [System.Net.NetworkCredential]::new('', $userInfoDbSecret).Password
$env:USER_INFO_DB_URL = 'jdbc:oracle:thin:@//127.0.0.1:11521/FREEPDB1'
java -jar build/libs/userInfoServices.war
```

For Git Bash/Linux:

```bash
read -r -s -p 'USER_INFO_SCHEMA password: ' USER_INFO_DB_PASSWORD
echo
export USER_INFO_DB_PASSWORD
java -jar build/libs/userInfoServices.war
```

Keep credentials out of source files, command history, request logs and shell
tracing. For deployed services, supply them through restricted environment
configuration or a secret manager.

| Environment variable | Default / behavior |
| --- | --- |
| `USER_INFO_DB_PASSWORD` | Password for `USER_INFO_SCHEMA`; supply externally for deployment |
| `USER_INFO_DB_URL` | `jdbc:oracle:thin:@//127.0.0.1:11521/FREEPDB1` |
| `USER_INFO_PORT` | `8770`; this service's embedded listening port |
| `USER_INFO_DB_PORT_ENABLED` | `false`; set `true` to load this service's port from `USER_INFO_SCHEMA.PROPERTIES` |
| `USER_INFO_EUREKA_DB_PORT_ENABLED` | `true`; read Eureka's port from `EUREKA_DB.PROPERTIES` when the Eureka client is enabled |
| `USER_INFO_BIND_ADDRESS` | `127.0.0.1` |
| `USER_INFO_HOSTNAME` | `localhost`; hostname advertised to Eureka |
| `EUREKA_URL` | `http://localhost/eureka-server/eureka/`; endpoint template whose port comes from Oracle in database mode |
| `EUREKA_CLIENT_ENABLED` | `true`; set `false` to skip registration and the Eureka port lookup |

The default API base URL is `http://127.0.0.1:8770/userInfoServices`. The database
tunnel remains on `127.0.0.1:11521`; changing an HTTP service port does not change
the Oracle tunnel port. `localhost` and `127.0.0.1` refer to the application host,
so these defaults assume the tunnel and Eureka are reachable on that same host.
For a remote Eureka server, set the host, scheme and path through `EUREKA_URL`.

## Shared Eureka port configuration

By default, startup connects to Oracle as `USER_INFO_SCHEMA`, reads the single
row in `EUREKA_DB.PROPERTIES` matching `APPLICATION=eureka-server`,
`PROFILE=jdbc`, `LABEL=jdbc`, `KEY=server.port`, and replaces the port in
`EUREKA_URL` with that value. This includes replacing any explicit port supplied
in the URL. The URL's scheme, host and path continue to identify the Eureka
endpoint. The service then registers at that resolved endpoint while listening
on its own port `8770`.

The database port must be an integer from 1 to 65535. A missing grant, missing
or duplicate row, invalid value, bad credentials or failed connection stops
startup in this mode. There is no fallback to port `8760` or to a port embedded
in `EUREKA_URL`.

To configure the Eureka endpoint manually instead, disable only its database
port lookup and supply a complete URL with the actual Eureka port:

```powershell
$env:USER_INFO_EUREKA_DB_PORT_ENABLED = 'false'
$env:EUREKA_URL = 'http://localhost:8760/eureka-server/eureka/'
```

`8760` above is a manual-configuration example; replace it with the port Eureka
actually uses. Setting `EUREKA_CLIENT_ENABLED=false` skips both registration
and the Eureka port lookup. Neither option removes the database requirement
for user records.

There is no polling or live reload. If Eureka's database port changes, restart
Eureka so it listens on the new port and restart `userInfoServices` so it reads
the new endpoint. A restarted client cannot connect to a new port until Eureka
is listening there. Changing this service's own port requires restarting
`userInfoServices`; it does not require changing Eureka's listening port.

## Optional database control of this service's own port

This is separate from the default-enabled Eureka lookup. With
`USER_INFO_DB_PORT_ENABLED=false`, `USER_INFO_PORT` controls the embedded
listening port and defaults to `8770`. To read this service's port from Oracle,
set `USER_INFO_DB_PORT_ENABLED=true` and restart. That lookup reads exactly
`APPLICATION=userInfoServices`, `PROFILE=jdbc`, `LABEL=jdbc`, `KEY=server.port`
from `USER_INFO_SCHEMA.PROPERTIES`.

A valid value overrides the ordinary port setting, including command-line
`--server.port`. A missing, duplicate or invalid row, missing password or
connection failure stops startup when this lookup is enabled. Changes are read
at startup, not live. Script `003_seed_port.sql` sets this row to `8770` even if
it previously held another value.

For an intentional port change, first inspect the existing row and select an
available integer from 1 to 65535. The following optional recipe is fully
commented to prevent accidental execution; review and uncomment it locally
while connected as `USER_INFO_SCHEMA` in `FREEPDB1`:

```sql
-- MERGE INTO USER_INFO_SCHEMA.PROPERTIES target
-- USING (
--     SELECT 'userInfoServices' AS application, 'jdbc' AS profile,
--            'jdbc' AS label, 'server.port' AS property_key,
--            '8771' AS property_value FROM dual
-- ) chosen
-- ON (target.APPLICATION = chosen.application AND target.PROFILE = chosen.profile
--     AND target.LABEL = chosen.label AND target."KEY" = chosen.property_key)
-- WHEN MATCHED THEN UPDATE SET target."VALUE" = chosen.property_value
-- WHEN NOT MATCHED THEN
--     INSERT (APPLICATION, PROFILE, LABEL, "KEY", "VALUE")
--     VALUES (chosen.application, chosen.profile, chosen.label,
--             chosen.property_key, chosen.property_value);
-- COMMIT;
```

Restart after committing a change. With database port lookup disabled,
`USER_INFO_PORT` controls the embedded listening port.

## API and authorization

All paths below are relative to `/userInfoServices`.

| Method and path | Access | Purpose |
| --- | --- | --- |
| `POST /userdetails` | Public | Register a user; role is always `USER` |
| `POST /authuserdetails` | Public | Verify username/password for an enabled user |
| `GET /userdetails?page=0&size=20` | HTTP Basic, `ADMIN` only | Paginated user details |
| `GET /userdetails/{id}` | HTTP Basic, same user or `ADMIN` | One user's details |

Registration takes JSON with these fields (replace the password placeholder
with a new private password):

```json
{
  "username": "alice",
  "password": "<your own private password>",
  "email": "alice@example.com",
  "phoneNumber": "+919876543210"
}
```

Credential verification takes only `username` and `password` in JSON. It is a
login check: success does not create a session or issue a JWT/token. GET calls
must send HTTP Basic credentials using the same registered database user on
each request. For example, curl prompts for the registered user's password:

```powershell
curl.exe --user alice "http://127.0.0.1:8770/userInfoServices/userdetails/1"
```

Use the actual ID returned during registration. An administrator can list users:

```powershell
curl.exe --user adminname "http://127.0.0.1:8770/userInfoServices/userdetails?page=0&size=20"
```

The collection uses zero-based pages and accepts `size` from 1 to 100.
Usernames and email addresses are trimmed and normalized to lowercase by the
application and are unique. Direct SQL inserts must follow the same convention;
the database unique constraints operate on the stored values. Passwords must
contain at least 10 Unicode characters and at most 72 UTF-8 bytes, are never trimmed, and are stored only as BCrypt hashes
with cost 12. Hashes are not returned in API responses. Usernames must contain
3 to 64 letters, digits, dots, underscores or hyphens, starting with a letter or
digit. Email addresses are limited to 254 characters. The API accepts phone
numbers with 7 to 20 digits and an optional leading `+`; the database column
allows 32 characters. The database accepts
only `USER`/`ADMIN` roles and `0`/`1` enabled values. A client cannot assign a role
or enabled state through public registration.

## Promote the first administrator

Register an ordinary user with a private password first. There is no default
administrator. An authorized operator can then connect as `USER_INFO_SCHEMA`
in `FREEPDB1`, verify the selected username, and promote only that existing
enabled account. Replace the placeholder below with its normalized username:

```sql
SELECT ID, USERNAME, USER_ROLE, ENABLED
FROM USER_INFO_SCHEMA.USER_DETAILS
WHERE USERNAME = '<registered-lowercase-username>';

UPDATE USER_INFO_SCHEMA.USER_DETAILS
SET USER_ROLE = 'ADMIN', MODIFIED_AT = SYSTIMESTAMP
WHERE USERNAME = '<registered-lowercase-username>' AND ENABLED = 1;
-- Verify that exactly one intended row was updated before committing.
COMMIT;
```

Use the promoted account's HTTP Basic credentials for the collection endpoint.
Role promotion is a manual administrative operation; the service exposes no
public role assignment API. To disable an account administratively, set
`ENABLED=0` and update `MODIFIED_AT` for its exact ID, then commit.

## Deployment

For executable WAR deployment, run `java -jar` as shown above. To expose it to
another machine, explicitly set `USER_INFO_BIND_ADDRESS` and advertise a
reachable `USER_INFO_HOSTNAME`. Serve remote traffic over HTTPS using your
deployment's TLS termination and restrict access with a firewall. HTTP Basic
and registration/login requests carry credentials and require TLS outside a
trusted local connection. Put request throttling in front of public registration
and credential checks before exposing the service broadly.

For external Tomcat, use Tomcat 11 on Java 25 and deploy as
`userInfoServices.war` to preserve the context path. The external connector's
port, bind address and TLS configuration belong to Tomcat's `server.xml`;
`USER_INFO_PORT`, the database port row and `USER_INFO_BIND_ADDRESS` cannot
reconfigure that connector. Align the advertised service port with the actual
connector when configuring Eureka registration.

The service does not create or migrate schemas, recover passwords, issue tokens,
or provide a user update/delete API. Apply SQL explicitly using the documented
owner and order, and keep database backups according to your deployment policy.
