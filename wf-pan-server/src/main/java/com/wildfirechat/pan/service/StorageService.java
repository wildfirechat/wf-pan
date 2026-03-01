package com.wildfirechat.pan.service;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.obs.services.ObsClient;
import com.obs.services.model.CopyObjectResult;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qiniu.storage.BucketManager;
import com.qiniu.storage.Configuration;
import com.qiniu.util.Auth;
import com.wildfirechat.pan.config.OssConfig;
import com.wildfirechat.pan.exception.BusinessException;
import io.minio.CopyObjectArgs;
import io.minio.CopySource;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.util.List;
import java.util.UUID;

@Service
@Slf4j
public class StorageService {

    @Autowired
    private OssConfig ossConfig;

    // 缓存各厂商客户端实例
    private volatile OSS aliyunClient;
    private volatile COSClient tencentClient;
    private volatile ObsClient huaweiClient;
    private volatile S3Client awsClient;
    private volatile Auth qiniuAuth;
    private volatile Configuration qiniuConfig;

    /**
     * 检查文件是否需要复制到Pan bucket，如果需要则执行复制
     *
     * @param sourceUrl 源文件URL
     * @return 复制后的URL（如果在Pan bucket中已存在或无需复制，返回原URL）
     * @throws BusinessException 复制失败或不支持时抛出异常
     */
    public String copyObjectIfNeeded(String sourceUrl) throws BusinessException {
        if (ossConfig.getMediaType() == OssConfig.TYPE_NONE) {
            throw new BusinessException("Media type not configured");
        }

        if (sourceUrl == null || sourceUrl.isEmpty()) {
            throw new BusinessException("Source URL is null or empty");
        }

        // 检查是否已经在Pan bucket中
        if (isInPanBucket(sourceUrl)) {
            log.debug("File already in Pan bucket: {}", sourceUrl);
            return sourceUrl;
        }

        // 生成目标key
        String targetKey = generateTargetKey(sourceUrl);

        // 根据provider类型执行复制
        return switch (ossConfig.getMediaType()) {
            case OssConfig.TYPE_QINIU -> copyUsingQiniu(sourceUrl, targetKey);
            case OssConfig.TYPE_ALIYUN -> copyUsingAliyun(sourceUrl, targetKey);
            case OssConfig.TYPE_WILDFIRE -> copyUsingMinio(sourceUrl, targetKey);
            case OssConfig.TYPE_GATEWAY -> throw new BusinessException("Gateway storage copy not supported");
            case OssConfig.TYPE_TENCENT -> copyUsingTencent(sourceUrl, targetKey);
            case OssConfig.TYPE_HUAWEI -> copyUsingHuawei(sourceUrl, targetKey);
            case OssConfig.TYPE_AWS_S3 -> copyUsingAwsS3(sourceUrl, targetKey);
            case OssConfig.TYPE_JDCLOUD -> copyUsingJdcloud(sourceUrl, targetKey);
            default -> throw new BusinessException("Unsupported media type: " + ossConfig.getMediaType());
        };
    }

    /**
     * 检查文件是否已在Pan bucket中
     */
    private boolean isInPanBucket(String sourceUrl) {
        String bucket = ossConfig.getBucket();
        if (bucket == null || bucket.isEmpty()) {
            return false;
        }
        // 检查URL中是否包含bucket名称
        return sourceUrl.contains("/" + bucket + "/");
    }

    /**
     * 生成目标key（保留原文件名，添加UUID前缀避免冲突）
     */
    private String generateTargetKey(String sourceUrl) {
        String fileName = extractFileName(sourceUrl);
        String uuid = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        return "pan/" + uuid + "-" + fileName;
    }

    /**
     * 从URL中提取文件名
     */
    private String extractFileName(String url) {
        if (url == null || url.isEmpty()) {
            return "unknown";
        }
        int lastSlash = url.lastIndexOf('/');
        if (lastSlash == -1) {
            return url;
        }
        String fileName = url.substring(lastSlash + 1);
        // 移除查询参数
        int queryIndex = fileName.indexOf('?');
        if (queryIndex != -1) {
            fileName = fileName.substring(0, queryIndex);
        }
        return fileName.isEmpty() ? "unknown" : fileName;
    }

