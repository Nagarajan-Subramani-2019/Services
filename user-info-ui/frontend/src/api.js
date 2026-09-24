export class ApiError extends Error {
  constructor(message, { status = 0, code = 'NETWORK_ERROR', errors = {} } = {}) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
    this.errors = errors && typeof errors === 'object' && !Array.isArray(errors) ? errors : {};
  }
}

export function positiveId(value) {
  return typeof value === 'number' ? Number.isSafeInteger(value) && value > 0
    : typeof value === 'string' && /^[1-9]\d{0,18}$/.test(value) && BigInt(value) <= 9223372036854775807n;
}

export function createApi(fetchImpl = (...args) => globalThis.fetch(...args), timeoutMs = 20000, { onUnauthorized = () => {} } = {}) {
  let token = null;
  let expires = 0;
  let sessionVersion = 0;
  let expiryTimer;
  const pending = new Set();

  function clearSession() {
    token = null; expires = 0; sessionVersion++;
    clearTimeout(expiryTimer);
    for (const controller of pending) controller.abort();
    pending.clear();
  }
  function expired() {
    clearSession();
    onUnauthorized();
  }
  function setSession(result) {
    const expiry = Date.parse(result?.expiresAt);
    if (result?.authenticated !== true || !positiveId(result?.user?.id) || typeof result.user.username !== 'string'
        || typeof result.accessToken !== 'string' || !/^[A-Za-z0-9._~-]{16,4096}$/.test(result.accessToken)
        || !Number.isFinite(expiry) || expiry <= Date.now()) {
      throw new ApiError('The service returned an invalid sign-in session. Please try again.', { code: 'INVALID_RESPONSE' });
    }
    clearSession();
    token = result.accessToken; expires = expiry;
    expiryTimer = setTimeout(expired, Math.min(expires - Date.now(), 2147483647));
    expiryTimer.unref?.();
  }

  async function send(path, { method = 'GET', body, signal, publicRequest = false } = {}) {
    if (!/^(?:users(?:\/[1-9]\d*)?|sign-in|sign-out|menu|components\/[a-z][a-z0-9-]{0,79}\/[a-z][a-z0-9-]{0,79}(?:\?[a-zA-Z0-9=&%.-]*)?)$/.test(path)) {
      throw new ApiError('This request path is unavailable.', { code: 'INVALID_PATH' });
    }
    if (!publicRequest && (!token || Date.now() >= expires)) {
      if (token) expired();
      throw new ApiError('Your session has expired. Please sign in again.', { status: 401, code: 'SESSION_EXPIRED' });
    }
    const requestVersion = sessionVersion;
    const controller = new AbortController();
    let timedOut = false;
    const cancel = () => controller.abort();
    if (signal?.aborted) cancel();
    else signal?.addEventListener('abort', cancel, { once: true });
    pending.add(controller);
    const timeout = setTimeout(() => { timedOut = true; controller.abort(); }, timeoutMs);
    try {
      const response = await fetchImpl(`./api/${path}`, {
        method,
        headers: { Accept: 'application/json', ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
          ...(!publicRequest ? { Authorization: `Bearer ${token}` } : {}) },
        ...(body !== undefined ? { body: JSON.stringify(body) } : {}),
        signal: controller.signal, credentials: 'omit', cache: 'no-store', redirect: 'error'
      });
      if (requestVersion !== sessionVersion || controller.signal.aborted) {
        throw new ApiError('Request cancelled.', { code: 'CANCELLED' });
      }
      if (response.status === 401 && !publicRequest) expired();
      let data = null;
      if (response.status !== 204) {
        try { data = await response.json(); }
        catch { throw new ApiError('The service returned an unexpected response. Please try again.', { status: response.status, code: 'INVALID_RESPONSE' }); }
      }
      if (!response.ok) {
        const fallback = response.status === 401
          ? (publicRequest ? 'The username or password is incorrect.' : 'Your session has expired. Please sign in again.')
          : 'The request could not be completed. Please try again.';
        throw new ApiError(typeof data?.message === 'string' ? data.message : fallback, {
          status: response.status, code: typeof data?.code === 'string' ? data.code : 'REQUEST_FAILED', errors: data?.errors
        });
      }
      if (requestVersion !== sessionVersion || controller.signal.aborted) throw new ApiError('Request cancelled.', { code: 'CANCELLED' });
      return data;
    } catch (error) {
      if (error instanceof ApiError) throw error;
      if (controller.signal.aborted) throw new ApiError(timedOut ? 'The service took too long to respond. Please try again.' : 'Request cancelled.', { code: timedOut ? 'TIMEOUT' : 'CANCELLED' });
      throw new ApiError('Cannot reach the service. Check your connection and try again.');
    } finally {
      clearTimeout(timeout); pending.delete(controller); signal?.removeEventListener('abort', cancel);
    }
  }

  return {
    request: (path, options) => send(path, { ...options, publicRequest: false }),
    hasSession: () => Boolean(token && Date.now() < expires),
    clearSession,
    createUser: ({ username, password, email, phoneNumber }) => send('users', {
      method: 'POST', body: { username, password, email, phoneNumber }, publicRequest: true
    }),
    signIn: async ({ username, password }) => {
      const result = await send('sign-in', { method: 'POST', body: { username, password }, publicRequest: true });
      setSession(result);
      return { authenticated: true, user: result.user, expiresAt: result.expiresAt };
    },
    signOut: async () => {
      try { if (token) await send('sign-out', { method: 'POST' }); }
      finally { clearSession(); }
    },
    getMenu: options => send('menu', options),
    getUser: id => positiveId(id) ? send(`users/${encodeURIComponent(id)}`)
      : Promise.reject(new ApiError('The profile ID is invalid.', { code: 'INVALID_ID' }))
  };
}
