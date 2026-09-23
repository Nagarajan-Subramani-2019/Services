package com.oxygenraj.userui;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.address=127.0.0.1", "server.servlet.context-path=/user-info-ui",
        "user-info.client.mode=discovery", "user-info.client.service-id=userInfoServices",
        "user-info.client.context-path=/discovered-user-context", "eureka.client.enabled=false"
})
@Import(UserInfoUiDiscoveryIntegrationTest.FixtureConfiguration.class)
class UserInfoUiDiscoveryIntegrationTest {
    private static final StubUserInfoServer DISCOVERED = new StubUserInfoServer();
    private static final StubUserInfoServer FORBIDDEN_FALLBACK = new StubUserInfoServer();
    @LocalServerPort private int port;
    @Autowired @Qualifier("fixtureDiscoveryClient") private DiscoveryClient discovery;
    private HttpClient http;

    @DynamicPropertySource
    static void addresses(DynamicPropertyRegistry properties) {
        properties.add("user-info.client.base-url", () -> FORBIDDEN_FALLBACK.origin() + "/never-fallback");
    }

    @BeforeEach void prepare() {
        DISCOVERED.reset();
        FORBIDDEN_FALLBACK.reset();
        reset(discovery);
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }
    @AfterEach void closeHttp() { http.close(); }
    @AfterAll static void closeFixtures() { DISCOVERED.close(); FORBIDDEN_FALLBACK.close(); }

    @Test void discoveryUsesServiceIdInstanceAddressAndConfiguredContext() throws Exception {
        when(discovery.getInstances("userInfoServices")).thenReturn(List.of(instance(DISCOVERED.port())));
        DISCOVERED.reply(200, StubUserInfoServer.USER);
        HttpResponse<String> response = getProfile();
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("sample.user");
        verify(discovery).getInstances("userInfoServices");
        assertThat(DISCOVERED.requests()).hasSize(1);
        assertThat(DISCOVERED.requests().getFirst().path()).isEqualTo("/discovered-user-context/userdetails/17");
        assertThat(FORBIDDEN_FALLBACK.requests()).isEmpty();
    }

    @Test void missingDiscoveryInstanceDoesNotFallBackToDirectAddress() throws Exception {
        when(discovery.getInstances("userInfoServices")).thenReturn(List.of());
        assertUnavailable(getProfile());
        assertThat(DISCOVERED.requests()).isEmpty();
        assertThat(FORBIDDEN_FALLBACK.requests()).isEmpty();
    }

    @Test void discoveryFailureIsSanitizedAndDoesNotFallBack() throws Exception {
        when(discovery.getInstances("userInfoServices")).thenThrow(new IllegalStateException("private-registry-host secret-detail"));
        HttpResponse<String> response = getProfile();
        assertUnavailable(response);
        assertThat(response.body()).doesNotContain("private-registry-host", "secret-detail", "IllegalStateException");
        assertThat(FORBIDDEN_FALLBACK.requests()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 65536})
    void invalidDiscoveredPortIsUnavailableWithoutNetworkRequest(int badPort) throws Exception {
        when(discovery.getInstances("userInfoServices")).thenReturn(List.of(instance(badPort)));
        assertUnavailable(getProfile());
        assertThat(DISCOVERED.requests()).isEmpty();
        assertThat(FORBIDDEN_FALLBACK.requests()).isEmpty();
    }

    @Test void skipsInvalidInstanceWhenAValidInstanceIsAvailable() throws Exception {
        when(discovery.getInstances("userInfoServices")).thenReturn(List.of(instance(0), instance(DISCOVERED.port())));
        DISCOVERED.reply(200, StubUserInfoServer.USER);
        assertThat(getProfile().statusCode()).isEqualTo(200);
        assertThat(DISCOVERED.requests()).hasSize(1);
        assertThat(FORBIDDEN_FALLBACK.requests()).isEmpty();
    }

    @Test void unreachableDiscoveredServiceIsUnavailableWithoutFallback() throws Exception {
        StubUserInfoServer offline = new StubUserInfoServer();
        int closedPort = offline.port();
        offline.close();
        when(discovery.getInstances("userInfoServices")).thenReturn(List.of(instance(closedPort)));
        assertUnavailable(getProfile());
        assertThat(FORBIDDEN_FALLBACK.requests()).isEmpty();
    }

    private DefaultServiceInstance instance(int servicePort) {
        return new DefaultServiceInstance("synthetic-instance", "userInfoServices", "127.0.0.1", servicePort, false);
    }
    private HttpResponse<String> getProfile() throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/user-info-ui/api/users/17"))
                        .timeout(Duration.ofSeconds(15)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    private void assertUnavailable(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.body()).contains("SERVICE_UNAVAILABLE");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixtureConfiguration {
        // Spring Cloud supplies the primary CompositeDiscoveryClient. It delegates to this
        // stub; marking both beans primary would make ObjectProvider lookup ambiguous.
        @Bean DiscoveryClient fixtureDiscoveryClient() { return mock(DiscoveryClient.class); }
    }
}
