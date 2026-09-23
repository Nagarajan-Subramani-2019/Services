package com.oxygenraj.userui.config;

import java.net.URI;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("user-info.client")
public record UserInfoClientProperties(String mode, String baseUrl, String serviceId, String contextPath) {
    public UserInfoClientProperties {
        mode = mode == null ? "direct" : mode.strip().toLowerCase(Locale.ROOT);
        baseUrl = baseUrl == null ? "http://127.0.0.1:8771/userInfoServices" : baseUrl.strip();
        serviceId = serviceId == null ? "userInfoServices" : serviceId.strip();
        contextPath = contextPath == null ? "/userInfoServices" : contextPath.strip();
        if (!mode.equals("direct") && !mode.equals("discovery")) {
            throw new IllegalArgumentException("user-info.client.mode must be direct or discovery");
        }
        if (serviceId.isBlank() || !serviceId.matches("[A-Za-z0-9._-]{1,128}")) {
            throw new IllegalArgumentException("Set a valid user-info.client.service-id");
        }
        if (!contextPath.matches("(/[A-Za-z0-9_-]+)*/*")) {
            throw new IllegalArgumentException("user-info.client.context-path must be a simple absolute URL path");
        }
        validateBaseUri(baseUrl);
        while (baseUrl.endsWith("/")) baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        while (contextPath.endsWith("/")) contextPath = contextPath.substring(0, contextPath.length() - 1);
    }

    public static URI validateBaseUri(String value) {
        try {
            URI uri = URI.create(value);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getHost().isBlank()
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || uri.getPort() == 0 || uri.getPort() < -1 || uri.getPort() > 65535) {
                throw new IllegalArgumentException();
            }
            return uri;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("User-info base URL must be HTTP(S), with a host and no credentials, query or fragment");
        }
    }
}
