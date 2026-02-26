package com.wildfirechat.pan.service.auth;

import cn.wildfirechat.common.ErrorCode;
import cn.wildfirechat.pojos.OutputApplicationUserInfo;
import cn.wildfirechat.sdk.UserAdmin;
import cn.wildfirechat.sdk.model.IMResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class ClientAuthService {
    
    // 简单的本地缓存，生产环境建议使用 Redis
    private final ConcurrentHashMap<String, AuthCacheEntry> authCache = new ConcurrentHashMap<>();
    
    // 缓存有效期 5 分钟
    private static final long CACHE_TTL_MS = 5 * 60 * 1000;
    
    /**
     * 验证 authCode 并返回 userId
     * 使用野火IM SDK验证
     */
    public String validateAuthCode(String authCode) {
        if (authCode == null || authCode.isEmpty()) {
            return null;
        }
        
        // 1. 检查缓存
        AuthCacheEntry cached = authCache.get(authCode);
        if (cached != null && !cached.isExpired()) {
            log.debug("Auth code validated from cache");
            return cached.getUserId();
        }
        
        // 2. 使用野火IM SDK验证
        String userId = validateWithIMServer(authCode);
        
        if (userId != null) {
            // 缓存结果
            authCache.put(authCode, new AuthCacheEntry(userId, System.currentTimeMillis()));
        }
        
        return userId;
    }
    
    /**
     * 使用野火IM SDK验证 authCode
     */
    private String validateWithIMServer(String authCode) {
        log.info("Validating authCode with IM SDK, authCode: {}", authCode);
        try {
            IMResult<OutputApplicationUserInfo> imResult = UserAdmin.applicationGetUserInfo(authCode);
            if (imResult != null && imResult.getErrorCode() == ErrorCode.ERROR_CODE_SUCCESS) {
                String userId = imResult.getResult().getUserId();
                log.info("Auth code validated, userId: {}", userId);
                return userId;
            } else {
                log.warn("Invalid authCode, errorCode: {}", imResult != null ? imResult.getErrorCode() : "null");
            }
        } catch (Exception e) {
            log.error("Failed to validate authCode with IM SDK", e);
        }
        return null;
    }
    
    /**
     * 清理过期的缓存
     */
    public void cleanExpiredCache() {
        long now = System.currentTimeMillis();
        authCache.entrySet().removeIf(entry -> entry.getValue().isExpired(now));
    }
    
    /**
     * 缓存条目
     */
    private static class AuthCacheEntry {
        private final String userId;
        private final long timestamp;
        
        AuthCacheEntry(String userId, long timestamp) {
            this.userId = userId;
            this.timestamp = timestamp;
        }
        
        String getUserId() {
            return userId;
        }
        
        boolean isExpired() {
            return isExpired(System.currentTimeMillis());
        }
        
        boolean isExpired(long now) {
            return (now - timestamp) > CACHE_TTL_MS;
        }
    }
}
