package com.oxygenraj.userinfo.config;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DatabasePortBootstrapIntegrationTests {

    @Test
    void springFactoriesAppliesTheDatabasePortAfterLoadingApplicationYaml() throws Exception {
        String testUrl = "jdbc:oracle:thin:@//127.0.0.1:1/FREEPDB1";
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet rows = mock(ResultSet.class);
        when(connection.prepareStatement(OraclePortReader.PORT_QUERY)).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true, false);
        when(rows.getString("VALUE")).thenReturn("8182");
        when(rows.getString("SESSION_USER")).thenReturn("USER_INFO_SCHEMA");
        when(rows.getString("CURRENT_SCHEMA")).thenReturn("USER_INFO_SCHEMA");

        // Preserve the driver's one-time registration before mocking the JDBC boundary.
        Class.forName("oracle.jdbc.OracleDriver");
        try (MockedStatic<DriverManager> driverManager = mockStatic(DriverManager.class)) {
            driverManager.when(() -> DriverManager.getConnection(eq(testUrl), any(Properties.class)))
                    .thenReturn(connection);
            SpringApplication application = new SpringApplication(BootstrapConfiguration.class);
            application.setWebApplicationType(WebApplicationType.NONE);
            application.setRegisterShutdownHook(false);
            try (ConfigurableApplicationContext context = application.run(
                    "--user-info.database-port.enabled=true",
                    "--spring.datasource.url=" + testUrl,
                    "--spring.datasource.password=test-only-password",
                    "--server.port=8082",
                    "--spring.main.banner-mode=off")) {
                assertThat(context.getEnvironment().getProperty("server.port", Integer.class)).isEqualTo(8182);
                assertThat(context.getEnvironment().getProperty("spring.application.name"))
                        .isEqualTo("userInfoServices");
                assertThat(context.getEnvironment().getProperty("spring.datasource.username"))
                        .isEqualTo("USER_INFO_SCHEMA");
                assertThat(context.getEnvironment().getProperty("server.servlet.context-path"))
                        .isEqualTo("/userInfoServices");
                assertThat(context.getEnvironment().getProperty("eureka.instance.instance-id"))
                        .isEqualTo("userInfoServices:8182");
                assertThat(context.getEnvironment().getProperty("eureka.instance.health-check-url-path"))
                        .isEqualTo("/userInfoServices/actuator/health");
            }
        }
        verify(rows).close();
        verify(statement).close();
        verify(connection).close();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class BootstrapConfiguration {
        // The normal environment bootstrap runs without datasource or web auto-configuration.
    }
}
