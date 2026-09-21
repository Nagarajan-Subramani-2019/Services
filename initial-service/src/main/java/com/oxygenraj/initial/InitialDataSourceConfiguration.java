package com.oxygenraj.initial;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class InitialDataSourceConfiguration {

    @Bean
    InitializingBean validateInitialDataSource(HikariDataSource dataSource) {
        return () -> {
            // Validate the final pool settings after all configuration has been bound.
            // Reading these settings does not start the pool or open a connection.
            if (!"INITIAL_DB".equals(dataSource.getUsername())) {
                throw new IllegalStateException(
                        "The initial-service datasource must use INITIAL_DB.");
            }
            String password = dataSource.getPassword();
            if (password == null || password.isBlank() || password.contains("${")) {
                throw new IllegalStateException(
                        "A database password must be supplied for INITIAL_DB.");
            }
        };
    }
}
