import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

test('banner-free shell preserves account flows and adds accessible workspace navigation', async () => {
  const html = await readFile(new URL('../src/index.html', import.meta.url), 'utf8');
  const css = await readFile(new URL('../src/styles.css', import.meta.url), 'utf8');
  assert.doesNotMatch(html, /<aside\b|story-panel|connection-art|Every connection/);
  assert.doesNotMatch(css, /story-panel|connection-art|\.orbit|\.art-card/);
  for (const id of ['sign-in-form', 'sign-up-form', 'profile-view', 'refresh-profile', 'sign-out', 'signed-in-shell', 'workspace-menu', 'account-button', 'component-view', 'retry-menu']) {
    assert.ok(html.includes(`id="${id}"`), `Missing required account element: ${id}`);
  }
  assert.ok(html.includes('This app does not save passwords in browser storage.'));
  assert.ok(html.includes('aria-label="Workspace navigation"'));
  assert.doesNotMatch(html, /Profile reads are currently public|does not create a persistent login/);
  const ids = [...html.matchAll(/\bid="([^"]+)"/g)].map(match => match[1]);
  assert.equal(new Set(ids).size, ids.length);
});

test('shell never stores tokens or renders dynamic HTML', async () => {
  const app = await readFile(new URL('../src/app.js', import.meta.url), 'utf8');
  const api = await readFile(new URL('../src/api.js', import.meta.url), 'utf8');
  assert.doesNotMatch(app + api, /innerHTML|outerHTML|insertAdjacentHTML|localStorage|sessionStorage|document\.cookie/);
  assert.match(app, /api\.signOut\(\)/);
  assert.match(app, /disposeComponent\(\)/);
});
