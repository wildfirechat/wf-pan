package com.wildfirechat.pan.service;

import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URL;
import java.util.List;

@Service
@Slf4j
public class StorageService {
    
    @Value("${storage.provider:none}")
    private String provider;
    
    @Autowired(required = false)
    private MinioClient minioClient;
    
    /**
     * 删除对象存储中的文件
     */
    public void deleteObject(String storageUrl) {
        if (storageUrl == null || storageUrl.isEmpty()) {
            return;
        }
        
        if ("none".equals(provider) || minioClient == null) {
            log.info("Storage provider not configured, skip delete: {}", storageUrl);
            return;
        }
        
        try {
            // 解析URL提取bucket和key
            // URL格式: http://host:port/bucket/key
            URL url = new URL(storageUrl);
            String path = url.getPath();
            if (path.startsWith("/")) {
                path = path.substring(1);
            }
            
            int slashIndex = path.indexOf('/');
            if (slashIndex == -1) {
                log.warn("Invalid storage URL: {}", storageUrl);
                return;
            }
            
            String bucket = path.substring(0, slashIndex);
            String objectKey = path.substring(slashIndex + 1);
            
            // 根据provider调用对应客户端删除
            if ("minio".equals(provider)) {
                minioClient.removeObject(
                    RemoveObjectArgs.builder()
                        .bucket(bucket)
                        .object(objectKey)
                        .build()
                );
            }
            
            log.info("Deleted object from storage: {}/{}" , bucket, objectKey);
            
        } catch (Exception e) {
            log.error("Failed to delete object: {}", storageUrl, e);
            // 不抛出异常，避免影响业务，但记录日志
        }
    }
    
    /**
     * 批量删除
     */
    public void deleteObjects(List<String> storageUrls) {
        if (storageUrls == null) return;
        for (String url : storageUrls) {
            deleteObject(url);
        }
    }
}
