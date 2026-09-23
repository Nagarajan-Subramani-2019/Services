package com.oxygenraj.userui;

import com.oxygenraj.userui.config.UserInfoClientProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserInfoClientPropertiesTest {
    @Test void defaultsTargetOnlyLocalUserService() {
        UserInfoClientProperties properties = new UserInfoClientProperties(null, null, null, null);
        assertThat(properties.mode()).isEqualTo("direct");
        assertThat(properties.baseUrl()).isEqualTo("http://127.0.0.1:8771/userInfoServices");
        assertThat(properties.serviceId()).isEqualTo("userInfoServices");
        assertThat(properties.contextPath()).isEqualTo("/userInfoServices");
    }

    @Test void trimsSettingsAndTrailingSlashesWithoutLosingBaseContext() {
        UserInfoClientProperties properties = new UserInfoClientProperties(" DISCOVERY ",
                " http://127.0.0.1:8771/nested/context/// ", " userInfoServices ", " /nested/context/// ");
        assertThat(properties.mode()).isEqualTo("discovery");
        assertThat(properties.baseUrl()).isEqualTo("http://127.0.0.1:8771/nested/context");
        assertThat(properties.contextPath()).isEqualTo("/nested/context");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "fallback", "auto", "direct,discovery"})
    void rejectsUnsupportedMode(String mode) {
        assertThatThrownBy(() -> new UserInfoClientProperties(mode, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "file:///private", "ftp://example.test", "http://", "http:///host",
            "http://user:secret@example.test/api", "http://example.test/api?secret=value", "http://example.test/#fragment",
            "http://localhost:0", "http://localhost:65536", "http://example.test:invalid", "relative/path"})
    void rejectsUnsafeOrMalformedBaseUrlWithoutEchoingIt(String baseUrl) {
        assertThatThrownBy(() -> new UserInfoClientProperties("direct", baseUrl, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("user:secret").hasMessageNotContaining("secret=value");
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://127.0.0.1:8771/userInfoServices", "https://backend.example.test/users",
            "http://[::1]:8771/userInfoServices", "https://backend.example.test"})
    void acceptsConfiguredHttpAndHttpsOrigins(String baseUrl) {
        assertThat(new UserInfoClientProperties("direct", baseUrl, null, null).baseUrl()).isEqualTo(baseUrl);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "my service", "../users", "users?host=other", "https://other"})
    void rejectsInvalidDiscoveryServiceId(String serviceId) {
        assertThatThrownBy(() -> new UserInfoClientProperties("discovery", null, serviceId, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"relative", "/../users", "/users?query=value", "/users#fragment", "/users%2Fother"})
    void rejectsUnsafeDiscoveryContextPath(String contextPath) {
        assertThatThrownBy(() -> new UserInfoClientProperties("discovery", null, null, contextPath))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
