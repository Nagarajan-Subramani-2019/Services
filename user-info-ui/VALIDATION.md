# Validation — 2026-09-23 component platform update

## Scope

The base UI now hosts database-registered components and shares opaque,
revocable sessions and activity grants with transaction-ui-component through
ui-platform-core. The original signup/profile flow and banner-free design
remain. No existing business-service source, real user/transaction data,
live database schema or running deployment was modified during verification.

## Automated checks

Java 25.0.4, Gradle 9.3.0, Node 24.15.0; cached dependencies, offline build.

| Project | Java tests | Node tests | Failures |
|---|---:|---:|---:|
| user-info-ui | 182 | 85 | 0 |
| transaction-ui-component | 63 | 19 | 0 |
| ui-platform-core | 38 | — | 0 |

387 tests passed, with no skipped Java tests. Shared-core tests are counted
once, although both application builds run them. Both complete WAR builds and
SQL ZIP checks succeeded; the component's extra numeric regressions passed
in a subsequent test run without changing its WAR.

Coverage includes registry routing, origin allowlists, direct/discovery lookup,
session expiry/revocation, per-user grant overrides, disabled accounts and
activities, sanitized errors, bounded responses, exact decimal amounts/large
IDs, pagination, cancellation, dynamic component loading and UI cleanup.
WAR checks cover executable/external-Tomcat dependency placement, required
assets and absence of SQL/node_modules. DB ZIPs contain SQL only.

## Executable WAR / browser verification

Both real built WARs ran on isolated loopback ports 18780/18782 using a
disposable H2 database and fictional upstream HTTP services. Eureka was
explicitly disabled. No Oracle connection or real account was used.

- Component sign-in and five-row transaction retrieval succeeded before the
  base UI started: APIs do not depend on the host UI running.
- Base sign-in loaded Transaction and disabled Item1–Item4 from registry
  tables, then loaded the component JavaScript/CSS through the host.
- The user selector showed two fictional users; selecting user 1 and Okay
  displayed five monthly rows. Selecting user 17 cleared the previous rows
  and displayed the empty-state message.
- Desktop and narrow-screen layouts were checked; document width did not
  overflow the viewport. Wide transaction data scrolls within its panel.
- Browser sign-out removed the workspace/data. No browser console errors.
- A base-issued token worked directly against the component; base sign-out
  returned 204 and that token then received 401 from the component.

The QA helper and fictional credentials are outside delivered projects and
are not embedded in either WAR. Temporary previews are not a live deployment.

## Not tested against the live environment

Oracle DDL/seed SQL was reviewed and packaged but NOT executed on Oracle.
H2 tests do not prove Oracle migration execution. Actual Oracle-backed
sessions, live Eureka registration, external Tomcat and public HTTPS/network
routing still require deployment-environment verification.

Run base DDL first, then component registration SQL, as UI_DATA_SCHEMA.
Set UI_DATA_DB_PASSWORD for both WARs. Neither build nor startup runs SQL.
Health reports process liveness, not database or upstream readiness.

All signed-in users initially may select any user's transactions. Existing
business API policies are unchanged; protect their ports separately. UI grants
do not secure direct access to those APIs. Reload requires sign-in because
bearer tokens remain only in browser memory.

## Local Windows build note

This host needed a process-only JAVA_TOOL_OPTIONS override:
`-Djdk.net.unixdomain.tmpdir=<existing writable directory>` to avoid Java's
Windows temporary Unix-domain socket-path error. No machine-wide Java or
security settings were changed.
