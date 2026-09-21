package com.oxygenraj.initial;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@Import(GreetingEndpointTest.DatabaseHealthConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.address=127.0.0.1",
        "server.servlet.context-path=",
        "spring.datasource.url=jdbc:oracle:thin:@//127.0.0.1:1/TEST_ONLY",
        "spring.datasource.username=INITIAL_DB",
        "spring.datasource.password=test-only",
        "spring.datasource.hikari.minimum-idle=0",
        "spring.datasource.hikari.initialization-fail-timeout=-1",
        "spring.sql.init.mode=never",
        "eureka.client.enabled=false",
        "eureka.client.service-url.defaultZone=http://127.0.0.1:1/eureka/"
})
class GreetingEndpointTest {

    @LocalServerPort
    private int port;

    @MockitoBean
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private HikariDataSource dataSource;

    @ParameterizedTest
    @CsvSource({"hi, Hi", "hello, Hello"})
    void returnsGreetingJson(String endpoint, String message) throws Exception {
        when(jdbcTemplate.queryForMap(OracleSchemaService.SESSION_IDENTITY_QUERY))
                .thenReturn(Map.of("SESSION_USER", "INITIAL_DB", "CURRENT_SCHEMA", "INITIAL_DB"));

        HttpResponse<String> response = get(endpoint);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("content-type").orElse(""))
                .startsWith("application/json");
        JSONAssert.assertEquals("""
                {"message":"%s","service":"initial-service","schema":"INITIAL_DB"}
                """.formatted(message), response.body(), JSONCompareMode.STRICT);
    }

    @Test
    void returns503WithoutDatabaseDetailsWhenTheQueryFails() throws Exception {
        when(jdbcTemplate.queryForMap(OracleSchemaService.SESSION_IDENTITY_QUERY))
                .thenThrow(new DataAccessResourceFailureException(
                        "SQL=SELECT SYS_CONTEXT; username=SYS; password=do-not-expose"));

        HttpResponse<String> response = get("hi");

        assertThat(response.statusCode()).isEqualTo(503);
        JSONAssert.assertEquals("""
                {"message":"Database is unavailable."}
                """, response.body(), JSONCompareMode.STRICT);
    }

    @ParameterizedTest
    @CsvSource({"SYS, INITIAL_DB", "INITIAL_DB, SYS"})
    void returns503WhenOracleReportsAnUnexpectedIdentity(String sessionUser, String currentSchema)
            throws Exception {
        when(jdbcTemplate.queryForMap(OracleSchemaService.SESSION_IDENTITY_QUERY))
                .thenReturn(Map.of("SESSION_USER", sessionUser, "CURRENT_SCHEMA", currentSchema));

        HttpResponse<String> response = get("hello");

        assertThat(response.statusCode()).isEqualTo(503);
        JSONAssert.assertEquals("""
                {"message":"Database is unavailable."}
                """, response.body(), JSONCompareMode.STRICT);
    }

    @Test
    void readinessUsesTheTestHealthContributorWithoutOpeningThePool() throws Exception {
        HttpResponse<String> response = getPath("/actuator/health/readiness");

        assertThat(response.statusCode()).isEqualTo(200);
        JSONAssert.assertEquals("{\"status\":\"UP\"}", response.body(), JSONCompareMode.STRICT);
        assertThat(dataSource.isRunning()).isFalse();
    }

    private HttpResponse<String> get(String endpoint) throws Exception {
        return getPath("/api/" + endpoint);
    }

    private HttpResponse<String> getPath(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        try (HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class DatabaseHealthConfiguration {

        @Bean("dbHealthContributor")
        HealthIndicator databaseHealth() {
            return () -> Health.up().build();
        }
    }
}
