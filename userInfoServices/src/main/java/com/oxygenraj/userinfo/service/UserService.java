package com.oxygenraj.userinfo.service;

import com.oxygenraj.userinfo.api.CreateUserRequest;
import com.oxygenraj.userinfo.api.UserPage;
import com.oxygenraj.userinfo.api.UserResponse;
import com.oxygenraj.userinfo.repository.UserRepository;
import java.util.Locale;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {
    private final UserRepository repository;
    private final PasswordEncoder encoder;

    public UserService(UserRepository repository, PasswordEncoder encoder) {
        this.repository = repository;
        this.encoder = encoder;
    }

    @Transactional
    public UserResponse create(CreateUserRequest request) {
        return repository.create(normalize(request.username()), encoder.encode(request.password()),
                normalize(request.email()), request.phoneNumber()).toResponse();
    }

    @Transactional(readOnly = true)
    public UserPage list(int page, int size) {
        return new UserPage(repository.findPage(page, size).stream().map(user -> user.toResponse()).toList(),
                page, size, repository.count());
    }

    @Transactional(readOnly = true)
    public UserResponse get(long id, Authentication authentication) {
        var user = repository.findById(id).orElseThrow(UserNotFoundException::new);
        boolean admin = authentication.getAuthorities().stream().anyMatch(role -> role.getAuthority().equals("ROLE_ADMIN"));
        if (!admin && !user.username().equals(normalize(authentication.getName()))) {
            // Do not disclose whether another user's ID exists.
            throw new UserNotFoundException();
        }
        return user.toResponse();
    }

    public UserResponse current(String username) {
        return repository.findByUsername(normalize(username)).orElseThrow(UserNotFoundException::new).toResponse();
    }

    public static String normalize(String value) { return value.strip().toLowerCase(Locale.ROOT); }
    public static class UserNotFoundException extends RuntimeException {}
}
