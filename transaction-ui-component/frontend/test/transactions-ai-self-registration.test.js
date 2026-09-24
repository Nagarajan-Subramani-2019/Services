import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

// Static contracts only: Oracle PL/SQL is not executed by these tests.
const source = await readFile(new URL('../../db/UI_DATA_SCHEMA/003_register_transactions_ai_self_data.sql', import.meta.url), 'utf8');
const sql = source.replace(/--[^\r\n]*/g, '').replace(/\s+/g, ' ');

test('self-data migration checks 002 prerequisites and conflicts with rollback', () => {
  assert.match(source, /after 002_register_transactions_ai\.sql/);
  assert.match(sql, /WHENEVER SQLERROR EXIT SQL.SQLCODE ROLLBACK/);
  assert.match(sql, /WHENEVER OSERROR EXIT FAILURE ROLLBACK/);
  assert.match(sql, /SERVER_CODE = 'transaction-ui' FOR UPDATE/);
  assert.match(sql, /l_activity\.COMPONENT_CODE <> 'transactions-ai'/);
  assert.match(sql, /l_activity\.VIEW_KEY <> 'transactions-ai'/);
  assert.match(sql, /l_component\.RESOURCE_NAME <> 'transactions-ai'/);
  for (const code of [20031,20032,20033,20034]) assert.ok(sql.includes(`RAISE_APPLICATION_ERROR(-${code},`));
  assert.match(sql, /WHEN OTHERS THEN ROLLBACK; RAISE; END; \/ COMMIT;/);
});

test('self-data routes match the unchanged host adapter contracts', () => {
  assert.match(sql, /'current-user' OPERATION_CODE, '\/api\/current-user' UPSTREAM_PATH, 'USER_OPTIONS' RESPONSE_TYPE, 'page,size' QUERY_PARAMETERS/);
  assert.match(sql, /'my-transactions', '\/api\/my-transactions', 'TRANSACTION_PAGE', 'userId,page,size'/);
  assert.match(sql, /'UI_ITEM1', 'TXN_AI_SELF_READ', s\.RESPONSE_TYPE/);
  assert.match(sql, /l_route\.FUNCTIONAL_ACTIVITY_CODE <> 'TXN_AI_SELF_READ'/);
  assert.match(sql, /l_route\.QUERY_PARAMETERS IS NULL/);
  assert.match(sql, /l_route\.QUERY_PARAMETERS <> expected\.QUERY_PARAMETERS/);
});

test('self-data registration preserves existing disable and deny decisions without DDL', () => {
  assert.equal([...sql.matchAll(/WHEN NOT MATCHED THEN INSERT/g)].length, 3);
  assert.doesNotMatch(sql, /WHEN MATCHED|UPDATE\s+UI_DATA_SCHEMA|DELETE FROM|\bCREATE\b|\bDROP\b|\bTRUNCATE\b|\bGRANT\b|\bREVOKE\b|EXECUTE IMMEDIATE/i);
  assert.doesNotMatch(sql, /UI_SESSION|USER_INFO_SCHEMA|USER_TRANSACT_SCHEMA|TXN_USERS_READ|TXN_LIST_READ/);
  assert.match(sql, /'TXN_AI_SELF_READ' FUNCTIONAL_ACTIVITY_CODE, 'transactions-ai' COMPONENT_CODE/);
  assert.match(sql, /'FUNCTIONAL' ACTIVITY_KIND, 'TXN_AI_SELF_READ' ACTIVITY_CODE, 'ALL' SUBJECT_TYPE, '\*' SUBJECT_ID/);
  assert.match(sql, /t\.SUBJECT_TYPE = s\.SUBJECT_TYPE AND t\.SUBJECT_ID = s\.SUBJECT_ID/);
});
