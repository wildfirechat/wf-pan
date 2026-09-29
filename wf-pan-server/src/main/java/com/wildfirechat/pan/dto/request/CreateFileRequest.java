package com.wildfirechat.pan.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateFileRequest {

    @NotNull(message = "空间ID不能为空")
    private Long spaceId;

    private Long parentId;  // null 或 0 表示根目录

    @FileName
    private String name;

    /**
     * 客户端上报的文件大小；文件在网盘 bucket 中时以对象存储的实际大小为准
     */
    @NotNull(message = "文件大小不能为空")
    @PositiveOrZero(message = "文件大小不能为负数")
    private Long size;

    @Size(max = 128, message = "mimeType不能超过128个字符")
    private String mimeType;

    @Size(max = 32, message = "md5不能超过32个字符")
    private String md5;

    @NotBlank(message = "存储URL不能为空")
    @Size(max = 760, message = "存储URL过长")
    private String storageUrl;

    /**
     * 是否需要拷贝物理文件到Pan bucket
     * true: 检查目标bucket是否存在文件，不存在则拷贝（用于从文件消息保存）
     * false: 仅创建文件记录，不拷贝物理文件（用于客户端直接上传）
     */
    private Boolean copy = false;
}
