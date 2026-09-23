package com.oxygenraj.userui;

import com.oxygenraj.userui.config.UserInfoClientProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.server.autoconfigure.ServerProperties;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;

@SpringBootApplication
@EnableConfigurationProperties({UserInfoClientProperties.class, ServerProperties.class})
public class UserInfoUiApplication extends SpringBootServletInitializer {
    @Override
    protected SpringApplicationBuilder configure(SpringApplicationBuilder application) {
        return application.sources(UserInfoUiApplication.class);
    }

    public static void main(String[] args) {
        SpringApplication.run(UserInfoUiApplication.class, args);
    }
}
