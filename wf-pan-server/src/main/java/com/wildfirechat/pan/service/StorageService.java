package com.wildfirechat.pan.service;

import com.wildfirechat.pan.config.OssConfig;
import com.wildfirechat.pan.exception.BusinessException;
import com.wildfirechat.pan.service.storage.ObjectLocation;
import com.wildfirechat.pan.service.storage.StorageProvider;
import com.wildfirechat.pan.service.storage.StorageProviders;
import com.wildfirechat.pan.service.storage.StorageUrls;
import com.wildfirechat.pan.service.storage.StorageUrls.ParsedUrl;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 对象存储访问。
 * <p>
 * 安全边界：客户端提交的 URL 只能位于网盘 bucket 或 media.trusted_url_prefixes 之下；
 * 服务端只删除网盘 bucket 中的对象，bucket 从不取自客户端输入。
 */
@Service
@Slf4j
public class StorageService {

    /**
     * @param url 保存到文件记录中的地址
     * @param key 网盘 bucket 中的对象 key；为空串表示对象不在网盘 bucket 中，不归网盘管理
     */
    public record StoredObject(String url, String key) {
    }

    private final OssConfig config;
    private final StorageProvider provider;
    /** 网盘 bucket 中对象 URL 的前缀，未配置对象存储时为 null */
    private final String bucketBaseUrl;
    /** bucketBaseUrl 的规范形式 */
    private final String ownedPrefix;
    private final List<String> trustedPrefixes;
    /** 对象存储 endpoint 的 host：URL 的 host 是它们时按路径风格（host/bucket/key）解析 */
    private final Set<String> endpointHosts;

    public StorageService(OssConfig config) {
        this.config = config;
        this.bucketBaseUrl = buildBucketBaseUrl(config);
        this.ownedPrefix = bucketBaseUrl == null ? null : StorageUrls.normalizePrefix(bucketBaseUrl)
            .orElseThrow(() -> new IllegalStateException("media.server_url 配置无效: " + config.getServerUrl()));
        this.trustedPrefixes = config.getTrustedUrlPrefixList().stream()
            .map(prefix -> StorageUrls.normalizePrefix(prefix)
                .orElseThrow(() -> new IllegalStateException("media.trusted_url_prefixes 配置无效: " + prefix)))
            .toList();
        this.endpointHosts = Stream.of(config.getServerUrl(), config.getAliyunEndpoint(),
                config.getHuaweiEndpoint(), config.getJdcloudEndpoint())
            .filter(StringUtils::hasText)
            .map(endpoint -> endpoint.contains("://") ? endpoint : "https://" + endpoint)
            .flatMap(endpoint -> StorageUrls.parse(endpoint).stream())
            .map(ParsedUrl::host)
            .collect(Collectors.toSet());
        this.provider = StorageProviders.create(config);

        log.info("对象存储: {}, bucket URL: {}, 受信任来源: {}", config.getMediaTypeName(), bucketBaseUrl, trustedPrefixes);
        if (bucketBaseUrl != null && provider == null) {
            log.warn("存储类型 {} 不支持服务端复制和删除", config.getMediaTypeName());
        }
    }

    @PreDestroy
    public void close() {
        if (provider != null) {
            try {
                provider.close();
            } catch (Exception e) {
                log.warn("Failed to close storage client", e);
            }
        }
    }

    /**
     * 校验客户端直接引用（不复制）的存储地址。
     * 地址须在网盘 bucket 或受信任前缀下；既未配置对象存储也未配置受信任前缀时，只要求是 http(s) 地址。
     */
    public StoredObject resolveReference(String url) {
        ParsedUrl parsed = parseOrThrow(url);
        Optional<String> key = ownedKey(parsed);
        if (key.isPresent()) {
            return new StoredObject(url, key.get());
        }
        boolean unrestricted = ownedPrefix == null && trustedPrefixes.isEmpty();
        if (!unrestricted && trustedPrefixes.stream().noneMatch(parsed::isUnder)) {
            throw new BusinessException("不允许引用该存储地址");
        }
        return new StoredObject(url, "");
    }

