package com.wildfirechat.pan.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CreateFolderRequest {
    
    @NotNull(message = "空间ID不能为空")
    private Long spaceId;
    
    private Long parentId;  // null表示根目录
    
    @NotBlank(message = "文件夹名称不能为空")
    private String name;
}
