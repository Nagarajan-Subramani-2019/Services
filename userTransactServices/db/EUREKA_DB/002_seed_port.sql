-- Run manually as EUREKA_DB against its EXISTING PROPERTIES table.
-- Seeds only this service's optional listening-port setting (8781).
-- Does not change eureka-server's existing port or create the shared table.
SET ECHO OFF
SET VERIFY OFF
SET DEFINE OFF
WHENEVER OSERROR EXIT FAILURE ROLLBACK
WHENEVER SQLERROR EXIT SQL.SQLCODE ROLLBACK

MERGE INTO EUREKA_DB.PROPERTIES target
USING (
    SELECT 'userTransactServices' AS application, 'jdbc' AS profile, 'jdbc' AS label,
           'server.port' AS property_key, '8781' AS property_value
      FROM dual
) seed
ON (target.APPLICATION = seed.application AND target.PROFILE = seed.profile
    AND target.LABEL = seed.label AND target."KEY" = seed.property_key)
WHEN MATCHED THEN UPDATE SET target."VALUE" = seed.property_value
WHEN NOT MATCHED THEN
    INSERT (APPLICATION, PROFILE, LABEL, "KEY", "VALUE")
    VALUES (seed.application, seed.profile, seed.label, seed.property_key, seed.property_value);
COMMIT;
PROMPT userTransactServices server.port is set to 8781.
