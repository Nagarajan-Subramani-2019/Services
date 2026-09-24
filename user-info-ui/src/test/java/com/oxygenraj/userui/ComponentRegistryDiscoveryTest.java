package com.oxygenraj.userui;

import com.oxygenraj.uiplatform.ComponentDefinition;
import com.oxygenraj.uiplatform.ComponentApi;
import com.oxygenraj.userui.client.ComponentRegistryClient;
import com.oxygenraj.userui.client.UpstreamFailure;
import com.oxygenraj.userui.config.ComponentClientProperties;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.util.LinkedMultiValueMap;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ComponentRegistryDiscoveryTest {
    private static final ComponentApi API = new ComponentApi("directory", "people", "/api/users",
            "UI_DIRECTORY", "DIRECTORY_READ", "USER_OPTIONS", List.of("page", "size"));

    @Test void resolvesDatabaseServiceAndContextOnlyToApprovedDiscoveredOrigin() {
        try (var discovered = new StubUserInfoServer(); var fallback = new StubUserInfoServer()) {
            discovered.reply(200, "{\"items\":[],\"page\":0,\"size\":20,\"totalElements\":0,\"totalPages\":0}");
            var discovery = mock(DiscoveryClient.class);
            when(discovery.getInstances("directory-component")).thenReturn(List.of(
                    new DefaultServiceInstance("directory-1", "directory-component", "127.0.0.1", discovered.port(), false)));
            var client = client(discovery, List.of(fallback.origin(), discovered.origin()));
            try {
                var page = (ComponentRegistryClient.Page<?>) client.request(definition(fallback.origin()), API, "Bearer fixture", new LinkedMultiValueMap<>());
                assertThat(page.items()).isEmpty();
                assertThat(discovered.requests().getFirst().path()).isEqualTo("/directory-server/api/users?page=0&size=20");
                assertThat(discovered.requests().getFirst().authorization()).isEqualTo("Bearer fixture");
                assertThat(fallback.requests()).isEmpty();
            } finally { client.close(); }
        }
    }

    @Test void noInstancesDoesNotFallBackToDatabaseBaseUrl() {
        try (var fallback = new StubUserInfoServer()) {
            var discovery = mock(DiscoveryClient.class);
            when(discovery.getInstances("directory-component")).thenReturn(List.of());
            var client = client(discovery, List.of(fallback.origin()));
            try {
                Throwable thrown = catchThrowable(() -> client.request(definition(fallback.origin()), API, "Bearer fixture", new LinkedMultiValueMap<>()));
                assertThat(thrown).isInstanceOf(UpstreamFailure.class);
                assertThat(((UpstreamFailure) thrown).status()).isEqualTo(503);
                assertThat(fallback.requests()).isEmpty();
            } finally { client.close(); }
        }
    }

    @Test void unapprovedDiscoveredOriginNeverReceivesToken() {
        try (var allowed = new StubUserInfoServer(); var forbidden = new StubUserInfoServer()) {
            var discovery = mock(DiscoveryClient.class);
            when(discovery.getInstances("directory-component")).thenReturn(List.of(
                    new DefaultServiceInstance("directory-1", "directory-component", "127.0.0.1", forbidden.port(), false)));
            var client = client(discovery, List.of(allowed.origin()));
            try {
                Throwable thrown = catchThrowable(() -> client.request(definition(allowed.origin()), API, "Bearer fixture", new LinkedMultiValueMap<>()));
                assertThat(thrown).isInstanceOf(UpstreamFailure.class);
                assertThat(forbidden.requests()).isEmpty();
                assertThat(allowed.requests()).isEmpty();
            } finally { client.close(); }
        }
    }

    @Test void discoveryFailureIsSanitized() {
        var discovery = mock(DiscoveryClient.class);
        when(discovery.getInstances("directory-component")).thenThrow(new IllegalStateException("internal discovery secret"));
        var client = client(discovery, List.of("http://127.0.0.1:1"));
        try {
            Throwable thrown = catchThrowable(() -> client.request(definition("http://127.0.0.1:1"), API, "Bearer fixture", new LinkedMultiValueMap<>()));
            assertThat(thrown).isInstanceOf(UpstreamFailure.class);
            assertThat(thrown.getMessage()).doesNotContain("secret", "fixture");
        } finally { client.close(); }
    }

    @Test void originsNormalizeCaseAndDefaultPortButKeepPortBoundary() {
        var properties = new ComponentClientProperties("direct", List.of("HTTP://LOCALHOST", "https://example.test/"));
        assertThat(properties.permits(URI.create("http://localhost:80/path"))).isTrue();
        assertThat(properties.permits(URI.create("https://example.test:443/path"))).isTrue();
        assertThat(properties.permits(URI.create("http://localhost:81/path"))).isFalse();
        assertThat(properties.permits(URI.create("http://example.test/path"))).isFalse();
    }

    @ParameterizedTest @ValueSource(strings = {"http://localhost/path", "http://name:secret@localhost", "http://localhost?x=1", "http://localhost#x", "file:///tmp/data", "http://localhost:0"})
    void invalidOriginConfigurationCannotEnableProxy(String origin) {
        assertThatThrownBy(() -> new ComponentClientProperties("direct", List.of(origin))).isInstanceOf(IllegalArgumentException.class);
    }

    private static ComponentDefinition definition(String origin) {
        return new ComponentDefinition("directory", "directory-server", "directory-component", origin + "/directory-server", "user-directory");
    }

    @SuppressWarnings("unchecked")
    private static ComponentRegistryClient client(DiscoveryClient discovery, List<String> origins) {
        ObjectProvider<DiscoveryClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(discovery);
        return new ComponentRegistryClient(new ComponentClientProperties("discovery", origins), provider, JsonMapper.builder().build());
    }
}
