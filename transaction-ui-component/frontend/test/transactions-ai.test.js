import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { mount } from '../src/js/components/transactions-ai/loader.js';

const PROFILE_PATH = 'components/transactions-ai/current-user?page=0&size=1';
const USER = { id: '7', username: 'signed-in-user' };
const page = (items, number = 0, size = 20, total = items.length) => ({ items, page: number, size, totalElements: total, totalPages: Math.ceil(total / size) });
const profile = (user = USER) => page([user], 0, 1, 1);
const transaction = (id = 1, userId = USER.id) => ({ id, userId, monthName: 'JANUARY', monthCount: 2, amount: '42.50', createdAt: '2026-09-24T10:00:00Z', modifiedAt: '2026-09-24T10:00:00Z', version: 0 });
const rows = count => Array.from({ length: count }, (_, index) => transaction(index + 1));
const tick = () => new Promise(resolve => setImmediate(resolve));
const deferred = () => {
  let resolve; let reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
};

// Minimal DOM double for lifecycle, local input, and asynchronous data behavior.
class Element {
  constructor(tag, documentRef) {
    this.tagName = tag.toUpperCase(); this.ownerDocument = documentRef;
    this.children = []; this.attributes = {}; this.events = new Map();
    this._text = ''; this.value = ''; this.hidden = false; this.disabled = false;
  }
  set textContent(value) { this.replaceChildren(); this._text = String(value); }
  get textContent() { return this._text + this.children.map(child => child.textContent).join(''); }
  append(...nodes) { for (const node of nodes) { node.parent = this; this.children.push(node); } }
  replaceChildren(...nodes) {
    for (const child of this.children) child.parent = null;
    this.children = []; this._text = ''; this.append(...nodes);
  }
  setAttribute(name, value) { this.attributes[name] = String(value); }
  removeAttribute(name) { delete this.attributes[name]; }
  addEventListener(name, handler) {
    if (!this.events.has(name)) this.events.set(name, new Set());
    this.events.get(name).add(handler);
  }
  removeEventListener(name, handler) { this.events.get(name)?.delete(handler); }
  emit(name) {
    const event = { defaultPrevented: false, preventDefault() { this.defaultPrevented = true; } };
    for (const handler of this.events.get(name) ?? []) handler(event);
    return event;
  }
  focus() { this.ownerDocument.activeElement = this; }
  remove() {
    if (this.parent) this.parent.children = this.parent.children.filter(child => child !== this);
    this.parent = null;
  }
}
function dom() {
  const documentRef = { createElement(tag) { return new Element(tag, this); } };
  return documentRef.createElement('section');
}
function descendants(node) { return [node, ...node.children.flatMap(descendants)]; }
const tag = (node, name) => descendants(node).find(item => item.tagName === name.toUpperCase());
const byClass = (node, name) => descendants(node).find(item => item.className?.split(' ').includes(name));
const button = (node, text) => descendants(node).find(item => item.tagName === 'BUTTON' && item.textContent === text);
function setup(handler = async path => path === PROFILE_PATH ? profile() : page([]), options = {}) {
  const container = options.container || dom();
  const calls = [];
  const cleanup = mount(container, {
    request(path, config) { calls.push({ path, ...config }); return handler(path, config); },
    onUnauthorized: options.onUnauthorized || (() => assert.fail('Unexpected authentication failure.'))
  });
  return { container, calls, cleanup, input: tag(container, 'input'), form: tag(container, 'form') };
}

test('mount presents accessible data-left and AI-right panels without a user selector', async () => {
  const { container, calls, input, form, cleanup } = setup();
  assert.equal(tag(container, 'h1').textContent, 'Transactions with AI');
  const panels = byClass(container, 'txn-ai-layout').children;
  assert.deepEqual(panels.map(panel => tag(panel, 'h2').textContent), ['Your transactions', 'AI text']);
  for (const panel of panels) assert.equal(panel.attributes['aria-labelledby'], tag(panel, 'h2').id);
  assert.equal(tag(container, 'select'), undefined);
  assert.equal(tag(container, 'label').htmlFor, input.id);
  assert.equal(input.type, 'text'); assert.equal(input.required, true); assert.equal(input.maxLength, 2000);
  assert.equal(input.autocomplete, 'off');
  assert.equal(form.children[0].children[1], input);
  assert.equal(form.children[1], button(container, 'Okay'));
  assert.equal(button(container, 'Okay').type, 'submit');
  assert.equal(byClass(container, 'txn-ai-data-panel').attributes['aria-busy'], 'true');
  assert.equal(byClass(container, 'txn-ai-data-status').textContent, 'Loading your account…');
  assert.deepEqual(calls.map(call => call.path), [PROFILE_PATH]);
  assert.equal(container.ownerDocument.activeElement, tag(container, 'h1'));
  await tick(); cleanup();
});