    /**
     * 使用七牛云SDK复制文件
     */
    private String copyUsingQiniu(String sourceUrl, String targetKey) {
        try {
            if (qiniuAuth == null) {
                qiniuAuth = Auth.create(ossConfig.getAccessKey(), ossConfig.getSecretKey());
                qiniuConfig = new Configuration(com.qiniu.storage.Region.autoRegion());
            }

            BucketManager bucketManager = new BucketManager(qiniuAuth, qiniuConfig);

            // 解析源URL获取bucket和key
            SourceObject sourceObj = parseSourceUrl(sourceUrl);

            // 获取源文件的下载URL（需要是公开可访问的）
            // 如果源文件在同一账号下，可以直接使用fetch
            String fetchUrl = sourceUrl;
            if (!sourceUrl.startsWith("http")) {
                fetchUrl = "http:" + sourceUrl;
            }

            // 使用fetch接口拉取文件
            bucketManager.fetch(fetchUrl, ossConfig.getBucket(), targetKey);

            log.info("Qiniu copy success: {} -> {}/{}", sourceUrl, ossConfig.getBucket(), targetKey);
            return buildTargetUrl(targetKey);

        } catch (Exception e) {
            log.error("Qiniu copy failed: {}", sourceUrl, e);
            throw new BusinessException("Qiniu copy failed: " + e.getMessage(), e);
        }
    }

    /**
     * 使用阿里云OSS SDK复制文件
     */
    private String copyUsingAliyun(String sourceUrl, String targetKey) {
        OSS client = null;
        try {
            // 创建客户端
            String endpoint = ossConfig.getAliyunEndpoint();
            if (endpoint == null || endpoint.isEmpty()) {
                // 从serverUrl解析endpoint
                endpoint = extractEndpointFromUrl(ossConfig.getServerUrl());
            }

            client = new OSSClientBuilder().build(endpoint, ossConfig.getAccessKey(), ossConfig.getSecretKey());

            // 解析源URL
            SourceObject sourceObj = parseSourceUrl(sourceUrl);

            // 构建拷贝请求
            String sourceBucket = sourceObj.bucket;
            String sourceKey = sourceObj.key;

            // 如果是同一个bucket，使用OSS拷贝
            if (sourceBucket.equals(ossConfig.getBucket())) {
                com.aliyun.oss.model.CopyObjectRequest copyRequest = new com.aliyun.oss.model.CopyObjectRequest(sourceBucket, sourceKey, ossConfig.getBucket(), targetKey);
                client.copyObject(copyRequest);
            } else {
                // 跨bucket拷贝，先下载再上传
                throw new BusinessException("Aliyun cross-bucket copy not implemented");
            }

            log.info("Aliyun OSS copy success: {} -> {}/{}", sourceUrl, ossConfig.getBucket(), targetKey);
            return buildTargetUrl(targetKey);

        } catch (Exception e) {
            log.error("Aliyun OSS copy failed: {}", sourceUrl, e);
            throw new BusinessException("Aliyun OSS copy failed: " + e.getMessage(), e);
        } finally {
            if (client != null) {
                client.shutdown();
            }
        }
    }

    /**
     * 使用MinIO SDK复制文件（野火私有、对象存储网关）
     */
    private String copyUsingMinio(String sourceUrl, String targetKey) {
        MinioClient client = null;
        try {
            // 创建客户端
            client = MinioClient.builder()
                    .endpoint(ossConfig.getServerUrl())
                    .credentials(ossConfig.getAccessKey(), ossConfig.getSecretKey())
                    .build();

            // 检查目标文件是否已存在
            try {
                client.statObject(
                        StatObjectArgs.builder()
                                .bucket(ossConfig.getBucket())
                                .object(targetKey)
                                .build()
                );
                log.debug("Target file already exists: {}/{}", ossConfig.getBucket(), targetKey);
                return buildTargetUrl(targetKey);
            } catch (ErrorResponseException e) {
                // 文件不存在，继续复制
                if (!e.errorResponse().code().equals("NoSuchKey")) {
                    throw e;
                }
            }

            // 解析源URL获取bucket和key
            SourceObject sourceObj = parseSourceUrl(sourceUrl);

            // 执行服务器端复制
            client.copyObject(
                    CopyObjectArgs.builder()
                            .bucket(ossConfig.getBucket())
                            .object(targetKey)
                            .source(CopySource.builder()
                                    .bucket(sourceObj.bucket)
                                    .object(sourceObj.key)
                                    .build())
                            .build()
            );
            log.info("MinIO copy success: {} -> {}/{}", sourceUrl, ossConfig.getBucket(), targetKey);

            return buildTargetUrl(targetKey);

        } catch (Exception e) {
            log.error("MinIO copy failed: {}", sourceUrl, e);
            throw new BusinessException("MinIO copy failed: " + e.getMessage(), e);
        }
    }

