package com.wildfirechat.pan.repository;

import com.wildfirechat.pan.constant.ShareTargetType;
import com.wildfirechat.pan.entity.PanShare;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface PanShareRepository extends JpaRepository<PanShare, Long> {

    List<PanShare> findByFileIdOrderByCreatedAtAsc(Long fileId);

    Optional<PanShare> findByFileIdAndTargetTypeAndTargetId(Long fileId, ShareTargetType targetType, String targetId);

    List<PanShare> findByFileIdAndTargetType(Long fileId, ShareTargetType targetType);

    List<PanShare> findByTargetTypeAndTargetId(ShareTargetType targetType, String targetId);

    List<PanShare> findByTargetTypeAndTargetIdIn(ShareTargetType targetType, Collection<String> targetIds);

    @Modifying
    @Query("DELETE FROM PanShare s WHERE s.fileId = :fileId")
    void deleteByFileId(@Param("fileId") Long fileId);
}
