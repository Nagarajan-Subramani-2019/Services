import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

// These are static migration contracts. They do not connect to a database or
// substitute for running the script in a disposable Oracle installation.
const migration = await readFile(new URL('../../db/UI_DATA_SCHEMA/002_register_transactions_ai.sql', import.meta.url), 'utf8');
const sql = migration.replace(/--[^\r\n]*/g, '').replace(/\s+/g, ' ').trim();
const componentMerge = sql.match(/MERGE INTO UI_DATA_SCHEMA\.UI_COMPONENT\b.*?;/i)?.[0];
const activityUpdate = sql.match(/UPDATE UI_DATA_SCHEMA\.UI_ACTIVITY\b.*?;/i)?.[0];
const grantMerge = sql.match(/MERGE INTO UI_DATA_SCHEMA\.ACTIVITY_GRANT\b.*?;/i)?.[0];

test('migration is explicitly ordered after the unchanged Item1 seed', async () => {
  assert.match(migration, /after 001_register_transaction_component\.sql/);
  const seed = await readFile(new URL('../../db/UI_DATA_SCHEMA/001_register_transaction_component.sql', import.meta.url), 'utf8');
  assert.match(seed, /SELECT 'UI_ITEM1','Item1','placeholder',20,0 FROM dual/);
  assert.match(seed, /VALUES\(s\.UI_ACTIVITY_CODE,'transactions',s\.LABEL,s\.VIEW_KEY,s\.SORT_ORDER,s\.ENABLED\)/);
  assert.match(seed, /SELECT 'UI','UI_ITEM1' FROM dual/);
});

test('SQLPlus errors roll back and the complete block commits only after success', () => {
  assert.match(sql, /WHENEVER SQLERROR EXIT SQL\.SQLCODE ROLLBACK/);
  assert.match(sql, /WHENEVER OSERROR EXIT FAILURE ROLLBACK/);
  assert.match(sql, /SET DEFINE OFF/);
  assert.match(sql, /EXCEPTION WHEN OTHERS THEN ROLLBACK; RAISE; END; \/ COMMIT;$/);
  assert.equal([...sql.matchAll(/\bCOMMIT\s*;/gi)].length, 1);
});

