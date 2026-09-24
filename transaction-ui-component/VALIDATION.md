# Automatic personal transactions layout — 2026-09-24

Java 25 / Gradle 9.3 build and artifact checks passed. All 101 component Java
tests, 38 shared-library Java tests and 50 frontend/static SQL tests passed
(189 total, no failures or skips). Tests include forged user-ID rejection,
expired/invalid/disabled sessions, entitlement denies, automatic self loading,
pagination, empty results, retries, stale responses and local-only AI input.

Browser QA ran the real new component WAR through a copy of the unchanged base
UI WAR against isolated H2/fictional upstream services. Clicking Transactions
with AI automatically displayed the signed-in user's five rows on the left,
with the AI Text/Okay panel on the right. The AI button showed the placeholder
without sending/saving text. At 390px width the panels stacked without page
overflow, and Okay remained beside its field. Logout removed the screen and
no browser console errors were reported.

SQL 003 was statically tested and reviewed, not executed on Oracle. The preview
seeded equivalent H2 metadata. Source SQL 001/002 is unchanged. No real database
or user service was modified or restarted. Apply 003 and restart the updated
component to activate automatic loading in your live setup. Base UI needs no
rebuild and no new database table is introduced.

# Historical: text-only Transactions with AI validation — 2026-09-24

Built successfully with Java 25.0.4, Gradle 9.3.0 and cached dependencies.
All 63 component Java tests, 38 shared-library Java tests and 35 frontend/static
SQL tests passed (136 total, zero failures or skips). Artifact verification
confirmed both component resource folders in the WAR and both SQL migrations
in the separate SQL-only ZIP. SQL is not included in the WAR.

Browser QA used the real newly built component WAR and a copy of the existing
base UI WAR on loopback-only test ports, with an in-memory H2 registry and fake
upstream data. Item1 was replaced by Transactions with AI; Item2–Item4 stayed
disabled. Blank validation, Okay, Enter submission, cleanup on navigation,
remount and logout passed. At 390px viewport width the Okay button remained to
the right of the text field, with no horizontal page overflow. The existing
Transaction screen still rendered all five fictional rows. No browser console
errors were reported. The new screen makes no API calls and stores no text.

Oracle migration 002 was statically reviewed/tested, not executed against
Oracle. The H2 preview seeded the equivalent registry state; it did not run
Oracle PL/SQL. Live database metadata, live services and their credentials were
not changed. Base UI source was not changed or rebuilt for this delivery.
Run migration 002 and restart the component to activate the new screen.

# Historical validation — 2026-09-23

The component WAR built successfully with Java 25.0.4, Gradle 9.3.0 and Node
24.15.0. Its 63 Java tests and 19 Node tests passed. The shared library's 38
tests also passed. Across the base UI, component and shared library, 387 tests
passed, counting shared tests once.

The real component WAR ran with a disposable H2 registry/session database
and fictional upstream services. Direct API sign-in and transaction retrieval
worked without the base UI running. With the base UI running, browser sign-in,
registered menu, dynamic resources, user selection, five-row display, empty
results and logout clearing passed. Cross-WAR token use/revocation was checked
(204 logout, then 401 with the revoked token).

WAR contents include js/components/transactions/loader.js, styles.css,
manifest.json and js/components/resources/cs-mapping.json under static
resources. SQL and node_modules are excluded. The separate DB ZIP contains
only UI_DATA_SCHEMA component registration/seed SQL, not shared DDL or schema
creation. No Oracle JET/PMU binaries are required.

No real Oracle SQL or live Eureka registration was tested or modified.
Scripts need manual execution and environment testing. See
../user-info-ui/VALIDATION.md for the test matrix/limitations and
DEPLOYMENT.md for installation order and configuration.
