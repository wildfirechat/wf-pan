package com.wildfirechat.pan.repository;

import com.wildfirechat.pan.entity.PanRecent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PanRecentRepository extends JpaRepository<PanRecent, Long> {

    Optional<PanRecent> findByUserIdAndFileId(String userId, Long fileId);

    List<PanRecent> findByUserIdOrderByOpenedAtDesc(String userId, Pageable pageable);

    @Modifying
    @Query("DELETE FROM PanRecent r WHERE r.fileId = :fileId")
    void deleteByFileId(@Param("fileId") Long fileId);

    @Modifying
    @Query("DELETE FROM PanRecent r WHERE r.userId = :userId AND r.fileId = :fileId")
    void deleteByUserIdAndFileId(@Param("userId") String userId, @Param("fileId") Long fileId);
}
