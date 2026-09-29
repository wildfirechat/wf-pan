package com.wildfirechat.pan.service.storage;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;

/**
 * 阿里云 OSS（同地域内支持跨 bucket 拷贝）
 */
final class AliyunStorageProvider implements StorageProvider {

    private final OSS client;

    AliyunStorageProvider(String endpoint, String accessKey, String secretKey) {
        this.client = new OSSClientBuilder().build(endpoint, accessKey, secretKey);
    }

    @Override
    public void copy(String sourceUrl, ObjectLocation source, String targetBucket, String targetKey) {
        client.copyObject(source.bucket(), source.key(), targetBucket, targetKey);
    }

    @Override
    public void delete(String bucket, String key) {
        client.deleteObject(bucket, key);
    }

    @Override
    public long size(String bucket, String key) {
        return client.getObjectMetadata(bucket, key).getContentLength();
    }

    @Override
    public void close() {
        client.shutdown();
    }
}
