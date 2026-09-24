# Transaction UI component server

An independently deployable Java 25 / Spring Boot 4.1.1 WAR serving UI components and read-only API adapters. Node builds static assets but is not needed at runtime.

Default service: transaction-ui-component; port8782; context /transaction-ui-component.

The first component, transactions, renders inside user-info-ui. It obtains user ID/username options from userInfoServices, then displays the selected user's transactions from userTransactServices when Okay is pressed.

The second component, transactions-ai, replaces Item1 with **Transactions with AI**.
It automatically loads the signed-in user's transactions on the left, with a
text box and an Okay button in a separate right-hand panel. The AI button still
has only a local placeholder response: no AI/model call, saved prompt, generated
link, chart or new table. See [TRANSACTIONS_AI.md](TRANSACTIONS_AI.md) for menu/API
registration SQL and deployment. Existing Transaction behavior is unchanged.

```text
Browser -> user-info-ui:8780
              |-- UI_DATA_SCHEMA: sessions, menus, component/API mappings
              +-- transaction-ui-component:8782
                    |-- js/components/transactions/loader.js + styles + manifest
                    |-- userInfoServices:8771 -> user selector
                    +-- userTransactServices:8781 -> selected-user transactions
```

The component deploys and exposes authenticated APIs without the host UI running. It still needs UI_DATA_SCHEMA, userInfoServices and userTransactServices for its operations. Both WARs use the embedded ui-platform-core library, not calls to the host UI, for sessions and entitlements.

## Build

Keep ui-platform-core as a sibling, then run ./gradlew clean build.

Outputs:
- build/libs/transaction-ui-component.war
- build/libs/transaction-ui-component-db.zip

The ZIP contains only component-owned registration/seed SQL. Shared table DDL belongs to user-info-ui. SQL is not embedded in the WAR and no schema/user is created.

## Reference pattern

The supplied PMU WAR informed the folder, loader, metadata and mapping structure. This implementation uses native ES modules and Spring Boot, not Oracle JET/RequireJS or PMU libraries. No reference business logic is copied.

Public assets: /js/components/transactions/loader.js, styles.css, manifest.json.
js/components/resources/cs-mapping.json is a build inventory; the database is the runtime authority for registration, menus, routes and permissions.

See [DEPLOYMENT.md](DEPLOYMENT.md) for setup and extensibility details.
