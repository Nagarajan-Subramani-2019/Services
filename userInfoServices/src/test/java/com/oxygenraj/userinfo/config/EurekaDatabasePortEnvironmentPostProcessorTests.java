package com.oxygenraj.userinfo.config;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.cloud.netflix.eureka.EurekaClientConfigBean;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.SimpleCommandLinePropertySource;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EurekaDatabasePortEnvironmentPostProcessorTests {

    private static final String PASSWORD = "test-only-database-password";
    private static final String UNRESOLVED_SECRET = "test-only-unresolved-secret";
    private static final String PREFIX = EurekaDatabasePortEnvironmentPostProcessor.PREFIX;
    private static final String ENDPOINT = EurekaDatabasePortEnvironmentPostProcessor.ENDPOINT_PROPERTY;
    private static final String DEFAULT_DATABASE_URL = EurekaDatabasePortEnvironmentPostProcessor.DEFAULT_DATABASE_URL;

    private final OracleEurekaPortReader portReader = mock(OracleEurekaPortReader.class);
    private final EurekaDatabasePortEnvironmentPostProcessor processor =
            new EurekaDatabasePortEnvironmentPostProcessor(portReader);
    private final SpringApplication application = new SpringApplication();

    @Test
    void runsAfterTheOwnPortProcessorAndConfigurationData() {
        assertThat(processor.getOrder()).isEqualTo(ConfigDataEnvironmentPostProcessor.ORDER + 2);
    }

    @Test
    void disabledEurekaSkipsLookupFlagCredentialsAndEndpointValidation() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("eureka.client.enabled", "false")
                .withProperty(PREFIX + "enabled", "${test-only-unresolved-secret}")
                .withProperty("user-info.properties-datasource.password", "${test-only-unresolved-secret}")
                .withProperty("user-info.properties-datasource.username", "${test-only-unresolved-secret}")
                .withProperty("user-info.properties-datasource.url", PASSWORD)
                .withProperty(ENDPOINT, "not-an-endpoint-" + PASSWORD)
                .withProperty("server.port", "8770");

        processor.postProcessEnvironment(environment, application);

        verifyNoInteractions(portReader);
        assertThat(environment.getProperty(ENDPOINT)).isEqualTo("not-an-endpoint-" + PASSWORD);
        assertThat(environment.getProperty("server.port")).isEqualTo("8770");
        assertNoDatabasePropertySource(environment);
    }

    @Test
    void disabledDatabaseLookupPreservesTheStaticEndpointAndSkipsCredentials() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty(PREFIX + "enabled", "false")
                .withProperty("user-info.properties-datasource.password", "${test-only-unresolved-secret}")
                .withProperty("user-info.properties-datasource.username", "${test-only-unresolved-secret}")
                .withProperty("user-info.properties-datasource.url", PASSWORD)
                .withProperty(ENDPOINT, "http://static.example.test:9988/registry/eureka/");

        processor.postProcessEnvironment(environment, application);

        verifyNoInteractions(portReader);
        assertThat(boundClientEndpoint(environment)).isEqualTo("http://static.example.test:9988/registry/eureka/");
        assertNoDatabasePropertySource(environment);
    }

    @Test
    void lookupIsEnabledByDefaultAndDoesNotChangeTheServicesOwnPort() {
        MockEnvironment environment = configuredEnvironment()
                .withProperty("user-info.database-port.enabled", "false")
                .withProperty("server.port", "8770")
                .withProperty("spring.application.name", "userInfoServices");
        when(portReader.readPort(DEFAULT_DATABASE_URL, PASSWORD)).thenReturn(8765);

        processor.postProcessEnvironment(environment, application);

        verify(portReader).readPort(DEFAULT_DATABASE_URL, PASSWORD);
        assertThat(boundClientEndpoint(environment)).isEqualTo("http://localhost:8765/eureka-server/eureka/");
        assertThat(environment.getProperty("server.port")).isEqualTo("8770");
        assertThat(environment.getProperty("spring.application.name")).isEqualTo("userInfoServices");
        MapPropertySource source = (MapPropertySource) environment.getPropertySources()
                .get(EurekaDatabasePortEnvironmentPostProcessor.PROPERTY_SOURCE);
        assertThat(source).isNotNull();
        assertThat(source.getSource()).containsExactly(Map.entry(ENDPOINT, "http://localhost:8765/eureka-server/eureka/"));
    }

    @Test
    void usesOnlyTheSeparateResolvedPropertiesDatasourceCredentials() {
        String databaseUrl = "jdbc:oracle:thin:@//database.example.test:1521/FREEPDB1";
        MockEnvironment environment = new MockEnvironment()
                .withProperty("EUREKA_DB_URL", databaseUrl)
                .withProperty("EUREKA_DB_PASSWORD", PASSWORD)
                .withProperty("user-info.properties-datasource.url", "${EUREKA_DB_URL}")
                .withProperty("user-info.properties-datasource.username", "EUREKA_DB")
                .withProperty("user-info.properties-datasource.password", "${EUREKA_DB_PASSWORD}")
                .withProperty("spring.datasource.url", "${test-only-unresolved-secret}")
                .withProperty("spring.datasource.password", "${test-only-unresolved-secret}")
                .withProperty("USER_INFO_DB_PASSWORD", "not-the-properties-account-password");
        when(portReader.readPort(databaseUrl, PASSWORD)).thenReturn(9123);

        processor.postProcessEnvironment(environment, application);

        verify(portReader).readPort(databaseUrl, PASSWORD);
        assertThat(boundClientEndpoint(environment)).isEqualTo("http://localhost:9123/eureka-server/eureka/");
    }

    @Test
    void neverFallsBackToCrudCredentialsWhenThePropertiesPasswordIsMissing() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.datasource.username", "USER_INFO_SCHEMA")
                .withProperty("spring.datasource.password", PASSWORD)
                .withProperty("USER_INFO_DB_PASSWORD", PASSWORD);

        Throwable failure = catchThrowable(() -> processor.postProcessEnvironment(environment, application));

        assertSanitized(failure);
        assertThat(failure).hasMessageContaining("EUREKA_DB_PASSWORD");
        verifyNoInteractions(portReader);
        assertNoDatabasePropertySource(environment);
    }

    @Test
    void usesThePropertiesUrlDefaultEvenWhenCrudUsesADifferentDatabase() {
        MockEnvironment environment = configuredEnvironment()
                .withProperty("spring.datasource.url", "jdbc:oracle:thin:@//crud.example.test:1521/FREEPDB1")
                .withProperty("USER_INFO_DB_URL", "jdbc:oracle:thin:@//other-crud.example.test:1521/FREEPDB1");
        when(portReader.readPort(DEFAULT_DATABASE_URL, PASSWORD)).thenReturn(9123);

        processor.postProcessEnvironment(environment, application);

        verify(portReader).readPort(DEFAULT_DATABASE_URL, PASSWORD);
        assertThat(boundClientEndpoint(environment)).isEqualTo("http://localhost:9123/eureka-server/eureka/");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "USER_INFO_SCHEMA", "SYSTEM", "eureka_db", PASSWORD,
            "${test-only-unresolved-secret}"})
    void rejectsAnUnexpectedPropertiesDatabaseUsernameWithoutConnecting(String username) {
        MockEnvironment environment = configuredEnvironment()
                .withProperty("user-info.properties-datasource.username", username);

        Throwable failure = catchThrowable(() -> processor.postProcessEnvironment(environment, application));

        assertSanitized(failure);
        verifyNoInteractions(portReader);
    }

    @ParameterizedTest
    @CsvSource({
            "http://localhost:8760/eureka-server/eureka/, http://localhost:9123/eureka-server/eureka/",
            "https://discovery.example.test:443/custom/eureka/, https://discovery.example.test:9123/custom/eureka/",
            "https://Discovery.Example.test/custom/eureka, https://Discovery.Example.test:9123/custom/eureka",
            "HTTPS://discovery.example.test/, HTTPS://discovery.example.test:9123/",
            "http://[::1]:8760/eureka-server/eureka/, http://[::1]:9123/eureka-server/eureka/",
            "https://[2001:db8::1]/proxy/eureka/, https://[2001:db8::1]:9123/proxy/eureka/",
            "https://discovery.example.test/proxy%2Feureka/%25/, https://discovery.example.test:9123/proxy%2Feureka/%25/"
    })
    void replacesOnlyThePortPreservingSchemeHostnameAndRawContextPath(String input, String expected) {
        MockEnvironment environment = configuredEnvironment().withProperty(ENDPOINT, input);
        when(portReader.readPort(DEFAULT_DATABASE_URL, PASSWORD)).thenReturn(9123);

        processor.postProcessEnvironment(environment, application);

        assertThat(boundClientEndpoint(environment)).isEqualTo(expected);
    }

    @Test
    void replacesThePortResolvedThroughTheEurekaUrlEnvironmentPlaceholder() {
        MockEnvironment environment = configuredEnvironment()
                .withProperty("EUREKA_URL", "https://tunnel.example.test:443/discovery/eureka/")
                .withProperty(ENDPOINT, "${EUREKA_URL:http://localhost/eureka-server/eureka/}");
        when(portReader.readPort(DEFAULT_DATABASE_URL, PASSWORD)).thenReturn(9123);

        processor.postProcessEnvironment(environment, application);

        assertThat(boundClientEndpoint(environment)).isEqualTo("https://tunnel.example.test:9123/discovery/eureka/");
    }

    @ParameterizedTest
    @ValueSource(strings = {"eureka.client.serviceUrl.defaultZone", "eureka.client.service-url.defaultZone"})
    void honorsTheEffectiveCommandLineEndpointAliasButMakesItsPortDatabaseAuthoritative(String cliProperty) {
        MockEnvironment environment = configuredEnvironment()
                .withProperty(ENDPOINT, "http://yaml.example.test:8760/yaml/eureka/")
                .withProperty("eureka.client.service-url.backupZone", "https://backup.example.test:443/eureka/");
        environment.getPropertySources().addFirst(new SimpleCommandLinePropertySource(
                "--" + cliProperty + "=https://cli.example.test:9999/cli/eureka/",
                "--server.port=8770"));
        when(portReader.readPort(DEFAULT_DATABASE_URL, PASSWORD)).thenReturn(9123);

        processor.postProcessEnvironment(environment, application);

        EurekaClientConfigBean client = boundClient(environment);
        assertThat(client.getServiceUrl().get("defaultZone")).isEqualTo("https://cli.example.test:9123/cli/eureka/");
        assertThat(client.getServiceUrl().get("backupZone")).isEqualTo("https://backup.example.test:443/eureka/");
        assertThat(environment.getProperty("server.port")).isEqualTo("8770");
        assertThat(environment.getPropertySources().iterator().next().getName())
                .isEqualTo(EurekaDatabasePortEnvironmentPostProcessor.PROPERTY_SOURCE);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", "http://localhost", "//localhost/eureka/", "/eureka/", "ftp://localhost/eureka/",
            "http:///eureka/", "http:localhost/eureka/", "http://localhost:/eureka/",
            "http://localhost:65536/eureka/", "http://localhost:abc/eureka/", "http://localhost/path with spaces/",
            "http://localhost/%broken/", "http://localhost/eureka/#test-only-database-password",
            "http://localhost/eureka/?password=test-only-database-password",
            "http://user:test-only-database-password@localhost/eureka/",
            "http://localhost/eureka/,http://other.example.test/eureka/",
            "http://localhost/path,part/eureka/", "${test-only-unresolved-secret}"
    })
    void rejectsInvalidOrSecretBearingEndpointsBeforeConnectingWithoutEchoingTheirContents(String endpoint) {
        MockEnvironment environment = configuredEnvironment().withProperty(ENDPOINT, endpoint);

        Throwable failure = catchThrowable(() -> processor.postProcessEnvironment(environment, application));

        assertSanitized(failure);
        verifyNoInteractions(portReader);
        assertNoDatabasePropertySource(environment);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "${test-only-unresolved-secret}"})
    void rejectsMissingBlankOrUnresolvedPasswordsWithoutLeakingTheirContents(String password) {
        MockEnvironment environment = new MockEnvironment();
        if (password != null) {
            environment.withProperty("user-info.properties-datasource.password", password);
        }

        Throwable failure = catchThrowable(() -> processor.postProcessEnvironment(environment, application));

        assertSanitized(failure);
        verifyNoInteractions(portReader);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", "https://database.example.test/test-only-database-password",
            "jdbc:oracle:thin:other-user/test-only-database-password@//database.example.test:1521/FREEPDB1",
            "${test-only-unresolved-secret}"
    })
    void rejectsInvalidOrCredentialBearingDatabaseUrls(String databaseUrl) {
        MockEnvironment environment = configuredEnvironment().withProperty("user-info.properties-datasource.url", databaseUrl);

        Throwable failure = catchThrowable(() -> processor.postProcessEnvironment(environment, application));

        assertSanitized(failure);
        verifyNoInteractions(portReader);
    }

    @ParameterizedTest
    @ValueSource(strings = {"eureka.client.enabled", "user-info.eureka.database-port.enabled"})
    void rejectsInvalidEnableFlagsWithoutLeakingTheirContents(String flag) {
        MockEnvironment environment = configuredEnvironment().withProperty(flag, PASSWORD);

        Throwable failure = catchThrowable(() -> processor.postProcessEnvironment(environment, application));

        assertSanitized(failure);
        verifyNoInteractions(portReader);
    }

    @Test
    void databaseFailureAbortsInsteadOfFallingBackToTheConfiguredEndpointPort() {
        MockEnvironment environment = configuredEnvironment().withProperty(ENDPOINT, "http://localhost:8760/eureka/");
        IllegalStateException databaseFailure = new IllegalStateException("Sanitized database lookup failure");
        when(portReader.readPort(DEFAULT_DATABASE_URL, PASSWORD)).thenThrow(databaseFailure);

        Throwable failure = catchThrowable(() -> processor.postProcessEnvironment(environment, application));

        assertThat(failure).isSameAs(databaseFailure);
        assertNoDatabasePropertySource(environment);
    }

    private static MockEnvironment configuredEnvironment() {
        return new MockEnvironment().withProperty("user-info.properties-datasource.password", PASSWORD);
    }

    private static String boundClientEndpoint(MockEnvironment environment) {
        return boundClient(environment).getServiceUrl().get("defaultZone");
    }

    private static EurekaClientConfigBean boundClient(MockEnvironment environment) {
        return Binder.get(environment).bind("eureka.client", Bindable.of(EurekaClientConfigBean.class)).get();
    }

    private static void assertNoDatabasePropertySource(MockEnvironment environment) {
        assertThat(environment.getPropertySources()
                .contains(EurekaDatabasePortEnvironmentPostProcessor.PROPERTY_SOURCE)).isFalse();
    }

    private static void assertSanitized(Throwable failure) {
        assertThat(failure).isInstanceOf(IllegalStateException.class).hasNoCause();
        assertThat(failure.getMessage()).doesNotContain(PASSWORD, UNRESOLVED_SECRET);
        assertThat(failure.getSuppressed()).isEmpty();
    }
}
