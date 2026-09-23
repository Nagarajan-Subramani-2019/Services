# user-info-ui

The screen follows the supplied sketch: **create account → sign in → Hi, hello
and user details**. It uses the existing `userInfoServices` APIs for all user
data and credential checks. No database is duplicated in this project.

## Why Node.js and a Java WAR both appear

Node.js builds the browser HTML, CSS and JavaScript. Gradle packages those assets
inside a Java 25 / Spring Boot 4.1.1 WAR. The Java service serves the UI, forwards
the three fixed API operations to `userInfoServices`, and registers with Eureka.
The deployed WAR does **not** run Node.js or require npm on the application host.
There are no third-party npm dependencies or frontend CDN dependencies.

```text
Browser → user-info-ui (8780, Java WAR)
                  │
                  ├─ static HTML / CSS / JavaScript built with Node
                  ├─ Eureka registration / optional backend discovery
                  └─ fixed same-origin API adapter → userInfoServices (8771)
                                                            │
                                                            └─ USER_INFO_SCHEMA.USER_DETAILS
```

The two schema passwords and the Oracle SSH tunnel remain the responsibility of
`userInfoServices`. Do not set database passwords in frontend JavaScript. The
UI does not query `EUREKA_DB.PROPERTIES`; its Eureka URL is supplied as runtime
configuration. Use the actual port on which your existing Eureka server runs.

## Build

Install Java 25 and Node.js 22 or newer on the build machine. The Gradle wrapper
pins Gradle 9.3.0. From this folder:

```bash
./gradlew clean build
```

PowerShell: `./gradlew.bat clean build`.

The output is `build/libs/user-info-ui.war`. The build runs Node frontend tests,
Java integration tests and checks that the WAR contains the generated UI assets
and the correct embedded/external Tomcat dependency layout.

No `npm install` is required. Gradle invokes Node directly. Set `NODE_BINARY` to
an absolute Node executable path if it is not on PATH. Frontend-only checks:

```bash
cd frontend
npm test
npm run build
```

Do not edit generated `frontend/dist` or `build` files. Change `frontend/src`
and rebuild. If adding a new asset, add its name to the explicit asset list in
`frontend/scripts/build.mjs`; Gradle packages the generated assets into the WAR.

## Run with your existing services

Start Oracle/tunnel and `userInfoServices` using their existing configuration.
Start Eureka on its configured port. Then, in Git Bash:

```bash
export USER_INFO_CONNECTION_MODE=direct
export USER_INFO_BASE_URL='http://127.0.0.1:8771/userInfoServices'
export EUREKA_URL='http://127.0.0.1:8760/eureka-server/eureka/'
java -jar build/libs/user-info-ui.war
```

Open **http://localhost:8780/user-info-ui/**. These backend/discovery ports are
configurable examples matching the recent local setup, not database changes.

The sign-up screen submits a real account creation request through
`userInfoServices`. The sign-in screen submits the entered credentials to its
existing credential-check endpoint. On success, the UI displays the returned
profile and can refresh it from the user-details API. It never invents a
successful sign-in when the backend is unavailable.

## Direct URL or Eureka discovery

`USER_INFO_CONNECTION_MODE=direct` is the default. It calls exactly the configured
`USER_INFO_BASE_URL`. This makes the first local run work without waiting for
the Eureka registry cache, while UI registration with Eureka remains enabled.

To locate the backend through Eureka instead:

```bash
export USER_INFO_CONNECTION_MODE=discovery
export USER_INFO_SERVICE_ID='userInfoServices'
export EUREKA_URL='http://127.0.0.1:8760/eureka-server/eureka/'
java -jar build/libs/user-info-ui.war
```

The backend must be registered and advertise a host and port reachable from the
UI's Java process. Registry propagation is not instant. Discovery mode fails
clearly if no backend instance is available; it does not silently switch to the
direct URL. The browser never receives or chooses a proxy destination URL.

For a local run without Eureka:

```bash
export EUREKA_CLIENT_ENABLED=false
export USER_INFO_CONNECTION_MODE=direct
java -jar build/libs/user-info-ui.war
```

Localhost always means the machine running the relevant process. For different
VMs, configure reachable hosts instead of copying loopback addresses between VMs.

## Runtime settings

