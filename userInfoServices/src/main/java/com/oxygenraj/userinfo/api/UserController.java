package com.oxygenraj.userinfo.api;

import com.oxygenraj.userinfo.service.UserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import java.net.URI;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
public class UserController {
    private final UserService service;
    private final AuthenticationManager authenticationManager;

    public UserController(UserService service, AuthenticationManager authenticationManager) {
        this.service = service;
        this.authenticationManager = authenticationManager;
    }

    @PostMapping(value = "/userdetails", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<UserResponse> create(@Valid @RequestBody CreateUserRequest request) {
        UserResponse user = service.create(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(user.id()).toUri();
        return ResponseEntity.created(location).body(user);
    }

    @GetMapping("/userdetails")
    public UserPage list(@RequestParam(defaultValue = "0") @Min(0) @Max(1000000) int page,
                         @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.list(page, size);
    }

    @GetMapping("/userdetails/{id}")
    public UserResponse get(@PathVariable @Positive long id, Authentication authentication) {
        return service.get(id, authentication);
    }

    @PostMapping(value = "/authuserdetails", consumes = MediaType.APPLICATION_JSON_VALUE)
    public AuthenticationResponse authenticate(@Valid @RequestBody AuthenticateRequest request) {
        Authentication authenticated = authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(request.username(), request.password()));
        return new AuthenticationResponse(true, service.current(authenticated.getName()));
    }

    /** Verification only: this endpoint does not issue an access token or create a session. */
    public record AuthenticationResponse(boolean authenticated, UserResponse user) {}
}
