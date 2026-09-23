-- Run manually as EUREKA_DB in FREEPDB1 against the existing shared PROPERTIES table.
-- EUREKA_DB.PROPERTIES is created by the Eureka service's database scripts.
-- Applies the requested userInfoServices port 8770.
-- Re-running updates this exact application's server.port row; review before execution.

SET ECHO OFF
SET VERIFY OFF
SET DEFINE OFF
WHENEVER OSERROR EXIT FAILURE ROLLBACK
WHENEVER SQLERROR EXIT SQL.SQLCODE ROLLBACK

MERGE INTO EUREKA_DB.PROPERTIES target
USING (
    SELECT 'userInfoServices' AS application,
           'jdbc' AS profile,
           'jdbc' AS label,
           'server.port' AS property_key,
           '8770' AS property_value
      FROM dual
) seed
ON (
    target.APPLICATION = seed.application
    AND target.PROFILE = seed.profile
    AND target.LABEL = seed.label
    AND target."KEY" = seed.property_key
)
WHEN MATCHED THEN
    UPDATE SET target."VALUE" = seed.property_value
WHEN NOT MATCHED THEN
    INSERT (APPLICATION, PROFILE, LABEL, "KEY", "VALUE")
    VALUES (seed.application, seed.profile, seed.label, seed.property_key, seed.property_value);

COMMIT;

PROMPT userInfoServices server.port is set to 8770. Restart the service to apply it.
