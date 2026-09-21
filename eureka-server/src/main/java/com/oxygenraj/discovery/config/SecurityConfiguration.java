package com.oxygenraj.discovery.config;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.security.autoconfigure.SecurityProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, SecurityProperties properties) throws Exception {
        SecurityProperties.User user = properties.getUser();
        String password = user.getPassword();
        if (user.isPasswordGenerated() || password == null || password.isBlank() || password.contains("${")) {
            throw new IllegalStateException(
                    "Set an explicit, non-blank Eureka password; unresolved placeholders are not allowed.");
        }

        http.authorizeHttpRequests(authorize -> authorize
                .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                .anyRequest().authenticated())
            .httpBasic(Customizer.withDefaults())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            // Set the status directly so a CSRF denial does not redispatch to the protected /error endpoint.
            .exceptionHandling(exceptions -> exceptions.accessDeniedHandler(
                    (request, response, exception) -> response.setStatus(HttpServletResponse.SC_FORBIDDEN)))
            .csrf(csrf -> csrf.ignoringRequestMatchers("/eureka/**"));

        return http.build();
    }
}
