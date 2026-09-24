# Deployment and table-driven components

## Projects and artifacts

Transactions with AI update (2026-09-24): only the component WAR and its
registration SQL change. The existing table-driven base UI supports this
screen without a rebuild or restart. See TRANSACTIONS_AI.md for the upgrade.
Normal builds generate artifacts under each project's `build/libs`.

Keep user-info-ui, transaction-ui-component and ui-platform-core beside one another in Services. The common library is embedded into both WARs and is not separately deployed.

Build each WAR with ./gradlew clean build using Java25 and Node on PATH. Each produces a WAR and a separate SQL-only DB ZIP in build/libs. Existing userInfoServices, userTransactServices and eureka-server are not modified.

## SQL installation

UI_DATA_SCHEMA must already exist with permission to create its own tables. Keep the database tunnel active on the machine using 127.0.0.1:11521.

Extract the two DB ZIPs to separate directories. Connect using a password prompt:

```bash
sqlplus -L "UI_DATA_SCHEMA@//127.0.0.1:11521/FREEPDB1"
```

Run, in order (use full paths from your extraction directories):

```sql
-- From the BASE UI DB ZIP:
@UI_DATA_SCHEMA/000_install_ui_platform.sql
-- From the COMPONENT DB ZIP:
@UI_DATA_SCHEMA/001_register_transaction_component.sql
-- From the COMPONENT DB ZIP, after 001 (or by itself when upgrading):
@UI_DATA_SCHEMA/002_register_transactions_ai.sql
-- Signed-in-user automatic data loading, after 002:
@UI_DATA_SCHEMA/003_register_transactions_ai_self_data.sql
```

The base DDL validates existing tables rather than replacing them. Oracle DDL commits implicitly; investigate validation failures rather than dropping data. Script 001 uses insert-only MERGEs. Script 002 converts and enables the original Item1 once; reruns preserve later disabling decisions. Existing addresses and grants are retained. Neither Gradle nor startup runs these scripts.

No existing users/transactions are inserted or changed. There is no CREATE USER, CREATE SCHEMA or privilege GRANT in the ZIPs.

## Table responsibilities

| UI_DATA_SCHEMA table | Purpose |
|---|---|
| UI_COMPONENT_SERVER | Server code, Eureka service ID and direct base URL. |
| UI_COMPONENT | Component code, server and resource-folder mapping. |
| UI_ACTIVITY | Menu label/order, UI_ACTIVITY_CODE, view key and enabled flag. |
| FUNCTIONAL_ACTIVITY | API FUNCTIONAL_ACTIVITY_CODE and enabled flag. |
| UI_COMPONENT_API | Operation, upstream path, activity codes, response format and allowed query parameters. |
| ACTIVITY_GRANT | ALL-signed-in or USER-specific allow/deny decisions. |
| UI_SESSION | Token hash, verified user identity and one-hour expiry; never passwords/raw tokens. |

Business users stay in USER_INFO_SCHEMA; transactions stay in USER_TRANSACT_SCHEMA. They are read through APIs, not cross-schema SQL. Existing shared port properties remain in EUREKA_DB; UI registration/entitlements live in UI_DATA_SCHEMA.

After 001: Transaction and disabled Item1–Item4.
After 002: Transaction, Transactions with AI, and disabled Item2–Item4.
After 003: Transactions with AI automatically loads the signed-in user's data
on the left, alongside its right-hand text-box/Okay panel. The AI button is still
a local-only placeholder; see TRANSACTIONS_AI.md.
UI codes: UI_TRANSACTIONS (Transaction) and UI_ITEM1 (Transactions with AI).
Functional codes: TXN_USERS_READ, TXN_LIST_READ and TXN_AI_SELF_READ.

All signed-in users initially have these grants. The original Transaction screen
still permits selecting any user under its existing grants. Transactions with AI
uses self-only endpoints: a requested userId must equal the authenticated session
user. USER decisions override ALL; missing grants and disabled servers/components/
activities deny access. Script 003 is insert-only and preserves existing disables
and denies; no new table or data source is created.

## Startup

In EACH terminal:

```bash
read -rsp 'UI_DATA_SCHEMA password: ' UI_DATA_DB_PASSWORD; echo
export UI_DATA_DB_PASSWORD
export UI_DATA_DB_URL='jdbc:oracle:thin:@//127.0.0.1:11521/FREEPDB1'
export EUREKA_URL='http://localhost:8760/eureka-server/eureka/'
export USER_INFO_BASE_URL='http://127.0.0.1:8771/userInfoServices'
```

