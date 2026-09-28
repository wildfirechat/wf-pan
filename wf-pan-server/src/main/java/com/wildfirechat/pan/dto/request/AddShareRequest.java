package com.wildfirechat.pan.dto.request;

import com.wildfirechat.pan.constant.FilePermission;
import com.wildfirechat.pan.constant.ShareTargetType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class AddShareRequest {
    
    @NotNull(message = "文件ID不能为空")
    private Long fileId;
    
    @NotNull(message = "分享对象类型不能为空")
    private ShareTargetType targetType;
    
    @NotBlank(message = "分享对象不能为空")
    private String targetId;
    
    /** VIEW / EDIT，默认 VIEW */
    private FilePermission permission = FilePermission.VIEW;
}
