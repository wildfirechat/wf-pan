package com.wildfirechat.pan.service;

import cn.wildfirechat.common.ErrorCode;
import cn.wildfirechat.pojos.OutputGroupIds;
import cn.wildfirechat.pojos.PojoGroupInfo;
import cn.wildfirechat.pojos.PojoGroupMember;
import cn.wildfirechat.sdk.GroupAdmin;
import cn.wildfirechat.sdk.model.IMResult;
import com.wildfirechat.pan.config.DocsConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * IM 群信息（群分享的权限判定用）。成员关系缓存 pan.group_cache_seconds（默认 60 秒），
 * 所以退群/被移出后最长这么久才失去群分享的权限。
 */
@Service
@Slf4j
public class IMGroupService {

    /** 野火群成员类型：4 = 已移出 */
    private static final int MEMBER_TYPE_REMOVED = 4;
    private static final long NAME_TTL_MS = 10 * 60 * 1000;

    @Autowired
    private DocsConfig docsConfig;

    private final ConcurrentHashMap<String, Entry<Boolean>> memberCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Entry<List<String>>> userGroupsCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Entry<String>> nameCache = new ConcurrentHashMap<>();

    /** 用户此刻是否是该群成员（查不到按"不是"处理） */
    public boolean isMember(String groupId, String userId) {
        return cached(memberCache, groupId + "|" + userId, ttl(), () -> {
            try {
                IMResult<PojoGroupMember> r = GroupAdmin.getGroupMember(groupId, userId);
                return r != null && r.getErrorCode() == ErrorCode.ERROR_CODE_SUCCESS && r.getResult() != null
                    && userId.equals(r.getResult().getMember_id())
                    && r.getResult().getType() != MEMBER_TYPE_REMOVED;
            } catch (Exception e) {
                log.error("查询群成员失败 group={} user={}", groupId, userId, e);
                return false;
            }
        });
    }

    /** 用户所在的群 */
    public List<String> getUserGroupIds(String userId) {
        return cached(userGroupsCache, userId, ttl(), () -> {
            try {
                IMResult<OutputGroupIds> r = GroupAdmin.getUserGroups(userId);
                if (r != null && r.getErrorCode() == ErrorCode.ERROR_CODE_SUCCESS && r.getResult() != null
                        && r.getResult().getGroupIds() != null) {
                    return r.getResult().getGroupIds();
                }
            } catch (Exception e) {
                log.error("查询用户所在群失败 user={}", userId, e);
            }
            return Collections.emptyList();
        });
    }

    /** 群名称（查不到返回群 id） */
    public String getGroupName(String groupId) {
        return cached(nameCache, groupId, NAME_TTL_MS, () -> {
            try {
                IMResult<PojoGroupInfo> r = GroupAdmin.getGroupInfo(groupId);
                if (r != null && r.getErrorCode() == ErrorCode.ERROR_CODE_SUCCESS && r.getResult() != null
                        && r.getResult().getName() != null && !r.getResult().getName().isEmpty()) {
                    return r.getResult().getName();
                }
            } catch (Exception e) {
                log.error("查询群信息失败 group={}", groupId, e);
            }
            return groupId;
        });
    }

    /** 群是否存在（分享给群时校验） */
    public boolean exists(String groupId) {
        try {
            IMResult<PojoGroupInfo> r = GroupAdmin.getGroupInfo(groupId);
            return r != null && r.getErrorCode() == ErrorCode.ERROR_CODE_SUCCESS && r.getResult() != null
                && !r.getResult().isDeleted();
        } catch (Exception e) {
            log.error("查询群信息失败 group={}", groupId, e);
            return false;
        }
    }

    private long ttl() {
        return docsConfig.getGroupCacheSeconds() * 1000L;
    }

    private static <T> T cached(ConcurrentHashMap<String, Entry<T>> cache, String key, long ttlMs, Supplier<T> loader) {
        long now = System.currentTimeMillis();
        Entry<T> e = cache.get(key);
        if (e != null && now - e.at < ttlMs) {
            return e.value;
        }
        T v = loader.get();
        cache.put(key, new Entry<>(v, now));
        if (cache.size() > 50_000) {
            cache.entrySet().removeIf(x -> now - x.getValue().at >= ttlMs);
        }
        return v;
    }

    private record Entry<T>(T value, long at) {}
}
