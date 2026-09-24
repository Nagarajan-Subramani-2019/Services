package com.oxygenraj.userui.api;

import com.oxygenraj.userui.client.UserInfoClient;
import com.oxygenraj.uiplatform.UiPlatform;
import com.oxygenraj.uiplatform.UiPlatformException;
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
    private final UiPlatform platform;

    public UserUiController(UserInfoClient client, UiPlatform platform) {
        this.client = client;
        this.platform = platform;
    }

    @PostMapping(value = "/users", consumes = "application/json")
    public ResponseEntity<UserView> create(@Valid @RequestBody CreateUserRequest input) {
        UserView user = client.create(input);
        return ResponseEntity.created(URI.create("users/" + user.id())).body(user);
    }

    @PostMapping(value = "/sign-in", consumes = "application/json")
    public SessionSignInResponse signIn(@Valid @RequestBody SignInRequest input) {
        SignInResponse verified = client.signIn(input);
        if (!verified.authenticated() || !verified.user().enabled()) {
            throw new UiPlatformException(401, "INVALID_CREDENTIALS", "The account cannot sign in.");
        }
        var session = platform.issueSession(verified.user().id(), verified.user().username());
        return new SessionSignInResponse(true, verified.user(), session.accessToken(), session.expiresAt());
    }

    @GetMapping("/users/{id}")
    public UserView find(@PathVariable @Positive long id) { return client.find(id); }
}
