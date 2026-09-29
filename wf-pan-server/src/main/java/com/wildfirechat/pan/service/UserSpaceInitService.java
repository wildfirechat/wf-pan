package com.wildfirechat.pan.service;

import com.wildfirechat.pan.constant.OwnerType;
import com.wildfirechat.pan.constant.SpaceType;
import com.wildfirechat.pan.entity.PanSpace;
import com.wildfirechat.pan.repository.PanSpaceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class UserSpaceInitService {

    private static final long DEFAULT_USER_QUOTA = 10L * 1024 * 1024 * 1024; // 10GB

    private final PanSpaceRepository spaceRepository;

    /**
     * 获取用户的公共空间和私有空间，不存在时自动创建
     *
     * @return [公共空间, 私有空间]
     */
    public List<PanSpace> getOrInitUserSpaces(String userId) {
        return List.of(
            getOrCreate(userId, SpaceType.USER_PUBLIC, "我的公开空间"),
            getOrCreate(userId, SpaceType.USER_PRIVATE, "我的私有空间"));
    }

    private PanSpace getOrCreate(String userId, SpaceType type, String name) {
        return spaceRepository.findBySpaceTypeAndOwnerId(type, userId).orElseGet(() -> {
            PanSpace space = new PanSpace();
            space.setSpaceType(type);
            space.setOwnerId(userId);
            space.setOwnerType(OwnerType.USER);
            space.setName(name);
            space.setTotalQuota(DEFAULT_USER_QUOTA);
            space.setAutoInit(true);
            try {
                PanSpace saved = spaceRepository.save(space);
                log.info("Auto initialized {} space for user: {}", type, userId);
                return saved;
            } catch (DataIntegrityViolationException e) {
                // 同一用户的首次请求并发到达，另一个请求已创建（唯一约束 space_type + owner_id）
                return spaceRepository.findBySpaceTypeAndOwnerId(type, userId).orElseThrow(() -> e);
            }
        });
    }
}
