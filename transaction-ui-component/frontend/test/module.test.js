import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { loadAllUsers, validatePage, amountLabel, transactionCells, mount } from '../src/js/components/transactions/loader.js';

const users = count => Array.from({ length: count }, (_, index) => ({ id: index + 1, username: `user-${index + 1}` }));
const page = (items, number, size, total) => ({ items, page: number, size, totalElements: total, totalPages: Math.ceil(total / size) });
const transaction = (id = 1, userId = '1') => ({ id, userId, monthName: 'JANUARY', monthCount: 2, amount: '42.50', createdAt: '2026-09-23T10:00:00Z', modifiedAt: '2026-09-23T10:00:00Z', version: 0 });
const tick = () => new Promise(resolve => setImmediate(resolve));

test('all user pages load before a complete list is returned', async () => {
  const all = users(205); const calls = [];
  const result = await loadAllUsers(async path => {
    calls.push(path); const number = Number(new URL(path, 'https://local/').searchParams.get('page'));
    return page(all.slice(number * 100, (number + 1) * 100), number, 100, all.length);
  });
  assert.equal(result.length, 205); assert.equal(result[204].id, '205'); assert.equal(calls.length, 3);
  assert.equal(calls[0], 'components/transactions/users?page=0&size=100');
});
test('empty directory is a completed list', async () => assert.deepEqual(await loadAllUsers(async () => page([], 0, 100, 0)), []));
test('oversized directory fails explicitly instead of returning a truncated dropdown', async () => {
  await assert.rejects(loadAllUsers(async () => page(users(100), 0, 100, 10001)), error => error.code === 'USER_LIST_LIMIT' && error.message.includes('10000'));
});
test('a later failed user page does not return partial users', async () => {
  let count = 0;
  await assert.rejects(loadAllUsers(async () => { if (count++) throw new Error('retry me'); return page(users(100), 0, 100, 101); }), /retry me/);
});
test('changing or duplicated users require a fresh complete directory', async () => {
  let count = 0;
  await assert.rejects(loadAllUsers(async () => count++ ? page([{ id: 1, username: 'duplicate' }], 1, 100, 101) : page(users(100), 0, 100, 101)), /invalid or changing/);
});
test('missing pagination metadata is rejected', () => {
  assert.throws(() => validatePage({ items: [] }, 0, 20));
  assert.throws(() => validatePage(page([], 0, 20, 21), 0, 20));
});
test('cancelled directory loading never returns user data', async () => {
  const controller = new AbortController(); controller.abort();
  await assert.rejects(loadAllUsers(() => assert.fail('should not fetch'), { signal: controller.signal }), error => error.code === 'CANCELLED');
});
test('amounts preserve decimal strings without assuming a currency', () => {
  assert.equal(amountLabel('99999999999999999.99'), '99,999,999,999,999,999.99');
  assert.equal(amountLabel(42.5), '42.50'); assert.equal(amountLabel('0'), '0.00');
  assert.throws(() => amountLabel(Number.MAX_SAFE_INTEGER)); assert.throws(() => amountLabel('1.234'));
});
test('transaction validation rejects a row belonging to a different user', () => {
  assert.throws(() => transactionCells(transaction(1, '2'), '1'));
  assert.equal(transactionCells(transaction(), '1')[3], '42.50');
});
test('full positive-long string identifiers survive without Number conversion', () => {
  const row = transaction('9223372036854775807', '9223372036854775807');
  assert.equal(transactionCells(row, row.userId)[0], row.id);
});

// A small DOM double exercises lifecycle and event behavior without a browser dependency.
class Element {
  constructor(tag, documentRef) {
    this.tagName = tag.toUpperCase(); this.ownerDocument = documentRef; this.children = []; this.attributes = {};
    this.events = new Map(); this.hidden = false; this.disabled = false; this._text = ''; this.value = '';
  }
  set textContent(text) { this._text = String(text); this.children = []; }
  get textContent() { return this._text + this.children.map(child => child.textContent).join(''); }
  append(...nodes) { for (const node of nodes) { node.parent = this; this.children.push(node); } }
  replaceChildren(...nodes) { for (const node of this.children) node.parent = null; this.children = []; this._text = ''; if (this.tagName === 'SELECT') this.value = ''; this.append(...nodes); }
  setAttribute(name, value) { this.attributes[name] = value; }
  addEventListener(name, handler) { if (!this.events.has(name)) this.events.set(name, new Set()); this.events.get(name).add(handler); }
  removeEventListener(name, handler) { this.events.get(name)?.delete(handler); }
  emit(name) { for (const handler of this.events.get(name) ?? []) handler({ preventDefault() {} }); }
  focus() { this.ownerDocument.activeElement = this; }
  remove() { if (this.parent) this.parent.children = this.parent.children.filter(node => node !== this); this.parent = null; }
}
function dom() {
  const documentRef = { createElement(tag) { return new Element(tag, this); }, activeElement: null };
  return documentRef.createElement('section');
}
function find(node, predicate) {
  if (predicate(node)) return node;
  for (const child of node.children) { const match = find(child, predicate); if (match) return match; }
  return null;
}
const tag = (node, name) => find(node, item => item.tagName === name.toUpperCase());
const button = (node, name) => find(node, item => item.tagName === 'BUTTON' && item.textContent === name);

