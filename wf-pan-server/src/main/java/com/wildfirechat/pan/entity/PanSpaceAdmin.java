package com.wildfirechat.pan.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Entity
@Table(name = "pan_space_admin", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"space_id", "user_id"})
})
@Data
public class PanSpaceAdmin {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "space_id", nullable = false)
    private Long spaceId;
    
    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;
    
    @Column(name = "admin_type", length = 32)
    private String adminType = "ADMIN"; // ADMIN or SUPER
    
    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();
}
