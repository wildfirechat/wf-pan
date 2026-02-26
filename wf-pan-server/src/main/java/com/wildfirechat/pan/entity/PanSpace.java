package com.wildfirechat.pan.entity;

import com.wildfirechat.pan.constant.OwnerType;
import com.wildfirechat.pan.constant.SpaceType;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "pan_space", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"space_type", "owner_id"})
})
@Data
public class PanSpace {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Enumerated(EnumType.STRING)
    @Column(name = "space_type", nullable = false, length = 32)
    private SpaceType spaceType;
    
    @Column(name = "owner_id", length = 64)
    private String ownerId;
    
    @Enumerated(EnumType.STRING)
    @Column(name = "owner_type", length = 32)
    private OwnerType ownerType;
    
    @Column(nullable = false, length = 128)
    private String name;
    
    @Column(name = "total_quota", nullable = false)
    private Long totalQuota = 10L * 1024 * 1024 * 1024; // 默认10GB
    
    @Column(name = "used_quota", nullable = false)
    private Long usedQuota = 0L;
    
    @Column(name = "file_count", nullable = false)
    private Integer fileCount = 0;
    
    @Column(name = "folder_count", nullable = false)
    private Integer folderCount = 0;
    
    @Column(name = "auto_init", nullable = false)
    private Boolean autoInit = false;
    
    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();
    
    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
