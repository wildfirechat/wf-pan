package com.wildfirechat.pan.entity;

import com.wildfirechat.pan.constant.FileType;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "pan_file", 
    indexes = {
        @Index(name = "idx_storage_key_deleted", columnList = "storage_key, is_deleted"),
        @Index(name = "idx_space_parent", columnList = "space_id, parent_id, is_deleted"),
        @Index(name = "idx_parent_id", columnList = "parent_id, is_deleted")
    })
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
    
    @Column(name = "storage_url", length = 760)
    private String storageUrl;

    /**
     * 网盘 bucket 中的对象 key，用于统计引用数和删除对象。
     * 空串表示对象不在网盘 bucket 中（不归网盘管理）；null 表示旧数据尚未回填
     */
    @Column(name = "storage_key", length = 760)
    private String storageKey;

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
