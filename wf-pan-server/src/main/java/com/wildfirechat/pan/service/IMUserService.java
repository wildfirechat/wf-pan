package com.wildfirechat.pan.service;

import cn.wildfirechat.common.ErrorCode;
import cn.wildfirechat.pojos.InputOutputUserInfo;
import cn.wildfirechat.sdk.UserAdmin;
import cn.wildfirechat.sdk.model.IMResult;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.wildfirechat.pan.dto.vo.UserInfoVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.Optional;

/**
 * IM 用户信息（带缓存）。查询成功缓存 24 小时；查询失败缓存 1 分钟，避免 IM 不可用时反复请求。
 */
@Service
@Slf4j
public class IMUserService {

    private static final Duration FOUND_TTL = Duration.ofHours(24);
    private static final Duration MISSING_TTL = Duration.ofMinutes(1);

    /**
     * @param info 查询失败时为 null
     */
    private record CachedUser(UserInfoVO info) {
    }

    private final Cache<String, CachedUser> userCache = Caffeine.newBuilder()
        .maximumSize(100_000)
        .expireAfter(new Expiry<String, CachedUser>() {
            @Override
            public long expireAfterCreate(String key, CachedUser value, long currentTime) {
                return (value.info() != null ? FOUND_TTL : MISSING_TTL).toNanos();
            }

            @Override
            public long expireAfterUpdate(String key, CachedUser value, long currentTime, long currentDuration) {
                return expireAfterCreate(key, value, currentTime);
            }

            @Override
            public long expireAfterRead(String key, CachedUser value, long currentTime, long currentDuration) {
                return currentDuration;
            }
        })
        .build();

    /**
     * IM 中的用户信息；用户不存在或 IM 不可用时为空
     */
    public Optional<UserInfoVO> findUserInfo(String userId) {
        if (!StringUtils.hasText(userId)) {
            return Optional.empty();
        }
        return Optional.ofNullable(userCache.get(userId, id -> new CachedUser(fetchFromIM(id))).info());
    }

    /**
     * 用户信息；查不到时返回只有 userId 的对象（显示名称也是 userId）
     */
    public UserInfoVO getUserInfoVO(String userId) {
        return findUserInfo(userId).orElseGet(() -> {
            UserInfoVO vo = new UserInfoVO();
            vo.setUserId(userId);
            vo.setDisplayName(userId);
            return vo;
        });
    }

    public String getUserDisplayName(String userId) {
        return getUserInfoVO(userId).getDisplayName();
    }

    private UserInfoVO fetchFromIM(String userId) {
        try {
            IMResult<InputOutputUserInfo> imResult = UserAdmin.getUserByUserId(userId);
            if (imResult != null && imResult.getErrorCode() == ErrorCode.ERROR_CODE_SUCCESS && imResult.getResult() != null) {
                return toVO(imResult.getResult());
            }
            log.debug("User not found in IM: {}", userId);
        } catch (Exception e) {
            log.warn("Failed to fetch user info from IM, userId: {}", userId, e);
        }
        return null;
    }

    private static UserInfoVO toVO(InputOutputUserInfo info) {
        UserInfoVO vo = new UserInfoVO();
        vo.setUserId(info.getUserId());
        vo.setName(info.getName());
        vo.setDisplayName(displayNameOf(info));
        vo.setPortrait(info.getPortrait());
        vo.setMobile(info.getMobile());
        vo.setEmail(info.getEmail());
        vo.setAddress(info.getAddress());
        vo.setCompany(info.getCompany());
        vo.setExtra(info.getExtra());
        return vo;
    }

    private static String displayNameOf(InputOutputUserInfo info) {
        if (StringUtils.hasText(info.getDisplayName())) {
            return info.getDisplayName();
        }
        if (StringUtils.hasText(info.getName())) {
            return info.getName();
        }
        return info.getUserId();
    }
}
