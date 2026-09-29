package com.wildfirechat.pan.service.auth;

import cn.wildfirechat.common.ErrorCode;
import cn.wildfirechat.pojos.OutputApplicationUserInfo;
import cn.wildfirechat.sdk.UserAdmin;
import cn.wildfirechat.sdk.model.IMResult;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
@Slf4j
public class ClientAuthService {

    // 只缓存验证成功的 authCode；单机缓存，多实例部署时各自缓存
    private final Cache<String, String> authCache = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofMinutes(5))
        .maximumSize(100_000)
        .build();

    /**
     * 验证 authCode 并返回 userId，无效时返回 null
     */
    public String validateAuthCode(String authCode) {
        if (authCode == null || authCode.isEmpty()) {
            return null;
        }

        String userId = authCache.getIfPresent(authCode);
        if (userId == null) {
            userId = validateWithIMServer(authCode);
            if (userId != null) {
                authCache.put(authCode, userId);
            }
        }
        return userId;
    }

    // authCode 本身就是用户凭证，任何情况下都不要写进日志
    private String validateWithIMServer(String authCode) {
        try {
            IMResult<OutputApplicationUserInfo> imResult = UserAdmin.applicationGetUserInfo(authCode);
            if (imResult != null && imResult.getErrorCode() == ErrorCode.ERROR_CODE_SUCCESS
                    && imResult.getResult() != null) {
                return imResult.getResult().getUserId();
            }
            log.warn("AuthCode rejected by IM server, errorCode: {}", imResult != null ? imResult.getErrorCode() : null);
        } catch (Exception e) {
            log.error("Failed to validate authCode with IM server", e);
        }
        return null;
    }
}
