package com.wildfirechat.pan.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "im.server")
public class IMServerConfig {
    private String adminUrl;
    private String adminSecret;
}
