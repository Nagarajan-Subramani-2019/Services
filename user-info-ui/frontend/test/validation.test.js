import test from 'node:test';
import assert from 'node:assert/strict';
import { validateRegistration, validateSignIn } from '../src/validation.js';

const valid = {
  username: 'alex.morgan', password: 'A-private-password-42', confirmPassword: 'A-private-password-42',
  email: 'alex@example.com', phoneNumber: '+919876543210'
};
test('valid signup data is accepted', () => assert.deepEqual(validateRegistration(valid), {}));
test('email check does not impose an extra public-domain requirement', () => assert.equal(validateRegistration({ ...valid, email: 'alex@localhost' }).email, undefined));
for (const username of ['', 'ab', '.alex', ' alex ', 'alex@home', 'a'.repeat(65), 'éclair']) {
  test(`invalid registration username ${JSON.stringify(username)} is rejected`, () => {
    assert.ok(validateRegistration({ ...valid, username }).username);
  });
}
for (const username of ['abc', 'A_b-c.d', 'a'.repeat(64)]) {
  test(`valid registration username ${username.length} characters`, () => {
    assert.equal(validateRegistration({ ...valid, username }).username, undefined);
  });
}
for (const password of ['', ' '.repeat(10), 'short1234', 'a'.repeat(73), '😀'.repeat(19)]) {
  test(`invalid password boundary (${password.length} JS characters)`, () => {
    assert.ok(validateRegistration({ ...valid, password, confirmPassword: password }).password);
  });
}
for (const password of ['a'.repeat(10), 'a'.repeat(72), '😀'.repeat(10), '😀'.repeat(18)]) {
  test(`valid password boundary (${password.length} JS characters)`, () => {
    assert.equal(validateRegistration({ ...valid, password, confirmPassword: password }).password, undefined);
  });
}
test('confirmation mismatch is rejected', () => assert.ok(validateRegistration({ ...valid, confirmPassword: 'different' }).confirmPassword));
for (const phoneNumber of ['+1234567', '1234567', '1'.repeat(20), `+${'1'.repeat(20)}`]) {
  test(`valid phone ${phoneNumber}`, () => assert.equal(validateRegistration({ ...valid, phoneNumber }).phoneNumber, undefined));
}
for (const phoneNumber of ['123456', '1'.repeat(21), '+91 1234567890', '(123)4567890', '++1234567']) {
  test(`invalid phone ${phoneNumber}`, () => assert.ok(validateRegistration({ ...valid, phoneNumber }).phoneNumber));
}
for (const email of ['', 'not-an-email', 'alex@', ' alex@example.com', `${'a'.repeat(250)}@example.com`]) {
  test(`invalid email length ${email.length}`, () => assert.ok(validateRegistration({ ...valid, email }).email));
}
test('sign-in does not impose new-account password minimum', () => assert.deepEqual(validateSignIn({ username: 'alex', password: 'short' }), {}));
test('sign-in rejects blank credentials', () => assert.deepEqual(Object.keys(validateSignIn({ username: ' ', password: ' ' })), ['username', 'password']));
test('sign-in rejects password longer than 72 UTF-8 bytes', () => assert.ok(validateSignIn({ username: 'alex', password: '😀'.repeat(19) }).password));
test('sign-in rejects excessively long username', () => assert.ok(validateSignIn({ username: 'a'.repeat(65), password: 'correct-password' }).username));
