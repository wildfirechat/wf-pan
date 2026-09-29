package com.wildfirechat.pan.service.storage;

import com.qiniu.common.QiniuException;
import com.qiniu.storage.BucketManager;
import com.qiniu.storage.Configuration;
import com.qiniu.storage.Region;
import com.qiniu.util.Auth;

/**
 * 七牛云：URL 中不含 bucket，拷贝通过 fetch 按 URL 拉取
 */
final class QiniuStorageProvider implements StorageProvider {

    private final BucketManager bucketManager;

    QiniuStorageProvider(String accessKey, String secretKey) {
        this.bucketManager = new BucketManager(Auth.create(accessKey, secretKey), new Configuration(Region.autoRegion()));
    }

    @Override
    public void copy(String sourceUrl, ObjectLocation source, String targetBucket, String targetKey) throws QiniuException {
        bucketManager.fetch(sourceUrl, targetBucket, targetKey);
    }

    @Override
    public void delete(String bucket, String key) throws QiniuException {
        bucketManager.delete(bucket, key);
    }

    @Override
    public long size(String bucket, String key) throws QiniuException {
        return bucketManager.stat(bucket, key).fsize;
    }
}
