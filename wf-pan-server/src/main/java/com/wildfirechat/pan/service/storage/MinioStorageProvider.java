package com.wildfirechat.pan.service.storage;

import io.minio.CopyObjectArgs;
import io.minio.CopySource;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;

/**
 * 野火私有对象存储（MinIO）
 */
final class MinioStorageProvider implements StorageProvider {

    private final MinioClient client;

    MinioStorageProvider(String endpoint, String accessKey, String secretKey) {
        this.client = MinioClient.builder()
            .endpoint(endpoint)
            .credentials(accessKey, secretKey)
            .build();
    }

    @Override
    public void copy(String sourceUrl, ObjectLocation source, String targetBucket, String targetKey) throws Exception {
        client.copyObject(CopyObjectArgs.builder()
            .bucket(targetBucket)
            .object(targetKey)
            .source(CopySource.builder().bucket(source.bucket()).object(source.key()).build())
            .build());
    }

    @Override
    public void delete(String bucket, String key) throws Exception {
        client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
    }

    @Override
    public long size(String bucket, String key) throws Exception {
        return client.statObject(StatObjectArgs.builder().bucket(bucket).object(key).build()).size();
    }
}
