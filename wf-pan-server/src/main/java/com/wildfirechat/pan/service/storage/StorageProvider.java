package com.wildfirechat.pan.service.storage;

/**
 * 单个对象存储厂商的操作。实现类持有可复用、线程安全的 SDK 客户端。
 */
public interface StorageProvider extends AutoCloseable {

    /**
     * 服务端拷贝对象
     *
     * @param sourceUrl 来源 URL（已通过白名单校验），按 URL 拉取的厂商（七牛）使用
     * @param source    来源对象位置，按 bucket/key 拷贝的厂商使用
     */
    void copy(String sourceUrl, ObjectLocation source, String targetBucket, String targetKey) throws Exception;

    void delete(String bucket, String key) throws Exception;

    long size(String bucket, String key) throws Exception;

    @Override
    default void close() throws Exception {
    }
}
