package com.wildfirechat.pan.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class DeleteFileRequest {
    
    @NotNull(message = "文件ID不能为空")
    private Long fileId;
}
