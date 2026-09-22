-- OPTIONAL, ONE-TIME DBA provisioning, run manually in interactive SQL*Plus.
-- Connect an authorized administrator directly to FREEPDB1. This folder name
-- is an administrative execution role, NOT a database schema to log in as.
-- Do not run this file as the application or use it to reset an existing user.
-- Review USERS/TEMP tablespaces and the 20M quota with your DBA before use.
-- Oracle DDL commits implicitly. A later failure does not undo earlier DDL.
-- No password is stored, substituted, or printed by this script.

SET ECHO OFF
SET VERIFY OFF
SET DEFINE OFF
WHENEVER OSERROR EXIT FAILURE ROLLBACK
WHENEVER SQLERROR EXIT SQL.SQLCODE ROLLBACK

DECLARE
    existing_users PLS_INTEGER;
BEGIN
    IF SYS_CONTEXT('USERENV', 'CON_NAME') <> 'FREEPDB1' THEN
        RAISE_APPLICATION_ERROR(-20001, 'Connect an authorized administrator directly to FREEPDB1.');
    END IF;

    SELECT COUNT(*) INTO existing_users
      FROM ALL_USERS
     WHERE USERNAME = 'USER_INFO_SCHEMA';

    IF existing_users <> 0 THEN
        RAISE_APPLICATION_ERROR(-20002, 'USER_INFO_SCHEMA already exists; inspect it with your DBA. No changes made.');
    END IF;
END;
/

-- Creating a locked schema-only account avoids a temporary/default password.
CREATE USER USER_INFO_SCHEMA NO AUTHENTICATION
    DEFAULT TABLESPACE USERS
    TEMPORARY TABLESPACE TEMP
    QUOTA 20M ON USERS
    ACCOUNT LOCK;

GRANT CREATE SESSION, CREATE TABLE TO USER_INFO_SCHEMA;

-- SQL*Plus prompts for the new password twice without echoing it. An authorized
-- administrator needs ALTER USER and access to DBA_USERS for this setup step.
PASSWORD USER_INFO_SCHEMA

DECLARE
    authentication VARCHAR2(30);
BEGIN
    SELECT AUTHENTICATION_TYPE INTO authentication
      FROM DBA_USERS
     WHERE USERNAME = 'USER_INFO_SCHEMA';

    IF authentication <> 'PASSWORD' THEN
        RAISE_APPLICATION_ERROR(-20003, 'Password setup did not complete; account remains locked. Ask your DBA to finish setup.');
    END IF;

    EXECUTE IMMEDIATE 'ALTER USER USER_INFO_SCHEMA ACCOUNT UNLOCK';
END;
/

PROMPT USER_INFO_SCHEMA provisioned. Reconnect as USER_INFO_SCHEMA to run its SQL folder.
