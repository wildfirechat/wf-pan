package com.wildfirechat.pan.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Entity
@Table(name = "pan_dept_member", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"dept_id", "user_id"})
})
@Data
public class PanDeptMember {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "dept_id", nullable = false, length = 64)
    private String deptId;
    
    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;
    
    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();
}
