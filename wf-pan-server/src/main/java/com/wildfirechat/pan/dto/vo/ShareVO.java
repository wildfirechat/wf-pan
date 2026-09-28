package com.wildfirechat.pan.dto.vo;

import com.wildfirechat.pan.constant.FilePermission;
import com.wildfirechat.pan.constant.ShareTargetType;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class ShareVO {
    
    private long id;
    private long fileId;
    private ShareTargetType targetType;
    private String targetId;
    private String targetName;
    private String targetPortrait;
    private FilePermission permission;
    private String createdBy;
    private String createdByName;
    private LocalDateTime createdAt;
}
