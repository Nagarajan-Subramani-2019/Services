import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

test('single-column screen has no banner and preserves account flows and privacy note', async () => {
  const html = await readFile(new URL('../src/index.html', import.meta.url), 'utf8');
  const css = await readFile(new URL('../src/styles.css', import.meta.url), 'utf8');
  assert.doesNotMatch(html, /<aside\b|story-panel|connection-art|Every connection/);
  assert.doesNotMatch(css, /story-panel|connection-art|\.orbit|\.art-card/);
  for (const id of ['sign-in-form', 'sign-up-form', 'profile-view', 'refresh-profile', 'sign-out']) {
    assert.ok(html.includes(`id="${id}"`), `Missing required account element: ${id}`);
  }
  assert.ok(html.includes('This app does not save passwords in browser storage.'));
});
