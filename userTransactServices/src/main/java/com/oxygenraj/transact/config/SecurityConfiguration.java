package com.oxygenraj.transact.config;

import jakarta.servlet.DispatcherType;
import java.time.Instant;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import tools.jackson.databind.ObjectMapper;

/** The requested transaction endpoints are public and do not use cookie/session authentication. */
@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

    @Bean
    AuthenticationManager authenticationManager() {
        // Suppress Boot's generated development user: this API has no login contract.
        return authentication -> {
            throw new BadCredentialsException("Authentication is not configured");
        };
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, AuthenticationManager manager,
            ObjectMapper mapper) throws Exception {
        http.authenticationManager(manager)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .csrf(csrf -> csrf.ignoringRequestMatchers("/transactions", "/transactions/*"))
                .authorizeHttpRequests(authorize -> authorize
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.GET, "/transactions", "/transactions/*", "/actuator/health").permitAll()
                        .requestMatchers(HttpMethod.POST, "/transactions").permitAll()
                        .requestMatchers(HttpMethod.PUT, "/transactions/*").permitAll()
                        .anyRequest().denyAll())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> {
                            response.setStatus(403);
                            response.setContentType("application/json");
                            mapper.writeValue(response.getWriter(), forbidden());
                        })
                        .accessDeniedHandler((request, response, exception) -> {
                            response.setStatus(403);
                            response.setContentType("application/json");
                            mapper.writeValue(response.getWriter(), forbidden());
                        }));
        return http.build();
    }

    private static Map<String, Object> forbidden() {
        return Map.of("code", "FORBIDDEN", "message", "Access denied",
                "errors", Map.of(), "timestamp", Instant.now());
    }
}
