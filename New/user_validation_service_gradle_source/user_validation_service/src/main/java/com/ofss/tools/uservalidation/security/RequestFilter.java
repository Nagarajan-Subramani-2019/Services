package com.ofss.tools.uservalidation.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestFilter extends OncePerRequestFilter {
    private static final Logger LOG = LoggerFactory.getLogger(RequestFilter.class);
    private final byte[] keyDigest;

    public RequestFilter(@Value("${service.security.api-key}") String apiKey) {
        if (apiKey == null || apiKey.isBlank() || apiKey.length() < 32) {
            throw new IllegalStateException("Set SERVICE_API_KEY to a random key of at least 32 characters.");
        }
        keyDigest = digest(apiKey);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String requestId = UUID.randomUUID().toString();
        long started = System.nanoTime();
        MDC.put("requestId", requestId);
        response.setHeader("X-Request-ID", requestId);
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        try {
            // Protect profile management and statistics; credential validation is public.
            String path = request.getServletPath();
            if (path.startsWith("/api/v1/") && !path.equals("/api/v1/login")
                    && !path.equals("/api/v1/health")) {
                String supplied = request.getHeader("X-API-Key");
                if (supplied == null || supplied.length() > 1024
                        || !MessageDigest.isEqual(keyDigest, digest(supplied))) {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType("application/json");
                    response.getWriter().write("{\"status\":401,\"code\":\"UNAUTHORIZED\","
                            + "\"message\":\"A valid X-API-Key header is required.\","
                            + "\"requestId\":\"" + requestId + "\"}");
                    return;
                }
            }
            chain.doFilter(request, response);
        } finally {
            LOG.info("HTTP method={} path={} status={} durationMs={}", request.getMethod(),
                    logRoute(request), response.getStatus(),
                    (System.nanoTime() - started) / 1_000_000);
            MDC.remove("requestId");
        }
    }

    private static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String logRoute(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (pattern != null) {
            return pattern.toString();
        }
        return request.getServletPath().startsWith("/api/v1/") ? "/api/v1/**" : "unmapped";
    }
}
