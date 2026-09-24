# Validation — 2026-09-23

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
