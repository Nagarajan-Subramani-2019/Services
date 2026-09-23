import { createApi, ApiError } from './api.js';
import { validateRegistration, validateSignIn } from './validation.js';

const api = createApi();
const $ = id => document.getElementById(id);
const signInForm = $('sign-in-form');
const signUpForm = $('sign-up-form');
let currentUser = null;
let refreshing = false;
let screenVersion = 0;

function setText(id, value) { $(id).textContent = value; }
function showMessage(id, text) { setText(id, text); $(id).hidden = !text; }
function clearErrors(form) {
  form.querySelectorAll('.field-error').forEach(element => { element.textContent = ''; });
  form.querySelectorAll('[aria-invalid]').forEach(element => element.removeAttribute('aria-invalid'));
  showMessage(`${form.id}-error`, '');
}
function displayErrors(form, errors, message = '') {
  let first;
  for (const [name, error] of Object.entries(errors)) {
    const input = form.elements.namedItem(name);
    const errorElement = input && $(`${input.id}-error`);
    if (input && errorElement && typeof error === 'string') {
      input.setAttribute('aria-invalid', 'true');
      errorElement.textContent = error;
      first ??= input;
    }
  }
  showMessage(`${form.id}-error`, message || 'Please check the highlighted fields.');
  first?.focus();
}
function setBusy(form, busy, label) {
  form.setAttribute('aria-busy', String(busy));
  form.querySelectorAll('button, input').forEach(element => { element.disabled = busy; });
  document.querySelectorAll('.tab, #create-account-link, #sign-in-link').forEach(button => { button.disabled = busy; });
  const submit = form.querySelector('[type="submit"]');
  submit.classList.toggle('is-busy', busy);
  submit.querySelector('[data-button-label]').textContent = label;
}
function clearPasswords(form) {
  for (const name of ['password', 'confirmPassword']) {
    const field = form.elements.namedItem(name);
    if (field) { field.value = ''; field.type = 'password'; }
  }
  form.querySelectorAll('[data-password-target]').forEach(button => {
    button.textContent = 'Show';
    button.setAttribute('aria-pressed', 'false');
    button.setAttribute('aria-label', button.dataset.passwordTarget.endsWith('confirmPassword') ? 'Show confirmation password' : 'Show password');
  });
}
function focusSubmissionError(form) {
  const invalid = form.querySelector('[aria-invalid="true"]');
  if (invalid) { invalid.focus(); return; }
  const error = $(`${form.id}-error`);
  if (!error.hidden) { error.tabIndex = -1; error.focus(); }
}
function showTab(tab, focus = false) {
  const isSignIn = tab === 'sign-in';
  for (const name of ['sign-in', 'sign-up']) {
    const active = tab === name;
    $(`${name}-tab`).classList.toggle('is-active', active);
    $(`${name}-tab`).setAttribute('aria-selected', String(active));
    $(`${name}-tab`).tabIndex = active ? 0 : -1;
    $(`${name}-panel`).hidden = !active;
  }
  clearPasswords(isSignIn ? signUpForm : signInForm);
  clearErrors(signInForm); clearErrors(signUpForm);
  showMessage('access-notice', '');
  setText('access-title', isSignIn ? 'Good to see you.' : 'Make yourself at home.');
  setText('access-description', isSignIn ? 'Sign in to pick up where you left off.' : 'A few details, and your next chapter begins.');
  if (focus) $(`${tab}-username`).focus();
}
function fieldValues(form) { return Object.fromEntries(new FormData(form)); }
function validUser(user) {
  return user && Number.isSafeInteger(user.id) && user.id > 0 && typeof user.username === 'string';
}
function dateLabel(value) {
  if (!value) return 'Not available';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? 'Not available' : new Intl.DateTimeFormat(undefined, { dateStyle: 'medium' }).format(date);
}
function displayProfile(user) {
  if (!validUser(user)) throw new ApiError('The service returned an incomplete profile. Please try again.', { code: 'INVALID_RESPONSE' });
  currentUser = user;
  setText('welcome-name', user.username);
  setText('profile-username', user.username);
  setText('profile-email', user.email || 'Not provided');
  setText('profile-phone', user.phoneNumber || 'Not provided');
  setText('profile-id', `#${user.id}`);
  setText('profile-role', user.role || 'Not available');
  setText('profile-created', dateLabel(user.createdAt));
  setText('profile-status', user.enabled ? 'Active' : 'Disabled');
  $('profile-status').classList.toggle('is-disabled', !user.enabled);
  $('access-view').hidden = true;
  $('profile-view').hidden = false;
  showMessage('profile-error', '');
}

