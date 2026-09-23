package com.oxygenraj.userinfo.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.env.SimpleCommandLinePropertySource;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class PropertiesDatabaseSettingsTests {

    private static final String PASSWORD = "test-only-properties-database-password";
    private static final String URL = "jdbc:oracle:thin:@//properties.example.test:1521/FREEPDB1";
    private static final String PREFIX = PropertiesDatabaseSettings.PREFIX;

    @Test
    void resolvesOnlyTheDedicatedPropertiesDatasource() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("EUREKA_DB_URL", URL)
                .withProperty("EUREKA_DB_PASSWORD", PASSWORD)
                .withProperty(PREFIX + "url", "${EUREKA_DB_URL}")
                .withProperty(PREFIX + "username", "EUREKA_DB")
                .withProperty(PREFIX + "password", "${EUREKA_DB_PASSWORD}")
                .withProperty("spring.datasource.url", "${test-only-unresolved-crud-url}")
                .withProperty("spring.datasource.password", "${test-only-unresolved-crud-password}");

        PropertiesDatabaseSettings settings = PropertiesDatabaseSettings.load(environment);

        assertThat(settings.url()).isEqualTo(URL);
        assertThat(settings.password()).isEqualTo(PASSWORD);
        assertThat(settings.toString()).doesNotContain(PASSWORD, URL);
    }

    @Test
    void supportsRelaxedDedicatedPropertyAliasesAndCommandLinePriority() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty(PREFIX + "url", PropertiesDatabaseSettings.DEFAULT_URL)
                .withProperty(PREFIX + "password", PASSWORD);
        environment.getPropertySources().addFirst(new SimpleCommandLinePropertySource(
                "--user-info.propertiesDatasource.url=" + URL,
                "--user-info.propertiesDatasource.username=EUREKA_DB"));

        PropertiesDatabaseSettings settings = PropertiesDatabaseSettings.load(environment);

        assertThat(settings.url()).isEqualTo(URL);
        assertThat(settings.password()).isEqualTo(PASSWORD);
    }

    @Test
    void missingDedicatedPasswordDoesNotUseCrudOrUnmappedEnvironmentCredentials() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.datasource.username", "USER_INFO_SCHEMA")
                .withProperty("spring.datasource.password", PASSWORD)
                .withProperty("USER_INFO_DB_PASSWORD", PASSWORD)
                .withProperty("EUREKA_DB_PASSWORD", PASSWORD);

        Throwable failure = catchThrowable(() -> PropertiesDatabaseSettings.load(environment));

        assertSanitized(failure);
        assertThat(failure).hasMessageContaining("EUREKA_DB_PASSWORD");
    }

    @ParameterizedTest
    @ValueSource(strings = {"jdbc:oracle:thin:@", "jdbc:oracle:thin:@   ", " ",
            "jdbc:oracle:thin:EUREKA_DB/test-only-properties-database-password@//host:1521/FREEPDB1"})
    void rejectsIncompleteOrCredentialBearingJdbcAddresses(String url) {
        MockEnvironment environment = new MockEnvironment()
                .withProperty(PREFIX + "url", url)
                .withProperty(PREFIX + "password", PASSWORD);

        Throwable failure = catchThrowable(() -> PropertiesDatabaseSettings.load(environment));

        assertSanitized(failure);
        assertThat(failure).hasMessageContaining("EUREKA_DB_URL");
    }

    @ParameterizedTest
    @ValueSource(strings = {"url", "username", "password"})
    void sanitizesUnresolvedPlaceholdersInEveryDedicatedSetting(String setting) {
        MockEnvironment environment = new MockEnvironment()
                .withProperty(PREFIX + "password", PASSWORD)
                .withProperty(PREFIX + setting, "${test-only-properties-database-password}");

        Throwable failure = catchThrowable(() -> PropertiesDatabaseSettings.load(environment));

        assertSanitized(failure);
    }

    private static void assertSanitized(Throwable failure) {
        assertThat(failure).isInstanceOf(IllegalStateException.class).hasNoCause();
        assertThat(failure.getMessage()).doesNotContain(PASSWORD, URL);
        assertThat(failure.getSuppressed()).isEmpty();
    }
}
