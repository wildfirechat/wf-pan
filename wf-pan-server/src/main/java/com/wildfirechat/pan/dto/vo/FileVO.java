package com.wildfirechat.pan.dto.vo;

import com.wildfirechat.pan.constant.FileType;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class FileVO {
    
    private long id;
    private long spaceId;
    private long parentId;
    private String name;
    private FileType type;
    private long size;
    private String mimeType;
    private String md5;
    private String storageUrl;
    private int childCount;
    private String creatorId;
    private String creatorName;
    private String creatorPortrait;  // 创建者头像
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
