import test from 'node:test';
import assert from 'node:assert/strict';
import { componentFor, menuItems, loadStylesheet } from '../src/components.js';

const item = overrides => ({ uiActivityCode: 'UI_TRANSACTIONS', componentCode: 'transactions', label: 'Transaction', viewKey: 'transactions', sortOrder: 1, enabled: true, ...overrides });
test('component descriptors use fixed same-origin proxy paths', () => {
  assert.equal(typeof componentFor('transactions').load, 'function');
  assert.equal(componentFor('transactions').stylesheet, './components/transactions/styles.css');
  assert.equal(componentFor('new-component-2').stylesheet, './components/new-component-2/styles.css');
  assert.equal(typeof componentFor('a'.repeat(80)).load, 'function');
});
test('malformed codes cannot create a loader or stylesheet descriptor', () => {
  for (const code of [undefined, null, {}, '', '__proto__', 'Transactions', 'two words', '1transactions', 'a'.repeat(81),
    'https://evil.example/loader.js', '../../other', 'a/b', 'a\\b', 'a%2Fb', 'a?url=evil', 'a#fragment', '<script>']) {
    assert.equal(componentFor(code), null);
    assert.throws(() => menuItems({ items: [item({ componentCode: code })] }));
  }
});
test('a new database-registered component needs no frontend registry entry', async () => {
  const result = menuItems({ items: [item({ componentCode: 'new-component', viewKey: 'new-screen' })] });
  assert.equal(result[0].enabled, true);
  const component = componentFor(result[0].componentCode);
  // Node has no host HTTP proxy; its missing-file URL proves the loader targets
  // the fixed component route rather than requiring a compiled registration.
  await assert.rejects(component.load(), error => error.code === 'ERR_MODULE_NOT_FOUND'
    && error.url === new URL('../src/components/new-component/loader.js', import.meta.url).href);
});
test('database menu is sorted and placeholders always stay disabled', () => {
  const result = menuItems({ items: [item({ uiActivityCode: 'ITEM1', label: 'Item 1', componentCode: 'base-ui', viewKey: 'placeholder', sortOrder: 2 }), item()] });
  assert.deepEqual(result.map(row => row.label), ['Transaction', 'Item 1']); assert.equal(result[1].enabled, false);
});
test('disabled registered components stay disabled', () => assert.equal(menuItems({ items: [item({ enabled: false })] })[0].enabled, false));
test('menu-supplied URLs never select a loader or stylesheet', () => {
  const [row] = menuItems({ items: [item({ componentCode: 'registered-report', viewKey: 'https://evil.example/',
    url: 'https://evil.example/', loader: 'data:text/javascript,alert(1)', stylesheet: '//evil.example/styles.css' })] });
  assert.equal(row.enabled, true);
  assert.equal(row.url, undefined); assert.equal(row.loader, undefined); assert.equal(row.stylesheet, undefined);
  assert.equal(componentFor(row.componentCode).stylesheet, './components/registered-report/styles.css');
});
test('invalid and duplicate menu entries fail visibly', () => {
  assert.throws(() => menuItems({ items: [item(), item()] }));
  assert.throws(() => menuItems({ items: [item({ enabled: 'true' })] }));
  assert.throws(() => menuItems({}));
});
test('stylesheet loader cannot request arbitrary URLs', async () => {
  for (const href of ['https://evil.example/style.css', '//evil/style.css', './components/../styles.css', './components/a%2Fb/styles.css', './components/a/styles.css?url=evil']) {
    await assert.rejects(loadStylesheet(href, {}), /unavailable/);
  }
});
