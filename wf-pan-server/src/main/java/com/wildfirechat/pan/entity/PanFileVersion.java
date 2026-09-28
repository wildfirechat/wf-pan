package com.wildfirechat.pan.entity;

import com.wildfirechat.pan.constant.VersionSource;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文件版本。每个版本对应一个独立的存储对象（写时复制，绝不覆盖）
 */
@Entity
@Table(name = "pan_file_version",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_ver", columnNames = {"file_id", "version_no"})
    },
    indexes = {
        @Index(name = "idx_ver_storage_key", columnList = "storage_key")
    })
@Data
public class PanFileVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "file_id", nullable = false)
    private Long fileId;

    @Column(name = "version_no", nullable = false)
    private Integer versionNo;

    @Column(name = "storage_url", nullable = false, length = 760)
    private String storageUrl;

    /**
     * 网盘 bucket 中的对象 key，含义同 {@link PanFile#getStorageKey()}
     */
    @Column(name = "storage_key", length = 760)
    private String storageKey;

    @Column
    private Long size = 0L;

    @Column(length = 32)
    private String md5;

    @Column(name = "editor_id", length = 64)
    private String editorId;

    @Column(name = "editor_name", length = 128)
    private String editorName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private VersionSource source;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();
}