test('signed-in profile automatically loads only its own transactions and renders safe text', async () => {
  const user = { id: '9223372036854775807', username: '<script>account</script>' };
  const pending = deferred();
  const { container, calls, cleanup } = setup(async path => path === PROFILE_PATH ? profile(user) : pending.promise);
  await tick();
  assert.deepEqual(calls.map(call => call.path), [PROFILE_PATH, `components/transactions-ai/my-transactions?userId=${user.id}&page=0&size=20`]);
  assert.equal(byClass(container, 'txn-ai-user').textContent, `Signed-in user: ${user.id} — ${user.username}`);
  assert.equal(byClass(container, 'txn-ai-data-status').textContent, 'Loading your transactions…');
  assert.equal(byClass(container, 'txn-ai-table-wrap').hidden, true);
  pending.resolve(page([transaction(1, user.id)]));
  await tick();
  assert.equal(tag(container, 'tbody').children.length, 1);
  assert.equal(tag(container, 'tbody').children[0].children[3].textContent, '42.50');
  assert.equal(tag(container, 'caption').textContent, `Transactions for ${user.username} (ID ${user.id})`);
  assert.equal(tag(container, 'script'), undefined);
  assert.equal(byClass(container, 'txn-ai-data-panel').attributes['aria-busy'], 'false');
  assert.equal(byClass(container, 'txn-ai-table-wrap').attributes.role, 'region');
  assert.equal(byClass(container, 'txn-ai-table-wrap').tabIndex, 0);
  cleanup();
});

test('an empty transaction page reports an empty state without a table or pager', async () => {
  const { container, cleanup } = setup(); await tick();
  assert.equal(byClass(container, 'txn-ai-data-status').textContent, 'No transactions found for your account.');
  assert.equal(tag(container, 'tbody').children.length, 0);
  assert.equal(byClass(container, 'txn-ai-table-wrap').hidden, true);
  assert.equal(tag(container, 'nav').hidden, true);
  assert.equal(byClass(container, 'transaction-page-label').textContent, '');
  cleanup();
});

test('malformed or ambiguous signed-in profiles never trigger transaction requests', async () => {
  const malformed = [null, { items: [USER] }, page([], 0, 1, 0), page([USER], 0, 1, 2),
    profile({ id: 0, username: 'bad' }), profile({ id: Number.MAX_SAFE_INTEGER + 1, username: 'bad' }),
    profile({ id: '7', username: '  ' }), profile({ id: '7', username: 42 }), profile({ username: 'missing-id' })];
  for (const response of malformed) {
    const { container, calls, cleanup } = setup(async () => response); await tick();
    assert.equal(calls.length, 1);
    assert.equal(byClass(container, 'txn-ai-data-error').hidden, false);
    assert.equal(button(container, 'Retry account').hidden, false);
    assert.equal(byClass(container, 'txn-ai-user').textContent, '');
    assert.equal(tag(container, 'tbody').children.length, 0);
    cleanup();
  }
});

test('wrong-user rows or invalid pagination reject the complete page without partial rendering', async () => {
  for (const response of [page([transaction(), transaction(2, '8')]), page([], 0, 20, 2), page([transaction()], 1, 20, 1)]) {
    const { container, cleanup } = setup(async path => path === PROFILE_PATH ? profile() : response); await tick();
    assert.equal(byClass(container, 'txn-ai-data-error').hidden, false);
    assert.equal(button(container, 'Retry transactions').hidden, false);
    assert.equal(tag(container, 'tbody').children.length, 0);
    assert.equal(byClass(container, 'txn-ai-table-wrap').hidden, true);
    cleanup();
  }
});

test('Next and Previous follow service pagination and suppress duplicate clicks while loading', async () => {
  const { container, calls, cleanup } = setup(async path => {
    if (path === PROFILE_PATH) return profile();
    const number = Number(new URL(path, 'https://local/').searchParams.get('page'));
    return page(number ? [transaction(21)] : rows(20), number, 20, 21);
  });
  await tick();
  assert.equal(tag(container, 'tbody').children.length, 20);
  assert.equal(button(container, 'Previous').disabled, true);
  assert.equal(button(container, 'Next').disabled, false);
  button(container, 'Next').emit('click'); button(container, 'Next').emit('click');
  assert.equal(tag(container, 'tbody').children.length, 0);
  assert.equal(byClass(container, 'txn-ai-data-panel').attributes['aria-busy'], 'true');
  await tick();
  assert.equal(calls.length, 3);
  assert.match(calls[2].path, /page=1&size=20$/);
  assert.equal(tag(container, 'tbody').children.length, 1);
  assert.equal(byClass(container, 'transaction-page-label').textContent, 'Page 2 of 2');
  assert.equal(button(container, 'Next').disabled, true);
  button(container, 'Previous').emit('click'); await tick();
  assert.match(calls[3].path, /page=0&size=20$/);
  assert.equal(tag(container, 'tbody').children.length, 20);
  cleanup();
});

