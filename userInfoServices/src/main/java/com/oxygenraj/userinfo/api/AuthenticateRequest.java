package com.oxygenraj.userinfo.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AuthenticateRequest(@NotBlank @Size(max = 64) String username,
                                  @NotBlank @Size(max = 72) String password) {
    @Override public String toString() { return "AuthenticateRequest[redacted]"; }
}
