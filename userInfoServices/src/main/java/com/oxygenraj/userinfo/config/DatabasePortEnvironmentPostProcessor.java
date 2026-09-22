package com.oxygenraj.userinfo.config;

import java.util.Map;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.convert.ConversionException;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/** Resolves an optional database port before the web server is constructed. */
public class DatabasePortEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String PREFIX = "user-info.database-port.";
    static final String PROPERTY_SOURCE = "userInfoDatabasePort";
    static final String DEFAULT_URL = "jdbc:oracle:thin:@//127.0.0.1:11521/FREEPDB1";

    private final OraclePortReader portReader;

    public DatabasePortEnvironmentPostProcessor() {
        this(new OraclePortReader());
    }

    DatabasePortEnvironmentPostProcessor(OraclePortReader portReader) {
        this.portReader = portReader;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String url;
        String password;
        try {
            if (!environment.getProperty(PREFIX + "enabled", Boolean.class, false)) {
                return;
            }
            // Use the same configuration as the application's CRUD datasource.
            url = environment.getProperty("spring.datasource.url", DEFAULT_URL);
            password = environment.getProperty("spring.datasource.password", "");
        }
        catch (IllegalArgumentException | ConversionException exception) {
            // Placeholder and conversion failures can echo externally supplied secrets.
            throw new IllegalStateException("Invalid database port configuration. Resolve USER_INFO_DB_URL and "
                    + "USER_INFO_DB_PASSWORD, and set USER_INFO_DB_PORT_ENABLED to true or false.");
        }

        if (password.isBlank() || password.contains("${")) {
            throw new IllegalStateException("Set USER_INFO_DB_PASSWORD before starting userInfoServices.");
        }
        if (!url.startsWith("jdbc:oracle:thin:@") || url.contains("${")) {
            throw new IllegalStateException(
                    "USER_INFO_DB_URL must be an Oracle Thin JDBC address without embedded credentials.");
        }

        int port = portReader.readPort(url, password);
        // When enabled, the database wins over USER_INFO_PORT and --server.port.
        environment.getPropertySources().addFirst(
                new MapPropertySource(PROPERTY_SOURCE, Map.of("server.port", port)));
    }

    @Override
    public int getOrder() {
        return ConfigDataEnvironmentPostProcessor.ORDER + 1;
    }
}
