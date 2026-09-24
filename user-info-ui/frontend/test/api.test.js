import test from 'node:test';
import assert from 'node:assert/strict';
import { createApi, ApiError, positiveId } from '../src/api.js';

const token = 'example-session-token-123456789';
const session = overrides => ({ authenticated: true, user: { id: 2, username: 'alex' }, accessToken: token, expiresAt: new Date(Date.now() + 60000).toISOString(), ...overrides });
const response = (data, status = 200) => ({ ok: status >= 200 && status < 300, status, json: async () => data });
async function signedIn(fetchImpl, options) {
  const api = createApi((url, init) => url === './api/sign-in' ? Promise.resolve(response(session())) : fetchImpl(url, init), 100, options);
  await api.signIn({ username: 'alex', password: 'private-value' });
  return api;
}

test('registration sends only supported fields without bearer or cookies', async () => {
  let captured;
  const api = createApi(async (url, options) => { captured = { url, options }; return response({ id: 1, username: 'alex' }, 201); });
  await api.createUser({ username: 'alex', password: 'secret', email: 'a@local', phoneNumber: '1234567', confirmPassword: 'secret', role: 'ADMIN' });
  assert.equal(captured.url, './api/users');
  assert.deepEqual(JSON.parse(captured.options.body), { username: 'alex', password: 'secret', email: 'a@local', phoneNumber: '1234567' });
  assert.equal(captured.options.headers.Authorization, undefined);
  assert.equal(captured.options.credentials, 'omit');
  assert.equal(captured.options.cache, 'no-store');
  assert.equal(captured.options.redirect, 'error');
});
test('sign-in keeps bearer in API closure and protected requests use it', async () => {
  const requests = [];
  const api = createApi(async (url, options) => { requests.push({ url, options }); return response(url.endsWith('sign-in') ? session() : { id: 2 }); });
  const result = await api.signIn({ username: 'alex', password: 'secret' });
  assert.equal(result.accessToken, undefined);
  assert.equal(requests[0].options.headers.Authorization, undefined);
  await api.getUser(2);
  assert.equal(requests[1].url, './api/users/2');
  assert.equal(requests[1].options.headers.Authorization, `Bearer ${token}`);
  assert.equal(requests[1].options.body, undefined);
  assert.equal(api.hasSession(), true);
  api.clearSession();
});
test('protected requests require sign-in before fetch', async () => {
  await assert.rejects(createApi(() => assert.fail('unexpected fetch')).getMenu(), error => error.status === 401);
});
test('sign-out posts bearer revocation and clears the session on a 204', async () => {
  const api = await signedIn(async (url, options) => {
    assert.equal(url, './api/sign-out'); assert.equal(options.method, 'POST');
    assert.equal(options.headers.Authorization, `Bearer ${token}`); return response(null, 204);
  });
  await api.signOut(); assert.equal(api.hasSession(), false);
});
test('failed revocation still clears local bearer', async () => {
  const api = await signedIn(async () => { throw new Error('internal hostname'); });
  await assert.rejects(api.signOut(), error => error.code === 'NETWORK_ERROR');
  assert.equal(api.hasSession(), false);
});
test('a protected 401 clears session and notifies once', async () => {
  let notices = 0;
  const api = await signedIn(async () => response({}, 401), { onUnauthorized: () => notices++ });
  await assert.rejects(api.getMenu(), error => error.status === 401);
  assert.equal(api.hasSession(), false); assert.equal(notices, 1);
  await assert.rejects(api.getMenu(), error => error.status === 401); assert.equal(notices, 1);
});
test('expiry timer clears an idle signed-in screen', async () => {
  let notify;
  const notified = new Promise(resolve => { notify = resolve; });
  const api = createApi(async () => response(session({ expiresAt: new Date(Date.now() + 30).toISOString() })), 100, { onUnauthorized: notify });
  await api.signIn({});
  const keepAlive = setTimeout(() => notify('timeout'), 500);
  assert.notEqual(await notified, 'timeout'); clearTimeout(keepAlive);
  assert.equal(api.hasSession(), false);
});
test('late protected responses after logout are discarded', async () => {
  let finish;
  const api = await signedIn(() => new Promise(resolve => { finish = resolve; }));
  const pending = api.getMenu(); api.clearSession(); finish(response({ items: ['sensitive'] }));
  await assert.rejects(pending, error => error.code === 'CANCELLED');
});
test('caller cancellation reaches fetch without pretending to be a timeout', async () => {
  const api = await signedIn((url, { signal }) => new Promise((resolve, reject) => signal.addEventListener('abort', () => reject(new Error('abort')), { once: true })));
  const controller = new AbortController(); const pending = api.request('menu', { signal: controller.signal }); controller.abort();
  await assert.rejects(pending, error => error.code === 'CANCELLED'); api.clearSession();
});
test('request timeout is distinguished from cancellation', async () => {
  const api = createApi((url, { signal }) => new Promise((resolve, reject) => signal.addEventListener('abort', () => reject(new Error('abort')), { once: true })), 5);
  await assert.rejects(api.signIn({}), error => error.code === 'TIMEOUT');
});
for (const result of [session({ accessToken: '' }), session({ expiresAt: 'yesterday' }), session({ expiresAt: '2020-01-01T00:00:00Z' }), session({ user: { id: 0 } }), { authenticated: true }]) {
  test('malformed sign-in cannot establish a session', async () => {
    const api = createApi(async () => response(result));
    await assert.rejects(api.signIn({}), error => error.code === 'INVALID_RESPONSE'); assert.equal(api.hasSession(), false);
  });
}
for (const path of ['https://evil.example', '//evil', '../users', 'components/../users', 'components/UPPER/users', 'components/a/b/c', 'menu?token=secret', '__proto__']) {
  test(`request rejects unapproved path ${path}`, async () => {
    await assert.rejects(createApi(() => assert.fail('unexpected fetch')).request(path), error => error.code === 'INVALID_PATH');
  });
}
test('a registered component can call a fixed same-origin API proxy without changing the shell', async () => {
  const api = await signedIn(async (url, options) => {
    assert.equal(url, './api/components/new-report/rows?page=0&size=20');
    assert.equal(options.headers.Authorization, `Bearer ${token}`);
    return response({ items: [] });
  });
  assert.deepEqual(await api.request('components/new-report/rows?page=0&size=20'), { items: [] });
  api.clearSession();
});
for (const id of [0, -1, '../secret', '1?token=value', 1.5, Number.MAX_SAFE_INTEGER + 1, '9223372036854775808']) {
  test(`unsafe profile ID is rejected: ${id}`, async () => {
    await assert.rejects(createApi(() => assert.fail('unexpected fetch')).getUser(id), error => error.code === 'INVALID_ID');
  });
}
test('decimal string IDs preserve the full Oracle positive-long range', () => assert.equal(positiveId('9223372036854775807'), true));
test('validation errors preserve backend fields without raw network failures', async () => {
  const api = createApi(async () => response({ code: 'VALIDATION_FAILED', message: 'Check details.', errors: { email: 'Invalid email.' } }, 400));
  await assert.rejects(api.createUser({}), error => error instanceof ApiError && error.errors.email === 'Invalid email.');
});
test('wrong credentials remain actionable without session-expiry notification', async () => {
  const api = createApi(async () => response({}, 401), 100, { onUnauthorized: () => assert.fail('not signed in') });
  await assert.rejects(api.signIn({}), error => error.status === 401 && error.message.includes('incorrect'));
});
test('non-JSON success is rejected', async () => {
  const api = await signedIn(async () => ({ ok: true, status: 200, json: async () => { throw new Error('HTML'); } }));
  await assert.rejects(api.getMenu(), error => error.code === 'INVALID_RESPONSE'); api.clearSession();
});
