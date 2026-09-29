package com.wildfirechat.pan.service.storage;

import com.obs.services.ObsClient;

import java.io.IOException;

/**
 * 华为云 OBS
 */
final class HuaweiStorageProvider implements StorageProvider {

    private final ObsClient client;

    HuaweiStorageProvider(String endpoint, String accessKey, String secretKey) {
        this.client = new ObsClient(accessKey, secretKey, endpoint);
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
    public void close() throws IOException {
        client.close();
    }
}
