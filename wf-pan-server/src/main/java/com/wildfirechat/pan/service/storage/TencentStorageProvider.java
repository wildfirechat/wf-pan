package com.wildfirechat.pan.service.storage;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.region.Region;

/**
 * 腾讯云 COS
 */
final class TencentStorageProvider implements StorageProvider {

    private final COSClient client;

    TencentStorageProvider(String region, String accessKey, String secretKey) {
        this.client = new COSClient(new BasicCOSCredentials(accessKey, secretKey), new ClientConfig(new Region(region)));
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
