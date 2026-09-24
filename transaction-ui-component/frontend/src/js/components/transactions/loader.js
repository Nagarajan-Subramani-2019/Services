// Component-server entry point: plain ES module, mounted by the host shell.
const USER_PAGE_SIZE = 100;
const MAX_USER_PAGES = 100;
const TRANSACTION_PAGE_SIZE = 20;

function failure(message, code = 'INVALID_RESPONSE') {
  return Object.assign(new Error(message), { code });
}
export function positiveId(value) {
  return typeof value === 'number' ? Number.isSafeInteger(value) && value > 0
    : typeof value === 'string' && /^[1-9]\d{0,18}$/.test(value) && BigInt(value) <= 9223372036854775807n;
}
export function validatePage(value, expectedPage, expectedSize) {
  if (!value || !Array.isArray(value.items) || value.page !== expectedPage || value.size !== expectedSize
      || !Number.isSafeInteger(value.totalElements) || value.totalElements < 0
      || !Number.isSafeInteger(value.totalPages) || value.totalPages < 0
      || value.totalPages !== Math.ceil(value.totalElements / expectedSize)
      || value.items.length !== Math.max(0, Math.min(expectedSize, value.totalElements - expectedPage * expectedSize))) {
    throw failure('The service returned incomplete pagination details. Please retry.');
  }
  return value;
}
export async function loadAllUsers(request, { signal, maxPages = MAX_USER_PAGES } = {}) {
  if (!Number.isSafeInteger(maxPages) || maxPages < 1) throw failure('Invalid user-list limit.');
  const users = [];
  const ids = new Set();
  let expectedTotal;
  for (let page = 0; page < maxPages; page++) {
    if (signal?.aborted) throw failure('Request cancelled.', 'CANCELLED');
    const result = validatePage(await request(`components/transactions/users?page=${page}&size=${USER_PAGE_SIZE}`, { signal }), page, USER_PAGE_SIZE);
    if (signal?.aborted) throw failure('Request cancelled.', 'CANCELLED');
    if (result.totalPages > maxPages) throw failure(`There are more than ${maxPages * USER_PAGE_SIZE} users. The complete list cannot be displayed here. Contact your administrator to enable a larger or searchable directory, then retry.`, 'USER_LIST_LIMIT');
    if (expectedTotal !== undefined && result.totalElements !== expectedTotal) throw failure('The user list changed while loading. Please retry to load the complete list.', 'USER_LIST_CHANGED');
    expectedTotal = result.totalElements;
    for (const user of result.items) {
      if (!user || !positiveId(user.id) || typeof user.username !== 'string' || !user.username.trim() || ids.has(String(user.id))) {
        throw failure('The service returned an invalid or changing user list. Please retry.');
      }
      ids.add(String(user.id)); users.push({ id: String(user.id), username: user.username });
    }
    if (page + 1 >= result.totalPages) return users;
  }
  throw failure('The complete user list could not be loaded. Please retry.');
}

export function amountLabel(value) {
  if (!['number', 'string'].includes(typeof value)) throw failure('The service returned an invalid amount.');
  const text = String(value);
  if (!/^\d{1,17}(?:\.\d{1,2})?$/.test(text) || (typeof value === 'number' && (!Number.isFinite(value) || Math.abs(value) > Number.MAX_SAFE_INTEGER / 100))) {
    throw failure('The service returned an amount that cannot be displayed exactly. Please retry.');
  }
  const [whole, fraction = ''] = text.split('.');
  return `${whole.replace(/\B(?=(\d{3})+(?!\d))/g, ',')}.${fraction.padEnd(2, '0')}`;
}
function dateLabel(value) {
  if (typeof value !== 'string' || Number.isNaN(Date.parse(value))) throw failure('The service returned an invalid transaction date.');
  return new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value));
}
export function transactionCells(row, userId) {
  if (!row || !positiveId(row.id) || !positiveId(row.userId) || String(row.userId) !== String(userId)
      || !/^(JANUARY|FEBRUARY|MARCH|APRIL|MAY|JUNE|JULY|AUGUST|SEPTEMBER|OCTOBER|NOVEMBER|DECEMBER)$/.test(row.monthName)
      || !Number.isInteger(row.monthCount) || row.monthCount < 1 || row.monthCount > 2147483647
      || !/^(0|[1-9]\d{0,18})$/.test(String(row.version))
      || (typeof row.version === 'number' && !Number.isSafeInteger(row.version))
      || BigInt(row.version) > 9223372036854775807n) throw failure('The service returned an invalid transaction. Please retry.');
  return [String(row.id), row.monthName, String(row.monthCount), amountLabel(row.amount), dateLabel(row.createdAt), dateLabel(row.modifiedAt), String(row.version)];
}

