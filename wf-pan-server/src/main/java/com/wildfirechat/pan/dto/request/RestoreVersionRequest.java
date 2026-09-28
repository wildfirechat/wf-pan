package com.wildfirechat.pan.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class RestoreVersionRequest {
    
    @NotNull(message = "文件ID不能为空")
    private Long fileId;
    
    @NotNull(message = "版本号不能为空")
    private Integer versionNo;
}
