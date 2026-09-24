-- Manual owner-only setup. Never creates a user, grants rights, or drops existing data.
-- Oracle DDL commits implicitly. Existing tables are validated before reuse.
-- If validation stops, inspect the existing object; do not drop it to force installation.
SET ECHO OFF
SET VERIFY OFF
SET DEFINE OFF
WHENEVER OSERROR EXIT FAILURE ROLLBACK
WHENEVER SQLERROR EXIT SQL.SQLCODE ROLLBACK

DECLARE
    PROCEDURE ensure_table(p_name VARCHAR2, p_ddl VARCHAR2) IS
        object_count PLS_INTEGER;
        table_count PLS_INTEGER;
    BEGIN
        SELECT COUNT(*) INTO object_count FROM USER_OBJECTS WHERE OBJECT_NAME = p_name;
        SELECT COUNT(*) INTO table_count FROM USER_TABLES WHERE TABLE_NAME = p_name;
        IF object_count = 0 THEN
            EXECUTE IMMEDIATE p_ddl;
        ELSIF table_count <> 1 THEN
            RAISE_APPLICATION_ERROR(-20001, 'Existing object is not the required table: ' || p_name);
        END IF;
    END;

    PROCEDURE assert_columns(p_table VARCHAR2, p_count PLS_INTEGER) IS
        actual_count PLS_INTEGER;
    BEGIN
        SELECT COUNT(*) INTO actual_count FROM USER_TAB_COLUMNS WHERE TABLE_NAME = p_table;
        IF actual_count <> p_count THEN
            RAISE_APPLICATION_ERROR(-20002, 'Unexpected existing columns in ' || p_table);
        END IF;
    END;

    PROCEDURE assert_text(p_table VARCHAR2, p_column VARCHAR2, p_type VARCHAR2, p_length PLS_INTEGER,
            p_nullable VARCHAR2 DEFAULT 'N') IS
        matching PLS_INTEGER;
    BEGIN
        SELECT COUNT(*) INTO matching FROM USER_TAB_COLUMNS
         WHERE TABLE_NAME = p_table AND COLUMN_NAME = p_column AND DATA_TYPE = p_type
           AND CHAR_LENGTH = p_length AND NULLABLE = p_nullable;
        IF matching <> 1 THEN
            RAISE_APPLICATION_ERROR(-20003, 'Unexpected column definition: ' || p_table || '.' || p_column);
        END IF;
    END;

    PROCEDURE assert_number(p_table VARCHAR2, p_column VARCHAR2, p_precision PLS_INTEGER) IS
        matching PLS_INTEGER;
    BEGIN
        SELECT COUNT(*) INTO matching FROM USER_TAB_COLUMNS
         WHERE TABLE_NAME = p_table AND COLUMN_NAME = p_column AND DATA_TYPE = 'NUMBER'
           AND DATA_PRECISION = p_precision AND DATA_SCALE = 0 AND NULLABLE = 'N';
        IF matching <> 1 THEN
            RAISE_APPLICATION_ERROR(-20004, 'Unexpected number definition: ' || p_table || '.' || p_column);
        END IF;
    END;

    PROCEDURE assert_time(p_column VARCHAR2) IS
        matching PLS_INTEGER;
    BEGIN
        SELECT COUNT(*) INTO matching FROM USER_TAB_COLUMNS
         WHERE TABLE_NAME = 'UI_SESSION' AND COLUMN_NAME = p_column
           AND DATA_TYPE LIKE 'TIMESTAMP%WITH TIME ZONE' AND NULLABLE = 'N';
        IF matching <> 1 THEN
            RAISE_APPLICATION_ERROR(-20005, 'Unexpected timestamp definition: UI_SESSION.' || p_column);
        END IF;
    END;

    PROCEDURE assert_primary_key(p_table VARCHAR2, p_columns VARCHAR2) IS
        actual_columns VARCHAR2(1000);
    BEGIN
        SELECT LISTAGG(cc.COLUMN_NAME, ',') WITHIN GROUP (ORDER BY cc.POSITION)
          INTO actual_columns
          FROM USER_CONSTRAINTS c JOIN USER_CONS_COLUMNS cc ON cc.CONSTRAINT_NAME = c.CONSTRAINT_NAME
         WHERE c.TABLE_NAME = p_table AND c.CONSTRAINT_TYPE = 'P'
           AND c.STATUS = 'ENABLED' AND c.VALIDATED = 'VALIDATED';
        IF actual_columns IS NULL OR actual_columns <> p_columns THEN
            RAISE_APPLICATION_ERROR(-20006, 'Unexpected primary key in ' || p_table);
        END IF;
    END;

    PROCEDURE assert_constraint(p_table VARCHAR2, p_name VARCHAR2, p_type VARCHAR2) IS
        matching PLS_INTEGER;
    BEGIN
        SELECT COUNT(*) INTO matching FROM USER_CONSTRAINTS
         WHERE TABLE_NAME = p_table AND CONSTRAINT_NAME = p_name AND CONSTRAINT_TYPE = p_type
           AND STATUS = 'ENABLED' AND VALIDATED = 'VALIDATED';
        IF matching <> 1 THEN
            RAISE_APPLICATION_ERROR(-20007, 'Missing enabled platform constraint: ' || p_name);
        END IF;
    END;
