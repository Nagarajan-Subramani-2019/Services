import { positiveId, validatePage, transactionCells } from '../transactions/loader.js';

const MAX_TEXT_LENGTH = 2000;
const PAGE_SIZE = 20;
let nextInstance = 0;

export function mount(container, { request, onUnauthorized = () => {} } = {}) {
  const documentRef = container.ownerDocument;
  const instanceId = `txn-ai-${++nextInstance}`;
  let active = true;
  let unauthorized = false;
  let currentUser = null;
  let profileController;
  let transactionController;
  let requestVersion = 0;
  let loading = false;
  let currentPage = 0;
  let totalPages = 0;
  let failedPage = 0;
  let retryTarget = null;
  const listeners = [];

  function element(tag, className, text) {
    const node = documentRef.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined) node.textContent = text;
    return node;
  }
  function listen(node, event, handler) {
    node.addEventListener(event, handler);
    listeners.push(() => node.removeEventListener(event, handler));
  }
  function liveStatus(className) {
    const node = element('p', className, '');
    node.setAttribute('role', 'status');
    node.setAttribute('aria-live', 'polite');
    return node;
  }
  function alert(className) {
    const node = element('p', className, '');
    node.setAttribute('role', 'alert');
    node.hidden = true;
    return node;
  }

  const root = element('div', 'txn-ai-screen');
  const heading = element('h1', 'txn-ai-heading', 'Transactions with AI');
  heading.tabIndex = -1;
  const layout = element('div', 'txn-ai-layout');
  const dataPanel = element('section', 'txn-ai-data-panel transaction-screen');
  const dataHeading = element('h2', 'txn-ai-panel-heading', 'Your transactions');
  dataHeading.id = `${instanceId}-transactions`;
  dataPanel.setAttribute('aria-labelledby', dataHeading.id);
  const userLabel = element('p', 'txn-ai-user', '');
  const dataStatus = liveStatus('txn-ai-data-status transaction-status');
  const dataError = alert('txn-ai-data-error transaction-error');
  const retry = element('button', 'transaction-secondary', 'Retry');
  retry.type = 'button';
  retry.hidden = true;
  const tableWrap = element('div', 'txn-ai-table-wrap transaction-table-wrap');
  tableWrap.tabIndex = 0;
  tableWrap.setAttribute('role', 'region');
  tableWrap.setAttribute('aria-label', 'Your transaction results');
  tableWrap.hidden = true;
  const table = element('table', 'transaction-table');
  const caption = element('caption');
  const thead = element('thead');
  const headers = element('tr');
  for (const text of ['Transaction ID', 'Month', 'Month count', 'Amount', 'Created', 'Modified', 'Version']) {
    const header = element('th', '', text);
    header.scope = 'col';
    headers.append(header);
  }
  thead.append(headers);
  const tbody = element('tbody');
  table.append(caption, thead, tbody);
  tableWrap.append(table);
  const pager = element('nav', 'transaction-pagination');
  pager.setAttribute('aria-label', 'Your transaction pages');
  pager.hidden = true;
  const previous = element('button', 'transaction-secondary', 'Previous');
  previous.type = 'button';
  const pageLabel = element('span', 'transaction-page-label', '');
  pageLabel.setAttribute('aria-live', 'polite');
  const next = element('button', 'transaction-secondary', 'Next');
  next.type = 'button';
  pager.append(previous, pageLabel, next);
  dataPanel.append(dataHeading, userLabel, dataStatus, dataError, retry, tableWrap, pager);

  const aiPanel = element('section', 'txn-ai-prompt-panel');
  const aiHeading = element('h2', 'txn-ai-panel-heading', 'AI text');
  aiHeading.id = `${instanceId}-prompt`;
  aiPanel.setAttribute('aria-labelledby', aiHeading.id);
  const form = element('form', 'txn-ai-form');
  form.noValidate = true;
  const field = element('div', 'txn-ai-field');
  const label = element('label', 'txn-ai-label', 'Text');
  const input = element('input', 'txn-ai-input');
  input.id = `${instanceId}-text`;
  input.type = 'text';
  input.required = true;
  input.maxLength = MAX_TEXT_LENGTH;
  input.autocomplete = 'off';
  label.htmlFor = input.id;
  const okay = element('button', 'txn-ai-primary', 'Okay');
  okay.type = 'submit';
  const error = alert('txn-ai-error');
  error.id = `${instanceId}-error`;
  const status = liveStatus('txn-ai-status');
  status.id = `${instanceId}-status`;
  input.setAttribute('aria-describedby', `${error.id} ${status.id}`);
  field.append(label, input);
  form.append(field, okay);
  aiPanel.append(aiHeading, form, error, status);
  layout.append(dataPanel, aiPanel);
  root.append(heading, layout);
  container.replaceChildren(root);

  function clearFeedback() {
    error.textContent = '';
    error.hidden = true;
    input.removeAttribute('aria-invalid');
    status.textContent = '';
  }
  function clearData() {
    tbody.replaceChildren();
    caption.textContent = '';
    tableWrap.hidden = true;
    pager.hidden = true;
    pageLabel.textContent = '';
    dataError.textContent = '';
    dataError.hidden = true;
    retry.hidden = true;
    retryTarget = null;
  }
  function updateControls() {
    previous.disabled = loading || currentPage <= 0;
    next.disabled = loading || currentPage + 1 >= totalPages;
    retry.disabled = loading;
    dataPanel.setAttribute('aria-busy', String(loading));
  }
  function report(problem, target) {
    if (problem?.status === 401) {
      unauthorized = true;
      profileController?.abort();
      transactionController?.abort();
      requestVersion++;
      currentUser = null;
      loading = false;
      userLabel.textContent = '';
      clearData();
      input.value = '';
      clearFeedback();
      input.disabled = true;
      okay.disabled = true;
      dataStatus.textContent = 'Your session has expired. Sign in again.';
      updateControls();
      onUnauthorized();
      return;
    }
    dataStatus.textContent = '';
    dataError.textContent = problem?.message || 'Your transactions could not be loaded. Please retry.';
    dataError.hidden = false;
    retryTarget = target;
    retry.textContent = target === 'profile' ? 'Retry account' : 'Retry transactions';
    retry.hidden = false;
  }

  async function loadProfile() {
    if (!active || unauthorized) return;
    profileController?.abort();
    transactionController?.abort();
    requestVersion++;
    const controller = new AbortController();
    profileController = controller;
    currentUser = null;
    userLabel.textContent = '';
    loading = true;
    clearData();
    dataStatus.textContent = 'Loading your account…';
    updateControls();
    try {
      const response = await request('components/transactions-ai/current-user?page=0&size=1', { signal: controller.signal });
      if (!active || unauthorized || controller.signal.aborted || controller !== profileController) return;
      const profile = validatePage(response, 0, 1);
      const user = profile.items[0];
      if (profile.totalElements !== 1 || !user || !positiveId(user.id)
          || typeof user.username !== 'string' || !user.username.trim()) {
        throw new Error('Your signed-in account could not be identified. Please retry.');
      }
      currentUser = { id: String(user.id), username: user.username };
      userLabel.textContent = `Signed-in user: ${currentUser.id} — ${currentUser.username}`;
      loading = false;
      void loadTransactions(0);
    } catch (problem) {
      if (!active || unauthorized || controller.signal.aborted || controller !== profileController) return;
      loading = false;
      report(problem, 'profile');
      if (active && !unauthorized) updateControls();
    }
  }

  async function loadTransactions(page) {
    if (!active || unauthorized || !currentUser) return;
    transactionController?.abort();
    const controller = new AbortController();
    transactionController = controller;
    const version = ++requestVersion;
    const user = currentUser;
    failedPage = page;
    loading = true;
    clearData();
    dataStatus.textContent = 'Loading your transactions…';
    updateControls();
    try {
      const response = await request(`components/transactions-ai/my-transactions?userId=${encodeURIComponent(user.id)}&page=${page}&size=${PAGE_SIZE}`, { signal: controller.signal });
      if (!active || unauthorized || controller.signal.aborted || version !== requestVersion || user !== currentUser) return;
      const data = validatePage(response, page, PAGE_SIZE);
      if (page > 0 && page >= data.totalPages) {
        failedPage = 0;
        throw new Error('Your transaction list changed. Retry to load the first page.');
      }
      const rows = data.items.map(row => transactionCells(row, user.id));
      for (const cells of rows) {
        const row = element('tr');
        for (const value of cells) row.append(element('td', '', value));
        tbody.append(row);
      }
      currentPage = page;
      totalPages = data.totalPages;
      caption.textContent = `Transactions for ${user.username} (ID ${user.id})`;
      tableWrap.hidden = rows.length === 0;
      pager.hidden = totalPages < 2;
      pageLabel.textContent = totalPages ? `Page ${page + 1} of ${totalPages}` : '';
      dataStatus.textContent = data.totalElements === 0 ? 'No transactions found for your account.'
        : `${data.totalElements} ${data.totalElements === 1 ? 'transaction' : 'transactions'} · Showing ${page * PAGE_SIZE + 1}–${page * PAGE_SIZE + rows.length}.`;
    } catch (problem) {
      if (!active || unauthorized || controller.signal.aborted || version !== requestVersion || user !== currentUser) return;
      report(problem, 'transactions');
    } finally {
      if (active && !unauthorized && !controller.signal.aborted && version === requestVersion) {
        loading = false;
        updateControls();
      }
    }
  }

  listen(input, 'input', () => { if (active && !unauthorized) clearFeedback(); });
  listen(form, 'submit', event => {
    event.preventDefault();
    if (!active || unauthorized) return;
    clearFeedback();
    if (!input.value.trim() || input.value.length > MAX_TEXT_LENGTH) {
      error.textContent = input.value.length > MAX_TEXT_LENGTH
        ? `Enter no more than ${MAX_TEXT_LENGTH} characters.`
        : 'Enter some text before choosing Okay.';
      error.hidden = false;
      input.setAttribute('aria-invalid', 'true');
      input.focus();
      return;
    }
    status.textContent = 'AI responses, links and graphs are not connected yet. Your text has not been sent or saved.';
  });
  listen(previous, 'click', () => {
    if (!loading && currentPage > 0) void loadTransactions(currentPage - 1);
  });
  listen(next, 'click', () => {
    if (!loading && currentPage + 1 < totalPages) void loadTransactions(currentPage + 1);
  });
  listen(retry, 'click', () => {
    if (loading) return;
    if (retryTarget === 'profile') void loadProfile();
    else if (retryTarget === 'transactions') void loadTransactions(failedPage);
  });
  heading.focus();
  void loadProfile();

  return () => {
    if (!active) return;
    active = false;
    profileController?.abort();
    transactionController?.abort();
    requestVersion++;
    for (const remove of listeners) remove();
    listeners.length = 0;
    currentUser = null;
    input.value = '';
    clearFeedback();
    clearData();
    userLabel.textContent = '';
    dataStatus.textContent = '';
    root.replaceChildren();
    root.remove();
  };
}
