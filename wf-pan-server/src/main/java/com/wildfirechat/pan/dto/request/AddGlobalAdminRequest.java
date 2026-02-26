package com.wildfirechat.pan.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AddGlobalAdminRequest {
    
    @NotBlank(message = "用户ID不能为空")
    private String userId;
    
    private String username;
}
