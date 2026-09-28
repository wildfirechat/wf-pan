package com.wildfirechat.pan.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class FileIdRequest {
    
    @NotNull(message = "文件ID不能为空")
    private Long fileId;
}
