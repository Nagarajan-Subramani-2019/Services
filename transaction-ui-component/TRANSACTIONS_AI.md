# Transactions with AI — automatic personal transactions

The SAME transaction-ui-component WAR hosts both screens on port 8782. The
original Transaction screen is unchanged. Transactions with AI automatically
loads the authenticated user's ID/name and transactions in its left panel. The
AI text box and Okay button are in a separate panel on the right. No user
selector or extra Okay click is needed to load personal transactions. The panels
stack on small screens. No host rebuild, new WAR or datasource is needed.

For this phase, submitting nonblank text displays a clear not-connected-yet
message. The AI button does not send or save the text, reload transactions, call an AI model,
create links, or draw graphs. No fake answer or graph is generated. Those are
future work, including the user's requested no-transactions link behavior.

## Source and packaging

- frontend/src/js/components/transactions-ai/loader.js: screen and form behavior.
- frontend/src/js/components/transactions-ai/styles.css: scoped styling.
- frontend/src/js/components/transactions-ai/manifest.json: UI_ITEM1 metadata.
- db/UI_DATA_SCHEMA/002_register_transactions_ai.sql: existing-table registration.
- db/UI_DATA_SCHEMA/003_register_transactions_ai_self_data.sql: self-data API metadata.
- src/main/java/com/oxygenraj/transactionui/ComponentController.java: self-only APIs.

The same component WAR packages these assets. SQL remains in the separate
transaction-ui-component-db.zip. No CREATE TABLE, CREATE USER or CREATE SCHEMA
is introduced. UI sessions and permissions continue to use the existing
UI_DATA_SCHEMA infrastructure.

## Install on an existing setup

The verified 2026-09-24 delivery is in
`build/transactions-ai-layout-release-20260924/transaction-ui-component.war`, with
`transaction-ui-component-db.zip` beside it. This separate release leaves any
running WAR untouched. No rebuild is required to use these delivered artifacts;
the next normal build still writes to build/libs.

1. Build through transaction-ui-component's Gradle 9.3 wrapper using Java 25:
   ./gradlew build, or use the verified release above. Do not use
   ui-platform-core's older standalone wrapper.
2. Stop only the old transaction-ui-component process before replacing its WAR.
   If the delivery saved the replacement in a separate release folder because
   the old WAR was locked, use that replacement path instead of build/libs.
3. Connect as UI_DATA_SCHEMA and run db/UI_DATA_SCHEMA/003_register_transactions_ai_self_data.sql.
   The shared tables and 001/002 registrations must already be installed. Fresh
   setups run shared DDL, then 001, 002, 003. No script runs automatically.
4. Start the updated component with Java 25 and the same environment variables
   and database credentials as before. The base UI WAR does not need rebuilding.
5. Sign out and back in to user-info-ui, or reload and sign in, to refresh the
   menu and cached component resources. Open Transactions with AI.

From the transaction-ui-component directory, connect without placing the
password on the command line:

```bash
sqlplus -L "UI_DATA_SCHEMA@//127.0.0.1:11521/FREEPDB1"
```

Then in SQL*Plus:

```sql
@db/UI_DATA_SCHEMA/003_register_transactions_ai_self_data.sql
exit
```

After stopping only the old component, start the delivered WAR in the terminal
with its existing UI_DATA_DB_PASSWORD and other deployment settings:

```bash
java -jar build/transactions-ai-layout-release-20260924/transaction-ui-component.war
```

Migration 002 replaces the original Item1 placeholder. New migration 003 adds
two API routes and functional permission TXN_AI_SELF_READ to the existing
UI_DATA_SCHEMA registry tables, initially for all signed-in users. Existing
disables, denies and per-user overrides are preserved; Item2–Item4 and business
data are unchanged. Review and run it only in your intended UI_DATA_SCHEMA.

## Authenticated data flow

The screen calls components/transactions-ai/current-user?page=0&size=1 through
the host's existing authenticated request function. The same component WAR's
/api/current-user endpoint returns the active session user's ID/name in the
existing USER_OPTIONS response format, without listing other users.

It then calls components/transactions-ai/my-transactions?userId=…&page=0&size=20.
The host requires userId for its TRANSACTION_PAGE adapter; the component's
/api/my-transactions endpoint compares that ID to the authenticated session and
rejects any different ID before accessing the transaction backend. Both new
endpoints require TXN_AI_SELF_READ, and the host also checks UI_ITEM1.
No user identity is inferred from DOM text, browser storage or a hardcoded ID.

The left panel includes loading, retry, empty and pagination states. Leaving
the screen cancels in-flight requests and clears transaction data and AI text.
The original Transaction endpoints and their permissions remain unchanged.

## Later phases

The future AI workflow can reuse the existing user/transaction services. A real
model endpoint, AI-specific permissions, response/link contracts and graph
behavior will be added later. This release adds self-data reads only, not AI calls.
