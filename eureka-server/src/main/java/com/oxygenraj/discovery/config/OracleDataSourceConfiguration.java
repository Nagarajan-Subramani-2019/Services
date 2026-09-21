package com.oxygenraj.discovery.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Optional Oracle connectivity. Eureka continues to keep its registry in memory.
 */
@Profile("oracle")
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties
public class OracleDataSourceConfiguration {

    @Bean
    @ConfigurationProperties("spring.datasource")
    DataSourceProperties oracleDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean(destroyMethod = "close")
    @ConfigurationProperties("spring.datasource.hikari")
    HikariDataSource dataSource(DataSourceProperties oracleDataSourceProperties) {
        return oracleDataSourceProperties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }

    @Bean
    InitializingBean oracleDataSourceValidation(HikariDataSource dataSource) {
        return () -> {
            // Validate after both datasource and Hikari properties have been applied.
            if (!"EUREKA_DB".equals(dataSource.getUsername())) {
                throw new IllegalStateException("The Eureka Oracle datasource must use EUREKA_DB.");
            }
            String password = dataSource.getPassword();
            if (password == null || password.isBlank() || password.contains("${")) {
                throw new IllegalStateException(
                        "Set an explicit, non-blank Eureka database password; unresolved placeholders are not allowed.");
            }
        };
    }
}
