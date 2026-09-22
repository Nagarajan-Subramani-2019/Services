package com.oxygenraj.userinfo.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/** Resolves the discovery server's port independently of this service's listening port. */
public class EurekaDatabasePortEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String PREFIX = "user-info.eureka.database-port.";
    static final String PROPERTY_SOURCE = "eurekaDatabaseEndpoint";
    static final String ENDPOINT_PROPERTY = "eureka.client.service-url.defaultZone";
    static final String DEFAULT_ENDPOINT = "http://localhost/eureka-server/eureka/";
    static final String DEFAULT_DATABASE_URL = "jdbc:oracle:thin:@//127.0.0.1:11521/FREEPDB1";

    private final OracleEurekaPortReader portReader;

    public EurekaDatabasePortEnvironmentPostProcessor() {
        this(new OracleEurekaPortReader());
    }

    EurekaDatabasePortEnvironmentPostProcessor(OracleEurekaPortReader portReader) {
        this.portReader = portReader;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String databaseUrl;
        String password;
        URI endpoint;
        try {
            // Disabled discovery must not inspect credentials, endpoint settings, or lookup flags.
            if (!environment.getProperty("eureka.client.enabled", Boolean.class, true)) {
                return;
            }
            if (!environment.getProperty(PREFIX + "enabled", Boolean.class, true)) {
                return;
            }
            databaseUrl = environment.getProperty("spring.datasource.url", DEFAULT_DATABASE_URL);
            password = environment.getProperty("spring.datasource.password", "");
            // Binder observes source priority across serviceUrl and service-url spellings.
            Map<String, String> serviceUrls = Binder.get(environment)
                    .bind("eureka.client.service-url", Bindable.mapOf(String.class, String.class))
                    .orElseGet(Map::of);
            endpoint = parseEndpoint(serviceUrls.getOrDefault("defaultZone", DEFAULT_ENDPOINT));
        }
        catch (RuntimeException exception) {
            // Binding and placeholder exceptions can include externally supplied secrets.
            throw new IllegalStateException("Invalid Eureka database port configuration. Resolve USER_INFO_DB_URL, "
                    + "USER_INFO_DB_PASSWORD and EUREKA_URL, and set EUREKA_CLIENT_ENABLED and "
                    + "USER_INFO_EUREKA_DB_PORT_ENABLED to true or false. EUREKA_URL must be one HTTP(S) URL "
                    + "with a hostname and path, without credentials, query parameters or a fragment.");
        }

        if (password.isBlank() || password.contains("${")) {
            throw new IllegalStateException("Set USER_INFO_DB_PASSWORD before resolving the Eureka server port.");
        }
        if (!databaseUrl.startsWith("jdbc:oracle:thin:@") || databaseUrl.contains("${")) {
            throw new IllegalStateException(
                    "USER_INFO_DB_URL must be an Oracle Thin JDBC address without embedded credentials.");
        }

        int port = portReader.readPort(databaseUrl, password);
        String hostname = endpoint.getHost();
        if (hostname.indexOf(':') >= 0 && !hostname.startsWith("[")) {
            hostname = "[" + hostname + "]";
        }
        // Retain raw path escapes: decoding/re-encoding can turn an escaped slash into a path separator.
        String databaseEndpoint = endpoint.getScheme() + "://" + hostname + ":" + port + endpoint.getRawPath();
        environment.getPropertySources().addFirst(
                new MapPropertySource(PROPERTY_SOURCE, Map.of(ENDPOINT_PROPERTY, databaseEndpoint)));
    }

    private static URI parseEndpoint(String value) {
        if (value == null || value.contains(",") || value.contains("${")) {
            throw new IllegalArgumentException("Invalid Eureka endpoint");
        }
        URI endpoint;
        try {
            endpoint = new URI(value).parseServerAuthority();
        }
        catch (URISyntaxException exception) {
            throw new IllegalArgumentException("Invalid Eureka endpoint");
        }
        String scheme = endpoint.getScheme();
        if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                || endpoint.getHost() == null || endpoint.getRawUserInfo() != null
                || endpoint.getRawQuery() != null || endpoint.getRawFragment() != null
                || endpoint.getRawPath() == null || !endpoint.getRawPath().startsWith("/")
                || endpoint.getPort() > 65535 || endpoint.getRawAuthority().endsWith(":")) {
            throw new IllegalArgumentException("Invalid Eureka endpoint");
        }
        return endpoint;
    }

    @Override
    public int getOrder() {
        return ConfigDataEnvironmentPostProcessor.ORDER + 2;
    }
}
