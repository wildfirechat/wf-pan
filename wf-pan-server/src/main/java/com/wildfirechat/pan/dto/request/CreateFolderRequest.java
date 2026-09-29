package com.wildfirechat.pan.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CreateFolderRequest {

    @NotNull(message = "空间ID不能为空")
    private Long spaceId;

    private Long parentId;  // null 或 0 表示根目录

    @FileName
    private String name;
}
