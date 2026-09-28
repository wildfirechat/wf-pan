package com.wildfirechat.pan.service;

import com.wildfirechat.pan.config.DocsConfig;
import com.wildfirechat.pan.util.Hs256Jwt;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 本服务自签的短时凭证：下载链接、在线文档网页会话
 */
@Service
@Slf4j
public class SignService {

    @Autowired
    private DocsConfig docsConfig;

    private String secret;

    @PostConstruct
    public void init() {
        secret = docsConfig.getSignSecret();
        if (secret == null || secret.isEmpty()) {
            byte[] b = new byte[32];
            new SecureRandom().nextBytes(b);
            secret = HexFormat.of().formatHex(b);
            log.warn("pan.sign_secret 未配置，已随机生成（重启后已发出的下载链接与网页会话失效）");
        }
    }

    /** 下载签名：绑定文件、版本、用户、到期时间 */
    public String signDownload(long fileId, int versionNo, String userId, long expireEpochSec) {
        return Hs256Jwt.hmacB64("dl|" + fileId + "|" + versionNo + "|" + userId + "|" + expireEpochSec, secret);
    }

    public boolean verifyDownload(long fileId, int versionNo, String userId, long expireEpochSec, String sig) {
        if (sig == null || expireEpochSec < System.currentTimeMillis() / 1000) {
            return false;
        }
        return constEq(signDownload(fileId, versionNo, userId, expireEpochSec), sig);
    }

    /** 网页会话 Cookie 值：userId.到期时间.签名 */
    public String issueWebSession(String userId) {
        long exp = System.currentTimeMillis() / 1000 + docsConfig.getWebSessionTtlSeconds();
        String uid = Base64.getUrlEncoder().withoutPadding().encodeToString(userId.getBytes(StandardCharsets.UTF_8));
        return uid + "." + exp + "." + Hs256Jwt.hmacB64("ws|" + uid + "|" + exp, secret);
    }

    /** 校验网页会话，通过返回 userId，否则 null */
    public String verifyWebSession(String value) {
        if (value == null) return null;
        String[] p = value.split("\\.");
        if (p.length != 3) return null;
        try {
            long exp = Long.parseLong(p[1]);
            if (exp < System.currentTimeMillis() / 1000) return null;
            if (!constEq(Hs256Jwt.hmacB64("ws|" + p[0] + "|" + exp, secret), p[2])) return null;
            return new String(Base64.getUrlDecoder().decode(p[0]), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean constEq(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
