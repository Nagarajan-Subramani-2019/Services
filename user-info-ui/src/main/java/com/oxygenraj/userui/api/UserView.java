package com.oxygenraj.userui.api;

/** Explicit response allowlist. A downstream password/hash can never be returned through this DTO. */
public record UserView(long id, String username, String email, String phoneNumber, String role,
        boolean enabled, String createdAt, String modifiedAt) {
    @Override
    public String toString() { return "UserView[id=" + id + ", details=REDACTED]"; }
}
