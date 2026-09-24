package com.oxygenraj.transact.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.SimpleCommandLinePropertySource;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DatabasePortEnvironmentPostProcessorTests {

    private static final String PASSWORD = "test-only-database-password";
    private static final String UNRESOLVED_SECRET = "test-only-unresolved-secret";
    private static final String PREFIX = DatabasePortEnvironmentPostProcessor.PREFIX;

    private final OraclePortReader portReader = mock(OraclePortReader.class);
    private final DatabasePortEnvironmentPostProcessor processor =
            new DatabasePortEnvironmentPostProcessor(portReader);
    private final SpringApplication application = new SpringApplication();

    @Test
    void runsImmediatelyAfterConfigDataHasLoaded() {
        assertThat(processor.getOrder()).isEqualTo(ConfigDataEnvironmentPostProcessor.ORDER + 1);
    }

    @Test
    void theLookupIsDisabledByDefault() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("server.port", "8082")
                .withProperty("user-transact.properties-datasource.password", "${test-only-unresolved-secret}");

        processor.postProcessEnvironment(environment, application);

        verifyNoInteractions(portReader);
        assertThat(environment.getProperty("server.port")).isEqualTo("8082");
        assertNoDatabasePropertySource(environment);
    }

    @Test
    void explicitlyDisablingTheLookupPreservesTheConfiguredPort() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty(PREFIX + "enabled", "false")
                .withProperty("user-transact.properties-datasource.url", "invalid-url-" + PASSWORD)
                .withProperty("user-transact.properties-datasource.username", "${test-only-unresolved-secret}")
                .withProperty("user-transact.properties-datasource.password", "${test-only-unresolved-secret}")
                .withProperty("server.port", "8083")
                .withProperty("spring.application.name", "userTransactServices");

        processor.postProcessEnvironment(environment, application);

        verifyNoInteractions(portReader);
        assertThat(environment.getProperty("server.port")).isEqualTo("8083");
        assertThat(environment.getProperty("spring.application.name")).isEqualTo("userTransactServices");
        assertNoDatabasePropertySource(environment);
    }

    @Test
    void enabledLookupUsesTheDefaultOracleAddress() {
        MockEnvironment environment = enabledEnvironment()
                .withProperty("user-transact.properties-datasource.password", PASSWORD)
                .withProperty("spring.datasource.url", "jdbc:oracle:thin:@//crud.example.test:1521/FREEPDB1")
                .withProperty("USER_TRANSACT_DB_URL", "jdbc:oracle:thin:@//other-crud.example.test:1521/FREEPDB1");
        when(portReader.readPort(DatabasePortEnvironmentPostProcessor.DEFAULT_URL, PASSWORD)).thenReturn(8084);

        processor.postProcessEnvironment(environment, application);

        verify(portReader).readPort("jdbc:oracle:thin:@//127.0.0.1:11521/FREEPDB1", PASSWORD);
        assertThat(environment.getProperty("server.port", Integer.class)).isEqualTo(8084);
    }

    @Test
    void readsOnlyTheSeparateResolvedPropertiesDatasourceConfiguration() {
        String url = "jdbc:oracle:thin:@//database.example.test:1521/TESTPDB";
        MockEnvironment environment = enabledEnvironment()
                .withProperty("EUREKA_DB_URL", url)
                .withProperty("EUREKA_DB_PASSWORD", PASSWORD)
                .withProperty("user-transact.properties-datasource.url", "${EUREKA_DB_URL}")
                .withProperty("user-transact.properties-datasource.username", "EUREKA_DB")
                .withProperty("user-transact.properties-datasource.password", "${EUREKA_DB_PASSWORD}")
                .withProperty("spring.datasource.url", "${test-only-unresolved-secret}")
                .withProperty("spring.datasource.password", "${test-only-unresolved-secret}")
                .withProperty("USER_TRANSACT_DB_PASSWORD", "not-the-properties-account-password");
        when(portReader.readPort(url, PASSWORD)).thenReturn(8085);

        processor.postProcessEnvironment(environment, application);

        verify(portReader).readPort(url, PASSWORD);
        assertThat(environment.getProperty("server.port", Integer.class)).isEqualTo(8085);
    }

    @Test
    void disablingEurekaDoesNotDisableTheIndependentServicePortLookup() {
        MockEnvironment environment = enabledEnvironment()
                .withProperty("eureka.client.enabled", "false")
                .withProperty("user-transact.properties-datasource.password", PASSWORD);
        when(portReader.readPort(DatabasePortEnvironmentPostProcessor.DEFAULT_URL, PASSWORD)).thenReturn(8781);

        processor.postProcessEnvironment(environment, application);

        verify(portReader).readPort(DatabasePortEnvironmentPostProcessor.DEFAULT_URL, PASSWORD);
        assertThat(environment.getProperty("server.port", Integer.class)).isEqualTo(8781);
    }

    @Test
    void neverFallsBackToCrudCredentialsWhenThePropertiesPasswordIsMissing() {
        MockEnvironment environment = enabledEnvironment()
                .withProperty("spring.datasource.username", "USER_TRANSACT_SCHEMA")
                .withProperty("spring.datasource.password", PASSWORD)
                .withProperty("USER_TRANSACT_DB_PASSWORD", PASSWORD);

        Throwable failure = catchThrowable(() -> processor.postProcessEnvironment(environment, application));

        assertSanitized(failure);
        assertThat(failure).hasMessageContaining("EUREKA_DB_PASSWORD");
        verifyNoInteractions(portReader);
        assertNoDatabasePropertySource(environment);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "USER_TRANSACT_SCHEMA", "SYSTEM", "eureka_db", PASSWORD,
            "${test-only-unresolved-secret}"})
    void rejectsAnUnexpectedPropertiesDatabaseUsernameWithoutConnecting(String username) {
        MockEnvironment environment = enabledEnvironment()
                .withProperty("user-transact.properties-datasource.username", username)
                .withProperty("user-transact.properties-datasource.password", PASSWORD);

        Throwable failure = catchThrowable(() -> processor.postProcessEnvironment(environment, application));

        assertSanitized(failure);
        verifyNoInteractions(portReader);
    }

    @Test
    void databasePortOverridesTheCommandLineWithoutPublishingCredentials() {
        MockEnvironment environment = enabledEnvironment()
                .withProperty("user-transact.properties-datasource.password", PASSWORD)
                .withProperty("server.port", "8082")
                .withProperty("spring.application.name", "userTransactServices")
                .withProperty("server.servlet.context-path", "/userTransactServices");
        environment.getPropertySources().addFirst(new SimpleCommandLinePropertySource(
                "--server.port=9090", "--custom.flag=unchanged"));
        when(portReader.readPort(DatabasePortEnvironmentPostProcessor.DEFAULT_URL, PASSWORD)).thenReturn(9443);

        processor.postProcessEnvironment(environment, application);

        assertThat(environment.getProperty("server.port", Integer.class)).isEqualTo(9443);
        assertThat(environment.getProperty("spring.application.name")).isEqualTo("userTransactServices");
        assertThat(environment.getProperty("server.servlet.context-path")).isEqualTo("/userTransactServices");
        assertThat(environment.getProperty("custom.flag")).isEqualTo("unchanged");
        assertThat(environment.getPropertySources().iterator().next().getName())
                .isEqualTo(DatabasePortEnvironmentPostProcessor.PROPERTY_SOURCE);
        MapPropertySource databaseProperties = (MapPropertySource) environment.getPropertySources()
                .get(DatabasePortEnvironmentPostProcessor.PROPERTY_SOURCE);
        assertThat(databaseProperties).isNotNull();
        assertThat(databaseProperties.getSource()).containsOnlyKeys("server.port");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "${test-only-unresolved-secret}"})
    void rejectsMissingBlankOrUnresolvedPasswordsWithoutLeakingTheirContents(String password) {
        MockEnvironment environment = enabledEnvironment();
        if (password != null) {
            environment.withProperty("user-transact.properties-datasource.password", password);
        }

        Throwable failure = catchThrowable(() -> processor.postProcessEnvironment(environment, application));

        assertSanitized(failure);
        verifyNoInteractions(portReader);
        assertNoDatabasePropertySource(environment);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "https://database.example.test/test-only-database-password",
            "jdbc:oracle:thin:other-user/test-only-database-password@//database.example.test:1521/TESTPDB",
            "${test-only-unresolved-secret}"
    })
    void rejectsInvalidOrCredentialBearingUrlsWithoutEchoingThem(String url) {
        MockEnvironment environment = enabledEnvironment()
                .withProperty("user-transact.properties-datasource.url", url)
                .withProperty("user-transact.properties-datasource.password", PASSWORD);

        Throwable failure = catchThrowable(() -> processor.postProcessEnvironment(environment, application));

        assertSanitized(failure);
        verifyNoInteractions(portReader);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-boolean", PASSWORD, "${test-only-unresolved-secret}"})
    void rejectsAnInvalidEnableFlagWithoutLeakingItsContents(String enabled) {
        MockEnvironment environment = new MockEnvironment()
                .withProperty(PREFIX + "enabled", enabled)
                .withProperty("user-transact.properties-datasource.password", PASSWORD);

        Throwable failure = catchThrowable(() -> processor.postProcessEnvironment(environment, application));

        assertSanitized(failure);
        verifyNoInteractions(portReader);
    }

    @Test
    void aDatabaseFailureDoesNotFallBackToTheYamlPort() {
        MockEnvironment environment = enabledEnvironment()
                .withProperty("user-transact.properties-datasource.password", PASSWORD)
                .withProperty("server.port", "8082");
        IllegalStateException databaseFailure = new IllegalStateException("Sanitized database lookup failure");
        when(portReader.readPort(DatabasePortEnvironmentPostProcessor.DEFAULT_URL, PASSWORD))
                .thenThrow(databaseFailure);

        Throwable failure = catchThrowable(() -> processor.postProcessEnvironment(environment, application));

        assertThat(failure).isSameAs(databaseFailure);
        assertNoDatabasePropertySource(environment);
    }

    private static MockEnvironment enabledEnvironment() {
        return new MockEnvironment().withProperty(PREFIX + "enabled", "true");
    }

    private static void assertNoDatabasePropertySource(MockEnvironment environment) {
        assertThat(environment.getPropertySources().contains(DatabasePortEnvironmentPostProcessor.PROPERTY_SOURCE))
                .isFalse();
    }

    private static void assertSanitized(Throwable failure) {
        assertThat(failure).isInstanceOf(IllegalStateException.class).hasNoCause();
        assertThat(failure.getMessage()).doesNotContain(PASSWORD, UNRESOLVED_SECRET);
        assertThat(failure.getSuppressed()).isEmpty();
    }
}
