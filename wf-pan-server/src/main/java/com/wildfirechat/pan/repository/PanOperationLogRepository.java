package com.wildfirechat.pan.repository;

import com.wildfirechat.pan.entity.PanOperationLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

@Repository
public interface PanOperationLogRepository extends JpaRepository<PanOperationLog, Long> {
    
    Page<PanOperationLog> findAllByOrderByCreatedAtDesc(Pageable pageable);
    
    Page<PanOperationLog> findByUserIdOrderByCreatedAtDesc(String userId, Pageable pageable);
    
    Page<PanOperationLog> findByOperationOrderByCreatedAtDesc(String operation, Pageable pageable);
    
    @Query("SELECT l FROM PanOperationLog l WHERE l.createdAt >= :startTime ORDER BY l.createdAt DESC")
    Page<PanOperationLog> findByCreatedAtAfterOrderByCreatedAtDesc(@Param("startTime") LocalDateTime startTime, Pageable pageable);
    
    @Modifying
    @Query("DELETE FROM PanOperationLog l WHERE l.createdAt < :beforeTime")
    int deleteByCreatedAtBefore(@Param("beforeTime") LocalDateTime beforeTime);
    
    @Modifying
    @Query("DELETE FROM PanOperationLog l WHERE l.id IN (SELECT l2.id FROM PanOperationLog l2 ORDER BY l2.createdAt DESC OFFSET :keepCount)")
    int deleteOldLogsKeepRecent(@Param("keepCount") long keepCount);
}