| Environment variable | Default / use |
| --- | --- |
| `USER_INFO_UI_PORT` | `8780` for the embedded Java server |
| `USER_INFO_UI_BIND_ADDRESS` | `127.0.0.1`; change deliberately for a restricted VM network |
| `USER_INFO_BASE_URL` | `http://127.0.0.1:8771/userInfoServices` in direct mode |
| `USER_INFO_CONNECTION_MODE` | `direct` or `discovery` |
| `USER_INFO_SERVICE_ID` | `userInfoServices` in discovery mode |
| `USER_INFO_CONTEXT_PATH` | `/userInfoServices` in discovery mode |
| `EUREKA_CLIENT_ENABLED` | `true`; `false` disables registration/discovery |
| `EUREKA_URL` | `http://localhost:8760/eureka-server/eureka/` |
| `USER_INFO_UI_HOSTNAME` | `localhost`; advertise a reachable hostname across VMs |
| `USER_INFO_UI_ADVERTISED_PORT` | Embedded port by default; set to the actual external Tomcat connector port |
| `USER_INFO_UI_PUBLIC_URL` | Optional full UI base URL, including `/user-info-ui`, **without a trailing slash** |

`USER_INFO_UI_PUBLIC_URL` controls Eureka dashboard/health links. It does not
configure HTTPS, a proxy, firewall rules or Eureka secure-port flags. Set those
separately if your deployment terminates TLS outside the application. The
source `application.yml` contains all non-secret defaults.

## API adapter

All paths below are relative to the UI context `/user-info-ui`:

| UI Java endpoint | Existing userInfoServices endpoint | Purpose |
| --- | --- | --- |
| `POST /api/users` | `POST /userdetails` | Register username/password/email/phone |
| `POST /api/sign-in` | `POST /authuserdetails` | Check credentials and return safe profile |
| `GET /api/users/{id}` | `GET /userdetails/{id}` | Refresh a profile without user authentication |

Only these fixed operations are proxied. There is no arbitrary URL proxy, SQL
endpoint, database connection or second user store. No credentials are forwarded
in an Authorization header. Sensitive user responses are not cached. The browser
calls its own origin, so no backend CORS relaxation is required.

## Authentication boundary — important

The existing `/authuserdetails` endpoint checks credentials but does not issue a
JWT or create a server login session. This UI preserves that behavior: successful
sign-in is an in-memory screen state, not an entitlement or security boundary.
Refresh/reload requires signing in again. Sign out clears that local UI state.
Passwords are not saved in localStorage, sessionStorage or cookies.

Profile GET endpoints are public as you requested in the backend change. Anyone
who can reach them may retrieve user profile data without signing in. The welcome
screen does not change that fact. A future entitlement service must enforce
authorization on the server/API boundary, not merely hide buttons in JavaScript.

Keep this application on loopback or a restricted development network until
that enforcement exists. Use HTTPS for nonlocal traffic, including the
UI-to-backend hop carrying sign-in credentials. Apply appropriate rate limits
before making registration or credential verification internet-accessible.

## Deploy to external Tomcat

Use Tomcat 11 with Java 25 and deploy `user-info-ui.war`. Node.js is not required
on Tomcat. Open the Tomcat host/connector port followed by `/user-info-ui/`.
Do not put the WAR into the Eureka server's deployment directory by mistake.

For external Tomcat, the connector owns the listening port and bind address.
Embedded `server.port` settings do not change `server.xml`. Align the advertised
Eureka host/port with the actual connector using `USER_INFO_UI_HOSTNAME` and
`USER_INFO_UI_ADVERTISED_PORT`. Keep the WAR name/context as `user-info-ui`, or
adjust the advertised URLs for your deployment. Ensure userInfoServices and Eureka
are reachable from the Tomcat process. Deploying this WAR does not deploy or
start those dependencies.

## Database folder

`db/USER_INFO_SCHEMA/README.md` explains why no migration is needed. The existing
table is sufficient; there is no SQL ZIP because there are no new SQL files.
Future user-data tables belong in `USER_INFO_SCHEMA` through the owning backend.
This build never runs SQL or creates schemas/users/grants.

## Folder map

```text
frontend/src/        Browser interface, validation and same-origin API calls
frontend/scripts/    Node build script
frontend/test/       Node unit tests
src/main/java/      Java API adapter, upstream routing and web configuration
src/main/resources/ Runtime configuration
src/test/java/      Isolated HTTP/discovery integration tests
db/USER_INFO_SCHEMA/ No-migration explanation
build.gradle        Node + Java build and WAR packaging
build/libs/         Deployable user-info-ui.war
```

See `VALIDATION.md` for the checks performed and live-environment limitations.
