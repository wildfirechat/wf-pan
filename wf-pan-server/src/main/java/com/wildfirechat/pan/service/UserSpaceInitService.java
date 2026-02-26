package com.wildfirechat.pan.service;

import com.wildfirechat.pan.constant.OwnerType;
import com.wildfirechat.pan.constant.SpaceType;
import com.wildfirechat.pan.dto.vo.SpaceVO;
import com.wildfirechat.pan.dto.vo.UserSpacesVO;
import com.wildfirechat.pan.entity.PanSpace;
import com.wildfirechat.pan.repository.PanSpaceRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
public class UserSpaceInitService {
    
    @Autowired
    private PanSpaceRepository spaceRepository;
    
    private static final long DEFAULT_USER_QUOTA = 10L * 1024 * 1024 * 1024; // 10GB
    
    /**
     * 获取或初始化用户空间
     */
    @Transactional
    public UserSpacesVO getOrInitUserSpaces(String userId, String username) {
        // 查询用户公共空间
        PanSpace publicSpace = spaceRepository
            .findBySpaceTypeAndOwnerId(SpaceType.USER_PUBLIC, userId)
            .orElse(null);
        
        // 查询用户私有空间
        PanSpace privateSpace = spaceRepository
            .findBySpaceTypeAndOwnerId(SpaceType.USER_PRIVATE, userId)
            .orElse(null);
        
        // 自动初始化
        if (publicSpace == null) {
            log.info("Auto initializing user public space for user: {}", userId);
            publicSpace = createUserSpace(userId, username, SpaceType.USER_PUBLIC, "我的公开空间");
        }
        
        if (privateSpace == null) {
            log.info("Auto initializing user private space for user: {}", userId);
            privateSpace = createUserSpace(userId, username, SpaceType.USER_PRIVATE, "我的私有空间");
        }
        
        return UserSpacesVO.builder()
            .publicSpace(convertToVO(publicSpace))
            .privateSpace(convertToVO(privateSpace))
            .build();
    }
    
    private PanSpace createUserSpace(String userId, String username, SpaceType type, String name) {
        PanSpace space = new PanSpace();
        space.setSpaceType(type);
        space.setOwnerId(userId);
        space.setOwnerType(OwnerType.USER);
        space.setName(name);
        space.setTotalQuota(DEFAULT_USER_QUOTA);
        space.setAutoInit(true);
        return spaceRepository.save(space);
    }
    
    private SpaceVO convertToVO(PanSpace space) {
        return SpaceVO.builder()
            .id(space.getId() != null ? space.getId() : 0L)
            .spaceType(space.getSpaceType())
            .ownerId(space.getOwnerId() != null ? space.getOwnerId() : "")
            .name(space.getName() != null ? space.getName() : "")
            .totalQuota(space.getTotalQuota() != null ? space.getTotalQuota() : 0L)
            .usedQuota(space.getUsedQuota() != null ? space.getUsedQuota() : 0L)
            .fileCount(space.getFileCount() != null ? space.getFileCount() : 0)
            .folderCount(space.getFolderCount() != null ? space.getFolderCount() : 0)
            .autoInit(space.getAutoInit() != null ? space.getAutoInit() : false)
            .createdAt(space.getCreatedAt())
            .updatedAt(space.getUpdatedAt())
            .build();
    }
}
