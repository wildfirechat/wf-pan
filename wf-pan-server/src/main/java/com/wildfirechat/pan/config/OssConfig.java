package com.wildfirechat.pan.config;

import io.minio.MinioClient;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 多厂商OSS配置类
 * 
 * media_type: 0=未配置, 1=七牛云, 2=阿里云, 3=野火私有, 4=对象存储网关, 
 *             5=腾讯云, 6=华为云, 7=AWS S3, 8=京东云
 */
@Configuration
@Data
@Slf4j
public class OssConfig {

    public static final int TYPE_NONE = 0;
    public static final int TYPE_QINIU = 1;
    public static final int TYPE_ALIYUN = 2;
    public static final int TYPE_WILDFIRE = 3;
    public static final int TYPE_GATEWAY = 4;
    public static final int TYPE_TENCENT = 5;
    public static final int TYPE_HUAWEI = 6;
    public static final int TYPE_AWS_S3 = 7;
    public static final int TYPE_JDCLOUD = 8;

    @Value("${media.type:0}")
    private int mediaType;

    @Value("${media.server_url:}")
    private String serverUrl;

    @Value("${media.access_key:}")
    private String accessKey;

    @Value("${media.secret_key:}")
    private String secretKey;

    @Value("${media.bucket:}")
    private String bucket;

    @Value("${media.region:}")
    private String region;

    // 阿里云特有配置
    @Value("${media.aliyun.endpoint:}")
    private String aliyunEndpoint;

    // 腾讯云特有配置
    @Value("${media.tencent.region:ap-guangzhou}")
    private String tencentRegion;

    // 华为云特有配置
    @Value("${media.huawei.endpoint:}")
    private String huaweiEndpoint;

    // AWS S3特有配置
    @Value("${media.aws.region:us-east-1}")
    private String awsRegion;

    // 京东云特有配置
    @Value("${media.jdcloud.endpoint:}")
    private String jdcloudEndpoint;

    // 七牛云特有配置
    @Value("${media.qiniu.region:z0}")
    private String qiniuRegion;

    /**
     * 获取媒体类型名称
     */
    public String getMediaTypeName() {
        return switch (mediaType) {
            case TYPE_NONE -> "未配置";
            case TYPE_QINIU -> "七牛云";
            case TYPE_ALIYUN -> "阿里云OSS";
            case TYPE_WILDFIRE -> "野火私有存储";
            case TYPE_GATEWAY -> "对象存储网关";
            case TYPE_TENCENT -> "腾讯云COS";
            case TYPE_HUAWEI -> "华为云OBS";
            case TYPE_AWS_S3 -> "AWS S3";
            case TYPE_JDCLOUD -> "京东云OSS";
            default -> "未知";
        };
    }

    /**
     * 是否为MinIO兼容类型（野火私有、对象存储网关）
     */
    public boolean isMinioCompatible() {
        return mediaType == TYPE_WILDFIRE || mediaType == TYPE_GATEWAY;
    }

    /**
     * 创建MinIO客户端（用于野火私有和对象存储网关）
     */
    @Bean(destroyMethod = "close")
    public MinioClient minioClient() {
        if (!isMinioCompatible()) {
            log.debug("当前配置不是MinIO兼容类型({})，不创建MinioClient", getMediaTypeName());
            return null;
        }
        
        if (serverUrl == null || serverUrl.isEmpty() || 
            accessKey == null || accessKey.isEmpty() || 
            secretKey == null || secretKey.isEmpty()) {
            log.warn("MinIO配置不完整，请检查 media.server_url, media.access_key, media.secret_key");
            return null;
        }

        try {
            MinioClient client = MinioClient.builder()
                    .endpoint(serverUrl)
                    .credentials(accessKey, secretKey)
                    .build();
            log.info("MinIO客户端创建成功: {}", serverUrl);
            return client;
        } catch (Exception e) {
            log.error("MinIO客户端创建失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 检查OSS配置是否有效
     */
    public boolean isValid() {
        if (mediaType == TYPE_NONE) {
            return false;
        }
        return serverUrl != null && !serverUrl.isEmpty() &&
               accessKey != null && !accessKey.isEmpty() &&
               secretKey != null && !secretKey.isEmpty() &&
               bucket != null && !bucket.isEmpty();
    }
}
