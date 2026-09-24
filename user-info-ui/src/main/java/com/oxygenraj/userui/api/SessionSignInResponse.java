package com.oxygenraj.userui.api;

import java.time.Instant;

/** The opaque token is stored only in frontend memory; its hash lives in the shared platform DB. */
public record SessionSignInResponse(boolean authenticated, UserView user, String accessToken, Instant expiresAt) {
    @Override
    public String toString() { return "SessionSignInResponse[credentials=REDACTED]"; }
}
