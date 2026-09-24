package com.oxygenraj.transactionui;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("component.backends")
public record BackendProperties(Endpoint users, Endpoint transactions) {
    public record Endpoint(String mode, String baseUrl, String serviceId, String contextPath) {
        public Endpoint {
            if (!"direct".equals(mode) && !"discovery".equals(mode)) throw new IllegalArgumentException("Backend mode must be direct or discovery");
            validateUri(URI.create(baseUrl));
            if (baseUrl.endsWith("/") || serviceId == null || !serviceId.matches("[A-Za-z0-9_-]+")
                    || contextPath == null || !contextPath.matches("/[A-Za-z0-9_-]+")) {
                throw new IllegalArgumentException("Invalid backend configuration");
            }
        }
    }
    public static void validateUri(URI uri) {
        if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || uri.getPort() == 0 || uri.getPort() > 65535) throw new IllegalArgumentException("Invalid backend URL");
    }
}
