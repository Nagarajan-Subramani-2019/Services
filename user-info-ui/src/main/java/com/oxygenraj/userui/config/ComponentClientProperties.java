package com.oxygenraj.userui.config;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ui.component-client")
public record ComponentClientProperties(String mode, List<String> allowedOrigins) {
    public ComponentClientProperties {
        mode = mode == null ? "direct" : mode.strip().toLowerCase(Locale.ROOT);
        if (!mode.equals("direct") && !mode.equals("discovery")) throw new IllegalArgumentException("Component mode must be direct or discovery");
        if (allowedOrigins == null) allowedOrigins = List.of("http://127.0.0.1:8782", "http://localhost:8782");
        if (allowedOrigins.isEmpty()) throw new IllegalArgumentException("Configure at least one permitted component origin");
        allowedOrigins = allowedOrigins.stream().map(value -> {
            URI uri = UserInfoClientProperties.validateBaseUri(value.strip());
            if (uri.getRawPath() != null && !uri.getRawPath().isEmpty() && !uri.getRawPath().equals("/")) {
                throw new IllegalArgumentException("Component allowed origins must not contain a context path");
            }
            return origin(uri);
        }).distinct().toList();
    }

    public boolean permits(URI uri) { return allowedOrigins.contains(origin(uri)); }

    public static String origin(URI uri) {
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        int port = uri.getPort() == -1 ? (scheme.equals("https") ? 443 : 80) : uri.getPort();
        return scheme + "://" + host + ":" + port;
    }
}
