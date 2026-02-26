package com.wildfirechat.pan.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class MoveRequest {
    
    @NotNull(message = "文件ID不能为空")
    private Long fileId;
    
    @NotNull(message = "目标父文件夹ID不能为空，根目录传0")
    private Long targetParentId;
    
    @NotNull(message = "目标空间ID不能为空")
    private Long targetSpaceId;
}
