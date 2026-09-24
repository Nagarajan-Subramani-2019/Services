package com.oxygenraj.transact.config;

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
import static org.mockito.ArgumentMatchers.argThat;
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
        when(rows.getString("SESSION_USER")).thenReturn("EUREKA_DB");
        when(rows.getString("CURRENT_SCHEMA")).thenReturn("EUREKA_DB");

        // Preserve the driver's one-time registration before mocking the JDBC boundary.
        Class.forName("oracle.jdbc.OracleDriver");
        try (MockedStatic<DriverManager> driverManager = mockStatic(DriverManager.class)) {
            driverManager.when(() -> DriverManager.getConnection(eq(testUrl), any(Properties.class)))
                    .thenReturn(connection);
            SpringApplication application = new SpringApplication(BootstrapConfiguration.class);
            application.setWebApplicationType(WebApplicationType.NONE);
            application.setRegisterShutdownHook(false);
            try (ConfigurableApplicationContext context = application.run(
                    "--user-transact.database-port.enabled=true",
                    "--eureka.client.enabled=false",
                    "--EUREKA_DB_URL=" + testUrl,
                    "--EUREKA_DB_PASSWORD=test-only-properties-password",
                    "--spring.datasource.url=jdbc:oracle:thin:@//crud.example.test:1521/FREEPDB1",
                    "--spring.datasource.password=test-only-crud-password",
                    "--server.port=8082",
                    "--spring.main.banner-mode=off")) {
                assertThat(context.getEnvironment().getProperty("server.port", Integer.class)).isEqualTo(8182);
                assertThat(context.getEnvironment().getProperty("spring.application.name"))
                        .isEqualTo("userTransactServices");
                assertThat(context.getEnvironment().getProperty("spring.datasource.username"))
                        .isEqualTo("USER_TRANSACT_SCHEMA");
                assertThat(context.getEnvironment().getProperty("spring.datasource.url"))
                        .isEqualTo("jdbc:oracle:thin:@//crud.example.test:1521/FREEPDB1");
                assertThat(context.getEnvironment().getProperty("spring.datasource.password"))
                        .isEqualTo("test-only-crud-password");
                assertThat(context.getEnvironment().getProperty("server.servlet.context-path"))
                        .isEqualTo("/userTransactServices");
                assertThat(context.getEnvironment().getProperty("eureka.instance.instance-id"))
                        .isEqualTo("userTransactServices:localhost:8182");
                assertThat(context.getEnvironment().getProperty("eureka.instance.health-check-url-path"))
                        .isEqualTo("/userTransactServices/actuator/health");
            }
            driverManager.verify(() -> DriverManager.getConnection(eq(testUrl), argThat(properties ->
                    "EUREKA_DB".equals(properties.getProperty("user"))
                    && "test-only-properties-password".equals(properties.getProperty("password")))));
            driverManager.verifyNoMoreInteractions();
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
