package com.wildfirechat.pan.dto.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class GlobalAdminVO {
    
    private long id;
    private String userId;
    private String username;
    private String createdBy;
    private LocalDateTime createdAt;
}