test('existing server and Item1 are locked and validated before any mutation', () => {
  const firstMutation = sql.search(/\b(?:MERGE INTO|UPDATE UI_DATA_SCHEMA|INSERT INTO)\b/i);
  const validation = sql.slice(0, firstMutation);
  assert.match(validation, /FROM UI_DATA_SCHEMA\.UI_COMPONENT_SERVER WHERE SERVER_CODE = 'transaction-ui' FOR UPDATE;/);
  assert.match(validation, /FROM UI_DATA_SCHEMA\.UI_ACTIVITY WHERE UI_ACTIVITY_CODE = 'UI_ITEM1' FOR UPDATE;/);
  assert.match(validation, /WHEN NO_DATA_FOUND THEN RAISE_APPLICATION_ERROR\(-20021,/);
  assert.match(validation, /WHEN NO_DATA_FOUND THEN RAISE_APPLICATION_ERROR\(-20022,/);
  assert.match(validation, /IF l_item_component = 'transactions' AND l_item_view_key = 'placeholder' THEN l_convert_item := TRUE;/);
  assert.match(validation, /ELSIF l_item_component = 'transactions-ai' AND l_item_view_key = 'transactions-ai' THEN l_convert_item := FALSE; ELSE RAISE_APPLICATION_ERROR\(-20023,/);
});

test('new component reuses the server and preserves an existing component disable', () => {
  assert.ok(componentMerge, 'component registration is present');
  assert.match(componentMerge, /'transactions-ai' COMPONENT_CODE, 'transaction-ui' SERVER_CODE, 'transactions-ai' RESOURCE_NAME, 'Transactions with AI' DISPLAY_NAME/);
  assert.match(componentMerge, /ON \(t\.COMPONENT_CODE = s\.COMPONENT_CODE\) WHEN NOT MATCHED THEN INSERT/);
  assert.match(componentMerge, /VALUES \(s\.COMPONENT_CODE, s\.SERVER_CODE, s\.RESOURCE_NAME, s\.DISPLAY_NAME, 1\);$/);
  assert.doesNotMatch(componentMerge, /WHEN MATCHED|\bUPDATE\b/i);
  assert.match(sql, /IF l_component_server <> 'transaction-ui' OR l_component_resource <> 'transactions-ai' THEN RAISE_APPLICATION_ERROR\(-20024,/);
  assert.ok(sql.indexOf('RAISE_APPLICATION_ERROR(-20024,') < sql.indexOf(activityUpdate), 'a conflicting registration stops before Item1 conversion');
});

test('Item1 enablement is confined to first conversion and its order is preserved', () => {
  assert.ok(activityUpdate, 'Item1 conversion is present');
  assert.match(sql, /IF l_convert_item THEN UPDATE UI_DATA_SCHEMA\.UI_ACTIVITY/);
  assert.match(activityUpdate, /SET COMPONENT_CODE = 'transactions-ai', LABEL = 'Transactions with AI', VIEW_KEY = 'transactions-ai', ENABLED = 1/);
  assert.match(activityUpdate, /WHERE UI_ACTIVITY_CODE = 'UI_ITEM1' AND COMPONENT_CODE = 'transactions' AND VIEW_KEY = 'placeholder';$/);
  assert.doesNotMatch(activityUpdate, /SORT_ORDER/);
  assert.match(sql, /IF SQL%ROWCOUNT <> 1 THEN RAISE_APPLICATION_ERROR\(-20025,/);
  assert.equal([...sql.matchAll(/\bUPDATE UI_DATA_SCHEMA\.UI_ACTIVITY\b/gi)].length, 1);
});

test('missing ALL UI grant is insert-only and cannot replace denies or user overrides', () => {
  assert.ok(grantMerge, 'UI grant registration is present');
  assert.match(grantMerge, /SELECT 'UI' ACTIVITY_KIND, 'UI_ITEM1' ACTIVITY_CODE, 'ALL' SUBJECT_TYPE, '\*' SUBJECT_ID FROM dual/);
  assert.match(grantMerge, /ON \(t\.ACTIVITY_KIND = s\.ACTIVITY_KIND AND t\.ACTIVITY_CODE = s\.ACTIVITY_CODE AND t\.SUBJECT_TYPE = s\.SUBJECT_TYPE AND t\.SUBJECT_ID = s\.SUBJECT_ID\) WHEN NOT MATCHED THEN INSERT/);
  assert.match(grantMerge, /VALUES \(s\.ACTIVITY_KIND, s\.ACTIVITY_CODE, s\.SUBJECT_TYPE, s\.SUBJECT_ID, 1\);$/);
  assert.doesNotMatch(grantMerge, /WHEN MATCHED|\bUPDATE\b|'USER'|'FUNCTIONAL'/i);
});

test('migration changes only component, Item1 and UI grant metadata', () => {
  const targets = [...sql.matchAll(/\b(?:MERGE INTO|UPDATE|INSERT INTO|DELETE FROM)\s+UI_DATA_SCHEMA\.([A-Z_]+)/gi)].map(match => match[1]);
  assert.deepEqual(targets, ['UI_COMPONENT', 'UI_ACTIVITY', 'ACTIVITY_GRANT']);
  assert.doesNotMatch(sql, /\b(?:CREATE|ALTER|DROP|TRUNCATE|GRANT|REVOKE|EXECUTE IMMEDIATE)\b/i);
  assert.doesNotMatch(sql, /UI_COMPONENT_API|FUNCTIONAL_ACTIVITY|USER_INFO_SCHEMA|USER_TRANSACT_SCHEMA|UI_SESSION/);
  assert.doesNotMatch(sql, /\b(?:CONNECT|PASSWORD|IDENTIFIED BY)\b/i);
  assert.doesNotMatch(sql, /UI_ITEM[234]|UI_TRANSACTIONS/);
});
