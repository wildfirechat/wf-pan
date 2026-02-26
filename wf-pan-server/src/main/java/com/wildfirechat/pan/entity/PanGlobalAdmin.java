package com.wildfirechat.pan.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Entity
@Table(name = "pan_global_admin")
@Data
public class PanGlobalAdmin {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "user_id", nullable = false, unique = true, length = 64)
    private String userId;
    
    @Column(length = 128)
    private String username;
    
    @Column(name = "created_by", length = 64)
    private String createdBy;
    
    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();
}
