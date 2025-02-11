package com.example.rest_test.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;

import java.util.List;

@Configuration
@PropertySource(value = "classpath:requests.yml", factory = YamlPropertySourceFactory.class)
@ConfigurationProperties(prefix = "requests")
public class RequestProperties {
    private List<RequestConfig> list;

    public List<RequestConfig> getList() {
        return list;
    }

    public void setList(List<RequestConfig> list) {
        this.list = list;
    }
}

