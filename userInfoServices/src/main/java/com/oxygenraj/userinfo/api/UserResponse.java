package com.oxygenraj.userinfo.api;

import java.time.OffsetDateTime;

/** Intentionally contains no password or password hash. */
public record UserResponse(long id, String username, String email, String phoneNumber,
                           String role, boolean enabled, OffsetDateTime createdAt, OffsetDateTime modifiedAt) {}