export function mount(container, { request, onUnauthorized = () => {} }) {
  const documentRef = container.ownerDocument;
  let active = true;
  let users = [];
  let usersController;
  let transactionController;
  let requestVersion = 0;
  let appliedUser = null;
  let currentPage = 0;
  let failedPage = 0;
  let totalPages = 0;
  let loading = false;
  const listeners = [];
  function element(tag, className, text) {
    const node = documentRef.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined) node.textContent = text;
    return node;
  }
  function listen(node, event, handler) { node.addEventListener(event, handler); listeners.push(() => node.removeEventListener(event, handler)); }
  const root = element('div', 'transaction-screen');
  const heading = element('h1', '', 'Transactions'); heading.tabIndex = -1;
  const description = element('p', 'transaction-description', 'Choose a user to view their transactions.');
  const form = element('form', 'transaction-selector');
  const field = element('div', 'transaction-user-field');
  const label = element('label', '', 'User ID / Name');
  const select = element('select'); select.id = 'transaction-user'; select.name = 'userId'; select.required = true; select.disabled = true;
  label.htmlFor = select.id;
  const placeholder = element('option', '', 'Loading users…'); placeholder.value = ''; select.append(placeholder);
  field.append(label, select);
  const okay = element('button', 'transaction-primary', 'Okay'); okay.type = 'submit'; okay.disabled = true;
  form.append(field, okay);
  const userStatus = element('p', 'transaction-status', 'Loading the complete user list…'); userStatus.setAttribute('role', 'status'); userStatus.setAttribute('aria-live', 'polite');
  const retryUsers = element('button', 'transaction-secondary', 'Retry user list'); retryUsers.type = 'button'; retryUsers.hidden = true;
  const status = element('p', 'transaction-status', ''); status.setAttribute('role', 'status'); status.setAttribute('aria-live', 'polite');
  const error = element('p', 'transaction-error'); error.setAttribute('role', 'alert'); error.hidden = true;
  const retryTransactions = element('button', 'transaction-secondary', 'Retry transactions'); retryTransactions.type = 'button'; retryTransactions.hidden = true;
  const results = element('div', 'transaction-results');
  const tableWrap = element('div', 'transaction-table-wrap'); tableWrap.tabIndex = 0; tableWrap.setAttribute('role', 'region'); tableWrap.setAttribute('aria-label', 'Transaction results'); tableWrap.hidden = true;
  const table = element('table', 'transaction-table');
  const caption = element('caption');
  const thead = element('thead'); const headerRow = element('tr');
  for (const text of ['Transaction ID', 'Month', 'Month count', 'Amount', 'Created', 'Modified', 'Version']) {
    const header = element('th', '', text); header.scope = 'col'; headerRow.append(header);
  }
  thead.append(headerRow);
  const tbody = element('tbody'); table.append(caption, thead, tbody); tableWrap.append(table);
  const pager = element('nav', 'transaction-pagination'); pager.setAttribute('aria-label', 'Transaction pages'); pager.hidden = true;
  const previous = element('button', 'transaction-secondary', 'Previous'); previous.type = 'button';
  const pageLabel = element('span', 'transaction-page-label'); pageLabel.setAttribute('aria-live', 'polite');
  const next = element('button', 'transaction-secondary', 'Next'); next.type = 'button';
  pager.append(previous, pageLabel, next); results.append(tableWrap, pager);
  root.append(heading, description, form, userStatus, retryUsers, status, error, retryTransactions, results);
  container.replaceChildren(root);
  heading.focus();

  function clearResults() {
    tbody.replaceChildren(); caption.textContent = ''; tableWrap.hidden = true; pager.hidden = true;
    pageLabel.textContent = ''; error.hidden = true; error.textContent = ''; retryTransactions.hidden = true;
  }
  function updateControls() {
    okay.disabled = select.disabled || !select.value || loading;
    okay.textContent = loading ? 'Loading…' : 'Okay';
    previous.disabled = loading || currentPage <= 0;
    next.disabled = loading || currentPage + 1 >= totalPages;
    results.setAttribute('aria-busy', String(loading));
  }
  function report(problem) {
    if (problem.status === 401) { onUnauthorized(); return; }
    error.textContent = problem.message || 'The request could not be completed. Please retry.'; error.hidden = false;
  }
  async function loadUsers() {
    usersController?.abort(); usersController = new AbortController();
    const controller = usersController;
    users = []; select.disabled = true; select.replaceChildren();
    const option = element('option', '', 'Loading users…'); option.value = ''; select.append(option);
    retryUsers.hidden = true; userStatus.textContent = 'Loading the complete user list…'; updateControls();
    try {
      const result = await loadAllUsers(request, { signal: controller.signal });
      if (!active || controller.signal.aborted) return;
      users = result; select.replaceChildren();
      const option = element('option', '', users.length ? 'Select a user' : 'No users available'); option.value = ''; select.append(option);
      for (const user of users) { const option = element('option', '', `${user.id} — ${user.username}`); option.value = user.id; select.append(option); }
      select.disabled = users.length === 0;
      userStatus.textContent = users.length ? `${users.length} ${users.length === 1 ? 'user' : 'users'} available.` : 'There are no users to select.';
      status.textContent = users.length ? 'Select a user, then choose Okay.' : '';
    } catch (problem) {
      if (!active || controller.signal.aborted || problem.code === 'CANCELLED') return;
      if (problem.status === 401) { onUnauthorized(); return; }
      select.replaceChildren(); const option = element('option', '', 'User list unavailable'); option.value = ''; select.append(option);
      userStatus.textContent = problem.message || 'The user list could not be loaded. Please retry.'; retryUsers.hidden = false;
    } finally { if (active && controller === usersController) updateControls(); }
  }
  async function loadTransactions(user, page) {
    transactionController?.abort(); transactionController = new AbortController();
    const controller = transactionController;
    const version = ++requestVersion;
    appliedUser = user; failedPage = page; loading = true; clearResults(); updateControls();
    status.textContent = `Loading transactions for ${user.username}…`;
    try {
      const data = validatePage(await request(`components/transactions/transactions?userId=${encodeURIComponent(user.id)}&page=${page}&size=${TRANSACTION_PAGE_SIZE}`, { signal: controller.signal }), page, TRANSACTION_PAGE_SIZE);
      if (!active || controller.signal.aborted || version !== requestVersion || select.value !== user.id) return;
      const rows = data.items.map(row => transactionCells(row, user.id));
      currentPage = page; totalPages = data.totalPages;
      if (data.totalElements && page >= data.totalPages) throw failure('The transaction list changed. Choose Okay to reload the first page.', 'PAGE_CHANGED');
      for (const cells of rows) { const tr = element('tr'); for (const value of cells) tr.append(element('td', '', value)); tbody.append(tr); }
      caption.textContent = `Transactions for ${user.username} (ID ${user.id})`;
      tableWrap.hidden = rows.length === 0; pager.hidden = data.totalPages < 2;
      pageLabel.textContent = `Page ${page + 1} of ${data.totalPages}`;
      status.textContent = data.totalElements === 0 ? `No transactions found for ${user.username}.`
        : `${data.totalElements} ${data.totalElements === 1 ? 'transaction' : 'transactions'} · Showing ${page * TRANSACTION_PAGE_SIZE + 1}–${page * TRANSACTION_PAGE_SIZE + rows.length}.`;
    } catch (problem) {
      if (!active || controller.signal.aborted || version !== requestVersion || problem.code === 'CANCELLED') return;
      status.textContent = ''; report(problem);
      if (problem.status !== 401) retryTransactions.hidden = false;
    } finally {
      if (active && version === requestVersion) { loading = false; updateControls(); }
    }
  }
  listen(select, 'change', () => {
    requestVersion++; transactionController?.abort(); appliedUser = null; loading = false; totalPages = 0;
    clearResults(); status.textContent = select.value ? 'Choose Okay to load this user’s transactions.' : 'Select a user, then choose Okay.'; updateControls();
  });
  listen(form, 'submit', event => { event.preventDefault(); const user = users.find(user => user.id === select.value); if (user && !loading) void loadTransactions(user, 0); });
  listen(previous, 'click', () => { if (appliedUser && !loading && currentPage > 0) void loadTransactions(appliedUser, currentPage - 1); });
  listen(next, 'click', () => { if (appliedUser && !loading && currentPage + 1 < totalPages) void loadTransactions(appliedUser, currentPage + 1); });
  listen(retryUsers, 'click', () => void loadUsers());
  listen(retryTransactions, 'click', () => { if (appliedUser && !loading) void loadTransactions(appliedUser, failedPage); });
  void loadUsers();
  return () => {
    active = false; requestVersion++; usersController?.abort(); transactionController?.abort();
    for (const remove of listeners) remove();
    users = []; appliedUser = null; root.replaceChildren(); root.remove();
  };
}
