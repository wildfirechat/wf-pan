package com.wildfirechat.pan.repository;

import com.wildfirechat.pan.constant.FileType;
import com.wildfirechat.pan.entity.PanFile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface PanFileRepository extends JpaRepository<PanFile, Long> {
    
    List<PanFile> findBySpaceIdAndParentIdAndIsDeletedFalseOrderByTypeDescNameAsc(
            Long spaceId, Long parentId);
    
    List<PanFile> findBySpaceIdAndParentIdAndIsDeletedFalse(Long spaceId, Long parentId);
    
    long countByParentIdAndIsDeletedFalse(Long parentId);
    
    Optional<PanFile> findByIdAndIsDeletedFalse(Long id);
    
    @Modifying
    @Query("UPDATE PanFile f SET f.childCount = f.childCount + 1 WHERE f.id = :parentId")
    void incrementChildCount(@Param("parentId") Long parentId);
    
    @Modifying
    @Query("UPDATE PanFile f SET f.childCount = f.childCount - 1 WHERE f.id = :parentId")
    void decrementChildCount(@Param("parentId") Long parentId);
    
    @Query("SELECT f FROM PanFile f WHERE f.spaceId = :spaceId AND f.isDeleted = false " +
           "AND (f.name LIKE %:keyword% OR f.creatorName LIKE %:keyword%)")
    Page<PanFile> searchBySpaceId(@Param("spaceId") Long spaceId, 
                                   @Param("keyword") String keyword, 
                                   Pageable pageable);
    
    @Query("SELECT f FROM PanFile f WHERE f.isDeleted = false " +
           "AND (f.name LIKE %:keyword% OR f.creatorName LIKE %:keyword%)")
    Page<PanFile> globalSearch(@Param("keyword") String keyword, Pageable pageable);
    
    /**
     * 获取所有未删除的文件（分页）
     */
    Page<PanFile> findByIsDeletedFalseOrderByCreatedAtDesc(Pageable pageable);
    
    List<PanFile> findByParentIdAndTypeAndIsDeletedFalse(Long parentId, FileType type);
    
    List<PanFile> findByParentIdAndIsDeletedFalse(Long parentId);
    
    /**
     * 统计唯一创建者数量（实际用户数）
     */
    @Query("SELECT COUNT(DISTINCT f.creatorId) FROM PanFile f WHERE f.isDeleted = false")
    long countDistinctCreators();
    
    /**
     * 统计文件数（不包括文件夹）
     */
    @Query("SELECT COUNT(f) FROM PanFile f WHERE f.type = 'FILE' AND f.isDeleted = false")
    long countFilesOnly();
    
    /**
     * 统计文件夹数
     */
    @Query("SELECT COUNT(f) FROM PanFile f WHERE f.type = 'FOLDER' AND f.isDeleted = false")
    long countFoldersOnly();
    
    /**
     * 统计所有文件的总大小
     */
    @Query("SELECT COALESCE(SUM(f.size), 0) FROM PanFile f WHERE f.type = 'FILE' AND f.isDeleted = false")
    long sumFileSize();
    
    /**
     * 统计今日上传数
     */
    @Query("SELECT COUNT(f) FROM PanFile f WHERE f.isDeleted = false AND f.createdAt >= :today")
    long countTodayUploads(@Param("today") LocalDateTime today);
    
    /**
     * 统计使用相同storageUrl的未删除文件数量（用于判断是否可删除OSS对象）
     */
    long countByStorageUrlAndIsDeletedFalse(String storageUrl);
}
