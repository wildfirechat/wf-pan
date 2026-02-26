package com.wildfirechat.pan.entity;

import com.wildfirechat.pan.constant.FileType;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "pan_file")
@Data
public class PanFile {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "space_id", nullable = false)
    private Long spaceId;
    
    @Column(name = "parent_id")
    private Long parentId;
    
    @Column(nullable = false, length = 255)
    private String name;
    
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FileType type;
    
    @Column
    private Long size = 0L;
    
    @Column(name = "mime_type", length = 128)
    private String mimeType;
    
    @Column(length = 32)
    private String md5;
    
    @Column(name = "storage_url", columnDefinition = "TEXT")
    private String storageUrl;
    
    @Column(name = "child_count")
    private Integer childCount = 0;
    
    @Column(name = "creator_id", nullable = false, length = 64)
    private String creatorId;
    
    @Column(name = "creator_name", length = 128)
    private String creatorName;
    
    @Column(name = "is_deleted", nullable = false)
    private Boolean isDeleted = false;
    
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;
    
    @Column(name = "deleted_by", length = 64)
    private String deletedBy;
    
    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();
    
    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
