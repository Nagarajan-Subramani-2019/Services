-- Run manually as USER_INFO_SCHEMA in FREEPDB1 after creating PROPERTIES.
-- Applies the requested userInfoServices port 8770.
-- Re-running updates this exact application's server.port row; review before execution.

SET ECHO OFF
SET VERIFY OFF
SET DEFINE OFF
WHENEVER OSERROR EXIT FAILURE ROLLBACK
WHENEVER SQLERROR EXIT SQL.SQLCODE ROLLBACK

BEGIN
    IF SYS_CONTEXT('USERENV', 'SESSION_USER') <> 'USER_INFO_SCHEMA'
       OR SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA') <> 'USER_INFO_SCHEMA'
       OR SYS_CONTEXT('USERENV', 'CON_NAME') <> 'FREEPDB1' THEN
        RAISE_APPLICATION_ERROR(
            -20001,
            'Connect as USER_INFO_SCHEMA with current schema USER_INFO_SCHEMA in FREEPDB1.'
        );
    END IF;
END;
/

MERGE INTO USER_INFO_SCHEMA.PROPERTIES target
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
