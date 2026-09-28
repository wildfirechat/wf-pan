package com.wildfirechat.pan.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class GetFileUrlRequest {
    
    @NotNull(message = "文件ID不能为空")
    private Long fileId;
    
    /** 取历史版本时填，默认当前版本 */
    private Integer versionNo;
}
