export class ApiError extends Error {
  constructor(message, { status = 0, code = 'NETWORK_ERROR', errors = {} } = {}) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
    this.errors = errors && typeof errors === 'object' && !Array.isArray(errors) ? errors : {};
  }
}

export function createApi(fetchImpl = (...args) => globalThis.fetch(...args), timeoutMs = 20000) {
  async function request(path, { method = 'GET', body } = {}) {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), timeoutMs);
    try {
      const response = await fetchImpl(`./api/${path}`, {
        method,
        headers: { Accept: 'application/json', ...(body ? { 'Content-Type': 'application/json' } : {}) },
        ...(body ? { body: JSON.stringify(body) } : {}),
        signal: controller.signal,
        credentials: 'omit',
        cache: 'no-store',
        redirect: 'error'
      });
      let data;
      try {
        data = await response.json();
      } catch {
        throw new ApiError('The service returned an unexpected response. Please try again.', {
          status: response.status, code: 'INVALID_RESPONSE'
        });
      }
      if (!response.ok) {
        const fallback = response.status === 401
          ? 'The username or password is incorrect.'
          : 'The request could not be completed. Please try again.';
        throw new ApiError(typeof data?.message === 'string' ? data.message : fallback, {
          status: response.status,
          code: typeof data?.code === 'string' ? data.code : 'REQUEST_FAILED',
          errors: data?.errors
        });
      }
      return data;
    } catch (error) {
      if (error instanceof ApiError) throw error;
      if (controller.signal.aborted) {
        throw new ApiError('The service took too long to respond. Check that userInfoServices is running, then try again.', {
          code: 'TIMEOUT'
        });
      }
      throw new ApiError('Cannot reach the service. Check your connection and try again.');
    } finally {
      clearTimeout(timeout);
    }
  }
  return {
    createUser: ({ username, password, email, phoneNumber }) => request('users', {
      method: 'POST', body: { username, password, email, phoneNumber }
    }),
    signIn: ({ username, password }) => request('sign-in', {
      method: 'POST', body: { username, password }
    }),
    getUser: id => {
      if (!/^\d+$/.test(String(id)) || !Number.isSafeInteger(Number(id)) || Number(id) < 1) {
        return Promise.reject(new ApiError('The profile ID is invalid.', { code: 'INVALID_ID' }));
      }
      return request(`users/${encodeURIComponent(id)}`);
    }
  };
}
