package com.oxygenraj.userui.security;

import com.oxygenraj.uiplatform.SessionPrincipal;
import com.oxygenraj.uiplatform.UiPlatform;
import com.oxygenraj.uiplatform.UiPlatformException;
import com.oxygenraj.userui.api.UserView;
import com.oxygenraj.userui.client.UpstreamFailure;
import com.oxygenraj.userui.client.UserInfoClient;
import org.springframework.stereotype.Service;

@Service
public class AuthorizedSessions {
    private final UiPlatform platform;
    private final UserInfoClient users;

    public AuthorizedSessions(UiPlatform platform, UserInfoClient users) {
        this.platform = platform;
        this.users = users;
    }

    public SessionPrincipal requireActive(String authorization) {
        SessionPrincipal principal = platform.authenticate(authorization);
        UserView current;
        try {
            current = users.find(principal.userId());
        } catch (UpstreamFailure exception) {
            if (exception.status() != 404) throw exception;
            platform.revoke(authorization);
            throw denied();
        }
        if (!current.enabled() || current.id() != principal.userId() || !current.username().equals(principal.username())) {
            platform.revoke(authorization);
            throw denied();
        }
        return principal;
    }

    private static UiPlatformException denied() {
        return new UiPlatformException(401, "SESSION_INVALID", "This session is no longer valid. Please sign in again.");
    }
}