In transaction-ui-component:

```bash
export USER_TRANSACT_BASE_URL='http://127.0.0.1:8781/userTransactServices'
java -jar build/libs/transaction-ui-component.war
```

In user-info-ui:

```bash
java -jar build/libs/user-info-ui.war
```

Open http://127.0.0.1:8780/user-info-ui/, sign in, select Transaction, choose user ID/username, then Okay. Transactions are read-only here.

On different VMs, localhost means the current VM. Configure real backend addresses, the component-server table URL and the host's allowed origins. The tunnel must exist on each machine using the loopback database URL.

The host setting `UI_COMPONENT_ALLOWED_ORIGINS` is a comma-separated exact origin allowlist (scheme, host, port), defaulting to `http://127.0.0.1:8782,http://localhost:8782`. It does not include paths. `UI_COMPONENT_CONNECTION_MODE=direct` uses registered BASE_URL; `discovery` finds the registered SERVICE_ID through Eureka and retains the registered context path. Both modes enforce the origin allowlist. Update UI_COMPONENT_SERVER.BASE_URL for a different host/context; its seed will not overwrite later changes.

In discovery mode, allowlist both the registered BASE_URL origin and the resolved Eureka instance origin. Current registered upstream paths have one `/api/<slug>` operation segment; nested or arbitrary paths are rejected.

EUREKA_URL supplies discovery location; these UI WARs do not independently query EUREKA_DB.PROPERTIES. Use EUREKA_CLIENT_ENABLED=false for direct-mode development without Eureka. Use USER_INFO_CONNECTION_MODE=discovery and USER_TRANSACT_CONNECTION_MODE=discovery for component backend lookup through Eureka. Set advertised hostname/port/URL for remote services.

UI ports are USER_INFO_UI_PORT (8780) and TRANSACTION_UI_PORT (8782). For external Tomcat use Tomcat11/Java25, matching WAR context names and connector/advertised-port configuration. A conventional web-container deployment uses the connector port, not server.port.

## APIs without the host UI

Base URL: http://127.0.0.1:8782/transaction-ui-component

| Method/path | Requirement |
|---|---|
| POST /api/sign-in | JSON username/password; verifies through userInfoServices and returns accessToken/expiresAt. |
| POST /api/sign-out | Bearer token; revokes session. |
| GET /api/users?page=0&size=100 | Bearer token and TXN_USERS_READ. |
| GET /api/transactions?userId=1&page=0&size=20 | Bearer token and TXN_LIST_READ. |

After assigning the returned token to a temporary UI_TOKEN variable:

```bash
curl --fail-with-body -H "Authorization: Bearer $UI_TOKEN" \
  'http://127.0.0.1:8782/transaction-ui-component/api/transactions?userId=1&page=0&size=20'
unset UI_TOKEN
```

Never put credentials/tokens in URLs, source control or logs. API-only callers require functional grants; the host screen additionally requires its UI grant.

## Future components

1. Package js/components/<resource-name>/loader.js, styles.css and manifest.json in a component server.
2. The ES-module loader exports mount(container,{request,onUnauthorized}), returning a cleanup function that aborts requests, removes listeners and clears user data.
3. Register server/component/menu rows, functional activities, API mappings and grants in that component's SQL ZIP.
4. Approve new server origins in the host configuration. A server can contain many components; components can also come from different WARs.
5. The host derives local loader URLs from validated component names and database metadata. It does not execute arbitrary JavaScript/URLs stored in menu rows.

Current safe API response contracts are USER_OPTIONS and TRANSACTION_PAGE. Additional component screens can load through registration; a fundamentally new API response shape also needs a corresponding safe host projector. Write routes need a deliberate route/security extension; there is no unrestricted write proxy.

## Operational notes

- Keep existing backend API ports private. Their direct/public API policies are unchanged and are not protected by new UI grants.
- Use HTTPS and reverse-proxy login rate limits for non-local deployments.
- Tokens are browser-memory-only; reload requires login. Logout revokes the shared token; if revocation fails during a DB outage, the token expires within one hour.
- Add operator-controlled cleanup for expired UI_SESSION rows; no automatic DB maintenance job is installed.
- Actuator health reports process liveness, not database/upstream readiness.
- The user selector loads complete pages, capped at10,000 users, and errors explicitly above that instead of truncating. Transactions have separate paging.
- The host sends money/large IDs to the browser as exact decimal strings; no currency is assumed.
- This is the PMU-style component-server architecture adapted to the current application, not Oracle JET/PMU binary compatibility.
