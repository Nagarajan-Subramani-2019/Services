package com.oxygenraj.userui.api;

import com.oxygenraj.userui.client.UserInfoClient;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class UserUiController {
    private final UserInfoClient client;

    public UserUiController(UserInfoClient client) { this.client = client; }

    @PostMapping(value = "/users", consumes = "application/json")
    public ResponseEntity<UserView> create(@Valid @RequestBody CreateUserRequest input) {
        UserView user = client.create(input);
        return ResponseEntity.created(URI.create("users/" + user.id())).body(user);
    }

    @PostMapping(value = "/sign-in", consumes = "application/json")
    public SignInResponse signIn(@Valid @RequestBody SignInRequest input) { return client.signIn(input); }

    @GetMapping("/users/{id}")
    public UserView find(@PathVariable @Positive long id) { return client.find(id); }
}
