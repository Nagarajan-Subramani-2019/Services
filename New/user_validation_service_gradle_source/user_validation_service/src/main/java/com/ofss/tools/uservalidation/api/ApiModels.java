package com.ofss.tools.uservalidation.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class ApiModels {
    private ApiModels() {
    }

    public record CreateUserRequest(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9._@-]{3,100}") String username,
            @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
            @NotBlank @Size(min = 12, max = 128) String password,
            @NotBlank @Size(max = 200) String name,
            @NotBlank @Size(max = 1000) String address) {
        @Override
        public String toString() {
            return "CreateUserRequest[redacted]";
        }
    }

    public record UpdateUserRequest(
            @Pattern(regexp = "[A-Za-z0-9._@-]{3,100}") String username,
            @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
            @Size(min = 12, max = 128) String password,
            @Size(min = 1, max = 200) String name,
            @Size(min = 1, max = 1000) String address) {
        @Override
        public String toString() {
            return "UpdateUserRequest[redacted]";
        }
    }

    public record LoginRequest(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9._@-]{3,100}") String username,
            @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
            @NotBlank @Size(max = 128) String password) {
        @Override
        public String toString() {
            return "LoginRequest[redacted]";
        }
    }

    public record UserResponse(long userId, String username, String name, String address,
            String status, Instant createdAt, Instant updatedAt, Instant lastLoginAt) {
    }

    public record UserPage(List<UserResponse> users, int page, int size, long totalUsers) {
    }

    public record LoginResponse(boolean authenticated, Long userId, String message) {
    }

    public record LoginStatistics(long totalLoggedIn, long totalFailedLogin,
            long totalLoginAttempts, BigDecimal averageLoginTimeMs) {
    }

    public record UserStatistics(long userId, String username, String status,
            Instant lastLoginAt, Long idleLoginDays, LoginStatistics loginStatistics) {
    }

    public record OverallStatistics(long activeUsers, long closedUsers,
            long usersNeverLoggedIn, long distinctUsersLoggedIn,
            LoginStatistics loginStatistics) {
    }

    public record ErrorResponse(Instant timestamp, int status, String code,
            String message, String requestId) {
    }
}
