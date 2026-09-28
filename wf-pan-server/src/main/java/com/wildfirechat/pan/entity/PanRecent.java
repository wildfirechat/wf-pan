package com.wildfirechat.pan.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 最近打开（在线文档首页用），每人每文件一行，打开时更新时间
 */
@Entity
@Table(name = "pan_recent",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_recent", columnNames = {"user_id", "file_id"})
    },
    indexes = {
        @Index(name = "idx_recent_user_time", columnList = "user_id, opened_at")
    })
@Data
public class PanRecent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Column(name = "file_id", nullable = false)
    private Long fileId;

    @Column(name = "opened_at", nullable = false)
    private LocalDateTime openedAt = LocalDateTime.now();
}