test('mount supplies an accessible selector, fetches only after Okay, and renders empty results', async () => {
  const container = dom(); const calls = [];
  const cleanup = mount(container, { request: async path => {
    calls.push(path); return path.includes('/users?') ? page(users(1), 0, 100, 1) : page([], 0, 20, 0);
  } });
  assert.equal(tag(container, 'h1').textContent, 'Transactions');
  assert.equal(tag(container, 'label').htmlFor, tag(container, 'select').id);
  await tick(); assert.equal(calls.length, 1);
  const select = tag(container, 'select'); select.value = '1'; select.emit('change');
  assert.equal(button(container, 'Okay').disabled, false); assert.equal(calls.length, 1);
  tag(container, 'form').emit('submit'); await tick();
  assert.equal(calls[1], 'components/transactions/transactions?userId=1&page=0&size=20');
  assert.match(container.textContent, /No transactions found for user-1/);
  cleanup(); assert.equal(container.children.length, 0);
});
test('Next and Previous follow service pagination and render rows as text', async () => {
  const container = dom(); const paths = [];
  const cleanup = mount(container, { request: async path => {
    if (path.includes('/users?')) return page([{ id: 1, username: '<script>safe</script>' }], 0, 100, 1);
    paths.push(path); const number = Number(new URL(path, 'https://local/').searchParams.get('page'));
    return page(Array.from({ length: number ? 1 : 20 }, (_, index) => transaction(number * 20 + index + 1)), number, 20, 21);
  } });
  await tick(); const select = tag(container, 'select'); select.value = '1'; select.emit('change'); tag(container, 'form').emit('submit'); await tick();
  assert.equal(tag(container, 'tbody').children.length, 20); assert.equal(button(container, 'Previous').disabled, true);
  assert.match(tag(container, 'caption').textContent, /<script>safe<\/script>/);
  button(container, 'Next').emit('click'); await tick(); assert.equal(tag(container, 'tbody').children.length, 1);
  assert.equal(button(container, 'Next').disabled, true); assert.match(paths[1], /page=1/);
  button(container, 'Previous').emit('click'); await tick(); assert.match(paths[2], /page=0/); cleanup();
});
test('selection changes cancel an older response and clear previous rows immediately', async () => {
  const container = dom(); let complete; let signal;
  const cleanup = mount(container, { request: (path, options) => path.includes('/users?') ? Promise.resolve(page(users(2), 0, 100, 2))
    : new Promise(resolve => { complete = resolve; signal = options.signal; }) });
  await tick(); const select = tag(container, 'select'); select.value = '1'; select.emit('change'); tag(container, 'form').emit('submit');
  select.value = '2'; select.emit('change'); assert.equal(signal.aborted, true);
  complete(page([transaction()], 0, 20, 1)); await tick();
  assert.equal(tag(container, 'tbody').children.length, 0); assert.equal(tag(container, 'caption').textContent, ''); cleanup();
});
test('cleanup cancels loading and a late response cannot restore sensitive data', async () => {
  const container = dom(); let complete; let signal;
  const cleanup = mount(container, { request: (path, options) => new Promise(resolve => { complete = resolve; signal = options.signal; }) });
  cleanup(); assert.equal(signal.aborted, true); complete(page(users(2), 0, 100, 2)); await tick();
  assert.equal(container.children.length, 0);
});
test('user load failure is retryable and leaves selection disabled', async () => {
  const container = dom(); let attempts = 0;
  const cleanup = mount(container, { request: async () => { if (attempts++ === 0) throw new Error('Service unavailable'); return page(users(1), 0, 100, 1); } });
  await tick(); assert.equal(tag(container, 'select').disabled, true); assert.equal(button(container, 'Retry user list').hidden, false);
  button(container, 'Retry user list').emit('click'); await tick(); assert.equal(tag(container, 'select').disabled, false); cleanup();
});
test('unauthorized component response returns control to the shell', async () => {
  let notifications = 0; const container = dom();
  const cleanup = mount(container, { request: async () => { throw Object.assign(new Error('Expired'), { status: 401 }); }, onUnauthorized: () => notifications++ });
  await tick(); assert.equal(notifications, 1); cleanup();
});
test('component has no HTML injection or browser storage path', async () => {
  const source = await readFile(new URL('../src/js/components/transactions/loader.js', import.meta.url), 'utf8');
  assert.doesNotMatch(source, /innerHTML|outerHTML|insertAdjacentHTML|localStorage|sessionStorage|document\.cookie|\bfetch\(/);
});

test('component metadata matches the host registry and approved activities', async () => {
  const manifest = JSON.parse(await readFile(new URL('../src/js/components/transactions/manifest.json', import.meta.url), 'utf8'));
  assert.equal(manifest.name, 'transaction-ui-component');
  assert.equal(manifest.componentCode, 'transactions');
  assert.equal(manifest.host, 'user-info-ui');
  assert.equal(manifest.viewKey, 'transactions');
  assert.equal(manifest.uiActivityCode, 'UI_TRANSACTIONS');
  assert.deepEqual(manifest.functionalActivityCodes, ['TXN_USERS_READ', 'TXN_LIST_READ']);
  assert.equal(manifest.componentServerName, 'transaction-ui-component');
  assert.equal(manifest.resourceBasePath, '/js/components/transactions');
  assert.equal(manifest.entry, 'loader.js');
  assert.equal(manifest.stylesheet, 'styles.css');
});

test('component-server mapping agrees with its published metadata', async () => {
  const mapping = JSON.parse(await readFile(new URL('../src/js/components/resources/cs-mapping.json', import.meta.url), 'utf8'));
  assert.deepEqual(mapping, {
    transactions: 'transaction-ui-component',
    'transactions-ai': 'transaction-ui-component'
  });
});
