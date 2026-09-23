package com.oxygenraj.userui.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateUserRequest(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]{2,63}",
                message = "Use 3–64 letters, digits, dots, underscores or hyphens; start with a letter or digit") String username,
        @ValidPassword String password,
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Pattern(regexp = "\\+?[0-9]{7,20}", message = "Use 7–20 digits with an optional leading +") String phoneNumber) {
    @Override
    public String toString() { return "CreateUserRequest[details=REDACTED]"; }
}
