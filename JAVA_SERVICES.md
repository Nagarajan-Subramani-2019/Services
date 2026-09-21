# Java 25 services

Two independent Gradle projects are provided so they can be built and deployed
separately. No cloud deployment or database provisioning has been performed.

| Project / WAR | Purpose | Oracle account | Standalone port |
| --- | --- | --- | --- |
| `eureka-server/eureka-server.war` | Password-protected Eureka discovery | `EUREKA_DB`, optional `oracle` profile | 8761 |
| `initial-service/initial-service.war` | GET Hi / Hello API, Eureka client | `INITIAL_DB`, required for greetings | 8081 |

WAR output files are in each project's `build/libs/` after building.

## Stack

- Java 25, Spring Boot 4.1.1, Spring Cloud 2025.1.3, Gradle wrapper 9.3.0.
- Oracle JDBC `ojdbc17`, with its version managed by Spring Boot.
- External WAR deployment requires **Tomcat 11 / Servlet 6.1** and Java 25.
  Tomcat 9 and Tomcat 10 are not suitable for these WARs.
- Each project has one application main class and a servlet initializer.
- The same WAR supports external Tomcat and `java -jar`.

Official references: [Boot requirements](https://docs.spring.io/spring-boot/system-requirements.html),
[Cloud compatibility](https://spring.io/projects/spring-cloud/),
[WAR deployment](https://docs.spring.io/spring-boot/how-to/deployment/traditional-deployment.html).

## Build both

Install a JDK 25 and set `JAVA_HOME` to that JDK. These projects do not silently
download a JDK or change your machine's default Java. From this directory:

```bash
cd eureka-server
bash ./gradlew clean build
cd ../initial-service
bash ./gradlew clean build
```

On Windows use `gradlew.bat` instead. `build` includes tests and a WAR-layout
check that prevents packaging required Eureka classes as container-only libraries.
Tests use isolated, mocked database access
and do not connect to your GCP database or change its contents.

## Prepare Oracle accounts (administrator action, not automatic)

Keep the SSH database tunnel active **on the machine running the applications**.
`127.0.0.1` always refers to that machine, not your laptop or another VM.

The JDBC service URL is:

```text
jdbc:oracle:thin:@//127.0.0.1:11521/FREEPDB1
```

Your administrative command is only for inspecting/provisioning accounts:

```bash
sqlplus -L "sys@//127.0.0.1:11521/FREEPDB1 as sysdba"
```

Enter the administrator password at SQL*Plus's prompt. First inspect:

```sql
SHOW CON_NAME
SELECT username, account_status
FROM dba_users
WHERE username IN ('EUREKA_DB', 'INITIAL_DB');
```

The container must be `FREEPDB1`. Preserve any existing accounts and data.
For an account that does **not** already exist, an administrator can create it
with a new locally chosen password, then grant only `CREATE SESSION`:

```sql
-- EXAMPLES ONLY: replace the password placeholders locally, never in this repo.
-- Run only the statements for missing users; do not drop or reset existing users.
CREATE USER EUREKA_DB IDENTIFIED BY "<choose-a-new-local-password>" CONTAINER=CURRENT;
GRANT CREATE SESSION TO EUREKA_DB;

CREATE USER INITIAL_DB IDENTIFIED BY "<choose-a-different-local-password>" CONTAINER=CURRENT;
GRANT CREATE SESSION TO INITIAL_DB;
```

Oracle DDL commits implicitly; a rollback does not undo successful user creation.
The current applications need no tables, quotas, `RESOURCE`, `DBA`, or `SYSDBA`.
Do not paste passwords into chat or commit them. Store application secrets in
restricted environment configuration or your deployment secret manager.

Test each account independently, entering its own password when prompted:

```bash
sqlplus -L "EUREKA_DB@//127.0.0.1:11521/FREEPDB1"
sqlplus -L "INITIAL_DB@//127.0.0.1:11521/FREEPDB1"
```

No account creation, migrations, startup DDL, or application table writes are
implemented. The greeting service reads `SESSION_USER` and `CURRENT_SCHEMA`
using `SYS_CONTEXT` from `DUAL`, requiring both to be exactly `INITIAL_DB`.

## Run as two standalone executable WARs

In one terminal on the application host:

```bash
cd eureka-server
read -r -s -p 'Choose Eureka API/dashboard password: ' EUREKA_PASSWORD; echo
export EUREKA_PASSWORD
export EUREKA_USERNAME=eureka
java -jar build/libs/eureka-server.war
```

Dashboard: <http://127.0.0.1:8761/eureka-server/>. Sign in with the configured
Eureka username/password. This password is **not** an Oracle account password.

Oracle is not required by the discovery registry. To enable the optional
`EUREKA_DB` datasource, before starting Eureka set:

```bash
export SPRING_PROFILES_ACTIVE=oracle
read -r -s -p 'EUREKA_DB password: ' EUREKA_DB_PASSWORD; echo
export EUREKA_DB_PASSWORD
```

Eureka's registry remains in memory, even with this profile. An Oracle outage
does not stop the registry; its optional DB health can report DOWN while the
separate liveness/readiness probes remain independent of that database.

In another terminal on the same application host:

```bash
cd initial-service
read -r -s -p 'INITIAL_DB password: ' INITIAL_DB_PASSWORD; echo
export INITIAL_DB_PASSWORD
# Replace the placeholder locally. URL-encode username/password characters
# such as @, :, /, #, and %. Do not put a real password into shared scripts.
export EUREKA_DEFAULT_ZONE='http://eureka:<URL_ENCODED_EUREKA_PASSWORD>@127.0.0.1:8761/eureka-server/eureka/'
java -jar build/libs/initial-service.war
```

The initial service registers as `INITIAL-SERVICE` in the Eureka dashboard.
It does not fetch the registry because it does not call other services yet.
Enable `eureka.client.fetch-registry` when adding service-to-service discovery.
Registration/health changes are asynchronous and may take approximately a
minute to appear. Its database must be healthy for the client to advertise UP.

```bash
curl --fail http://127.0.0.1:8081/initial-service/api/hi
curl --fail http://127.0.0.1:8081/initial-service/api/hello
```

Successful responses:

```json
{"message":"Hi","service":"initial-service","schema":"INITIAL_DB"}
```

```json
{"message":"Hello","service":"initial-service","schema":"INITIAL_DB"}
```

Database failures or an unexpected schema return HTTP 503 without exposing SQL,
credentials, or internal connection errors. The example greeting API has no user
authentication; keep it private until an authentication/authorization design is
added. Eureka's API and dashboard require HTTP Basic authentication.

## Deploy both WARs to one external Tomcat

Use a dedicated **Tomcat 11 running Java 25**, with its connector bound to
loopback or a restricted private interface. Do not deploy over unrelated apps.

1. Configure these environment variables for the Tomcat process:
   `EUREKA_USERNAME`, `EUREKA_PASSWORD`, `INITIAL_DB_PASSWORD`, and
   `EUREKA_DEFAULT_ZONE`. The latter must use Tomcat's actual connector port,
   for example `http://eureka:<encoded-password>@127.0.0.1:8080/eureka-server/eureka/`.
2. Set `INITIAL_ADVERTISED_PORT=8080` (or the actual connector port), and set
   `INITIAL_HOSTNAME` to the hostname clients can reach. Keep `localhost` only
   when all callers run on this same host.
3. To enable Eureka's database connection too, set
   `SPRING_PROFILES_ACTIVE=oracle` and `EUREKA_DB_PASSWORD` for that process.
   This shared profile name does not change INITIAL_DB's separate datasource.
4. Copy the built files as `webapps/eureka-server.war` and
   `webapps/initial-service.war`. Keep these WAR names: their names determine
   the context paths used by these configurations.
5. Start Tomcat and inspect its startup logs. Dashboard:
   `http://127.0.0.1:8080/eureka-server/`; greetings:
   `http://127.0.0.1:8080/initial-service/api/hi` and `/api/hello`.

Tomcat controls the actual listening address, connector port, and WAR context
path. Spring's `server.port` and `server.address` configure only the executable
WAR's embedded server. Do not assume those properties secure external Tomcat.
Do not set a global `SPRING_DATASOURCE_USERNAME` or shared
`SPRING_DATASOURCE_PASSWORD`: each service uses its own account and password env.

The two WARs in one Tomcat share one JVM but have separate application contexts.
Two `java -jar` commands or two Tomcat instances use separate JVMs.

## Networking and operational notes

- The executable WARs bind to loopback by default. For cross-VM access, use a
  deliberately configured private address and restrictive firewall rules.
- HTTP Basic is not encryption. Use HTTPS/reverse proxy or SSH forwarding for
  remote access. Do not expose the Eureka registry or DB listener publicly.
- A tunnel made with `nohup` does not restart after disconnects or reboots.
  Use a reviewed systemd unit before relying on it for unattended applications.
- The existing cloud VM, Tomcat, SSH tunnel, firewall, database, and credentials
  have **not** been modified by creating these projects.
- Actuator exposes only `health` and `info`; health details are not exposed.
- There is no Kafka, gateway, CI/CD deployment, or additional business service
  in this initial scope. Add those only when the requirements are defined.

## Windows build troubleshooting

If a JDK 25 build fails before compilation with `Unable to establish loopback
connection` in `UnixDomainSockets`, use a short existing local directory for
Java's Unix-domain socket temporary files. For example, in PowerShell:

```powershell
New-Item -ItemType Directory -Force .gradle-sockets | Out-Null
$taskSocketDir = (Resolve-Path .gradle-sockets).Path
$env:JAVA_TOOL_OPTIONS = "-Djdk.net.unixdomain.tmpdir=$taskSocketDir"
.\gradlew.bat test bootWar
```

This is a process-local workaround; it does not change system Java or firewall
settings. [JDK networking properties](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/net/doc-files/net-properties.html)
