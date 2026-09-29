package com.wildfirechat.pan.service;

import com.wildfirechat.pan.constant.SpaceType;
import com.wildfirechat.pan.entity.PanFile;
import com.wildfirechat.pan.entity.PanSpace;
import com.wildfirechat.pan.repository.PanGlobalAdminRepository;
import com.wildfirechat.pan.repository.PanSpaceAdminRepository;
import com.wildfirechat.pan.repository.PanSpaceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 客户端用户（IM userId）对空间和文件的权限判断。
 * 管理后台不是 IM 用户，使用 {@link #canConsoleAccessSpace(PanSpace)}。
 */
@Service
@RequiredArgsConstructor
public class PermissionService {

    private final PanSpaceRepository spaceRepository;
    private final PanSpaceAdminRepository spaceAdminRepository;
    private final PanGlobalAdminRepository globalAdminRepository;
    private final ConfigService configService;

    public boolean isGlobalAdmin(String userId) {
        return globalAdminRepository.existsByUserId(userId);
    }

    /**
     * 管理后台是否可以访问该空间：用户私有空间受配置 pan.admin.manage-private-space 控制
     */
    public boolean canConsoleAccessSpace(PanSpace space) {
        return space.getSpaceType() != SpaceType.USER_PRIVATE || configService.isAdminCanManagePrivateSpace();
    }

    public boolean canAccessSpace(String userId, Long spaceId) {
        return spaceRepository.findById(spaceId).map(space -> canAccessSpace(userId, space)).orElse(false);
    }

    public boolean canAccessSpace(String userId, PanSpace space) {
        return switch (space.getSpaceType()) {
            case GLOBAL_PUBLIC, USER_PUBLIC -> true;
            case USER_PRIVATE -> isOwner(userId, space) || canGlobalAdminAccessPrivateSpace(userId);
        };
    }

    public boolean canManageSpace(String userId, Long spaceId) {
        return spaceRepository.findById(spaceId).map(space -> canManageSpace(userId, space)).orElse(false);
    }

    public boolean canManageSpace(String userId, PanSpace space) {
        if (spaceAdminRepository.existsBySpaceIdAndUserId(space.getId(), userId)) {
            return true;
        }
        return switch (space.getSpaceType()) {
            case GLOBAL_PUBLIC -> isGlobalAdmin(userId);
            case USER_PUBLIC -> isOwner(userId, space);
            case USER_PRIVATE -> isOwner(userId, space) || canGlobalAdminAccessPrivateSpace(userId);
        };
    }

    /**
     * 空间管理者、文件创建者可以删除；全局管理员可以删除非私有空间中的任何文件
     */
    public boolean canDeleteFile(String userId, PanFile file) {
        PanSpace space = spaceRepository.findById(file.getSpaceId()).orElse(null);
        if (space == null) {
            return false;
        }
        if (userId.equals(file.getCreatorId()) || canManageSpace(userId, space)) {
            return true;
        }
        return space.getSpaceType() != SpaceType.USER_PRIVATE && isGlobalAdmin(userId);
    }

    private boolean canGlobalAdminAccessPrivateSpace(String userId) {
        return configService.isAdminCanManagePrivateSpace() && isGlobalAdmin(userId);
    }

    private static boolean isOwner(String userId, PanSpace space) {
        return userId.equals(space.getOwnerId());
    }
}
