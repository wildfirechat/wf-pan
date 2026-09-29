package com.wildfirechat.pan.dto.vo;

import com.wildfirechat.pan.constant.SpaceType;
import com.wildfirechat.pan.entity.PanSpace;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class SpaceVO {

    private long id;
    private SpaceType spaceType;
    private String ownerId;
    private String name;
    private long totalQuota;
    private long usedQuota;
    private int fileCount;
    private int folderCount;
    private boolean autoInit;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // 额外权限信息
    private boolean canManage;

    public static SpaceVO of(PanSpace space, String displayName, boolean canManage) {
        return SpaceVO.builder()
            .id(space.getId())
            .spaceType(space.getSpaceType())
            .ownerId(space.getOwnerId() != null ? space.getOwnerId() : "")
            .name(displayName)
            .totalQuota(space.getTotalQuota())
            .usedQuota(space.getUsedQuota())
            .fileCount(space.getFileCount())
            .folderCount(space.getFolderCount())
            .autoInit(Boolean.TRUE.equals(space.getAutoInit()))
            .createdAt(space.getCreatedAt())
            .updatedAt(space.getUpdatedAt())
            .canManage(canManage)
            .build();
    }
}
