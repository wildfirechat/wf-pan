package com.wildfirechat.pan.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CopyRequest {
    
    @NotNull(message = "文件ID不能为空")
    private Long fileId;
    
    @NotNull(message = "目标父文件夹ID不能为空，根目录传0")
    private Long targetParentId;
    
    @NotNull(message = "目标空间ID不能为空")
    private Long targetSpaceId;
    
    /**
     * 是否需要拷贝物理文件
     * true: 检查目标bucket是否存在文件，不存在则拷贝
     * false: 仅创建文件记录，不拷贝物理文件
     * 
     * 客户端上传时: false
     * 从文件消息保存时: true
     */
    private Boolean copy = false;
}
