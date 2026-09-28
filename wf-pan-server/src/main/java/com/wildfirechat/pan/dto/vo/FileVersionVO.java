package com.wildfirechat.pan.dto.vo;

import com.wildfirechat.pan.constant.VersionSource;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class FileVersionVO {
    
    private long fileId;
    private int versionNo;
    private long size;
    private VersionSource source;
    private String editorId;
    private String editorName;
    private boolean current;
    private LocalDateTime createdAt;
}
