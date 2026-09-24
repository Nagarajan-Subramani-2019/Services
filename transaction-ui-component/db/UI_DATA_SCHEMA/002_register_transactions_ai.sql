-- Run as UI_DATA_SCHEMA after 001_register_transaction_component.sql.
-- Applies to a fresh install or an existing installation of 001.
-- Registration metadata only: no DDL, business data or API registrations.
-- The original Item1 placeholder is enabled on its first conversion only.
-- Reruns preserve activity/component disables, sort order and existing grants.
WHENEVER SQLERROR EXIT SQL.SQLCODE ROLLBACK
WHENEVER OSERROR EXIT FAILURE ROLLBACK
SET DEFINE OFF

DECLARE
    l_server_code UI_DATA_SCHEMA.UI_COMPONENT_SERVER.SERVER_CODE%TYPE;
    l_item_component UI_DATA_SCHEMA.UI_ACTIVITY.COMPONENT_CODE%TYPE;
    l_item_view_key UI_DATA_SCHEMA.UI_ACTIVITY.VIEW_KEY%TYPE;
    l_component_server UI_DATA_SCHEMA.UI_COMPONENT.SERVER_CODE%TYPE;
    l_component_resource UI_DATA_SCHEMA.UI_COMPONENT.RESOURCE_NAME%TYPE;
    l_convert_item BOOLEAN;
BEGIN
    -- Reuse the server registered by 001, including its address and enabled flag.
    -- This lock also serializes concurrent executions of this migration.
    BEGIN
        SELECT SERVER_CODE INTO l_server_code
        FROM UI_DATA_SCHEMA.UI_COMPONENT_SERVER
        WHERE SERVER_CODE = 'transaction-ui'
        FOR UPDATE;
    EXCEPTION
        WHEN NO_DATA_FOUND THEN
            RAISE_APPLICATION_ERROR(-20021,
                'Missing transaction-ui server. Run 001_register_transaction_component.sql first.');
    END;

    BEGIN
        SELECT COMPONENT_CODE, VIEW_KEY INTO l_item_component, l_item_view_key
        FROM UI_DATA_SCHEMA.UI_ACTIVITY
        WHERE UI_ACTIVITY_CODE = 'UI_ITEM1'
        FOR UPDATE;
    EXCEPTION
        WHEN NO_DATA_FOUND THEN
            RAISE_APPLICATION_ERROR(-20022,
                'Missing UI_ITEM1 activity. Run 001_register_transaction_component.sql first.');
    END;

    -- Only the original placeholder may be converted. The converted pair is
    -- the rerun marker, so subsequent operator changes to ENABLED are retained.
    IF l_item_component = 'transactions' AND l_item_view_key = 'placeholder' THEN
        l_convert_item := TRUE;
    ELSIF l_item_component = 'transactions-ai' AND l_item_view_key = 'transactions-ai' THEN
        l_convert_item := FALSE;
    ELSE
        RAISE_APPLICATION_ERROR(-20023,
            'UI_ITEM1 has been repurposed. Review its component and view before running this migration.');
    END IF;

    MERGE INTO UI_DATA_SCHEMA.UI_COMPONENT t
    USING (SELECT 'transactions-ai' COMPONENT_CODE, 'transaction-ui' SERVER_CODE,
                  'transactions-ai' RESOURCE_NAME, 'Transactions with AI' DISPLAY_NAME FROM dual) s
    ON (t.COMPONENT_CODE = s.COMPONENT_CODE)
    WHEN NOT MATCHED THEN INSERT (COMPONENT_CODE, SERVER_CODE, RESOURCE_NAME, DISPLAY_NAME, ENABLED)
    VALUES (s.COMPONENT_CODE, s.SERVER_CODE, s.RESOURCE_NAME, s.DISPLAY_NAME, 1);

    -- An existing registration must belong to this resource on this server.
    -- Its display name and enabled flag remain under administrator control.
    SELECT SERVER_CODE, RESOURCE_NAME INTO l_component_server, l_component_resource
    FROM UI_DATA_SCHEMA.UI_COMPONENT
    WHERE COMPONENT_CODE = 'transactions-ai'
    FOR UPDATE;
    IF l_component_server <> 'transaction-ui' OR l_component_resource <> 'transactions-ai' THEN
        RAISE_APPLICATION_ERROR(-20024,
            'Conflicting transactions-ai component registration. Review its server and resource.');
    END IF;

    IF l_convert_item THEN
        UPDATE UI_DATA_SCHEMA.UI_ACTIVITY
        SET COMPONENT_CODE = 'transactions-ai', LABEL = 'Transactions with AI',
            VIEW_KEY = 'transactions-ai', ENABLED = 1
        WHERE UI_ACTIVITY_CODE = 'UI_ITEM1'
          AND COMPONENT_CODE = 'transactions'
          AND VIEW_KEY = 'placeholder';
        -- SORT_ORDER is deliberately unchanged (20 in the original seed).
        IF SQL%ROWCOUNT <> 1 THEN
            RAISE_APPLICATION_ERROR(-20025, 'UI_ITEM1 changed during conversion. No changes were committed.');
        END IF;
    END IF;

    -- Add the original ALL grant only if absent. Existing ALL denies and every
    -- USER-specific allow/deny remain unchanged. No functional grant is needed.
    MERGE INTO UI_DATA_SCHEMA.ACTIVITY_GRANT t
    USING (SELECT 'UI' ACTIVITY_KIND, 'UI_ITEM1' ACTIVITY_CODE,
                  'ALL' SUBJECT_TYPE, '*' SUBJECT_ID FROM dual) s
    ON (t.ACTIVITY_KIND = s.ACTIVITY_KIND AND t.ACTIVITY_CODE = s.ACTIVITY_CODE
        AND t.SUBJECT_TYPE = s.SUBJECT_TYPE AND t.SUBJECT_ID = s.SUBJECT_ID)
    WHEN NOT MATCHED THEN INSERT (ACTIVITY_KIND, ACTIVITY_CODE, SUBJECT_TYPE, SUBJECT_ID, ALLOWED)
    VALUES (s.ACTIVITY_KIND, s.ACTIVITY_CODE, s.SUBJECT_TYPE, s.SUBJECT_ID, 1);
EXCEPTION
    WHEN OTHERS THEN
        ROLLBACK;
        RAISE;
END;
/

COMMIT;
