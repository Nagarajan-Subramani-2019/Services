// Authenticated menu entries select a component code. The host's table-backed
// proxy resolves that code to an approved component server and resource path.
const componentCodePattern = /^[a-z][a-z0-9-]{0,79}$/;

export function componentFor(componentCode) {
  if (typeof componentCode !== 'string' || !componentCodePattern.test(componentCode)) return null;
  const code = encodeURIComponent(componentCode);
  return Object.freeze({
    load: () => import(`./components/${code}/loader.js`),
    stylesheet: `./components/${code}/styles.css`
  });
}

export function menuItems(data) {
  if (!data || !Array.isArray(data.items) || data.items.length > 100) throw new Error('The menu could not be loaded. Please retry.');
  const codes = new Set();
  return data.items.map(item => {
    if (!item || typeof item.uiActivityCode !== 'string' || !item.uiActivityCode || codes.has(item.uiActivityCode)
        || !componentFor(item.componentCode) || typeof item.label !== 'string' || !item.label.trim()
        || item.label.length > 100 || typeof item.viewKey !== 'string' || !Number.isSafeInteger(item.sortOrder)
        || typeof item.enabled !== 'boolean') throw new Error('The menu contains invalid entries. Please retry.');
    codes.add(item.uiActivityCode);
    return {
      uiActivityCode: item.uiActivityCode, componentCode: item.componentCode,
      label: item.label, viewKey: item.viewKey, sortOrder: item.sortOrder,
      enabled: item.enabled && item.viewKey !== 'placeholder'
    };
  }).sort((a, b) => a.sortOrder - b.sortOrder || a.uiActivityCode.localeCompare(b.uiActivityCode));
}

export function loadStylesheet(href, documentRef = document) {
  const path = /^\.\/components\/([a-z][a-z0-9-]{0,79})\/styles\.css$/.exec(href);
  if (!path) return Promise.reject(new Error('Component stylesheet is unavailable.'));
  const existing = documentRef.querySelector(`link[data-component-styles="${path[1]}"]`);
  if (existing?.dataset.loaded === 'true') return Promise.resolve();
  if (existing) existing.remove();
  return new Promise((resolve, reject) => {
    const link = documentRef.createElement('link');
    link.rel = 'stylesheet'; link.href = href; link.dataset.componentStyles = path[1];
    link.addEventListener('load', () => { link.dataset.loaded = 'true'; resolve(); }, { once: true });
    link.addEventListener('error', () => { link.remove(); reject(new Error('The component screen could not be loaded. Please retry.')); }, { once: true });
    documentRef.head.append(link);
  });
}
