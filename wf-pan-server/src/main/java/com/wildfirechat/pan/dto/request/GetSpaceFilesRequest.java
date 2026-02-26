package com.wildfirechat.pan.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class GetSpaceFilesRequest {
    
    @NotNull(message = "空间ID不能为空")
    private Long spaceId;
    
    // 父文件夹ID，根目录传0或不传
    private Long parentId = 0L;
}
