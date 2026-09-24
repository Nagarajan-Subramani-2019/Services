-- Run as UI_DATA_SCHEMA after 002_register_transactions_ai.sql.
-- Existing-table registration only. No schemas, tables or business data added.
-- Insert-only: existing disables, denies and per-user overrides are retained.
WHENEVER SQLERROR EXIT SQL.SQLCODE ROLLBACK
WHENEVER OSERROR EXIT FAILURE ROLLBACK
SET DEFINE OFF

DECLARE
    l_server UI_DATA_SCHEMA.UI_COMPONENT_SERVER.SERVER_CODE%TYPE;
    l_component UI_DATA_SCHEMA.UI_COMPONENT%ROWTYPE;
    l_activity UI_DATA_SCHEMA.UI_ACTIVITY%ROWTYPE;
    l_function_owner UI_DATA_SCHEMA.FUNCTIONAL_ACTIVITY.COMPONENT_CODE%TYPE;
    l_route UI_DATA_SCHEMA.UI_COMPONENT_API%ROWTYPE;
BEGIN
    -- Serialize this registration with migrations 001/002 using the server row.
    SELECT SERVER_CODE INTO l_server FROM UI_DATA_SCHEMA.UI_COMPONENT_SERVER
    WHERE SERVER_CODE = 'transaction-ui' FOR UPDATE;
    SELECT * INTO l_component FROM UI_DATA_SCHEMA.UI_COMPONENT
    WHERE COMPONENT_CODE = 'transactions-ai' FOR UPDATE;
    SELECT * INTO l_activity FROM UI_DATA_SCHEMA.UI_ACTIVITY
    WHERE UI_ACTIVITY_CODE = 'UI_ITEM1' FOR UPDATE;
    IF l_component.SERVER_CODE <> 'transaction-ui'
       OR l_component.RESOURCE_NAME <> 'transactions-ai'
       OR l_activity.COMPONENT_CODE <> 'transactions-ai'
       OR l_activity.VIEW_KEY <> 'transactions-ai' THEN
        RAISE_APPLICATION_ERROR(-20031, 'Transactions with AI registration conflicts with migration 002. Review before continuing.');
    END IF;

    MERGE INTO UI_DATA_SCHEMA.FUNCTIONAL_ACTIVITY t
    USING (SELECT 'TXN_AI_SELF_READ' FUNCTIONAL_ACTIVITY_CODE, 'transactions-ai' COMPONENT_CODE FROM dual) s
    ON (t.FUNCTIONAL_ACTIVITY_CODE = s.FUNCTIONAL_ACTIVITY_CODE)
    WHEN NOT MATCHED THEN INSERT (FUNCTIONAL_ACTIVITY_CODE, COMPONENT_CODE, ENABLED)
    VALUES (s.FUNCTIONAL_ACTIVITY_CODE, s.COMPONENT_CODE, 1);
    SELECT COMPONENT_CODE INTO l_function_owner FROM UI_DATA_SCHEMA.FUNCTIONAL_ACTIVITY
    WHERE FUNCTIONAL_ACTIVITY_CODE = 'TXN_AI_SELF_READ' FOR UPDATE;
    IF l_function_owner <> 'transactions-ai' THEN
        RAISE_APPLICATION_ERROR(-20032, 'TXN_AI_SELF_READ belongs to another component. No changes committed.');
    END IF;

    -- The existing shell already supports these two response contracts.
    -- my-transactions requires userId for its adapter, but the server rejects
    -- any ID different from the authenticated session user, even on direct calls.
    FOR expected IN (
        SELECT 'current-user' OPERATION_CODE, '/api/current-user' UPSTREAM_PATH,
               'USER_OPTIONS' RESPONSE_TYPE, 'page,size' QUERY_PARAMETERS FROM dual
        UNION ALL
        SELECT 'my-transactions', '/api/my-transactions', 'TRANSACTION_PAGE', 'userId,page,size' FROM dual
    ) LOOP
        MERGE INTO UI_DATA_SCHEMA.UI_COMPONENT_API t
        USING (SELECT 'transactions-ai' COMPONENT_CODE, expected.OPERATION_CODE OPERATION_CODE,
                      expected.UPSTREAM_PATH UPSTREAM_PATH, expected.RESPONSE_TYPE RESPONSE_TYPE,
                      expected.QUERY_PARAMETERS QUERY_PARAMETERS FROM dual) s
        ON (t.COMPONENT_CODE = s.COMPONENT_CODE AND t.OPERATION_CODE = s.OPERATION_CODE)
        WHEN NOT MATCHED THEN INSERT (COMPONENT_CODE, OPERATION_CODE, UPSTREAM_PATH,
            UI_ACTIVITY_CODE, FUNCTIONAL_ACTIVITY_CODE, RESPONSE_TYPE, QUERY_PARAMETERS, ENABLED)
        VALUES (s.COMPONENT_CODE, s.OPERATION_CODE, s.UPSTREAM_PATH,
            'UI_ITEM1', 'TXN_AI_SELF_READ', s.RESPONSE_TYPE, s.QUERY_PARAMETERS, 1);

        SELECT * INTO l_route FROM UI_DATA_SCHEMA.UI_COMPONENT_API
        WHERE COMPONENT_CODE = 'transactions-ai' AND OPERATION_CODE = expected.OPERATION_CODE
        FOR UPDATE;
        IF l_route.UPSTREAM_PATH <> expected.UPSTREAM_PATH
           OR l_route.UI_ACTIVITY_CODE <> 'UI_ITEM1'
           OR l_route.FUNCTIONAL_ACTIVITY_CODE <> 'TXN_AI_SELF_READ'
           OR l_route.RESPONSE_TYPE <> expected.RESPONSE_TYPE
           OR l_route.QUERY_PARAMETERS IS NULL
           OR l_route.QUERY_PARAMETERS <> expected.QUERY_PARAMETERS THEN
            RAISE_APPLICATION_ERROR(-20033, 'Conflicting Transactions with AI API route. Review registration before continuing.');
        END IF;
    END LOOP;

    MERGE INTO UI_DATA_SCHEMA.ACTIVITY_GRANT t
    USING (SELECT 'FUNCTIONAL' ACTIVITY_KIND, 'TXN_AI_SELF_READ' ACTIVITY_CODE,
                  'ALL' SUBJECT_TYPE, '*' SUBJECT_ID FROM dual) s
    ON (t.ACTIVITY_KIND = s.ACTIVITY_KIND AND t.ACTIVITY_CODE = s.ACTIVITY_CODE
        AND t.SUBJECT_TYPE = s.SUBJECT_TYPE AND t.SUBJECT_ID = s.SUBJECT_ID)
    WHEN NOT MATCHED THEN INSERT (ACTIVITY_KIND, ACTIVITY_CODE, SUBJECT_TYPE, SUBJECT_ID, ALLOWED)
    VALUES (s.ACTIVITY_KIND, s.ACTIVITY_CODE, s.SUBJECT_TYPE, s.SUBJECT_ID, 1);
EXCEPTION
    WHEN NO_DATA_FOUND THEN
        ROLLBACK;
        RAISE_APPLICATION_ERROR(-20034, 'Missing Transactions with AI registration. Run 001 and 002 first.');
    WHEN OTHERS THEN
        ROLLBACK;
        RAISE;
END;
/
COMMIT;