    /**
     * 把对象导入网盘 bucket：已在网盘 bucket 中则直接引用，否则从受信任的来源做服务端拷贝。
     */
    public StoredObject importObject(String sourceUrl) {
        ParsedUrl parsed = parseOrThrow(sourceUrl);
        Optional<String> ownedKey = ownedKey(parsed);
        if (ownedKey.isPresent()) {
            return new StoredObject(sourceUrl, ownedKey.get());
        }
        if (provider == null) {
            throw new BusinessException("未配置对象存储或当前存储类型不支持复制文件");
        }
        if (trustedPrefixes.stream().noneMatch(parsed::isUnder)) {
            throw new BusinessException("不允许从该地址复制文件");
        }

        ObjectLocation source = config.getMediaType() == OssConfig.TYPE_QINIU ? null : locate(parsed);
        String targetKey = newObjectKey(parsed.fileName());
        try {
            provider.copy(sourceUrl, source, config.getBucket(), targetKey);
        } catch (Exception e) {
            log.error("Failed to copy object {} to {}/{}", sourceUrl, config.getBucket(), targetKey, e);
            throw new BusinessException("文件复制失败");
        }
        log.info("Object copied: {} -> {}/{}", sourceUrl, config.getBucket(), targetKey);
        return new StoredObject(bucketBaseUrl + StorageUrls.encodePath(targetKey), targetKey);
    }

    /**
     * URL 对应的网盘 bucket 对象 key，不在网盘 bucket 中时为空
     */
    public Optional<String> ownedKey(String url) {
        return StorageUrls.parse(url).flatMap(this::ownedKey);
    }

    /**
     * 网盘 bucket 中对象的实际大小；查询失败时为空
     */
    public OptionalLong objectSize(String key) {
        if (provider == null || !StringUtils.hasText(key)) {
            return OptionalLong.empty();
        }
        try {
            return OptionalLong.of(provider.size(config.getBucket(), key));
        } catch (Exception e) {
            log.warn("Failed to stat object {}: {}", key, e.getMessage());
            return OptionalLong.empty();
        }
    }

    /**
     * 删除网盘 bucket 中的对象，失败只记录日志
     */
    public void deleteObject(String key) {
        if (!StringUtils.hasText(key)) {
            return;
        }
        if (provider == null) {
            log.warn("Storage type {} does not support deleting, object kept: {}", config.getMediaTypeName(), key);
            return;
        }
        try {
            provider.delete(config.getBucket(), key);
            log.info("Object deleted: {}/{}", config.getBucket(), key);
        } catch (Exception e) {
            log.error("Failed to delete object {}/{}", config.getBucket(), key, e);
        }
    }

    private Optional<String> ownedKey(ParsedUrl url) {
        if (ownedPrefix == null || !url.isUnder(ownedPrefix)) {
            return Optional.empty();
        }
        String key = url.canonical().substring(ownedPrefix.length());
        return key.isEmpty() ? Optional.empty() : Optional.of(key);
    }

    private ObjectLocation locate(ParsedUrl url) {
        boolean pathStyle = config.getMediaType() == OssConfig.TYPE_WILDFIRE || endpointHosts.contains(url.host());
        return (pathStyle ? StorageUrls.pathStyle(url) : StorageUrls.virtualHostStyle(url))
            .orElseThrow(() -> new BusinessException("无法解析存储地址"));
    }

    private static ParsedUrl parseOrThrow(String url) {
        return StorageUrls.parse(url).orElseThrow(() -> new BusinessException("存储URL无效"));
    }

    /**
     * 保留原文件名，加随机前缀避免冲突
     */
    private static String newObjectKey(String fileName) {
        String uuid = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        return uuid + "-" + (fileName.isEmpty() ? "file" : fileName);
    }

    private static String buildBucketBaseUrl(OssConfig config) {
        String serverUrl = config.getServerUrl();
        String bucket = config.getBucket();
        if (config.getMediaType() == OssConfig.TYPE_NONE || !StringUtils.hasText(serverUrl) || !StringUtils.hasText(bucket)) {
            return null;
        }
        String base = serverUrl.endsWith("/") ? serverUrl.substring(0, serverUrl.length() - 1) : serverUrl;
        return switch (config.getMediaType()) {
            case OssConfig.TYPE_ALIYUN -> "https://" + bucket + "." + URI.create(base).getRawAuthority() + "/";
            case OssConfig.TYPE_QINIU -> base + "/";
            default -> base + "/" + bucket + "/";
        };
    }
}
