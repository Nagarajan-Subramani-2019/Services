package com.oxygenraj.transact.config;

import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockStatic;

/** Exercises real YAML and environment processors without an Oracle or Eureka network call. */
class ServiceConfigurationBootstrapTests {
    @Test
    void disabledDiscoveryNeedsNoSharedPasswordAndKeepsSafeServiceDefaults() {
        try (MockedStatic<DriverManager> driver = mockStatic(DriverManager.class);
             ConfigurableApplicationContext context = application().run(
                     "--EUREKA_CLIENT_ENABLED=false", "--USER_TRANSACT_DB_PORT_ENABLED=false",
                     "--EUREKA_DB_PASSWORD=", "--USER_TRANSACT_PORT=8781",
                     "--USER_TRANSACT_DB_URL=jdbc:oracle:thin:@//127.0.0.1:11521/FREEPDB1",
                     "--spring.main.banner-mode=off")) {
            ConfigurableEnvironment env = context.getEnvironment();
            assertThat(env.getProperty("spring.application.name")).isEqualTo("userTransactServices");
            assertThat(env.getProperty("server.port")).isEqualTo("8781");
            assertThat(env.getProperty("server.servlet.context-path")).isEqualTo("/userTransactServices");
            assertThat(env.getProperty("spring.datasource.username")).isEqualTo("USER_TRANSACT_SCHEMA");
            assertThat(env.getProperty("spring.sql.init.mode")).isEqualTo("never");
            assertThat(env.getProperty("management.endpoints.web.exposure.include")).isEqualTo("health");
            assertThat(env.getProperty("management.endpoint.health.show-details")).isEqualTo("never");
            assertThat(env.getProperty("management.endpoint.health.show-components")).isEqualTo("never");
            assertThat(env.getProperty("spring.jackson.deserialization.accept-float-as-int")).isEqualTo("false");
            assertThat(env.getProperty("spring.jackson.deserialization.fail-on-unknown-properties")).isEqualTo("true");
            driver.verifyNoInteractions();
        }
    }

    @Test
    void externalTomcatCanAdvertiseItsConnectorAndExplicitPublicUrls() {
        try (ConfigurableApplicationContext context = application().run(
                "--EUREKA_CLIENT_ENABLED=false", "--USER_TRANSACT_DB_PORT_ENABLED=false",
                "--USER_TRANSACT_PORT=8781", "--USER_TRANSACT_ADVERTISED_PORT=9090",
                "--USER_TRANSACT_HOSTNAME=transactions.example.test",
                "--USER_TRANSACT_PUBLIC_URL=https://transactions.example.test/userTransactServices",
                "--spring.main.banner-mode=off")) {
            ConfigurableEnvironment env = context.getEnvironment();
            assertThat(env.getProperty("server.port")).isEqualTo("8781");
            assertThat(env.getProperty("eureka.instance.hostname")).isEqualTo("transactions.example.test");
            assertThat(env.getProperty("eureka.instance.non-secure-port")).isEqualTo("9090");
            assertThat(env.getProperty("eureka.instance.instance-id")).isEqualTo("userTransactServices:transactions.example.test:9090");
            assertThat(env.getProperty("eureka.instance.home-page-url"))
                    .isEqualTo("https://transactions.example.test/userTransactServices/");
            assertThat(env.getProperty("eureka.instance.health-check-url"))
                    .isEqualTo("https://transactions.example.test/userTransactServices/actuator/health");
            assertThat(env.getProperty("eureka.instance.status-page-url"))
                    .isEqualTo("https://transactions.example.test/userTransactServices/actuator/health");
        }
    }

    private static SpringApplication application() {
        var app = new SpringApplication(BootstrapConfiguration.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setRegisterShutdownHook(false);
        return app;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class BootstrapConfiguration {}
}