test('profile failure can be retried and then automatically loads transactions', async () => {
  let attempts = 0;
  const { container, calls, cleanup } = setup(async path => {
    if (path !== PROFILE_PATH) return page([transaction()]);
    if (++attempts === 1) throw new Error('Account unavailable');
    return profile();
  });
  await tick();
  assert.equal(byClass(container, 'txn-ai-data-error').textContent, 'Account unavailable');
  button(container, 'Retry account').emit('click'); await tick();
  assert.equal(attempts, 2); assert.equal(calls.length, 3);
  assert.equal(tag(container, 'tbody').children.length, 1);
  assert.equal(byClass(container, 'txn-ai-data-error').hidden, true);
  cleanup();
});

test('transaction failure retries the failed page without reloading the account', async () => {
  let failed = false;
  const { container, calls, cleanup } = setup(async path => {
    if (path === PROFILE_PATH) return profile();
    const number = Number(new URL(path, 'https://local/').searchParams.get('page'));
    if (number && !failed) { failed = true; throw new Error('Transactions unavailable'); }
    return page(number ? [transaction(21)] : rows(20), number, 20, 21);
  });
  await tick(); button(container, 'Next').emit('click'); await tick();
  assert.equal(byClass(container, 'txn-ai-data-error').textContent, 'Transactions unavailable');
  assert.equal(tag(container, 'tbody').children.length, 0);
  button(container, 'Retry transactions').emit('click'); await tick();
  assert.equal(calls.filter(call => call.path === PROFILE_PATH).length, 1);
  assert.equal(calls[3].path, calls[2].path);
  assert.equal(tag(container, 'tbody').children.length, 1);
  cleanup();
});

test('a vanished later page retries page zero, including when all transactions disappear', async () => {
  for (const remaining of [20, 0]) {
    let transactionCalls = 0;
    const { container, calls, cleanup } = setup(async path => {
      if (path === PROFILE_PATH) return profile();
      const number = Number(new URL(path, 'https://local/').searchParams.get('page'));
      transactionCalls++;
      if (transactionCalls === 1) return page(rows(20), 0, 20, 21);
      return page(number ? [] : rows(remaining), number, 20, remaining);
    });
    await tick(); button(container, 'Next').emit('click'); await tick();
    assert.match(byClass(container, 'txn-ai-data-error').textContent, /Retry to load the first page/);
    button(container, 'Retry transactions').emit('click'); await tick();
    assert.match(calls[3].path, /page=0&size=20$/);
    assert.equal(tag(container, 'tbody').children.length, remaining);
    assert.equal(byClass(container, 'txn-ai-data-error').hidden, true);
    cleanup();
  }
});

test('current profile or transaction 401 clears data and local text and notifies the host once', async () => {
  for (const failProfile of [true, false]) {
    let notifications = 0;
    const { container, input, form, calls, cleanup } = setup(async path => {
      if (!failProfile && path === PROFILE_PATH) return profile();
      throw Object.assign(new Error('Expired'), { status: 401 });
    }, { onUnauthorized() { notifications++; } });
    input.value = 'Private local text'; form.emit('submit'); await tick();
    assert.equal(notifications, 1); assert.equal(input.value, '');
    assert.equal(input.disabled, true); assert.equal(button(container, 'Okay').disabled, true);
    assert.equal(byClass(container, 'txn-ai-user').textContent, '');
    assert.equal(tag(container, 'tbody').children.length, 0);
    assert.equal(byClass(container, 'txn-ai-data-error').hidden, true);
    assert.match(byClass(container, 'txn-ai-data-status').textContent, /session has expired/);
    const before = calls.length; form.emit('submit'); await tick();
    assert.equal(calls.length, before); assert.equal(notifications, 1);
    cleanup();
  }
});

test('an unauthorized callback can synchronously unmount without later UI mutation', async () => {
  let mounted;
  mounted = setup(async () => { throw Object.assign(new Error('Expired'), { status: 401 }); }, {
    onUnauthorized() { mounted.cleanup(); }
  });
  await tick(); assert.equal(mounted.container.children.length, 0);
});

