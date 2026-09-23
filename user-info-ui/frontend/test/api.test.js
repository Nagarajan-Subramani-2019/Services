import test from 'node:test';
import assert from 'node:assert/strict';
import { createApi, ApiError } from '../src/api.js';

const response = (data, status = 200) => ({ ok: status >= 200 && status < 300, status, json: async () => data });
test('registration uses same-origin relative URL and only the four supported fields', async () => {
  let captured;
  const api = createApi(async (url, options) => { captured = { url, options }; return response({ id: 1, username: 'alex' }, 201); });
  const result = await api.createUser({ username: 'alex', password: 'private-value', email: 'alex@example.com', phoneNumber: '+1234567890', confirmPassword: 'private-value', role: 'ADMIN' });
  assert.equal(captured.url, './api/users');
  assert.equal(captured.options.method, 'POST');
  assert.deepEqual(JSON.parse(captured.options.body), { username: 'alex', password: 'private-value', email: 'alex@example.com', phoneNumber: '+1234567890' });
  assert.equal(captured.options.credentials, 'omit');
  assert.equal(captured.options.cache, 'no-store');
  assert.equal(captured.options.redirect, 'error');
  assert.equal(captured.options.headers['Content-Type'], 'application/json');
  assert.equal(result.id, 1);
});
test('signin sends JSON credentials to the signin adapter, with no invented auth state', async () => {
  let captured;
  const result = await createApi(async (url, options) => { captured = { url, options }; return response({ authenticated: true, user: { id: 2 } }); })
    .signIn({ username: 'alex', password: 'private-value', rememberMe: true });
  assert.equal(captured.url, './api/sign-in');
  assert.deepEqual(JSON.parse(captured.options.body), { username: 'alex', password: 'private-value' });
  assert.equal(result.authenticated, true);
});
test('profile uses GET without authorization or password', async () => {
  await createApi(async (url, options) => {
    assert.equal(url, './api/users/42');
    assert.equal(options.method, 'GET');
    assert.equal(options.body, undefined);
    assert.equal(options.headers.Authorization, undefined);
    return response({ id: 42 });
  }).getUser(42);
});
for (const id of [0, -1, '../secret', '1?token=value', 1.5, 'a', Number.MAX_SAFE_INTEGER + 1]) {
  test(`unsafe profile ID ${id} is rejected before fetch`, async () => {
    await assert.rejects(createApi(() => assert.fail('fetch should not run')).getUser(id), error => error.code === 'INVALID_ID');
  });
}
test('validation errors preserve safe backend field messages', async () => {
  const api = createApi(async () => response({ code: 'VALIDATION_FAILED', message: 'Please check your details.', errors: { email: 'Invalid email.' } }, 400));
  await assert.rejects(api.createUser({}), error => error instanceof ApiError && error.status === 400 && error.errors.email === 'Invalid email.');
});
test('duplicate registration is propagated as a 409', async () => {
  await assert.rejects(createApi(async () => response({ code: 'USER_EXISTS', message: 'User already exists.' }, 409)).createUser({}), error => error.status === 409 && error.code === 'USER_EXISTS');
});
test('wrong credentials return an actionable authentication error', async () => {
  await assert.rejects(createApi(async () => response({}, 401)).signIn({}), error => error.status === 401 && error.message.includes('incorrect'));
});
test('HTML/non-JSON response is not rendered or accepted', async () => {
  const api = createApi(async () => ({ ok: true, status: 200, json: async () => { throw new Error('HTML'); } }));
  await assert.rejects(api.getUser(1), error => error.code === 'INVALID_RESPONSE');
});
test('network failure does not expose raw error details', async () => {
  await assert.rejects(createApi(async () => { throw new Error('sensitive internal address'); }).getUser(1), error => error.code === 'NETWORK_ERROR' && !error.message.includes('sensitive'));
});
test('requests time out and abort', async () => {
  const api = createApi((url, { signal }) => new Promise((resolve, reject) => signal.addEventListener('abort', () => reject(new Error('aborted')), { once: true })), 5);
  await assert.rejects(api.getUser(1), error => error.code === 'TIMEOUT');
});
test('malformed backend field error collections are discarded', async () => {
  const api = createApi(async () => response({ message: 'Error', errors: ['not-a-map'] }, 400));
  await assert.rejects(api.getUser(1), error => Object.keys(error.errors).length === 0);
});
