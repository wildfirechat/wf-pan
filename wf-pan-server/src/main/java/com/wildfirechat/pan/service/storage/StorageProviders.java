package com.wildfirechat.pan.service.storage;

import com.wildfirechat.pan.config.OssConfig;
import org.springframework.util.StringUtils;

import java.net.URI;

public final class StorageProviders {

    private static final String JDCLOUD_REGION = "cn-north-1";

    private StorageProviders() {
    }

    /**
     * 按 media.type 创建对应厂商的实现；未配置或不支持服务端操作（对象存储网关）时返回 null
     */
    public static StorageProvider create(OssConfig config) {
        String accessKey = config.getAccessKey();
        String secretKey = config.getSecretKey();
        return switch (config.getMediaType()) {
            case OssConfig.TYPE_QINIU -> new QiniuStorageProvider(accessKey, secretKey);
            case OssConfig.TYPE_ALIYUN -> new AliyunStorageProvider(
                firstNonBlank(config.getAliyunEndpoint(), originOf(config.getServerUrl())), accessKey, secretKey);
            case OssConfig.TYPE_WILDFIRE -> new MinioStorageProvider(config.getServerUrl(), accessKey, secretKey);
            case OssConfig.TYPE_TENCENT -> new TencentStorageProvider(config.getTencentRegion(), accessKey, secretKey);
            case OssConfig.TYPE_HUAWEI -> new HuaweiStorageProvider(
                firstNonBlank(config.getHuaweiEndpoint(), config.getServerUrl()), accessKey, secretKey);
            case OssConfig.TYPE_AWS_S3 -> new S3StorageProvider(config.getAwsRegion(), null, accessKey, secretKey);
            case OssConfig.TYPE_JDCLOUD -> new S3StorageProvider(JDCLOUD_REGION,
                firstNonBlank(config.getJdcloudEndpoint(), config.getServerUrl()), accessKey, secretKey);
            default -> null;
        };
    }

    private static String firstNonBlank(String preferred, String fallback) {
        return StringUtils.hasText(preferred) ? preferred : fallback;
    }

    /**
     * scheme://host[:port]，去掉路径
     */
    private static String originOf(String url) {
        try {
            URI uri = URI.create(url);
            return uri.getScheme() + "://" + uri.getRawAuthority();
        } catch (IllegalArgumentException e) {
            return url;
        }
    }
}