BEGIN
    IF SYS_CONTEXT('USERENV', 'SESSION_USER') <> 'UI_DATA_SCHEMA'
            OR SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA') <> 'UI_DATA_SCHEMA' THEN
        RAISE_APPLICATION_ERROR(-20000, 'Connect as UI_DATA_SCHEMA with current schema UI_DATA_SCHEMA');
    END IF;

    ensure_table('UI_COMPONENT_SERVER', q'[
        CREATE TABLE UI_DATA_SCHEMA.UI_COMPONENT_SERVER (
            SERVER_CODE VARCHAR2(80 CHAR) NOT NULL,
            SERVICE_ID VARCHAR2(120 CHAR) NOT NULL,
            BASE_URL VARCHAR2(512 CHAR) NOT NULL,
            ENABLED NUMBER(1) DEFAULT 1 NOT NULL,
            CONSTRAINT UI_COMPONENT_SERVER_PK PRIMARY KEY (SERVER_CODE),
            CONSTRAINT UI_COMP_SERVER_ENABLED_CK CHECK (ENABLED IN (0, 1))
        )]');
    assert_columns('UI_COMPONENT_SERVER', 4);
    assert_text('UI_COMPONENT_SERVER', 'SERVER_CODE', 'VARCHAR2', 80);
    assert_text('UI_COMPONENT_SERVER', 'SERVICE_ID', 'VARCHAR2', 120);
    assert_text('UI_COMPONENT_SERVER', 'BASE_URL', 'VARCHAR2', 512);
    assert_number('UI_COMPONENT_SERVER', 'ENABLED', 1);
    assert_primary_key('UI_COMPONENT_SERVER', 'SERVER_CODE');
    assert_constraint('UI_COMPONENT_SERVER', 'UI_COMP_SERVER_ENABLED_CK', 'C');

    ensure_table('UI_COMPONENT', q'[
        CREATE TABLE UI_DATA_SCHEMA.UI_COMPONENT (
            COMPONENT_CODE VARCHAR2(80 CHAR) NOT NULL,
            SERVER_CODE VARCHAR2(80 CHAR) NOT NULL,
            RESOURCE_NAME VARCHAR2(80 CHAR) NOT NULL,
            DISPLAY_NAME VARCHAR2(200 CHAR) NOT NULL,
            ENABLED NUMBER(1) DEFAULT 1 NOT NULL,
            CONSTRAINT UI_COMPONENT_PK PRIMARY KEY (COMPONENT_CODE),
            CONSTRAINT UI_COMPONENT_SERVER_FK FOREIGN KEY (SERVER_CODE)
                REFERENCES UI_DATA_SCHEMA.UI_COMPONENT_SERVER (SERVER_CODE),
            CONSTRAINT UI_COMPONENT_ENABLED_CK CHECK (ENABLED IN (0, 1))
        )]');
    assert_columns('UI_COMPONENT', 5);
    assert_text('UI_COMPONENT', 'COMPONENT_CODE', 'VARCHAR2', 80);
    assert_text('UI_COMPONENT', 'SERVER_CODE', 'VARCHAR2', 80);
    assert_text('UI_COMPONENT', 'RESOURCE_NAME', 'VARCHAR2', 80);
    assert_text('UI_COMPONENT', 'DISPLAY_NAME', 'VARCHAR2', 200);
    assert_number('UI_COMPONENT', 'ENABLED', 1);
    assert_primary_key('UI_COMPONENT', 'COMPONENT_CODE');
    assert_constraint('UI_COMPONENT', 'UI_COMPONENT_SERVER_FK', 'R');
    assert_constraint('UI_COMPONENT', 'UI_COMPONENT_ENABLED_CK', 'C');

    ensure_table('UI_ACTIVITY', q'[
        CREATE TABLE UI_DATA_SCHEMA.UI_ACTIVITY (
            UI_ACTIVITY_CODE VARCHAR2(120 CHAR) NOT NULL,
            COMPONENT_CODE VARCHAR2(80 CHAR) NOT NULL,
            LABEL VARCHAR2(200 CHAR) NOT NULL,
            VIEW_KEY VARCHAR2(120 CHAR) NOT NULL,
            SORT_ORDER NUMBER(10) DEFAULT 0 NOT NULL,
            ENABLED NUMBER(1) DEFAULT 1 NOT NULL,
            CONSTRAINT UI_ACTIVITY_PK PRIMARY KEY (UI_ACTIVITY_CODE),
            CONSTRAINT UI_ACTIVITY_COMPONENT_FK FOREIGN KEY (COMPONENT_CODE)
                REFERENCES UI_DATA_SCHEMA.UI_COMPONENT (COMPONENT_CODE),
            CONSTRAINT UI_ACTIVITY_ENABLED_CK CHECK (ENABLED IN (0, 1)),
            CONSTRAINT UI_ACTIVITY_ORDER_CK CHECK (SORT_ORDER BETWEEN 0 AND 2147483647)
        )]');
    assert_columns('UI_ACTIVITY', 6);
    assert_text('UI_ACTIVITY', 'UI_ACTIVITY_CODE', 'VARCHAR2', 120);
    assert_text('UI_ACTIVITY', 'COMPONENT_CODE', 'VARCHAR2', 80);
    assert_text('UI_ACTIVITY', 'LABEL', 'VARCHAR2', 200);
    assert_text('UI_ACTIVITY', 'VIEW_KEY', 'VARCHAR2', 120);
    assert_number('UI_ACTIVITY', 'SORT_ORDER', 10);
    assert_number('UI_ACTIVITY', 'ENABLED', 1);
    assert_primary_key('UI_ACTIVITY', 'UI_ACTIVITY_CODE');
    assert_constraint('UI_ACTIVITY', 'UI_ACTIVITY_COMPONENT_FK', 'R');
    assert_constraint('UI_ACTIVITY', 'UI_ACTIVITY_ENABLED_CK', 'C');
    assert_constraint('UI_ACTIVITY', 'UI_ACTIVITY_ORDER_CK', 'C');

    ensure_table('FUNCTIONAL_ACTIVITY', q'[
        CREATE TABLE UI_DATA_SCHEMA.FUNCTIONAL_ACTIVITY (
            FUNCTIONAL_ACTIVITY_CODE VARCHAR2(120 CHAR) NOT NULL,
            COMPONENT_CODE VARCHAR2(80 CHAR) NOT NULL,
            ENABLED NUMBER(1) DEFAULT 1 NOT NULL,
            CONSTRAINT FUNCTIONAL_ACTIVITY_PK PRIMARY KEY (FUNCTIONAL_ACTIVITY_CODE),
            CONSTRAINT FUNC_ACTIVITY_COMPONENT_FK FOREIGN KEY (COMPONENT_CODE)
                REFERENCES UI_DATA_SCHEMA.UI_COMPONENT (COMPONENT_CODE),
            CONSTRAINT FUNC_ACTIVITY_ENABLED_CK CHECK (ENABLED IN (0, 1))
        )]');
    assert_columns('FUNCTIONAL_ACTIVITY', 3);
    assert_text('FUNCTIONAL_ACTIVITY', 'FUNCTIONAL_ACTIVITY_CODE', 'VARCHAR2', 120);
    assert_text('FUNCTIONAL_ACTIVITY', 'COMPONENT_CODE', 'VARCHAR2', 80);
    assert_number('FUNCTIONAL_ACTIVITY', 'ENABLED', 1);
    assert_primary_key('FUNCTIONAL_ACTIVITY', 'FUNCTIONAL_ACTIVITY_CODE');
    assert_constraint('FUNCTIONAL_ACTIVITY', 'FUNC_ACTIVITY_COMPONENT_FK', 'R');
    assert_constraint('FUNCTIONAL_ACTIVITY', 'FUNC_ACTIVITY_ENABLED_CK', 'C');

    ensure_table('UI_COMPONENT_API', q'[
        CREATE TABLE UI_DATA_SCHEMA.UI_COMPONENT_API (
            COMPONENT_CODE VARCHAR2(80 CHAR) NOT NULL,
            OPERATION_CODE VARCHAR2(80 CHAR) NOT NULL,
            UPSTREAM_PATH VARCHAR2(200 CHAR) NOT NULL,
            UI_ACTIVITY_CODE VARCHAR2(120 CHAR) NOT NULL,
            FUNCTIONAL_ACTIVITY_CODE VARCHAR2(120 CHAR) NOT NULL,
            RESPONSE_TYPE VARCHAR2(30 CHAR) NOT NULL,
            QUERY_PARAMETERS VARCHAR2(200 CHAR),
            ENABLED NUMBER(1) DEFAULT 1 NOT NULL,
            CONSTRAINT UI_COMPONENT_API_PK PRIMARY KEY (COMPONENT_CODE, OPERATION_CODE),
            CONSTRAINT UI_COMP_API_COMPONENT_FK FOREIGN KEY (COMPONENT_CODE)
                REFERENCES UI_DATA_SCHEMA.UI_COMPONENT (COMPONENT_CODE),
            CONSTRAINT UI_COMP_API_UI_FK FOREIGN KEY (UI_ACTIVITY_CODE)
                REFERENCES UI_DATA_SCHEMA.UI_ACTIVITY (UI_ACTIVITY_CODE),
            CONSTRAINT UI_COMP_API_FUNCTION_FK FOREIGN KEY (FUNCTIONAL_ACTIVITY_CODE)
                REFERENCES UI_DATA_SCHEMA.FUNCTIONAL_ACTIVITY (FUNCTIONAL_ACTIVITY_CODE),
            CONSTRAINT UI_COMP_API_ENABLED_CK CHECK (ENABLED IN (0, 1))
        )]');
    assert_columns('UI_COMPONENT_API', 8);
    assert_text('UI_COMPONENT_API', 'COMPONENT_CODE', 'VARCHAR2', 80);
    assert_text('UI_COMPONENT_API', 'OPERATION_CODE', 'VARCHAR2', 80);
    assert_text('UI_COMPONENT_API', 'UPSTREAM_PATH', 'VARCHAR2', 200);
    assert_text('UI_COMPONENT_API', 'UI_ACTIVITY_CODE', 'VARCHAR2', 120);
    assert_text('UI_COMPONENT_API', 'FUNCTIONAL_ACTIVITY_CODE', 'VARCHAR2', 120);
    assert_text('UI_COMPONENT_API', 'RESPONSE_TYPE', 'VARCHAR2', 30);
    assert_text('UI_COMPONENT_API', 'QUERY_PARAMETERS', 'VARCHAR2', 200, 'Y');
    assert_number('UI_COMPONENT_API', 'ENABLED', 1);
    assert_primary_key('UI_COMPONENT_API', 'COMPONENT_CODE,OPERATION_CODE');
    assert_constraint('UI_COMPONENT_API', 'UI_COMP_API_COMPONENT_FK', 'R');
    assert_constraint('UI_COMPONENT_API', 'UI_COMP_API_UI_FK', 'R');
    assert_constraint('UI_COMPONENT_API', 'UI_COMP_API_FUNCTION_FK', 'R');
    assert_constraint('UI_COMPONENT_API', 'UI_COMP_API_ENABLED_CK', 'C');

    -- UI/FUNCTIONAL is a polymorphic reference, validated by the application on every check.
    ensure_table('ACTIVITY_GRANT', q'[
        CREATE TABLE UI_DATA_SCHEMA.ACTIVITY_GRANT (
            ACTIVITY_KIND VARCHAR2(10 CHAR) NOT NULL,
            ACTIVITY_CODE VARCHAR2(120 CHAR) NOT NULL,
            SUBJECT_TYPE VARCHAR2(4 CHAR) NOT NULL,
            SUBJECT_ID VARCHAR2(32 CHAR) NOT NULL,
            ALLOWED NUMBER(1) NOT NULL,
            CONSTRAINT ACTIVITY_GRANT_PK PRIMARY KEY (ACTIVITY_KIND, ACTIVITY_CODE, SUBJECT_TYPE, SUBJECT_ID),
            CONSTRAINT ACTIVITY_GRANT_KIND_CK CHECK (ACTIVITY_KIND IN ('UI', 'FUNCTIONAL')),
            CONSTRAINT ACTIVITY_GRANT_SUBJECT_CK CHECK (
                (SUBJECT_TYPE = 'ALL' AND SUBJECT_ID = '*') OR
                (SUBJECT_TYPE = 'USER' AND REGEXP_LIKE(SUBJECT_ID, '^[1-9][0-9]{0,18}$'))),
            CONSTRAINT ACTIVITY_GRANT_ALLOWED_CK CHECK (ALLOWED IN (0, 1))
        )]');
    assert_columns('ACTIVITY_GRANT', 5);
    assert_text('ACTIVITY_GRANT', 'ACTIVITY_KIND', 'VARCHAR2', 10);
    assert_text('ACTIVITY_GRANT', 'ACTIVITY_CODE', 'VARCHAR2', 120);
    assert_text('ACTIVITY_GRANT', 'SUBJECT_TYPE', 'VARCHAR2', 4);
    assert_text('ACTIVITY_GRANT', 'SUBJECT_ID', 'VARCHAR2', 32);
    assert_number('ACTIVITY_GRANT', 'ALLOWED', 1);
    assert_primary_key('ACTIVITY_GRANT', 'ACTIVITY_KIND,ACTIVITY_CODE,SUBJECT_TYPE,SUBJECT_ID');
    assert_constraint('ACTIVITY_GRANT', 'ACTIVITY_GRANT_KIND_CK', 'C');
    assert_constraint('ACTIVITY_GRANT', 'ACTIVITY_GRANT_SUBJECT_CK', 'C');
    assert_constraint('ACTIVITY_GRANT', 'ACTIVITY_GRANT_ALLOWED_CK', 'C');

    ensure_table('UI_SESSION', q'[
        CREATE TABLE UI_DATA_SCHEMA.UI_SESSION (
            TOKEN_HASH CHAR(64 CHAR) NOT NULL,
            USER_ID NUMBER(19) NOT NULL,
            USERNAME VARCHAR2(100 CHAR) NOT NULL,
            EXPIRES_AT TIMESTAMP(6) WITH TIME ZONE NOT NULL,
            CREATED_AT TIMESTAMP(6) WITH TIME ZONE NOT NULL,
            CONSTRAINT UI_SESSION_PK PRIMARY KEY (TOKEN_HASH),
            CONSTRAINT UI_SESSION_USER_CK CHECK (USER_ID BETWEEN 1 AND 9223372036854775807),
            CONSTRAINT UI_SESSION_HASH_CK CHECK (REGEXP_LIKE(TOKEN_HASH, '^[0-9a-f]{64}$')),
            CONSTRAINT UI_SESSION_EXPIRY_CK CHECK (EXPIRES_AT > CREATED_AT)
        )]');
    assert_columns('UI_SESSION', 5);
    assert_text('UI_SESSION', 'TOKEN_HASH', 'CHAR', 64);
    assert_number('UI_SESSION', 'USER_ID', 19);
    assert_text('UI_SESSION', 'USERNAME', 'VARCHAR2', 100);
    assert_time('EXPIRES_AT');
    assert_time('CREATED_AT');
    assert_primary_key('UI_SESSION', 'TOKEN_HASH');
    assert_constraint('UI_SESSION', 'UI_SESSION_USER_CK', 'C');
    assert_constraint('UI_SESSION', 'UI_SESSION_HASH_CK', 'C');
    assert_constraint('UI_SESSION', 'UI_SESSION_EXPIRY_CK', 'C');
END;
/
PROMPT UI platform tables exist and passed owner, column, primary-key and constraint validation.
