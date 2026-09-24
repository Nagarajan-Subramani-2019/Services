# userTransactServices

Java 25 / Spring Boot 4.1.1 / Spring Cloud 2025.1.3 / Gradle transaction API.
Default embedded port: **8781**. Context: **`/userTransactServices`**.

This change is API-only. No UI, user-profile copying, user lookup or account
validation was added. `userId` is a positive logical reference to a user managed
by userInfoServices; the API does **not yet check whether that user exists**.

## What is stored

The already-created `USER_TRANSACT_SCHEMA` owns the quoted uppercase table
`USER_TRANSACT_SCHEMA."TRANSACTION"`. It contains:

| API field | Meaning |
| --- | --- |
| `id` | Sequence-generated transaction ID; not supplied on create |
| `userId` | Positive user ID; no cross-schema user details stored |
| `monthName` | Full English month, e.g. `SEPTEMBER`; trimmed and uppercased |
| `monthCount` | Positive **number of months**, not the month number; 24 is valid |
| `amount` | Nonnegative amount, at most 17 integer digits and 2 decimal places |
| `version` | Starts at 0; increments on every update to prevent lost edits |
| `createdAt`, `modifiedAt` | Database timestamps with time zone |

The amount is stored as entered: it is not multiplied by the number of months.
No currency, interest, year or payment-processing rules are inferred. Zero is
allowed; negative amounts and fractional month counts are rejected.

## API endpoints

Base URL: `http://localhost:8781/userTransactServices`

| Method | Path | Result |
| --- | --- | --- |
| POST | `/transactions` | Add/store one transaction; `201` with body and Location |
| GET | `/transactions?page=0&size=20` | List transactions, sorted by ID |
| GET | `/transactions?userId=42&page=0&size=20` | List only one user's transactions |
| GET | `/transactions/{id}` | Fetch one transaction, or `404` |
| PUT | `/transactions/{id}` | Replace editable fields using the last-read version |

Page is zero-based; size is 1–100. List responses contain `items`, `page`,
`size`, `totalElements`, `totalPages`. Repeated POSTs create separate records;
there is no implicit upsert or retry deduplication. No DELETE endpoint is provided.

### Add / store

Run after the service and database are ready. Replace `42` with your intended
user ID from userInfoServices:

```bash
curl -i -X POST 'http://localhost:8781/userTransactServices/transactions' \
  -H 'Content-Type: application/json' \
  --data '{"userId":42,"monthName":"September","monthCount":24,"amount":1234.56}'
```

Use the returned `id` in the next requests (the examples below use ID `1`):

```bash
curl 'http://localhost:8781/userTransactServices/transactions/1'
curl 'http://localhost:8781/userTransactServices/transactions?userId=42&page=0&size=20'
```

### Update

Include all four editable fields plus the **latest returned version**:

```bash
curl -i -X PUT 'http://localhost:8781/userTransactServices/transactions/1' \
  -H 'Content-Type: application/json' \
  --data '{"userId":42,"monthName":"October","monthCount":36,"amount":1500.00,"version":0}'
```

If version 0 is current, the updated response contains version 1. Sending version
0 again returns `409 VERSION_CONFLICT`; GET the latest record before deciding
whether to retry. Invalid input is `400`, missing records `404`, and database
failures use a sanitized `503` error. Unknown JSON fields are rejected.

## Build and output

From this folder with Java 25 on PATH:

```bash
./gradlew clean build
```

PowerShell: `./gradlew.bat clean build`. Stop any process using this WAR before
`clean`, especially on Windows. The wrapper uses Gradle 9.3.0.

Outputs:

```text
build/libs/userTransactServices.war
build/libs/userTransactServices-db.zip
```

The WAR contains Java, runtime configuration and the Oracle driver—not SQL.
The ZIP contains SQL only, grouped by owner schema:

```text
USER_TRANSACT_SCHEMA/001_create_transaction.sql
EUREKA_DB/002_seed_port.sql
```

## Run the SQL manually first

The schemas are created externally. Neither Gradle nor startup creates schemas,
users, grants or tables. No changes to the live database were performed when
this project was created.

1. Keep the existing Oracle tunnel to `127.0.0.1:11521/FREEPDB1` active, or use
   your actual Oracle host/service.
2. As `USER_TRANSACT_SCHEMA`, run the one-time table/sequence/index script.
3. Optionally, as `EUREKA_DB`, seed this service's port row if enabling its own
   database-driven listening port. This MERGE affects only `userTransactServices`.

From the source project directory, SQL*Plus prompts for each password:

```bash
sqlplus -L 'USER_TRANSACT_SCHEMA@//127.0.0.1:11521/FREEPDB1' @db/USER_TRANSACT_SCHEMA/001_create_transaction.sql
sqlplus -L 'EUREKA_DB@//127.0.0.1:11521/FREEPDB1' @db/EUREKA_DB/002_seed_port.sql
```

After extracting the ZIP, use its schema-relative paths without the `db/`
prefix. DDL commits implicitly; the creation script deliberately fails on an
existing object instead of dropping or overwriting it. If partially applied,
inspect existing objects before continuing. Schema accounts must already have
the externally managed permissions needed to create their own objects.

