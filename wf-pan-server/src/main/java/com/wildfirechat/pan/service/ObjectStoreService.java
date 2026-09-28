package com.wildfirechat.pan.service;

import com.wildfirechat.pan.config.OssConfig;
import com.wildfirechat.pan.exception.BusinessException;
import com.wildfirechat.pan.service.StorageService.StoredObject;
import com.wildfirechat.pan.service.storage.ObjectLocation;
import com.wildfirechat.pan.service.storage.StorageUrls;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * 网盘桶的服务端读写（网盘桶私有：客户端不直接访问对象，下载经本服务签名链接、编辑器经内部接口）。
 * v1 只支持 S3 兼容的存储：野火 MinIO（3）、AWS S3（7）、京东云（8），统一用 MinIO 客户端访问。
 */
@Service
@Slf4j
public class ObjectStoreService {

    @Autowired
    private OssConfig ossConfig;

    @Autowired
    private StorageService storageService;

    private volatile MinioClient client;

    /** 当前存储是否支持服务端读写（不支持时：不开放在线编辑、下载仍返回原地址） */
    public boolean isSupported() {
        int t = ossConfig.getMediaType();
        return (t == OssConfig.TYPE_WILDFIRE || t == OssConfig.TYPE_AWS_S3 || t == OssConfig.TYPE_JDCLOUD)
            && StringUtils.hasText(ossConfig.getServerUrl()) && StringUtils.hasText(ossConfig.getBucket())
            && StringUtils.hasText(ossConfig.getAccessKey()) && StringUtils.hasText(ossConfig.getSecretKey());
    }

    /**
     * 写入一个新对象，返回存储地址和 key。键 = {keyPrefix}/{随机}.{扩展名}，不含用户输入的文字。
     * keyPrefix：已有文件的新版本用 f{fileId}，新建文件用 new
     */
    public StoredObject put(String keyPrefix, String fileName, Path content, String contentType) {
        String key = keyPrefix + "/" + UUID.randomUUID().toString().replace("-", "") + extension(fileName);
        try (InputStream in = Files.newInputStream(content)) {
            client().putObject(PutObjectArgs.builder()
                .bucket(ossConfig.getBucket())
                .object(key)
                .stream(in, Files.size(content), -1)
                .contentType(contentType != null ? contentType : "application/octet-stream")
                .build());
        } catch (Exception e) {
            throw new BusinessException("写入存储失败: " + e.getMessage(), e);
        }
        return new StoredObject(storageService.urlOf(key), key);
    }

    /** 读对象；offset/length 为 null 时读整个对象 */
    public InputStream open(String storageUrl, Long offset, Long length) {
        ObjectLocation ref = locate(storageUrl);
        try {
            GetObjectArgs.Builder b = GetObjectArgs.builder().bucket(ref.bucket()).object(ref.key());
            if (offset != null) b.offset(offset);
            if (length != null) b.length(length);
            return client().getObject(b.build());
        } catch (Exception e) {
            throw new BusinessException("读取存储失败: " + e.getMessage(), e);
        }
    }

    public long size(String storageUrl) {
        ObjectLocation ref = locate(storageUrl);
        try {
            StatObjectResponse r = client().statObject(StatObjectArgs.builder().bucket(ref.bucket()).object(ref.key()).build());
            return r.size();
        } catch (Exception e) {
            throw new BusinessException("读取存储失败: " + e.getMessage(), e);
        }
    }

    private MinioClient client() {
        if (client == null) {
            synchronized (this) {
                if (client == null) {
                    MinioClient.Builder b = MinioClient.builder()
                        .endpoint(ossConfig.getServerUrl())
                        .credentials(ossConfig.getAccessKey(), ossConfig.getSecretKey());
                    // 其他 S3 兼容存储不指定 region，由客户端向服务端查询
                    if (ossConfig.getMediaType() == OssConfig.TYPE_AWS_S3 && StringUtils.hasText(ossConfig.getAwsRegion())) {
                        b.region(ossConfig.getAwsRegion());
                    }
                    client = b.build();
                }
            }
        }
        return client;
    }

    /**
     * 只读网盘 bucket 和受信任来源（media.trusted_url_prefixes）中的对象：
     * 网盘 bucket 按 key 读；受信任来源（旧数据引用的 IM 媒体桶等）按路径式地址解析 bucket
     */
    private ObjectLocation locate(String storageUrl) {
        StoredObject ref = storageService.resolveReference(storageUrl);
        if (!ref.key().isEmpty()) {
            return new ObjectLocation(ossConfig.getBucket(), ref.key());
        }
        return StorageUrls.parse(storageUrl).flatMap(StorageUrls::pathStyle)
            .orElseThrow(() -> new BusinessException("无法识别的存储地址"));
    }

    private static String extension(String name) {
        if (name == null) return "";
        int i = name.lastIndexOf('.');
        if (i < 0 || i == name.length() - 1) return "";
        String ext = name.substring(i).toLowerCase();
        return ext.matches("\\.[a-z0-9]{1,8}") ? ext : "";
    }

}
