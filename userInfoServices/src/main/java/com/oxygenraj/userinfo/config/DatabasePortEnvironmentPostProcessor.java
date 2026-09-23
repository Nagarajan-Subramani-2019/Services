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
    static final String DEFAULT_URL = PropertiesDatabaseSettings.DEFAULT_URL;

    private final OraclePortReader portReader;

    public DatabasePortEnvironmentPostProcessor() {
        this(new OraclePortReader());
    }

    DatabasePortEnvironmentPostProcessor(OraclePortReader portReader) {
        this.portReader = portReader;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        try {
            if (!environment.getProperty(PREFIX + "enabled", Boolean.class, false)) {
                return;
            }
        }
        catch (IllegalArgumentException | ConversionException exception) {
            // Placeholder and conversion failures can echo externally supplied secrets.
            throw new IllegalStateException("Invalid database port configuration. "
                    + "Set USER_INFO_DB_PORT_ENABLED to true or false.");
        }

        PropertiesDatabaseSettings database = PropertiesDatabaseSettings.load(environment);
        int port = portReader.readPort(database.url(), database.password());
        // When enabled, the database wins over USER_INFO_PORT and --server.port.
        environment.getPropertySources().addFirst(
                new MapPropertySource(PROPERTY_SOURCE, Map.of("server.port", port)));
    }

    @Override
    public int getOrder() {
        return ConfigDataEnvironmentPostProcessor.ORDER + 1;
    }
}
