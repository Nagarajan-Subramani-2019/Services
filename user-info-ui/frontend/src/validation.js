const usernamePattern = /^[A-Za-z0-9][A-Za-z0-9._-]{2,63}$/;
const phonePattern = /^\+?[0-9]{7,20}$/;
const bytes = value => new TextEncoder().encode(value).length;

export function validateRegistration(values) {
  const errors = {};
  if (!usernamePattern.test(values.username ?? '')) {
    errors.username = 'Use 3–64 letters, numbers, dots, underscores or hyphens. Start with a letter or number.';
  }
  const password = values.password ?? '';
  if (!password.trim() || [...password].length < 10 || password.length > 72 || bytes(password) > 72) {
    errors.password = 'Use at least 10 characters and no more than 72 UTF-8 bytes (up to 72 ordinary letters).';
  }
  const email = values.email ?? '';
  // Keep this a light format check; the service's @Email validator is authoritative.
  if (!email || email.length > 254 || !/^[^\s@]+@[^\s@]+$/.test(email)) {
    errors.email = 'Enter a valid email address, no longer than 254 characters.';
  }
  if (!phonePattern.test(values.phoneNumber ?? '')) {
    errors.phoneNumber = 'Use 7–20 digits, with an optional + at the beginning. No spaces or dashes.';
  }
  if ((values.confirmPassword ?? '') !== password) {
    errors.confirmPassword = 'Your passwords do not match.';
  }
  return errors;
}

export function validateSignIn(values) {
  const errors = {};
  if (!values.username?.trim() || values.username.length > 64) {
    errors.username = 'Enter your username (up to 64 characters).';
  }
  if (!values.password?.trim() || values.password.length > 72 || bytes(values.password) > 72) {
    errors.password = 'Enter your password (no more than 72 UTF-8 bytes).';
  }
  return errors;
}
