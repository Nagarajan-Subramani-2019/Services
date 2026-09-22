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
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cloud.netflix.eureka.EurekaClientConfigBean;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Real Boot environment/registration, mock JDBC only: no Oracle or Eureka connections. */
class EurekaBootstrapIntegrationTests {
    private static final String JDBC = "jdbc:oracle:thin:@//127.0.0.1:1/FREEPDB1";

    @Test
    void ownPortAndSharedEurekaPortAreIndependentAndBoundBeforeClientCreation() throws Exception {
        Connection own = connection(OraclePortReader.PORT_QUERY, "8770");
        Connection discovery = connection(OracleEurekaPortReader.PORT_QUERY, "8760");
        Class.forName("oracle.jdbc.OracleDriver");
        try (MockedStatic<DriverManager> driver = mockStatic(DriverManager.class)) {
            driver.when(() -> DriverManager.getConnection(eq(JDBC), any(Properties.class)))
                    .thenReturn(own, discovery);
            try (ConfigurableApplicationContext context = application().run(
                    "--spring.datasource.url=" + JDBC,
                    "--spring.datasource.password=test-only-password",
                    "--user-info.database-port.enabled=true",
                    "--eureka.client.enabled=true",
                    "--user-info.eureka.database-port.enabled=true",
                    "--server.port=9998",
                    "--eureka.client.serviceUrl.defaultZone=http://discovery.example.test:9999/eureka-server/eureka/",
                    "--spring.main.banner-mode=off")) {
                assertThat(context.getEnvironment().getProperty("server.port", Integer.class)).isEqualTo(8770);
                assertThat(context.getEnvironment().getProperty("eureka.instance.instance-id"))
                        .isEqualTo("userInfoServices:8770");
                EurekaClientConfigBean client = Binder.get(context.getEnvironment())
                        .bind("eureka.client", Bindable.of(EurekaClientConfigBean.class)).get();
                assertThat(client.getEurekaServerServiceUrls("defaultZone"))
                        .containsExactly("http://discovery.example.test:8760/eureka-server/eureka/");
                assertThat(client.isRegisterWithEureka()).isTrue();
                assertThat(client.isFetchRegistry()).isTrue();
            }
            driver.verify(() -> DriverManager.getConnection(eq(JDBC), any(Properties.class)), times(2));
        }
        verify(own).close();
        verify(discovery).close();
    }

    @Test
    void defaultYamlOwnPortIs8770WhileEurekaUsesTheDatabaseValue() throws Exception {
        Connection discovery = connection(OracleEurekaPortReader.PORT_QUERY, "8877");
        Class.forName("oracle.jdbc.OracleDriver");
        try (MockedStatic<DriverManager> driver = mockStatic(DriverManager.class)) {
            driver.when(() -> DriverManager.getConnection(eq(JDBC), any(Properties.class)))
                    .thenReturn(discovery);
            try (ConfigurableApplicationContext context = application().run(
                    "--spring.datasource.url=" + JDBC,
                    "--spring.datasource.password=test-only-password",
                    "--user-info.database-port.enabled=false",
                    "--USER_INFO_PORT=8770",
                    "--eureka.client.enabled=true",
                    "--user-info.eureka.database-port.enabled=true",
                    "--EUREKA_URL=https://discovery.example.test/eureka-server/eureka/",
                    "--spring.main.banner-mode=off")) {
                assertThat(context.getEnvironment().getProperty("server.port", Integer.class)).isEqualTo(8770);
                EurekaClientConfigBean client = Binder.get(context.getEnvironment())
                        .bind("eureka.client", Bindable.of(EurekaClientConfigBean.class)).get();
                assertThat(client.getEurekaServerServiceUrls("defaultZone"))
                        .containsExactly("https://discovery.example.test:8877/eureka-server/eureka/");
            }
            driver.verify(() -> DriverManager.getConnection(eq(JDBC), any(Properties.class)), times(1));
        }
    }

    private static Connection connection(String sql, String port) throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet rows = mock(ResultSet.class);
        when(connection.prepareStatement(sql)).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true, false);
        when(rows.getString("VALUE")).thenReturn(port);
        when(rows.getString("SESSION_USER")).thenReturn("USER_INFO_SCHEMA");
        when(rows.getString("CURRENT_SCHEMA")).thenReturn("USER_INFO_SCHEMA");
        when(rows.getString("CON_NAME")).thenReturn("FREEPDB1");
        return connection;
    }

    private static SpringApplication application() {
        var application = new SpringApplication(BootstrapConfiguration.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setRegisterShutdownHook(false);
        return application;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class BootstrapConfiguration {}
}
