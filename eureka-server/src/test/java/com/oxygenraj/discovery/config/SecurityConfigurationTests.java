package com.oxygenraj.discovery.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.util.PlaceholderResolutionException;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityConfigurationTests {

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    SecurityAutoConfiguration.class,
                    UserDetailsServiceAutoConfiguration.class,
                    ServletWebSecurityAutoConfiguration.class))
            .withUserConfiguration(SecurityConfiguration.class)
            .withPropertyValues("spring.security.user.name=eureka");

    @Test
    void acceptsAnExplicitPassword() {
        contextRunner
                .withPropertyValues("spring.security.user.password=test-only-password")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(SecurityFilterChain.class);
                });
    }

    @Test
    void rejectsAnAbsentPasswordInsteadOfUsingAGeneratedPassword() {
        contextRunner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseMessage(
                    "Set an explicit, non-blank Eureka password; unresolved placeholders are not allowed.");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void rejectsBlankPasswords(String password) {
        contextRunner
                .withPropertyValues("spring.security.user.password=" + password)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseMessage(
                            "Set an explicit, non-blank Eureka password; unresolved placeholders are not allowed.");
                });
    }

    @Test
    void rejectsAnUnresolvedPasswordBeforeTheSecurityChainIsCreated() {
        contextRunner
                .withPropertyValues("spring.security.user.password=${__UNSET_EUREKA_PASSWORD_FOR_TEST__}")
                .run(context -> {
                    assertThat(context).hasFailed();
                    // Boot's user-details condition resolves this property before our bean validation runs.
                    assertThat(context.getStartupFailure()).rootCause()
                            .isInstanceOf(PlaceholderResolutionException.class)
                            .hasMessageContaining("__UNSET_EUREKA_PASSWORD_FOR_TEST__");
                });
    }
}
