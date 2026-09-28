package com.wildfirechat.pan.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ShareIdRequest {
    
    @NotNull(message = "分享ID不能为空")
    private Long shareId;
}
