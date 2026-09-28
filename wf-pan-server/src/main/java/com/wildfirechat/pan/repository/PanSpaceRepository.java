package com.wildfirechat.pan.repository;

import com.wildfirechat.pan.constant.SpaceType;
import com.wildfirechat.pan.entity.PanSpace;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface PanSpaceRepository extends JpaRepository<PanSpace, Long> {

    Optional<PanSpace> findBySpaceTypeAndOwnerId(SpaceType spaceType, String ownerId);

    boolean existsBySpaceType(SpaceType spaceType);

    Page<PanSpace> findBySpaceTypeIn(Collection<SpaceType> spaceTypes, Pageable pageable);

    @Query("SELECT s FROM PanSpace s WHERE " +
           "(s.spaceType = 'GLOBAL_PUBLIC') OR " +
           "(s.ownerId = :userId) " +
           "ORDER BY CASE WHEN s.spaceType = 'GLOBAL_PUBLIC' THEN 0 ELSE 1 END, " +
           "s.createdAt DESC")
    List<PanSpace> findAccessibleSpaces(@Param("userId") String userId);

    /**
     * 在不超过总配额的前提下增加已用配额
     *
     * @return 1 表示成功，0 表示配额不足或空间不存在
     */
    @Modifying
    @Query("UPDATE PanSpace s SET s.usedQuota = s.usedQuota + :size " +
           "WHERE s.id = :id AND s.usedQuota + :size <= s.totalQuota")
    int tryIncreaseUsedQuota(@Param("id") Long id, @Param("size") long size);

    @Modifying
    @Query("UPDATE PanSpace s SET s.usedQuota = s.usedQuota + :size WHERE s.id = :id")
    void increaseUsedQuota(@Param("id") Long id, @Param("size") long size);

    @Modifying
    @Query("UPDATE PanSpace s SET s.usedQuota = " +
           "CASE WHEN s.usedQuota > :size THEN s.usedQuota - :size ELSE 0 END WHERE s.id = :id")
    void decreaseUsedQuota(@Param("id") Long id, @Param("size") long size);

    @Modifying
    @Query("UPDATE PanSpace s SET " +
           "s.fileCount = CASE WHEN s.fileCount + :files > 0 THEN s.fileCount + :files ELSE 0 END, " +
           "s.folderCount = CASE WHEN s.folderCount + :folders > 0 THEN s.folderCount + :folders ELSE 0 END " +
           "WHERE s.id = :id")
    void adjustCounts(@Param("id") Long id, @Param("files") int files, @Param("folders") int folders);
}