test('cleanup during profile loading aborts it and a late profile cannot start transactions', async () => {
  const pending = deferred();
  const { container, calls, cleanup } = setup(() => pending.promise);
  cleanup(); assert.equal(calls[0].signal.aborted, true);
  pending.resolve(profile()); await tick();
  assert.equal(calls.length, 1); assert.equal(container.children.length, 0);
});

test('late transaction success cannot overwrite a fresh mount', async () => {
  const pending = deferred();
  const old = setup(async path => path === PROFILE_PATH ? profile() : pending.promise);
  await tick(); old.cleanup();
  assert.equal(old.calls[0].signal.aborted, true); assert.equal(old.calls[1].signal.aborted, true);
  const fresh = setup(async path => path === PROFILE_PATH ? profile() : page([transaction(99)]), { container: old.container });
  await tick(); pending.resolve(page([transaction(42)])); await tick();
  assert.equal(tag(fresh.container, 'tbody').children[0].children[0].textContent, '99');
  old.cleanup(); assert.equal(fresh.container.children.length, 1);
  fresh.cleanup();
});

test('late 401 after disposal does not notify the host or disturb the next mount', async () => {
  for (const failProfile of [true, false]) {
    const pending = deferred(); let notifications = 0;
    const old = setup(path => !failProfile && path === PROFILE_PATH ? Promise.resolve(profile()) : pending.promise,
      { onUnauthorized() { notifications++; } });
    await tick(); old.cleanup();
    const fresh = setup(undefined, { container: old.container }); await tick();
    pending.reject(Object.assign(new Error('Old expired session'), { status: 401 })); await tick();
    assert.equal(notifications, 0);
    assert.equal(byClass(fresh.container, 'txn-ai-data-status').textContent, 'No transactions found for your account.');
    fresh.cleanup();
  }
});

test('AI submission stays local and works while account data is loading', async () => {
  const pending = deferred();
  const { container, input, form, calls, cleanup } = setup(() => pending.promise);
  const privateText = '<script>private transaction request</script>';
  input.value = privateText;
  assert.equal(form.emit('submit').defaultPrevented, true);
  await tick(); assert.equal(calls.length, 1);
  assert.equal(input.value, privateText);
  assert.equal(byClass(container, 'txn-ai-status').textContent, 'AI responses, links and graphs are not connected yet. Your text has not been sent or saved.');
  assert.equal(byClass(container, 'txn-ai-data-status').textContent, 'Loading your account…');
  assert.equal(container.textContent.includes(privateText), false);
  assert.equal(descendants(container).some(node => ['A', 'CANVAS', 'SVG', 'SCRIPT'].includes(node.tagName)), false);
  cleanup(); pending.resolve(profile()); await tick();
});

test('AI submission and editing do not reload or alter already loaded transaction data', async () => {
  const { container, input, form, calls, cleanup } = setup(async path => path === PROFILE_PATH ? profile() : page([transaction()]));
  await tick(); const dataBefore = byClass(container, 'txn-ai-data-panel').textContent;
  input.value = 'First request'; form.emit('submit');
  input.value = 'Changed request'; input.emit('input');
  assert.equal(byClass(container, 'txn-ai-status').textContent, '');
  form.emit('submit'); await tick();
  assert.equal(calls.length, 2);
  assert.equal(byClass(container, 'txn-ai-data-panel').textContent, dataBefore);
  assert.equal(container.textContent.includes('First request'), false);
  assert.equal(container.textContent.includes('Changed request'), false);
  cleanup();
});

test('AI input rejects blank/oversized text, accepts the limit, and clears errors on edit', async () => {
  const { container, input, form, calls, cleanup } = setup(); await tick();
  for (const value of ['', '   ', '\t\n ', '\u00a0', 'x'.repeat(2001)]) {
    input.value = value; form.emit('submit');
    const error = byClass(container, 'txn-ai-error');
    assert.equal(error.hidden, false);
    assert.equal(error.textContent, value.length > 2000 ? 'Enter no more than 2000 characters.' : 'Enter some text before choosing Okay.');
    assert.equal(input.attributes['aria-invalid'], 'true');
    assert.equal(input.attributes['aria-describedby'].split(' ').includes(error.id), true);
    assert.equal(container.ownerDocument.activeElement, input);
    assert.equal(byClass(container, 'txn-ai-status').textContent, '');
  }
  input.value = 'x'.repeat(2000); input.emit('input');
  assert.equal(byClass(container, 'txn-ai-error').hidden, true);
  assert.equal(input.attributes['aria-invalid'], undefined);
  form.emit('submit');
  assert.match(byClass(container, 'txn-ai-status').textContent, /not been sent or saved/);
  assert.equal(calls.length, 2); cleanup();
});

