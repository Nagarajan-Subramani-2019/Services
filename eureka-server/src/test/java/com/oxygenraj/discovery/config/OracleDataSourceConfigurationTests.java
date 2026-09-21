package com.oxygenraj.discovery.config;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class OracleDataSourceConfigurationTests {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(OracleDataSourceConfiguration.class);

    @Test
    void doesNotCreateADataSourceWithoutTheOracleProfile() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(DataSource.class);
        });
    }

    @Test
    void bindsOracleAndPoolSettingsOnlyWhenTheProfileIsActive() {
        oracleContextRunner()
                .withPropertyValues(
                        "spring.datasource.password=test-only-database-password",
                        "spring.datasource.hikari.maximum-pool-size=3",
                        "spring.datasource.hikari.minimum-idle=0",
                        "spring.datasource.hikari.connection-timeout=1000")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(DataSource.class);

                    HikariDataSource dataSource = context.getBean(HikariDataSource.class);
                    assertThat(dataSource.getJdbcUrl()).isEqualTo("jdbc:oracle:thin:@//localhost:1521/FREEPDB1");
                    assertThat(dataSource.getUsername()).isEqualTo("EUREKA_DB");
                    assertThat(dataSource.getPassword()).isEqualTo("test-only-database-password");
                    assertThat(dataSource.getDriverClassName()).isEqualTo("oracle.jdbc.OracleDriver");
                    assertThat(dataSource.getMaximumPoolSize()).isEqualTo(3);
                    assertThat(dataSource.getMinimumIdle()).isZero();
                    assertThat(dataSource.getConnectionTimeout()).isEqualTo(1000);
                    // Binding a profile must not attempt a connection to a real database.
                    assertThat(dataSource.isRunning()).isFalse();
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "spring.datasource.username=SYSTEM",
            "spring.datasource.hikari.username=SYSTEM",
            "spring.datasource.hikari.username=eureka_db"
    })
    void rejectsUsernameOverridesInEitherPropertyGroup(String override) {
        oracleContextRunner()
                .withPropertyValues("spring.datasource.password=test-only-database-password", override)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage("The Eureka Oracle datasource must use EUREKA_DB.");
                });
    }

    @Test
    void rejectsAMissingDatabasePassword() {
        oracleContextRunner().run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseMessage(
                    "Set an explicit, non-blank Eureka database password; unresolved placeholders are not allowed.");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "   ",
            "${__UNSET_EUREKA_DB_PASSWORD_FOR_TEST__}"
    })
    void rejectsBlankOrUnresolvedDatabasePasswords(String password) {
        oracleContextRunner()
                .withPropertyValues("spring.datasource.password=" + password)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseMessage(
                            "Set an explicit, non-blank Eureka database password; unresolved placeholders are not allowed.");
                });
    }

    @Test
    void rejectsAnUnresolvedHikariPasswordOverride() {
        oracleContextRunner()
                .withPropertyValues(
                        "spring.datasource.password=test-only-database-password",
                        "spring.datasource.hikari.password=${__UNSET_EUREKA_DB_PASSWORD_FOR_TEST__}")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseMessage(
                            "Set an explicit, non-blank Eureka database password; unresolved placeholders are not allowed.");
                });
    }

    private ApplicationContextRunner oracleContextRunner() {
        return contextRunner
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("oracle"))
                .withPropertyValues(
                        "spring.datasource.url=jdbc:oracle:thin:@//localhost:1521/FREEPDB1",
                        "spring.datasource.username=EUREKA_DB",
                        "spring.datasource.driver-class-name=oracle.jdbc.OracleDriver");
    }
}