    /**
     * 使用腾讯云COS SDK复制文件
     */
    private String copyUsingTencent(String sourceUrl, String targetKey) {
        COSClient client = null;
        try {
            // 创建客户端
            BasicCOSCredentials credentials = new BasicCOSCredentials(ossConfig.getAccessKey(), ossConfig.getSecretKey());
            ClientConfig clientConfig = new ClientConfig(new com.qcloud.cos.region.Region(ossConfig.getTencentRegion()));
            client = new COSClient(credentials, clientConfig);

            // 解析源URL
            SourceObject sourceObj = parseSourceUrl(sourceUrl);

            // 构建拷贝请求
            com.qcloud.cos.model.CopyObjectRequest copyRequest = new com.qcloud.cos.model.CopyObjectRequest(
                    sourceObj.bucket, sourceObj.key,
                    ossConfig.getBucket(), targetKey
            );

            client.copyObject(copyRequest);

            log.info("Tencent COS copy success: {} -> {}/{}", sourceUrl, ossConfig.getBucket(), targetKey);
            return buildTargetUrl(targetKey);

        } catch (Exception e) {
            log.error("Tencent COS copy failed: {}", sourceUrl, e);
            throw new BusinessException("Tencent COS copy failed: " + e.getMessage(), e);
        } finally {
            if (client != null) {
                client.shutdown();
            }
        }
    }

    /**
     * 使用华为云OBS SDK复制文件
     */
    private String copyUsingHuawei(String sourceUrl, String targetKey) {
        try {
            // 创建客户端
            String endpoint = ossConfig.getHuaweiEndpoint();
            if (endpoint == null || endpoint.isEmpty()) {
                endpoint = ossConfig.getServerUrl();
            }

            ObsClient client = new ObsClient(ossConfig.getAccessKey(), ossConfig.getSecretKey(), endpoint);

            // 解析源URL
            SourceObject sourceObj = parseSourceUrl(sourceUrl);

            // 执行拷贝
            CopyObjectResult result = client.copyObject(
                    sourceObj.bucket, sourceObj.key,
                    ossConfig.getBucket(), targetKey
            );

            if (result.getEtag() != null) {
                log.info("Huawei OBS copy success: {} -> {}/{}", sourceUrl, ossConfig.getBucket(), targetKey);
                return buildTargetUrl(targetKey);
            } else {
                throw new BusinessException("Huawei OBS copy failed: no ETag returned");
            }

        } catch (Exception e) {
            log.error("Huawei OBS copy failed: {}", sourceUrl, e);
            throw new BusinessException("Huawei OBS copy failed: " + e.getMessage(), e);
        }
    }

    /**
     * 使用AWS S3 SDK复制文件
     */
    private String copyUsingAwsS3(String sourceUrl, String targetKey) {
        S3Client client = null;
        try {
            // 创建客户端
            AwsBasicCredentials credentials = AwsBasicCredentials.create(ossConfig.getAccessKey(), ossConfig.getSecretKey());
            client = S3Client.builder()
                    .region(software.amazon.awssdk.regions.Region.of(ossConfig.getAwsRegion()))
                    .credentialsProvider(StaticCredentialsProvider.create(credentials))
                    .build();

            // 解析源URL
            SourceObject sourceObj = parseSourceUrl(sourceUrl);

            // 构建拷贝请求
            String copySource = sourceObj.bucket + "/" + sourceObj.key;

            software.amazon.awssdk.services.s3.model.CopyObjectRequest copyRequest = software.amazon.awssdk.services.s3.model.CopyObjectRequest.builder()
                    .sourceBucket(sourceObj.bucket)
                    .sourceKey(sourceObj.key)
                    .destinationBucket(ossConfig.getBucket())
                    .destinationKey(targetKey)
                    .build();

            client.copyObject(copyRequest);

            log.info("AWS S3 copy success: {} -> {}/{}", sourceUrl, ossConfig.getBucket(), targetKey);
            return buildTargetUrl(targetKey);

        } catch (Exception e) {
            log.error("AWS S3 copy failed: {}", sourceUrl, e);
            throw new BusinessException("AWS S3 copy failed: " + e.getMessage(), e);
        } finally {
            if (client != null) {
                client.close();
            }
        }
    }

