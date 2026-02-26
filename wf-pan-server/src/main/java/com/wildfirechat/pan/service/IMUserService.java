package com.wildfirechat.pan.service;

import cn.wildfirechat.common.ErrorCode;
import cn.wildfirechat.pojos.InputOutputUserInfo;
import cn.wildfirechat.sdk.UserAdmin;
import cn.wildfirechat.sdk.model.IMResult;
import com.wildfirechat.pan.dto.vo.UserInfoVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class IMUserService {
    
    // 用户信息缓存，1天过期
    private final ConcurrentHashMap<String, UserCacheEntry> userCache = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 24 * 60 * 60 * 1000; // 24小时
    
    /**
     * 获取用户显示名称
     */
    public String getUserDisplayName(String userId) {
        UserInfoVO userInfo = getUserInfoVO(userId);
        return userInfo != null ? userInfo.getDisplayName() : userId;
    }
    
    /**
     * 获取用户完整信息（带缓存）- 返回原始IM对象
     */
    public InputOutputUserInfo getUserInfo(String userId) {
        if (userId == null || userId.isEmpty()) {
            return null;
        }
        
        // 检查缓存
        UserCacheEntry cached = userCache.get(userId);
        if (cached != null && !cached.isExpired()) {
            return convertToInputOutputUserInfo(cached.getUserInfo());
        }
        
        // 从IM服务获取
        return fetchInputOutputUserInfoFromIM(userId);
    }
    
    /**
     * 从IM服务获取原始用户信息
     */
    private InputOutputUserInfo fetchInputOutputUserInfoFromIM(String userId) {
        log.debug("Fetching raw user info from IM SDK, userId: {}", userId);
        try {
            IMResult<InputOutputUserInfo> imResult = UserAdmin.getUserByUserId(userId);
            if (imResult != null && imResult.getErrorCode() == ErrorCode.ERROR_CODE_SUCCESS) {
                // 缓存结果
                InputOutputUserInfo imUser = imResult.getResult();
                if (imUser != null) {
                    UserInfoVO vo = convertToUserInfoVO(imUser);
                    userCache.put(userId, new UserCacheEntry(vo, System.currentTimeMillis()));
                }
                return imUser;
            }
        } catch (Exception e) {
            log.error("Failed to fetch user info from IM SDK, userId: {}", userId, e);
        }
        return null;
    }
    
    /**
     * 转换 UserInfoVO 到 InputOutputUserInfo
     */
    private InputOutputUserInfo convertToInputOutputUserInfo(UserInfoVO vo) {
        if (vo == null) return null;
        InputOutputUserInfo info = new InputOutputUserInfo();
        info.setUserId(vo.getUserId());
        info.setName(vo.getName());
        info.setDisplayName(vo.getDisplayName());
        info.setPortrait(vo.getPortrait());
        info.setMobile(vo.getMobile());
        info.setEmail(vo.getEmail());
        info.setAddress(vo.getAddress());
        info.setCompany(vo.getCompany());
        info.setExtra(vo.getExtra());
        return info;
    }
    
    /**
     * 转换 InputOutputUserInfo 到 UserInfoVO
     */
    private UserInfoVO convertToUserInfoVO(InputOutputUserInfo info) {
        if (info == null) return null;
        UserInfoVO vo = new UserInfoVO();
        vo.setUserId(info.getUserId());
        vo.setName(info.getName());
        vo.setDisplayName(getDisplayName(info));
        vo.setPortrait(info.getPortrait());
        vo.setMobile(info.getMobile());
        vo.setEmail(info.getEmail());
        vo.setAddress(info.getAddress());
        vo.setCompany(info.getCompany());
        vo.setExtra(info.getExtra());
        return vo;
    }
    
    /**
     * 获取用户完整信息（带缓存）- 返回VO对象
     */
    public UserInfoVO getUserInfoVO(String userId) {
        if (userId == null || userId.isEmpty()) {
            return null;
        }
        
        // 1. 检查缓存
        UserCacheEntry cached = userCache.get(userId);
        if (cached != null && !cached.isExpired()) {
            log.debug("User info cache hit: {}", userId);
            return cached.getUserInfo();
        }
        
        // 2. 从IM服务获取
        UserInfoVO userInfo = fetchUserInfoFromIM(userId);
        
        // 3. 缓存结果
        if (userInfo != null) {
            userCache.put(userId, new UserCacheEntry(userInfo, System.currentTimeMillis()));
            log.debug("User info cached: {}", userId);
        }
        
        return userInfo;
    }
    
    /**
     * 从IM服务获取用户信息
     */
    private UserInfoVO fetchUserInfoFromIM(String userId) {
        log.debug("Fetching user info from IM SDK, userId: {}", userId);
        try {
            IMResult<InputOutputUserInfo> imResult = UserAdmin.getUserByUserId(userId);
            if (imResult != null && imResult.getErrorCode() == ErrorCode.ERROR_CODE_SUCCESS) {
                InputOutputUserInfo imUser = imResult.getResult();
                return convertToUserInfoVO(imUser);
            }
        } catch (Exception e) {
            log.error("Failed to fetch user info from IM SDK, userId: {}", userId, e);
        }
        
        // 如果获取失败，返回基本对象
        UserInfoVO vo = new UserInfoVO();
        vo.setUserId(userId);
        vo.setDisplayName(userId);
        return vo;
    }
    
    /**
     * 获取显示名称
     */
    private String getDisplayName(InputOutputUserInfo userInfo) {
        if (userInfo.getDisplayName() != null && !userInfo.getDisplayName().isEmpty()) {
            return userInfo.getDisplayName();
        } else if (userInfo.getName() != null && !userInfo.getName().isEmpty()) {
            return userInfo.getName();
        }
        return userInfo.getUserId();
    }
    
    /**
     * 清理过期缓存
     */
    public void cleanExpiredCache() {
        long now = System.currentTimeMillis();
        int beforeSize = userCache.size();
        userCache.entrySet().removeIf(entry -> entry.getValue().isExpired(now));
        int afterSize = userCache.size();
        log.info("Cleaned user cache: {} -> {} entries", beforeSize, afterSize);
    }
    
    /**
     * 清除指定用户的缓存
     */
    public void clearUserCache(String userId) {
        userCache.remove(userId);
        log.info("User cache cleared: {}", userId);
    }
    
    /**
     * 缓存条目
     */
    private static class UserCacheEntry {
        private final UserInfoVO userInfo;
        private final long timestamp;
        
        UserCacheEntry(UserInfoVO userInfo, long timestamp) {
            this.userInfo = userInfo;
            this.timestamp = timestamp;
        }
        
        UserInfoVO getUserInfo() {
            return userInfo;
        }
        
        boolean isExpired() {
            return isExpired(System.currentTimeMillis());
        }
        
        boolean isExpired(long now) {
            return (now - timestamp) > CACHE_TTL_MS;
        }
    }
}
