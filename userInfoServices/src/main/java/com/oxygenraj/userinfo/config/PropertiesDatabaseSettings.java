package com.oxygenraj.userinfo.config;

import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.ConfigurableEnvironment;

/** Separate credentials for startup configuration reads; never falls back to the CRUD datasource. */
final class PropertiesDatabaseSettings {

    static final String PREFIX = "user-info.properties-datasource.";
    static final String DEFAULT_URL = "jdbc:oracle:thin:@//127.0.0.1:11521/FREEPDB1";

    private final String url;
    private final String password;

    private PropertiesDatabaseSettings(String url, String password) {
        this.url = url;
        this.password = password;
    }

    static PropertiesDatabaseSettings load(ConfigurableEnvironment environment) {
        String url;
        String username;
        String password;
        try {
            Binder binder = Binder.get(environment);
            url = binder.bind(PREFIX + "url", String.class).orElse(DEFAULT_URL);
            username = binder.bind(PREFIX + "username", String.class).orElse("EUREKA_DB");
            password = binder.bind(PREFIX + "password", String.class).orElse("");
        }
        catch (RuntimeException exception) {
            // Placeholder/binding failures can disclose supplied values and nested exception details.
            throw new IllegalStateException("Invalid properties database configuration. Resolve EUREKA_DB_URL and "
                    + "EUREKA_DB_PASSWORD, and set user-info.properties-datasource.username to EUREKA_DB.");
        }

        if (!"EUREKA_DB".equals(username)) {
            throw new IllegalStateException("user-info.properties-datasource.username must be EUREKA_DB.");
        }
        if (password.isBlank() || password.contains("${")) {
            throw new IllegalStateException("Set EUREKA_DB_PASSWORD before enabling database port lookups.");
        }
        String jdbcPrefix = "jdbc:oracle:thin:@";
        if (!url.startsWith(jdbcPrefix) || url.substring(jdbcPrefix.length()).isBlank() || url.contains("${")) {
            throw new IllegalStateException(
                    "EUREKA_DB_URL must be an Oracle Thin JDBC address without embedded credentials.");
        }
        return new PropertiesDatabaseSettings(url, password);
    }

    String url() {
        return url;
    }

    String password() {
        return password;
    }

    @Override
    public String toString() {
        return "PropertiesDatabaseSettings[username=EUREKA_DB, credentials=redacted]";
    }
}
