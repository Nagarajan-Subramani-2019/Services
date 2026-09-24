package com.oxygenraj.userui.api;

import com.oxygenraj.uiplatform.MenuItem;
import com.oxygenraj.uiplatform.UiPlatform;
import com.oxygenraj.userui.security.AuthorizedSessions;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class SessionController {
    private final UiPlatform platform;
    private final AuthorizedSessions sessions;

    public SessionController(UiPlatform platform, AuthorizedSessions sessions) {
        this.platform = platform;
        this.sessions = sessions;
    }

    @GetMapping("/menu")
    public MenuResponse menu(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return new MenuResponse(platform.menu(sessions.requireActive(authorization)));
    }

    @PostMapping("/sign-out")
    public ResponseEntity<Void> signOut(@RequestHeader(value = "Authorization", required = false) String authorization) {
        platform.authenticate(authorization);
        platform.revoke(authorization);
        return ResponseEntity.noContent().build();
    }

    public record MenuResponse(List<MenuItem> items) { }
}