    /**
     * 使用京东云OSS SDK复制文件
     */
    private String copyUsingJdcloud(String sourceUrl, String targetKey) {
        // 京东云OSS兼容S3协议，使用AWS S3 SDK实现
        S3Client client = null;
        try {
            // 创建客户端
            String endpoint = ossConfig.getJdcloudEndpoint();
            if (endpoint == null || endpoint.isEmpty()) {
                endpoint = ossConfig.getServerUrl();
            }

            AwsBasicCredentials credentials = AwsBasicCredentials.create(ossConfig.getAccessKey(), ossConfig.getSecretKey());
            client = S3Client.builder()
                    .endpointOverride(URI.create(endpoint))
                    .region(software.amazon.awssdk.regions.Region.of("cn-north-1")) // 京东云默认区域
                    .credentialsProvider(StaticCredentialsProvider.create(credentials))
                    .build();

            // 解析源URL
            SourceObject sourceObj = parseSourceUrl(sourceUrl);

            // 执行拷贝
            software.amazon.awssdk.services.s3.model.CopyObjectRequest copyRequest = software.amazon.awssdk.services.s3.model.CopyObjectRequest.builder()
                    .sourceBucket(sourceObj.bucket)
                    .sourceKey(sourceObj.key)
                    .destinationBucket(ossConfig.getBucket())
                    .destinationKey(targetKey)
                    .build();

            client.copyObject(copyRequest);

            log.info("JDCloud OSS copy success: {} -> {}/{}", sourceUrl, ossConfig.getBucket(), targetKey);
            return buildTargetUrl(targetKey);

        } catch (Exception e) {
            log.error("JDCloud OSS copy failed: {}", sourceUrl, e);
            throw new BusinessException("JDCloud OSS copy failed: " + e.getMessage(), e);
        } finally {
            if (client != null) {
                client.close();
            }
        }
    }

    /**
     * 从URL提取endpoint
     */
    private String extractEndpointFromUrl(String url) {
        if (url == null || url.isEmpty()) {
            return "";
        }
        try {
            URL u = new URL(url);
            return u.getProtocol() + "://" + u.getHost() + 
                   (u.getPort() != -1 ? ":" + u.getPort() : "");
        } catch (Exception e) {
            return url;
        }
    }

    /**
     * 解析源URL获取bucket和key
     */
    private SourceObject parseSourceUrl(String sourceUrl) {
        try {
            URL url = new URL(sourceUrl);
            String path = url.getPath();
            if (path.startsWith("/")) {
                path = path.substring(1);
            }

            int slashIndex = path.indexOf('/');
            if (slashIndex == -1) {
                return new SourceObject("unknown", path);
            }

            String bucket = path.substring(0, slashIndex);
            String key = path.substring(slashIndex + 1);
            return new SourceObject(bucket, key);
        } catch (Exception e) {
            log.error("Failed to parse source URL: {}", sourceUrl, e);
            return new SourceObject("unknown", "unknown");
        }
    }

    /**
     * 构建目标URL
     */
    private String buildTargetUrl(String targetKey) {
        String serverUrl = ossConfig.getServerUrl();
        String bucket = ossConfig.getBucket();
        
        if (serverUrl == null || serverUrl.isEmpty()) {
            return targetKey;
        }
        
        String baseUrl = serverUrl;
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        
        // 不同厂商的URL格式可能不同
        return switch (ossConfig.getMediaType()) {
            case OssConfig.TYPE_ALIYUN -> 
                "https://" + bucket + "." + extractEndpointFromUrl(serverUrl).replace("https://", "").replace("http://", "") + "/" + targetKey;
            case OssConfig.TYPE_QINIU -> 
                serverUrl + "/" + targetKey;
            default -> 
                baseUrl + "/" + bucket + "/" + targetKey;
        };
    }

