package com.wildfirechat.pan.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class GetUserPublicSpaceRequest {
    
    @NotBlank(message = "目标用户ID不能为空")
    private String targetUserId;
}