$('sign-in-tab').addEventListener('click', () => showTab('sign-in'));
$('sign-up-tab').addEventListener('click', () => showTab('sign-up'));
$('create-account-link').addEventListener('click', () => showTab('sign-up', true));
$('sign-in-link').addEventListener('click', () => showTab('sign-in', true));
document.querySelector('[role="tablist"]').addEventListener('keydown', event => {
  if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key) || $('sign-in-tab').disabled) return;
  event.preventDefault();
  const current = $('sign-in-tab').getAttribute('aria-selected') === 'true' ? 'sign-in' : 'sign-up';
  const next = event.key === 'Home' ? 'sign-in' : event.key === 'End' ? 'sign-up' : current === 'sign-in' ? 'sign-up' : 'sign-in';
  showTab(next);
  $(`${next}-tab`).focus();
});
document.querySelectorAll('[data-password-target]').forEach(button => {
  button.addEventListener('click', () => {
    const input = $(button.dataset.passwordTarget);
    const visible = input.type === 'password';
    input.type = visible ? 'text' : 'password';
    button.textContent = visible ? 'Hide' : 'Show';
    button.setAttribute('aria-pressed', String(visible));
    const subject = input.name === 'confirmPassword' ? 'confirmation password' : 'password';
    button.setAttribute('aria-label', `${visible ? 'Hide' : 'Show'} ${subject}`);
  });
});
for (const form of [signInForm, signUpForm]) {
  form.addEventListener('input', event => {
    const field = event.target;
    if (field.id && $(`${field.id}-error`)) {
      field.removeAttribute('aria-invalid');
      setText(`${field.id}-error`, '');
    }
  });
}

signUpForm.addEventListener('submit', async event => {
  event.preventDefault();
  if (signUpForm.getAttribute('aria-busy') === 'true') return;
  clearErrors(signUpForm);
  const values = fieldValues(signUpForm);
  const errors = validateRegistration(values);
  if (Object.keys(errors).length) { displayErrors(signUpForm, errors); return; }
  setBusy(signUpForm, true, 'Creating your account…');
  let created = false;
  try {
    const user = await api.createUser(values);
    if (!validUser(user)) throw new ApiError('An unexpected response was received. Check whether the account was created before retrying.', { code: 'INVALID_RESPONSE' });
    signUpForm.reset();
    showTab('sign-in');
    $('sign-in-username').value = user.username;
    showMessage('access-notice', 'Your account is ready. Sign in with your new username and password.');
    created = true;
  } catch (error) {
    displayErrors(signUpForm, error.errors ?? {}, error.message || 'We couldn’t create your account. Please try again.');
  } finally {
    clearPasswords(signUpForm);
    setBusy(signUpForm, false, 'Create account');
    if (created) $('sign-in-password').focus();
    else focusSubmissionError(signUpForm);
  }
});

signInForm.addEventListener('submit', async event => {
  event.preventDefault();
  if (signInForm.getAttribute('aria-busy') === 'true') return;
  clearErrors(signInForm);
  showMessage('access-notice', '');
  const values = fieldValues(signInForm);
  const errors = validateSignIn(values);
  if (Object.keys(errors).length) { displayErrors(signInForm, errors); return; }
  setBusy(signInForm, true, 'Checking your details…');
  try {
    const result = await api.signIn(values);
    if (result?.authenticated !== true) throw new ApiError('Your credentials could not be verified.', { code: 'INVALID_CREDENTIALS' });
    displayProfile(result.user);
    screenVersion++;
    setText('profile-updated', 'Loaded from userInfoServices');
    $('welcome-title').focus();
  } catch (error) {
    displayErrors(signInForm, error.errors ?? {}, error.message || 'We couldn’t sign you in. Please try again.');
  } finally {
    clearPasswords(signInForm);
    setBusy(signInForm, false, 'Sign in');
    if (!$('access-view').hidden) focusSubmissionError(signInForm);
  }
});

$('refresh-profile').addEventListener('click', async () => {
  if (!currentUser || refreshing) return;
  const requestVersion = screenVersion;
  const userId = currentUser.id;
  refreshing = true;
  $('refresh-profile').disabled = true;
  $('refresh-profile').textContent = 'Refreshing…';
  showMessage('profile-error', '');
  try {
    const user = await api.getUser(userId);
    if (requestVersion !== screenVersion) return;
    displayProfile(user);
    setText('profile-updated', `Updated at ${new Intl.DateTimeFormat(undefined, { timeStyle: 'short' }).format(new Date())}`);
  } catch (error) {
    if (requestVersion !== screenVersion) return;
    showMessage('profile-error', error.status === 404 ? 'This profile is no longer available. Sign out or try again later.' : error.message);
    setText('profile-updated', 'Refresh failed — showing previous details');
  } finally {
    refreshing = false;
    $('refresh-profile').disabled = false;
    $('refresh-profile').textContent = 'Refresh ↻';
  }
});
$('sign-out').addEventListener('click', () => {
  currentUser = null;
  screenVersion++;
  for (const id of ['welcome-name', 'profile-username', 'profile-email', 'profile-phone', 'profile-id', 'profile-role', 'profile-created']) setText(id, '');
  $('profile-view').hidden = true;
  $('access-view').hidden = false;
  signInForm.reset(); signUpForm.reset();
  showTab('sign-in', true);
  showMessage('access-notice', 'Your profile has been cleared from this screen. See you again soon.');
});
function updateConnectionNotice() { $('offline-notice').hidden = navigator.onLine; }
window.addEventListener('online', updateConnectionNotice);
window.addEventListener('offline', updateConnectionNotice);
window.addEventListener('pagehide', () => { clearPasswords(signInForm); clearPasswords(signUpForm); });
updateConnectionNotice();
