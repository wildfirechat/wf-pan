package com.wildfirechat.pan.config;

import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class StorageConfig {
    
    @Value("${storage.provider:none}")
    private String provider;
    
    @Value("${storage.endpoint:}")
    private String endpoint;
    
    @Value("${storage.access-key:}")
    private String accessKey;
    
    @Value("${storage.secret-key:}")
    private String secretKey;
    
    @Bean
    public MinioClient minioClient() {
        if (!"minio".equals(provider) || endpoint.isEmpty()) {
            return null;
        }
        
        return MinioClient.builder()
            .endpoint(endpoint)
            .credentials(accessKey, secretKey)
            .build();
    }
}
