package com.wildfirechat.pan.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class EditorConfigRequest {
    
    @NotNull(message = "文件ID不能为空")
    private Long fileId;
    
    /** pc / mobile。手机端一律只读（社区版的手机网页端不能编辑） */
    private String platform = "pc";
    
    /** true = 即使有编辑权也按只读打开 */
    private Boolean view = false;
}
