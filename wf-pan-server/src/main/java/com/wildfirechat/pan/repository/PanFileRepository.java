package com.wildfirechat.pan.repository;

import com.wildfirechat.pan.entity.PanFile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface PanFileRepository extends JpaRepository<PanFile, Long> {

    String NOT_IN_PRIVATE_SPACE = "(:includePrivate = true OR f.spaceId NOT IN " +
        "(SELECT s.id FROM PanSpace s WHERE s.spaceType = com.wildfirechat.pan.constant.SpaceType.USER_PRIVATE))";

    List<PanFile> findBySpaceIdAndParentIdAndIsDeletedFalseOrderByTypeDescNameAsc(Long spaceId, Long parentId);

    boolean existsBySpaceIdAndParentIdAndNameIgnoreCaseAndIsDeletedFalse(Long spaceId, Long parentId, String name);

    boolean existsBySpaceIdAndParentIdAndNameIgnoreCaseAndIsDeletedFalseAndIdNot(
            Long spaceId, Long parentId, String name, Long id);

    long countByParentIdAndIsDeletedFalse(Long parentId);

    List<PanFile> findByParentIdInAndIsDeletedFalse(Collection<Long> parentIds);

    Optional<PanFile> findByIdAndIsDeletedFalse(Long id);

    @Modifying
    @Query("UPDATE PanFile f SET f.childCount = f.childCount + 1 WHERE f.id = :parentId")
    void incrementChildCount(@Param("parentId") Long parentId);

    @Modifying
    @Query("UPDATE PanFile f SET f.childCount = f.childCount - 1 WHERE f.id = :parentId AND f.childCount > 0")
    void decrementChildCount(@Param("parentId") Long parentId);

    @Query("SELECT f FROM PanFile f WHERE f.spaceId = :spaceId AND f.isDeleted = false " +
           "AND (f.name LIKE %:keyword% OR f.creatorName LIKE %:keyword%)")
    Page<PanFile> searchBySpaceId(@Param("spaceId") Long spaceId,
                                  @Param("keyword") String keyword,
                                  Pageable pageable);

    /**
     * 管理后台全局搜索，includePrivate=false 时排除用户私有空间
     */
    @Query("SELECT f FROM PanFile f WHERE f.isDeleted = false " +
           "AND (f.name LIKE %:keyword% OR f.creatorName LIKE %:keyword%) AND " + NOT_IN_PRIVATE_SPACE)
    Page<PanFile> globalSearch(@Param("keyword") String keyword,
                               @Param("includePrivate") boolean includePrivate,
                               Pageable pageable);

    /**
     * 管理后台文件列表，includePrivate=false 时排除用户私有空间
     */
    @Query("SELECT f FROM PanFile f WHERE f.isDeleted = false AND " + NOT_IN_PRIVATE_SPACE +
           " ORDER BY f.createdAt DESC")
    Page<PanFile> findAllForConsole(@Param("includePrivate") boolean includePrivate, Pageable pageable);

    /**
     * 统计唯一创建者数量（实际用户数）
     */
    @Query("SELECT COUNT(DISTINCT f.creatorId) FROM PanFile f WHERE f.isDeleted = false")
    long countDistinctCreators();

    @Query("SELECT COUNT(f) FROM PanFile f WHERE f.type = 'FILE' AND f.isDeleted = false")
    long countFilesOnly();

    @Query("SELECT COUNT(f) FROM PanFile f WHERE f.type = 'FOLDER' AND f.isDeleted = false")
    long countFoldersOnly();

    @Query("SELECT COALESCE(SUM(f.size), 0) FROM PanFile f WHERE f.type = 'FILE' AND f.isDeleted = false")
    long sumFileSize();

    @Query("SELECT COUNT(f) FROM PanFile f WHERE f.isDeleted = false AND f.createdAt >= :today")
    long countTodayUploads(@Param("today") LocalDateTime today);

    /**
     * 统计引用同一对象的未删除文件数量（用于判断是否可删除OSS对象）
     */
    long countByStorageKeyAndIsDeletedFalse(String storageKey);

    /**
     * 尚未回填 storageKey 的文件
     */
    List<PanFile> findTop500ByStorageKeyIsNullAndStorageUrlIsNotNull();
}
