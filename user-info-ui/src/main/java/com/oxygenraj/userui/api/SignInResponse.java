package com.oxygenraj.userui.api;

/** Credential verification result only; this is not a session or an entitlement token. */
public record SignInResponse(boolean authenticated, UserView user) { }