    /**
     * 内部类：源对象信息
     */
    private record SourceObject(String bucket, String key) {}

    /**
     * 删除对象存储中的文件
     */
    public void deleteObject(String storageUrl) {
        if (storageUrl == null || storageUrl.isEmpty()) {
            return;
        }

        if (ossConfig.getMediaType() == OssConfig.TYPE_NONE) {
            log.debug("Storage provider not configured, skip delete: {}", storageUrl);
            return;
        }

        try {
            // 根据provider调用对应客户端删除
            switch (ossConfig.getMediaType()) {
                case OssConfig.TYPE_WILDFIRE -> deleteUsingMinio(storageUrl);
                case OssConfig.TYPE_GATEWAY -> {
                    // TODO: 对象存储网关删除功能待实现
                    log.warn("对象存储网关删除功能暂未实现: {}", storageUrl);
                }
                case OssConfig.TYPE_ALIYUN -> deleteUsingAliyun(storageUrl);
                case OssConfig.TYPE_TENCENT -> deleteUsingTencent(storageUrl);
                case OssConfig.TYPE_QINIU -> deleteUsingQiniu(storageUrl);
                case OssConfig.TYPE_HUAWEI -> deleteUsingHuawei(storageUrl);
                case OssConfig.TYPE_AWS_S3 -> deleteUsingAwsS3(storageUrl);
                case OssConfig.TYPE_JDCLOUD -> deleteUsingJdcloud(storageUrl);
                default -> log.warn("Unsupported media type for delete: {}", ossConfig.getMediaType());
            }
        } catch (Exception e) {
            log.error("Failed to delete object: {}", storageUrl, e);
            // 不抛出异常，避免影响业务
        }
    }

    /**
     * 使用MinIO删除文件
     */
    private void deleteUsingMinio(String storageUrl) {
        MinioClient client = null;
        try {
            // 创建客户端
            client = MinioClient.builder()
                    .endpoint(ossConfig.getServerUrl())
                    .credentials(ossConfig.getAccessKey(), ossConfig.getSecretKey())
                    .build();
            
            SourceObject sourceObj = parseSourceUrl(storageUrl);
            client.removeObject(
                    RemoveObjectArgs.builder()
                            .bucket(sourceObj.bucket)
                            .object(sourceObj.key)
                            .build()
            );
            log.info("MinIO delete success: {}/{}", sourceObj.bucket, sourceObj.key);
        } catch (Exception e) {
            log.error("MinIO delete failed: {}", storageUrl, e);
        }
    }

    /**
     * 使用阿里云OSS删除文件
     */
    private void deleteUsingAliyun(String storageUrl) {
        OSS client = null;
        try {
            String endpoint = ossConfig.getAliyunEndpoint();
            if (endpoint == null || endpoint.isEmpty()) {
                endpoint = extractEndpointFromUrl(ossConfig.getServerUrl());
            }
            client = new OSSClientBuilder().build(endpoint, ossConfig.getAccessKey(), ossConfig.getSecretKey());
            
            SourceObject sourceObj = parseSourceUrl(storageUrl);
            client.deleteObject(sourceObj.bucket, sourceObj.key);
            log.info("Aliyun OSS delete success: {}/{}", sourceObj.bucket, sourceObj.key);
        } catch (Exception e) {
            log.error("Aliyun OSS delete failed: {}", storageUrl, e);
        } finally {
            if (client != null) client.shutdown();
        }
    }

