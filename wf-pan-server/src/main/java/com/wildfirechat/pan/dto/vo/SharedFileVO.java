package com.wildfirechat.pan.dto.vo;

import com.wildfirechat.pan.constant.FilePermission;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 共享给我的文件：同一文件去重，取最高权限，列出来源（某人直接分享 / 经某群）
 */
@Data
@Builder
public class SharedFileVO {
    
    private FileVO file;
    private FilePermission permission;
    private List<ShareVO> sources;
    private LocalDateTime sharedAt;
}
