package com.wildfirechat.pan.service;

import com.wildfirechat.pan.constant.FilePermission;
import com.wildfirechat.pan.constant.FileType;
import com.wildfirechat.pan.constant.ShareTargetType;
import com.wildfirechat.pan.constant.SpaceType;
import com.wildfirechat.pan.entity.PanFile;
import com.wildfirechat.pan.entity.PanShare;
import com.wildfirechat.pan.entity.PanSpace;
import com.wildfirechat.pan.repository.PanGlobalAdminRepository;
import com.wildfirechat.pan.repository.PanShareRepository;
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
    private final PanShareRepository shareRepository;
    private final IMGroupService groupService;

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
     * 用户对文件的实际权限：空间规则、单独分享、群分享三者取最大。
     * 下载、打开编辑器、查看版本等按单个文件访问的入口都走这里。
     */
    public FilePermission effectivePermission(String userId, PanFile file) {
        if (userId == null || file == null || Boolean.TRUE.equals(file.getIsDeleted())) {
            return FilePermission.NONE;
        }
        PanSpace space = spaceRepository.findById(file.getSpaceId()).orElse(null);
        if (space == null) {
            return FilePermission.NONE;
        }
        if (canManageSpace(userId, space)) {
            return FilePermission.EDIT;
        }
        FilePermission perm = canAccessSpace(userId, space) ? FilePermission.VIEW : FilePermission.NONE;
        // 分享只针对文件（v1 不支持文件夹分享）
        if (file.getType() != FileType.FILE) {
            return perm;
        }
        PanShare userShare = shareRepository
            .findByFileIdAndTargetTypeAndTargetId(file.getId(), ShareTargetType.USER, userId).orElse(null);
        if (userShare != null) {
            perm = FilePermission.max(perm, userShare.getPermission());
        }
        if (perm == FilePermission.EDIT) {
            return perm;
        }
        for (PanShare groupShare : shareRepository.findByFileIdAndTargetType(file.getId(), ShareTargetType.GROUP)) {
            if (groupShare.getPermission().ordinal() <= perm.ordinal()) {
                continue;
            }
            if (groupService.isMember(groupShare.getTargetId(), userId)) {
                perm = FilePermission.max(perm, groupShare.getPermission());
            }
        }
        return perm;
    }

    /**
     * 谁能分享：文件所在空间的管理者（本人、空间管理员、全局管理员）。v1 可编辑者不能再转分享。
     */
    public boolean canShare(String userId, PanFile file) {
        return file != null && file.getType() == FileType.FILE && canManageSpace(userId, file.getSpaceId());
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