The shared `EUREKA_DB.PROPERTIES` table must already exist. The separate existing
row `eureka-server / jdbc / jdbc / server.port` supplies Eureka's port. This
project does not create that table or change that Eureka-server row.

## Start with database-configured Eureka

Two separate connections are used:

- `USER_TRANSACT_SCHEMA`: pooled JDBC connection for transaction data.
- `EUREKA_DB`: short-lived, read-only startup queries to its shared PROPERTIES
  table. Credentials never fall back to the transaction account.

In Git Bash/Linux, enter passwords privately at the prompts:

```bash
read -rsp 'USER_TRANSACT_SCHEMA password: ' USER_TRANSACT_DB_PASSWORD
printf '\n'
export USER_TRANSACT_DB_PASSWORD
read -rsp 'EUREKA_DB password: ' EUREKA_DB_PASSWORD
printf '\n'
export EUREKA_DB_PASSWORD

export USER_TRANSACT_DB_URL='jdbc:oracle:thin:@//127.0.0.1:11521/FREEPDB1'
export EUREKA_DB_URL='jdbc:oracle:thin:@//127.0.0.1:11521/FREEPDB1'
export EUREKA_URL='http://localhost/eureka-server/eureka/'
java -jar build/libs/userTransactServices.war
```

Startup reads Eureka's port from `EUREKA_DB.PROPERTIES`, inserts it into
`EUREKA_URL`, verifies the existing transaction objects, starts on 8781, and
the Eureka client attempts registration. Registration still requires the
Eureka server and network route to be available. Restart after changing a
database port setting; these are startup reads, not live polling.

The default loopback hostname works only when services run on the same machine.
On different VMs, set the actual Eureka hostname and reachable service hostname;
do not advertise another machine's `localhost`.

## Runtime settings

| Environment variable | Default / effect |
| --- | --- |
| `USER_TRANSACT_DB_PASSWORD` | Required; no built-in password |
| `USER_TRANSACT_DB_URL` | Oracle Thin `127.0.0.1:11521/FREEPDB1` |
| `EUREKA_DB_PASSWORD` | Required when either database-port lookup is enabled |
| `EUREKA_DB_URL` | Same default Oracle address, separate owner connection |
| `USER_TRANSACT_PORT` | `8781` |
| `USER_TRANSACT_DB_PORT_ENABLED` | `false`; when true reads this service's own port from EUREKA_DB.PROPERTIES, overriding `server.port` |
| `USER_TRANSACT_EUREKA_DB_PORT_ENABLED` | `true`; reads Eureka's existing port |
| `EUREKA_CLIENT_ENABLED` | `true`; false skips Eureka discovery-port lookup and registration |
| `EUREKA_URL` | `http://localhost/eureka-server/eureka/`; supply a full URL including port when lookup is disabled |
| `USER_TRANSACT_DB_CHECK_ENABLED` | `true`; validates owner, table columns and sequence without writing or consuming an ID |
| `USER_TRANSACT_BIND_ADDRESS` | `127.0.0.1`; restricted local access by default |
| `USER_TRANSACT_HOSTNAME` | `localhost`; advertised Eureka hostname |
| `USER_TRANSACT_ADVERTISED_PORT` | Defaults to the resolved service port |
| `USER_TRANSACT_PUBLIC_URL` | Optional app URL including `/userTransactServices`, no trailing slash; advertised links only |

For explicit Eureka URL mode, use `USER_TRANSACT_EUREKA_DB_PORT_ENABLED=false`
and e.g. `EUREKA_URL=http://localhost:8760/eureka-server/eureka/`. To disable
Eureka entirely, use `EUREKA_CLIENT_ENABLED=false`. An explicitly enabled
**own-port** database lookup remains independent of that flag.

## Deployment and security boundary

Executable WAR: Java 25. External container: Tomcat 11 with Java 25. With external
Tomcat, its connector controls the listening port—not `server.port`. Set the
advertised host/port to match that connector and keep the WAR/context name
`userTransactServices`. Public URL settings do not configure TLS or firewall rules.

The requested transaction endpoints are currently public, stateless APIs with no
user/role validation. No Basic-login popup or generated password is used. Other
routes are denied; only Actuator health is exposed without details. **Anyone who
can reach these APIs can list or modify transactions.** Keep them on loopback or
a trusted restricted network until the future entitlement service enforces
authorization at the API boundary. UI checks alone are not sufficient.

Use HTTPS for nonlocal access, manage secrets outside source control, and do not
run the application as SYS/SYSDBA. No cross-schema grant or foreign key to user
details is required for this API-only implementation.

## Folders

```text
src/main/java/com/oxygenraj/transact/
  api/          Requests, responses, controllers and sanitized errors
  service/      Transaction boundaries and optimistic-update handling
  repository/   Parameterized Oracle SQL, pagination and sequence IDs
  domain/       Stored transaction record
  config/       Separate Eureka DB bootstrap, readiness and HTTP policy
src/main/resources/  Runtime YAML and environment-processor registration
src/test/            Isolated API/database/configuration tests
db/USER_TRANSACT_SCHEMA/  Transaction DDL only
db/EUREKA_DB/             Optional own-port MERGE only
build.gradle              WAR + SQL ZIP packaging; no SQL execution
```

See `VALIDATION.md` for tested behavior and live-environment limitations.
