package com.oxygenraj.transactionui;

import com.oxygenraj.uiplatform.UiPlatformConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import(UiPlatformConfiguration.class)
@EnableConfigurationProperties(BackendProperties.class)
public class TransactionUiApplication extends SpringBootServletInitializer {
    @Override protected SpringApplicationBuilder configure(SpringApplicationBuilder app) {
        return app.sources(TransactionUiApplication.class);
    }
    public static void main(String[] args) { SpringApplication.run(TransactionUiApplication.class, args); }
}
