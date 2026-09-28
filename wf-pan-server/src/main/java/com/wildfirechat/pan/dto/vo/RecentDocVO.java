package com.wildfirechat.pan.dto.vo;

import com.wildfirechat.pan.constant.FilePermission;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class RecentDocVO {
    
    private FileVO file;
    private FilePermission permission;
    private LocalDateTime openedAt;
}
