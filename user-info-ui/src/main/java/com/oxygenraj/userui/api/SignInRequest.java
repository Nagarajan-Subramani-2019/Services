package com.oxygenraj.userui.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SignInRequest(@NotBlank @Size(max = 64) String username,
        @NotBlank @Size(max = 72) String password) {
    @Override
    public String toString() { return "SignInRequest[details=REDACTED]"; }
}
