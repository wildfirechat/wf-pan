package com.wildfirechat.pan.repository;

import com.wildfirechat.pan.constant.SpaceType;
import com.wildfirechat.pan.entity.PanSpace;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PanSpaceRepository extends JpaRepository<PanSpace, Long> {
    
    Optional<PanSpace> findBySpaceTypeAndOwnerId(SpaceType spaceType, String ownerId);
    
    Optional<PanSpace> findBySpaceType(SpaceType spaceType);
    
    List<PanSpace> findByOwnerId(String ownerId);
    
    @Query("SELECT s FROM PanSpace s WHERE " +
           "(s.spaceType = 'GLOBAL_PUBLIC') OR " +
           "(s.spaceType = 'USER_PUBLIC') OR " +
           "(s.ownerId = :userId) " +
           "ORDER BY s.createdAt DESC")
    List<PanSpace> findAccessibleSpaces(@Param("userId") String userId);
    
    @Query("SELECT s FROM PanSpace s WHERE s.spaceType = 'DEPT_PUBLIC' OR s.spaceType = 'DEPT_PRIVATE'")
    List<PanSpace> findAllDeptSpaces();
}
