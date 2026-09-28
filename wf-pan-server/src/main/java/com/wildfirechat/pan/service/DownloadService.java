package com.wildfirechat.pan.service;

import com.wildfirechat.pan.config.DocsConfig;
import com.wildfirechat.pan.entity.PanFile;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 客户端下载链接：本服务签名的短时链接（/pan/dl/...），由本服务从私有网盘桶读出。
 * 不用对象存储的预签名 URL：那要求签名里的 Host 与客户端实际访问的一致，双网（主/备地址）下做不到。
 */
@Service
public class DownloadService {

    @Autowired
    private DocsConfig docsConfig;

    @Autowired
    private SignService signService;

    @Autowired
    private ObjectStoreService objectStoreService;

    /**
     * 生成下载地址。存储不支持服务端读写时返回原存储地址（保持旧行为）。
     */
    public String downloadUrl(PanFile file, int versionNo, String userId, HttpServletRequest request) {
        if (!objectStoreService.isSupported()) {
            return file.getStorageUrl();
        }
        long exp = System.currentTimeMillis() / 1000 + docsConfig.getDownloadTtlSeconds();
        String sig = signService.signDownload(file.getId(), versionNo, userId, exp);
        return externalBase(request) + docsConfig.getPanPublicPath() + "/dl/" + file.getId()
            + "?v=" + versionNo
            + "&u=" + URLEncoder.encode(userId, StandardCharsets.UTF_8)
            + "&e=" + exp
            + "&s=" + sig;
    }

    /**
     * 客户端访问本服务时用的 scheme://host（NG 转发时带 Host 与 X-Forwarded-Proto）。
     * 主/备双网下各自用各自的入口，所以按请求取而不是写死在配置里。
     */
    public static String externalBase(HttpServletRequest request) {
        String host = request.getHeader("Host");
        if (host == null || host.isEmpty()) {
            return "";
        }
        String proto = request.getHeader("X-Forwarded-Proto");
        if (proto == null || proto.isEmpty()) {
            proto = request.getScheme();
        }
        return proto + "://" + host;
    }
}
