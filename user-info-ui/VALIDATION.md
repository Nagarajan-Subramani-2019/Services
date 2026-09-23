# Validation — 2026-09-23

## Scope

New `user-info-ui` project only. Existing Eureka, userInfoServices, database
tables and live user records were not modified by this task. The requested
decorative banner has been removed; the interface is a single-column account
screen with create-account, sign-in and welcome/profile states.

## Automated checks

- Gradle build using Java 25.0.4, Gradle 9.3.0 and Node 24.15.0.
- 107 Java tests: 32 configuration cases, 67 HTTP integration cases and
  8 discovery cases. Zero failures/errors.
- 58 Node tests passed, covering request construction, validation, error
  handling, limits and the banner-free layout regression.
- WAR verification checks generated HTML/CSS/JavaScript, executable/external
  Tomcat dependency placement, and absence of SQL/node_modules.
- No npm dependency installation, schema creation or SQL execution is needed.

HTTP integration tests launch disposable loopback servers; discovery tests use
an isolated DiscoveryClient fixture. They verify actual HTTP forwarding,
credential checking, public profile retrieval, safe response projection,
sanitized errors, response limits, timeouts configured in the client, fixed
destinations, redirect rejection and no-store/security headers.

## Browser verification

The executable WAR was previewed locally with Eureka disabled and a separate
disposable in-memory backend. Signup, invalid credentials, successful sign-in,
the returned profile, profile refresh and sign-out clearing were checked.
The final banner-free build was retested through signup/sign-in/sign-out;
desktop and 390-pixel mobile layouts were inspected with no horizontal overflow.
This fixture has no Oracle connection and is
not included in the delivered project.

## Not verified against the live environment

The existing local userInfoServices endpoint at port 8771 and Eureka endpoint
at port 8760 were not accepting connections during this task. Therefore live
Oracle-backed signup and actual Eureka registration are **not claimed tested**.
External Tomcat deployment and public HTTPS/network routing were not exercised.
Start those services and use their actual addresses in the supplied environment
variables before testing real accounts.

The existing credential check is not a JWT/session/entitlement implementation.
Profile reads remain public by the existing backend design. Keep the UI and API
restricted until server-side entitlement is implemented.

## Local Windows build note

This machine needed a task-local Java IPC temporary-directory override to avoid
a Windows Unix-domain socket error in Gradle. No persistent Java settings were
changed. If the same error occurs, choose an existing short writable directory
and set `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=<directory>` for that
terminal before invoking Gradle. This is an environment workaround, not an
application requirement.
