package com.ofss.tools.uservalidation.api;

import com.ofss.tools.uservalidation.api.ApiModels.CreateUserRequest;
import com.ofss.tools.uservalidation.api.ApiModels.LoginRequest;
import com.ofss.tools.uservalidation.api.ApiModels.LoginResponse;
import com.ofss.tools.uservalidation.api.ApiModels.OverallStatistics;
import com.ofss.tools.uservalidation.api.ApiModels.UpdateUserRequest;
import com.ofss.tools.uservalidation.api.ApiModels.UserPage;
import com.ofss.tools.uservalidation.api.ApiModels.UserResponse;
import com.ofss.tools.uservalidation.api.ApiModels.UserStatistics;
import com.ofss.tools.uservalidation.service.UserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/v1")
@Validated
public class UserController {
    private final UserService users;

    public UserController(UserService users) {
        this.users = users;
    }

    @GetMapping("/users")
    public UserPage list(@RequestParam(defaultValue = "ALL") String status,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return users.list(status, page, size);
    }

    @GetMapping("/users/ids")
    public List<Long> ids(@RequestParam(defaultValue = "ALL") String status) {
        return users.ids(status);
    }

    @GetMapping("/users/{userId}")
    public UserResponse get(@PathVariable @Positive long userId) {
        return users.get(userId);
    }

    @PostMapping("/users")
    public ResponseEntity<UserResponse> create(@Valid @RequestBody CreateUserRequest request) {
        UserResponse created = users.create(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}")
                .buildAndExpand(created.userId()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    @PostMapping("/users/{userId}/update")
    public UserResponse update(@PathVariable @Positive long userId,
            @Valid @RequestBody UpdateUserRequest request) {
        return users.update(userId, request);
    }

    @PostMapping({"/users/{userId}/delete", "/users/{userId}/close"})
    public UserResponse close(@PathVariable @Positive long userId) {
        return users.close(userId);
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        LoginResponse result = users.login(request);
        return ResponseEntity.status(result.authenticated() ? HttpStatus.OK : HttpStatus.UNAUTHORIZED).body(result);
    }

    @GetMapping("/users/{userId}/statistics")
    public UserStatistics statistics(@PathVariable @Positive long userId) {
        return users.statistics(userId);
    }

    @GetMapping("/statistics")
    public OverallStatistics overallStatistics() {
        return users.overallStatistics();
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        users.checkDatabase();
        return Map.of("service", "user_validation_service", "status", "UP");
    }
}