    /**
     * 使用腾讯云COS删除文件
     */
    private void deleteUsingTencent(String storageUrl) {
        COSClient client = null;
        try {
            BasicCOSCredentials credentials = new BasicCOSCredentials(ossConfig.getAccessKey(), ossConfig.getSecretKey());
            ClientConfig clientConfig = new ClientConfig(new com.qcloud.cos.region.Region(ossConfig.getTencentRegion()));
            client = new COSClient(credentials, clientConfig);
            
            SourceObject sourceObj = parseSourceUrl(storageUrl);
            client.deleteObject(sourceObj.bucket, sourceObj.key);
            log.info("Tencent COS delete success: {}/{}", sourceObj.bucket, sourceObj.key);
        } catch (Exception e) {
            log.error("Tencent COS delete failed: {}", storageUrl, e);
        } finally {
            if (client != null) client.shutdown();
        }
    }

    /**
     * 使用七牛云删除文件
     */
    private void deleteUsingQiniu(String storageUrl) {
        try {
            if (qiniuAuth == null) {
                qiniuAuth = Auth.create(ossConfig.getAccessKey(), ossConfig.getSecretKey());
                qiniuConfig = new Configuration(com.qiniu.storage.Region.autoRegion());
            }
            
            BucketManager bucketManager = new BucketManager(qiniuAuth, qiniuConfig);
            SourceObject sourceObj = parseSourceUrl(storageUrl);
            bucketManager.delete(sourceObj.bucket, sourceObj.key);
            log.info("Qiniu delete success: {}/{}", sourceObj.bucket, sourceObj.key);
        } catch (Exception e) {
            log.error("Qiniu delete failed: {}", storageUrl, e);
        }
    }

    /**
     * 使用华为云OBS删除文件
     */
    private void deleteUsingHuawei(String storageUrl) {
        try {
            String endpoint = ossConfig.getHuaweiEndpoint();
            if (endpoint == null || endpoint.isEmpty()) {
                endpoint = ossConfig.getServerUrl();
            }
            ObsClient client = new ObsClient(ossConfig.getAccessKey(), ossConfig.getSecretKey(), endpoint);
            
            SourceObject sourceObj = parseSourceUrl(storageUrl);
            client.deleteObject(sourceObj.bucket, sourceObj.key);
            log.info("Huawei OBS delete success: {}/{}", sourceObj.bucket, sourceObj.key);
        } catch (Exception e) {
            log.error("Huawei OBS delete failed: {}", storageUrl, e);
        }
    }

    /**
     * 使用AWS S3删除文件
     */
    private void deleteUsingAwsS3(String storageUrl) {
        S3Client client = null;
        try {
            AwsBasicCredentials credentials = AwsBasicCredentials.create(ossConfig.getAccessKey(), ossConfig.getSecretKey());
            client = S3Client.builder()
                    .region(software.amazon.awssdk.regions.Region.of(ossConfig.getAwsRegion()))
                    .credentialsProvider(StaticCredentialsProvider.create(credentials))
                    .build();
            
            SourceObject sourceObj = parseSourceUrl(storageUrl);
            client.deleteObject(builder -> builder.bucket(sourceObj.bucket).key(sourceObj.key));
            log.info("AWS S3 delete success: {}/{}", sourceObj.bucket, sourceObj.key);
        } catch (Exception e) {
            log.error("AWS S3 delete failed: {}", storageUrl, e);
        } finally {
            if (client != null) client.close();
        }
    }

    /**
     * 使用京东云OSS删除文件
     */
    private void deleteUsingJdcloud(String storageUrl) {
        S3Client client = null;
        try {
            String endpoint = ossConfig.getJdcloudEndpoint();
            if (endpoint == null || endpoint.isEmpty()) {
                endpoint = ossConfig.getServerUrl();
            }
            
            AwsBasicCredentials credentials = AwsBasicCredentials.create(ossConfig.getAccessKey(), ossConfig.getSecretKey());
            client = S3Client.builder()
                    .endpointOverride(URI.create(endpoint))
                    .region(software.amazon.awssdk.regions.Region.of("cn-north-1"))
                    .credentialsProvider(StaticCredentialsProvider.create(credentials))
                    .build();
            
            SourceObject sourceObj = parseSourceUrl(storageUrl);
            client.deleteObject(builder -> builder.bucket(sourceObj.bucket).key(sourceObj.key));
            log.info("JDCloud OSS delete success: {}/{}", sourceObj.bucket, sourceObj.key);
        } catch (Exception e) {
            log.error("JDCloud OSS delete failed: {}", storageUrl, e);
        } finally {
            if (client != null) client.close();
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
