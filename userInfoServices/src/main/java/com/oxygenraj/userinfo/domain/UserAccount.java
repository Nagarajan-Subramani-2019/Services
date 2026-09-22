package com.oxygenraj.userinfo.domain;

import com.oxygenraj.userinfo.api.UserResponse;
import java.time.OffsetDateTime;

/** Internal persistence model; never returned by a controller. */
public record UserAccount(long id, String username, String passwordHash, String email,
                          String phoneNumber, String role, boolean enabled,
                          OffsetDateTime createdAt, OffsetDateTime modifiedAt) {
    public UserResponse toResponse() {
        return new UserResponse(id, username, email, phoneNumber, role, enabled, createdAt, modifiedAt);
    }
    @Override public String toString() { return "UserAccount[id=" + id + ", details=redacted]"; }
}
