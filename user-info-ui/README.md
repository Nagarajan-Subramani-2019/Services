# user-info-ui — host shell

Java 25 / Spring Boot 4.1.1 executable WAR with a Node-built frontend.
Default URL: http://127.0.0.1:8780/user-info-ui/

The host retains signup, sign-in and profile details. It now loads its signed-in menu, component resource mappings and API routes from UI_DATA_SCHEMA. No Oracle JET or PMU dependencies are used.

## Build

Keep ui-platform-core as a sibling directory; it is a Java library embedded inside each WAR, not a third server.

```bash
./gradlew clean build
```

Outputs:
- build/libs/user-info-ui.war
- build/libs/user-info-ui-db.zip (shared table DDL under UI_DATA_SCHEMA)

Node is a build-time tool only. Set NODE_BINARY if it is not on PATH.
On Windows, stop the process using this WAR before running clean; Java locks the running archive.

## Install and start

The schema is created externally. Apply this project's DB ZIP first, then the component's registration ZIP, connecting as UI_DATA_SCHEMA to FREEPDB1. No build or startup executes SQL.

```bash
read -rsp 'UI_DATA_SCHEMA password: ' UI_DATA_DB_PASSWORD; echo
export UI_DATA_DB_PASSWORD
export UI_DATA_DB_URL='jdbc:oracle:thin:@//127.0.0.1:11521/FREEPDB1'
export EUREKA_URL='http://localhost:8760/eureka-server/eureka/'
export USER_INFO_BASE_URL='http://127.0.0.1:8771/userInfoServices'
java -jar build/libs/user-info-ui.war
```

See the sibling transaction-ui-component/DEPLOYMENT.md for complete SQL order, configuration, table explanations and API calls.

## Contracts

- POST /api/users: existing public signup.
- POST /api/sign-in: credentials checked by userInfoServices; issues a shared opaque one-hour session.
- POST /api/sign-out: revokes that session.
- GET /api/users/{id}: existing public safe profile read, unchanged.
- GET /api/menu: authenticated, database-filtered menu.
- GET /api/components/{componentCode}/{operation}: mapped API, both UI and functional grants required.
- GET /components/{componentCode}/{assetName}: registered public loader, stylesheet or manifest; never a data proxy.

Tokens stay in browser memory only, never storage, cookies or URLs. Passwords are not retained after login. Database stores only token hashes. Reload requires sign-in. Disabled/deleted/renamed accounts and revoked/expired tokens are rejected; permissions are rechecked on protected requests.

All signed-in users may initially select any user's transactions, as requested. This is not record-level ownership enforcement. USER grants/denials override ALL grants.

The existing userInfoServices and userTransactServices direct API policies are unchanged. New UI permissions do not secure those underlying APIs. Keep backend ports private; use HTTPS and reverse-proxy login rate limiting outside local development.

Component origins are explicitly allowlisted. Database registration alone does not authorize arbitrary outbound network requests. Missing credentials/tables cause protected operations to fail closed with 503 while the WAR may still deploy. Actuator health is process liveness, not end-to-end readiness.
