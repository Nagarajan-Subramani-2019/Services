package com.oxygenraj.initial;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class InitialDataSourceConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class))
            .withUserConfiguration(InitialDataSourceConfiguration.class)
            .withPropertyValues(
                    "spring.datasource.url=jdbc:oracle:thin:@//127.0.0.1:1/TEST_ONLY",
                    "spring.datasource.username=INITIAL_DB",
                    "spring.datasource.driver-class-name=oracle.jdbc.OracleDriver",
                    "spring.datasource.hikari.minimum-idle=0",
                    "spring.datasource.hikari.initialization-fail-timeout=-1");

    @Test
    void acceptsInitialDbCredentialsWithoutOpeningThePool() {
        contextRunner.withPropertyValues("spring.datasource.password=test-only")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    HikariDataSource dataSource = context.getBean(HikariDataSource.class);
                    assertThat(dataSource.getUsername()).isEqualTo("INITIAL_DB");
                    assertThat(dataSource.isRunning()).isFalse();
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"SYS", "SYSTEM", "EUREKA_DB", "initial_db", ""})
    void rejectsAnUnexpectedDatasourceUsername(String username) {
        contextRunner.withPropertyValues(
                        "spring.datasource.username=" + username,
                        "spring.datasource.password=test-only")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseMessage(
                            "The initial-service datasource must use INITIAL_DB.");
                });
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "${MISSING_INITIAL_TEST_PASSWORD}"})
    void rejectsMissingBlankOrUnresolvedPasswords(String password) {
        ApplicationContextRunner runner = password == null ? contextRunner
                : contextRunner.withPropertyValues("spring.datasource.password=" + password);
        runner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseMessage(
                    "A database password must be supplied for INITIAL_DB.");
        });
    }

    @Test
    void rejectsAHikariUsernameOverrideAfterBinding() {
        contextRunner.withPropertyValues(
                        "spring.datasource.password=test-only",
                        "spring.datasource.hikari.username=SYS")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseMessage(
                            "The initial-service datasource must use INITIAL_DB.");
                });
    }

    @Test
    void rejectsABlankHikariPasswordOverrideAfterBinding() {
        contextRunner.withPropertyValues(
                        "spring.datasource.password=test-only",
                        "spring.datasource.hikari.password=")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseMessage(
                            "A database password must be supplied for INITIAL_DB.");
                });
    }
}
