package com.wildfirechat.pan.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CreateFileRequest {
    
    @NotNull(message = "空间ID不能为空")
    private Long spaceId;
    
    private Long parentId;  // null表示根目录
    
    @NotBlank(message = "文件名不能为空")
    private String name;
    
    @NotNull(message = "文件大小不能为空")
    private Long size;
    
    private String mimeType;
    
    private String md5;
    
    @NotBlank(message = "存储URL不能为空")
    private String storageUrl;
    
    /**
     * 是否需要拷贝物理文件到Pan bucket
     * true: 检查目标bucket是否存在文件，不存在则拷贝（用于从文件消息保存）
     * false: 仅创建文件记录，不拷贝物理文件（用于客户端直接上传）
     */
    private Boolean copy = false;
}
