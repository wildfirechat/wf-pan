package com.wildfirechat.pan.entity;

import com.wildfirechat.pan.constant.FilePermission;
import com.wildfirechat.pan.constant.ShareTargetType;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文件分享（v1 只支持文件，不支持文件夹）
 */
@Entity
@Table(name = "pan_share",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_share", columnNames = {"file_id", "target_type", "target_id"})
    },
    indexes = {
        @Index(name = "idx_share_target", columnList = "target_type, target_id")
    })
@Data
public class PanShare {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "file_id", nullable = false)
    private Long fileId;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 8)
    private ShareTargetType targetType;

    @Column(name = "target_id", nullable = false, length = 64)
    private String targetId;

    /** 只取 VIEW / EDIT */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private FilePermission permission;

    @Column(name = "created_by", nullable = false, length = 64)
    private String createdBy;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();
}
