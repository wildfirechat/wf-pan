package com.wildfirechat.pan.service.storage;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

import java.net.URI;

/**
 * S3 协议：AWS S3，以及兼容 S3 的京东云 OSS（通过 endpointOverride）
 */
final class S3StorageProvider implements StorageProvider {

    private final S3Client client;

    /**
     * @param endpoint 为空时使用 AWS 默认 endpoint
     */
    S3StorageProvider(String region, String endpoint, String accessKey, String secretKey) {
        S3ClientBuilder builder = S3Client.builder()
            .region(Region.of(region))
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)));
        if (endpoint != null && !endpoint.isEmpty()) {
            builder.endpointOverride(URI.create(endpoint));
        }
        this.client = builder.build();
    }

    @Override
    public void copy(String sourceUrl, ObjectLocation source, String targetBucket, String targetKey) {
        client.copyObject(request -> request
            .sourceBucket(source.bucket())
            .sourceKey(source.key())
            .destinationBucket(targetBucket)
            .destinationKey(targetKey));
    }

    @Override
    public void delete(String bucket, String key) {
        client.deleteObject(request -> request.bucket(bucket).key(key));
    }

    @Override
    public long size(String bucket, String key) {
        return client.headObject(request -> request.bucket(bucket).key(key)).contentLength();
    }

    @Override
    public void close() {
        client.close();
    }
}
