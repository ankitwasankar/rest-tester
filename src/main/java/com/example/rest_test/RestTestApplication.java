package com.example.rest_test;

import com.example.rest_test.config.RequestProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(RequestProperties.class)
public class RestTestApplication {
    public static void main(String[] args) {
        SpringApplication.run(RestTestApplication.class, args);
    }
}