test('cleanup clears text, rows, feedback and listeners and remount starts fresh', async () => {
  const { container, input, form, cleanup } = setup(async path => path === PROFILE_PATH ? profile() : page([transaction()]));
  await tick(); input.value = 'Private local text'; form.emit('submit');
  const oldNodes = descendants(container); const status = byClass(container, 'txn-ai-status'); const tbody = tag(container, 'tbody');
  cleanup(); cleanup();
  assert.equal(input.value, ''); assert.equal(status.textContent, ''); assert.equal(tbody.children.length, 0);
  assert.equal(container.children.length, 0);
  for (const node of oldNodes) for (const handlers of node.events.values()) assert.equal(handlers.size, 0);
  form.emit('submit'); input.emit('input'); assert.equal(status.textContent, '');
  const fresh = setup(undefined, { container }); await tick();
  assert.equal(fresh.input.value, ''); assert.notEqual(fresh.input.id, input.id);
  assert.equal(byClass(container, 'txn-ai-status').textContent, '');
  fresh.cleanup();
});

test('loader permits only the two authenticated request routes and has no AI transport or storage', async () => {
  const source = await readFile(new URL('../src/js/components/transactions-ai/loader.js', import.meta.url), 'utf8');
  assert.match(source, /import \{ positiveId, validatePage, transactionCells \} from '\.\.\/transactions\/loader\.js'/);
  assert.equal((source.match(/\brequest\s*\(/g) || []).length, 2);
  assert.ok(source.includes("request('components/transactions-ai/current-user?page=0&size=1'"));
  assert.ok(source.includes('request(`components/transactions-ai/my-transactions?userId=${encodeURIComponent(user.id)}&page=${page}&size=${PAGE_SIZE}`'));
  assert.doesNotMatch(source, /innerHTML|outerHTML|insertAdjacentHTML|localStorage|sessionStorage|indexedDB|\.cookie|\bfetch\s*\(|XMLHttpRequest|WebSocket|EventSource|sendBeacon|\.location|\bimport\s*\(|components\/transactions\//);
});

test('responsive layout gives data more desktop space and keeps Okay beside the input when stacked', async () => {
  const css = await readFile(new URL('../src/js/components/transactions-ai/styles.css', import.meta.url), 'utf8');
  assert.match(css, /@import url\("\.\.\/transactions\/styles\.css"\)/);
  assert.match(css, /\.txn-ai-layout\s*\{[^}]*grid-template-columns:\s*minmax\(0,\s*1\.7fr\) minmax\(280px,\s*1fr\)/s);
  assert.match(css, /@media \(max-width: 900px\)\s*\{\s*\.txn-ai-layout\s*\{\s*grid-template-columns:\s*minmax\(0,\s*1fr\)/);
  assert.match(css, /\.txn-ai-table-wrap\s*\{[^}]*max-width:\s*100%;[^}]*overflow-x:\s*auto/);
  const form = css.match(/\.txn-ai-form\s*\{([^}]+)\}/)[1];
  assert.match(form, /display:\s*grid/); assert.match(form, /grid-template-columns:\s*minmax\(0,\s*1fr\)\s+auto/);
  assert.match(form, /direction:\s*ltr/);
  const narrow = css.slice(css.indexOf('@media (max-width: 540px)'));
  assert.doesNotMatch(narrow, /grid-template-columns|flex-direction|grid-column|\border\s*:/);
});

test('metadata uses the existing WAR and signed-in transaction permission', async () => {
  const manifest = JSON.parse(await readFile(new URL('../src/js/components/transactions-ai/manifest.json', import.meta.url), 'utf8'));
  assert.equal(manifest.name, 'transaction-ui-component'); assert.equal(manifest.componentCode, 'transactions-ai');
  assert.equal(manifest.componentServerName, 'transaction-ui-component');
  assert.equal(manifest.resourceBasePath, '/js/components/transactions-ai');
  assert.equal(manifest.host, 'user-info-ui'); assert.equal(manifest.viewKey, 'transactions-ai');
  assert.equal(manifest.uiActivityCode, 'UI_ITEM1');
  assert.deepEqual(manifest.functionalActivityCodes, ['TXN_AI_SELF_READ']);
  assert.equal(manifest.entry, 'loader.js'); assert.equal(manifest.stylesheet, 'styles.css');
});
