package com.wildfirechat.pan.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class WebSessionRequest {
    
    @NotBlank(message = "authCode 不能为空")
    private String authCode;
}
