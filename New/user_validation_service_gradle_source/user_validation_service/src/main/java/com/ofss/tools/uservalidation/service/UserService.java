package com.ofss.tools.uservalidation.service;

import com.ofss.tools.uservalidation.api.ApiException;
import com.ofss.tools.uservalidation.api.ApiModels.CreateUserRequest;
import com.ofss.tools.uservalidation.api.ApiModels.LoginRequest;
import com.ofss.tools.uservalidation.api.ApiModels.LoginResponse;
import com.ofss.tools.uservalidation.api.ApiModels.OverallStatistics;
import com.ofss.tools.uservalidation.api.ApiModels.UpdateUserRequest;
import com.ofss.tools.uservalidation.api.ApiModels.UserPage;
import com.ofss.tools.uservalidation.api.ApiModels.UserResponse;
import com.ofss.tools.uservalidation.api.ApiModels.UserStatistics;
import com.ofss.tools.uservalidation.repository.UserRepository;
import com.ofss.tools.uservalidation.repository.UserRepository.Credentials;
import com.ofss.tools.uservalidation.security.PasswordHasher;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class UserService {
    private static final Logger LOG = LoggerFactory.getLogger(UserService.class);
    private final UserRepository repository;
    private final PasswordHasher hasher;
    private final String dummyHash;

    public UserService(UserRepository repository, PasswordHasher hasher) {
        this.repository = repository;
        this.hasher = hasher;
        dummyHash = hasher.hash(UUID.randomUUID().toString());
    }

    @Transactional
    public UserResponse create(CreateUserRequest request) {
        long userId = repository.create(normalize(request.username()), hasher.hash(request.password()),
                nonBlank(request.name(), "name"), nonBlank(request.address(), "address"));
        auditAfterCommit("USER_CREATED", userId);
        return findRequired(userId);
    }

    @Transactional(readOnly = true)
    public UserPage list(String status, int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAGINATION",
                    "page must be non-negative and size must be between 1 and 100.");
        }
        String selected = status(status);
        return new UserPage(repository.list(selected, page, size), page, size, repository.count(selected));
    }

    @Transactional(readOnly = true)
    public List<Long> ids(String status) {
        return repository.ids(status(status));
    }

    @Transactional(readOnly = true)
    public UserResponse get(long userId) {
        return findRequired(userId);
    }

    @Transactional
    public UserResponse update(long userId, UpdateUserRequest request) {
        if (request.username() == null && request.password() == null
                && request.name() == null && request.address() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "EMPTY_UPDATE", "Provide at least one field to update.");
        }
        Credentials credentials = lockRequired(userId);
        if (!"ACTIVE".equals(credentials.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "USER_CLOSED", "Closed user records cannot be updated.");
        }
        UserResponse existing = findRequired(userId);
        String password = request.password() == null ? credentials.passwordHash()
                : hasher.hash(nonBlank(request.password(), "password"));
        repository.update(userId, request.username() == null ? existing.username() : normalize(request.username()),
                password, request.name() == null ? existing.name() : nonBlank(request.name(), "name"),
                request.address() == null ? existing.address() : nonBlank(request.address(), "address"));
        auditAfterCommit("USER_UPDATED", userId);
        return findRequired(userId);
    }

    @Transactional
    public UserResponse close(long userId) {
        Credentials credentials = lockRequired(userId);
        if (!"CLOSED".equals(credentials.status())) {
            repository.close(userId);
            auditAfterCommit("USER_CLOSED", userId);
        }
        return findRequired(userId);
    }

    @Transactional
    public LoginResponse login(LoginRequest request) {
        long started = System.nanoTime();
        String username = normalize(request.username());
        Optional<Credentials> credentials = repository.lockByUsername(username);
        boolean passwordMatches = hasher.matches(request.password(),
                credentials.map(Credentials::passwordHash).orElse(dummyHash));
        boolean authenticated = credentials.isPresent() && passwordMatches
                && "ACTIVE".equals(credentials.get().status());
        Long userId = credentials.map(Credentials::userId).orElse(null);
        BigDecimal duration = BigDecimal.valueOf(System.nanoTime() - started, 6)
                .setScale(3, RoundingMode.HALF_UP);
        // Return failure normally so the failed-attempt transaction commits before HTTP 401.
        repository.recordLogin(userId, username, authenticated, duration);
        auditAfterCommit(authenticated ? "LOGIN_SUCCESS" : "LOGIN_FAILED", userId);
        return new LoginResponse(authenticated, authenticated ? userId : null,
                authenticated ? "Login successful." : "Invalid username or password.");
    }

    @Transactional(readOnly = true)
    public UserStatistics statistics(long userId) {
        UserResponse user = findRequired(userId);
        Long idleDays = user.lastLoginAt() == null ? null
                : Math.max(0L, Duration.between(user.lastLoginAt(), Instant.now()).toDays());
        return new UserStatistics(userId, user.username(), user.status(), user.lastLoginAt(),
                idleDays, repository.userStatistics(userId));
    }

    @Transactional(readOnly = true)
    public OverallStatistics overallStatistics() {
        return repository.overallStatistics();
    }

    public void checkDatabase() {
        repository.checkDatabase();
    }

    private UserResponse findRequired(long userId) {
        return repository.find(userId).orElseThrow(this::notFound);
    }

    private Credentials lockRequired(long userId) {
        return repository.lockById(userId).orElseThrow(this::notFound);
    }

    private ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "The user record was not found.");
    }

    private String normalize(String username) {
        return username.toLowerCase(Locale.ROOT);
    }

    private String status(String value) {
        String selected = value.toUpperCase(Locale.ROOT);
        if (!List.of("ACTIVE", "CLOSED", "ALL").contains(selected)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_STATUS", "status must be ACTIVE, CLOSED, or ALL.");
        }
        return selected;
    }

    private String nonBlank(String value, String field) {
        if (value.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", field + " must not be blank.");
        }
        return field.equals("password") ? value : value.strip();
    }

    private void auditAfterCommit(String action, Long userId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                LOG.info("action={} userId={}", action, userId == null ? "unknown" : userId);
            }
        });
    }
}
