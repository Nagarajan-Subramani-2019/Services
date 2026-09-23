package com.oxygenraj.userinfo.config;

import com.oxygenraj.userinfo.repository.UserRepository;
import com.oxygenraj.userinfo.service.UserService;
import com.oxygenraj.userinfo.api.ApiExceptionHandler.ApiError;
import jakarta.servlet.DispatcherType;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfiguration {
    @Bean
    PasswordEncoder passwordEncoder() {
        var bcrypt = new BCryptPasswordEncoder(12);
        return new PasswordEncoder() {
            @Override
            public String encode(CharSequence rawPassword) { return bcrypt.encode(rawPassword); }

            @Override
            public boolean matches(CharSequence rawPassword, String encodedPassword) {
                // Apply BCrypt's byte limit to Basic auth and the JSON login path as well.
                if (rawPassword == null || rawPassword.length() > 72
                        || rawPassword.toString().getBytes(StandardCharsets.UTF_8).length > 72) {
                    return false;
                }
                return bcrypt.matches(rawPassword, encodedPassword);
            }

            @Override
            public boolean upgradeEncoding(String encodedPassword) { return bcrypt.upgradeEncoding(encodedPassword); }
        };
    }

    @Bean
    UserDetailsService userDetailsService(UserRepository repository) {
        return name -> {
            var account = repository.findByUsername(UserService.normalize(name))
                    .orElseThrow(() -> new UsernameNotFoundException("Invalid username or password"));
            return User.withUsername(account.username()).password(account.passwordHash())
                    .roles(account.role()).disabled(!account.enabled()).build();
        };
    }

    @Bean
    AuthenticationManager authenticationManager(UserDetailsService users, PasswordEncoder encoder) {
        var provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(encoder);
        return new ProviderManager(provider);
    }

    @Bean
    @Order(1)
    SecurityFilterChain publicUserDetailsSecurityFilterChain(HttpSecurity http) throws Exception {
        // Reads are intentionally public until a separate entitlement service is added.
        // Do not install HTTP Basic here: even stale credentials must not gate a read.
        http.securityMatchers(matchers -> matchers
                        .requestMatchers(HttpMethod.GET, "/userdetails", "/userdetails/*"))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll());
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain securityFilterChain(HttpSecurity http, AuthenticationManager manager, ObjectMapper mapper) throws Exception {
        http.authenticationManager(manager)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                // These public JSON-only POSTs do not rely on session/cookie authentication.
                .csrf(csrf -> csrf.ignoringRequestMatchers("/userdetails", "/authuserdetails"))
                .authorizeHttpRequests(authorize -> authorize
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/userdetails", "/authuserdetails").permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/info").hasRole("ADMIN")
                        .anyRequest().denyAll())
                .httpBasic(basic -> basic.authenticationEntryPoint((request, response, exception) -> {
                    response.setStatus(401);
                    response.setHeader("WWW-Authenticate", "Basic realm=\"userInfoServices\"");
                    response.setContentType("application/json");
                    mapper.writeValue(response.getWriter(), new ApiError("UNAUTHORIZED", "Valid credentials are required", Map.of(), Instant.now()));
                }))
                .exceptionHandling(errors -> errors.accessDeniedHandler((request, response, exception) -> {
                    response.setStatus(403);
                    response.setContentType("application/json");
                    mapper.writeValue(response.getWriter(), new ApiError("FORBIDDEN", "Access denied", Map.of(), Instant.now()));
                }));
        return http.build();
    }
}
