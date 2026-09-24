package com.oxygenraj.userui;

import com.oxygenraj.userui.config.UserInfoClientProperties;
import com.oxygenraj.userui.config.ComponentClientProperties;
import com.oxygenraj.uiplatform.UiPlatformConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.server.autoconfigure.ServerProperties;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@EnableConfigurationProperties({UserInfoClientProperties.class, ComponentClientProperties.class, ServerProperties.class})
@Import(UiPlatformConfiguration.class)
public class UserInfoUiApplication extends SpringBootServletInitializer {
    @Override
    protected SpringApplicationBuilder configure(SpringApplicationBuilder application) {
        return application.sources(UserInfoUiApplication.class);
    }

    public static void main(String[] args) {
        SpringApplication.run(UserInfoUiApplication.class, args);
    }
}
