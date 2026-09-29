package com.wildfirechat.pan.config;

import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.List;

/**
 * 多厂商OSS配置类
 *
 * media_type: 0=未配置, 1=七牛云, 2=阿里云, 3=野火私有, 4=对象存储网关,
 *             5=腾讯云, 6=华为云, 7=AWS S3, 8=京东云
 */
@Configuration
@Data
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

    /**
     * 除网盘 bucket 外，允许客户端引用或复制（copy=true）的 URL 前缀，逗号分隔，
     * 如 IM 文件消息所在 bucket：http://minio.example.com:9000/media/
     */
    @Value("${media.trusted_url_prefixes:}")
    private String trustedUrlPrefixes;

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

    public List<String> getTrustedUrlPrefixList() {
        return Arrays.stream(StringUtils.commaDelimitedListToStringArray(trustedUrlPrefixes))
            .map(String::trim)
            .filter(StringUtils::hasText)
            .toList();
    }

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
}
