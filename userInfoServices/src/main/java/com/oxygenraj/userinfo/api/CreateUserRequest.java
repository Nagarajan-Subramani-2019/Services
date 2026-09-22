package com.oxygenraj.userinfo.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateUserRequest(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]{2,63}",
                message = "Username must be 3-64 characters: letters, digits, dot, underscore or hyphen") String username,
        @ValidPassword String password,
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Pattern(regexp = "\\+?[0-9]{7,20}",
                message = "Phone number must have 7-20 digits, optionally prefixed by +") String phoneNumber) {
    @Override public String toString() { return "CreateUserRequest[redacted]"; }
}
