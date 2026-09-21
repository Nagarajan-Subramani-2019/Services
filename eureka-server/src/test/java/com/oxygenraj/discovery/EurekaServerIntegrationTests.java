package com.oxygenraj.discovery;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.servlet.context-path=/eureka-server",
        "spring.security.user.name=eureka",
        "spring.security.user.password=test-only-password",
        "eureka.client.register-with-eureka=false",
        "eureka.client.fetch-registry=false",
        "eureka.client.service-url.defaultZone=http://localhost:0/eureka-server/eureka/",
        "eureka.instance.hostname=localhost"
})
class EurekaServerIntegrationTests {

    private static final String CONTEXT_PATH = "/eureka-server";
    private static final String AUTHORIZATION = "Basic " + Base64.getEncoder().encodeToString(
            "eureka:test-only-password".getBytes(StandardCharsets.UTF_8));

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @LocalServerPort
    private int port;

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void startsWithoutAnOracleDataSource() {
        assertThat(applicationContext.getBeansOfType(DataSource.class)).isEmpty();
    }

    @Test
    void exposesHealthWithoutCredentialsAndWithoutDetails() throws Exception {
        HttpResponse<String> response = send("GET", "/actuator/health", false, null);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"")
                .doesNotContain("\"components\"", "\"details\"");
    }

    @Test
    void requiresCredentialsForTheDashboardAndRegistry() throws Exception {
        HttpResponse<String> dashboard = send("GET", "/", false, null);
        HttpResponse<String> registry = send("GET", "/eureka/apps", false, null);

        assertThat(dashboard.statusCode()).isEqualTo(401);
        assertThat(registry.statusCode()).isEqualTo(401);
        assertThat(registry.headers().firstValue("WWW-Authenticate")).hasValueSatisfying(
                value -> assertThat(value).startsWith("Basic"));
    }

    @Test
    void servesTheDashboardAndRegistryWithCredentials() throws Exception {
        HttpResponse<String> dashboard = send("GET", "/", true, null);
        HttpResponse<String> registry = send("GET", "/eureka/apps", true, null);

        assertThat(dashboard.statusCode()).isEqualTo(200);
        assertThat(dashboard.body()).containsIgnoringCase("eureka");
        assertThat(registry.statusCode()).isEqualTo(200);
        assertThat(registry.body()).contains("\"applications\"");
    }

    @Test
    void keepsCsrfProtectionOutsideTheEurekaApi() throws Exception {
        HttpResponse<String> response = send("POST", "/not-a-eureka-endpoint", true, "{}");

        assertThat(response.statusCode()).isEqualTo(403);
    }

    @Test
    void authenticatesRegistrationHeartbeatLookupAndDeregistration() throws Exception {
        String application = "LOCAL-INTEGRATION-TEST";
        String instanceId = "test-" + UUID.randomUUID();
        String applicationPath = "/eureka/apps/" + application;
        String instancePath = applicationPath + "/" + instanceId;
        String instanceJson = """
                {
                  "instance": {
                    "instanceId": "%s",
                    "hostName": "localhost",
                    "app": "%s",
                    "ipAddr": "127.0.0.1",
                    "status": "UP",
                    "port": {"$": %d, "@enabled": "true"},
                    "securePort": {"$": 443, "@enabled": "false"},
                    "dataCenterInfo": {
                      "@class": "com.netflix.appinfo.InstanceInfo$DefaultDataCenterInfo",
                      "name": "MyOwn"
                    },
                    "leaseInfo": {
                      "renewalIntervalInSecs": 30,
                      "durationInSecs": 90
                    },
                    "vipAddress": "local-integration-test"
                  }
                }
                """.formatted(instanceId, application, port);

        assertThat(send("POST", applicationPath, false, instanceJson).statusCode()).isEqualTo(401);

        try {
            assertThat(send("POST", applicationPath, true, instanceJson).statusCode()).isEqualTo(204);

            HttpResponse<String> instance = send("GET", instancePath, true, null);
            assertThat(instance.statusCode()).isEqualTo(200);
            assertThat(instance.body()).contains(instanceId);

            assertThat(send("PUT", instancePath, false, null).statusCode()).isEqualTo(401);
            assertThat(send("PUT", instancePath, true, null).statusCode()).isEqualTo(200);
            assertThat(send("DELETE", instancePath, false, null).statusCode()).isEqualTo(401);
            assertThat(send("DELETE", instancePath, true, null).statusCode()).isEqualTo(200);
            assertThat(send("GET", instancePath, true, null).statusCode()).isEqualTo(404);
        }
        finally {
            // Ensure a failed assertion never leaves the test registration in the registry.
            send("DELETE", instancePath, true, null);
        }
    }

    private HttpResponse<String> send(String method, String path, boolean authenticated, String body)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + CONTEXT_PATH + path))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", path.equals("/") ? "text/html" : "application/json");

        if (authenticated) {
            request.header("Authorization", AUTHORIZATION);
        }

        HttpRequest.BodyPublisher publisher = HttpRequest.BodyPublishers.noBody();
        if (body != null) {
            request.header("Content-Type", "application/json");
            publisher = HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8);
        }

        return client.send(request.method(method, publisher).build(), HttpResponse.BodyHandlers.ofString());
    }
}
