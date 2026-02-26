package com.wildfirechat.pan.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Entity
@Table(name = "pan_operation_log")
@Data
public class PanOperationLog {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;
    
    @Column(nullable = false, length = 64)
    private String operation;
    
    @Column(name = "target_type", length = 32)
    private String targetType;
    
    @Column(name = "target_id")
    private Long targetId;
    
    @Column(name = "space_id")
    private Long spaceId;
    
    @Column(name = "details", columnDefinition = "json")
    private String details;  // MySQL 使用 JSON 类型，存储 JSON 字符串
    
    @Column(name = "ip_address", length = 64)
    private String ipAddress;
    
    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();
}
