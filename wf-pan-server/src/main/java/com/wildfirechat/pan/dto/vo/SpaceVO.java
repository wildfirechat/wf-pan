package com.wildfirechat.pan.dto.vo;

import com.wildfirechat.pan.constant.SpaceType;
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
}
