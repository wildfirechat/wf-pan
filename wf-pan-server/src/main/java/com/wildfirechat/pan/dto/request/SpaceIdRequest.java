package com.wildfirechat.pan.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class SpaceIdRequest {
    
    @NotNull(message = "空间ID不能为空")
    private Long spaceId;
}
