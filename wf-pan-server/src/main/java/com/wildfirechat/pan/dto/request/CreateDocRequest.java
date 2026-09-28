package com.wildfirechat.pan.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CreateDocRequest {
    
    /** docx / xlsx / pptx */
    @NotBlank(message = "文档类型不能为空")
    private String type;
    
    /** 文件名（不含扩展名也可以），为空时用「未命名文档」等 */
    private String name;
    
    /** 为空时放在个人私有空间的「我的文档」文件夹 */
    private Long spaceId;
    
    private Long parentId;
}
