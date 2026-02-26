package com.wildfirechat.pan.repository;

import com.wildfirechat.pan.entity.PanSpaceAdmin;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PanSpaceAdminRepository extends JpaRepository<PanSpaceAdmin, Long> {
    
    boolean existsBySpaceIdAndUserId(Long spaceId, String userId);
    
    List<PanSpaceAdmin> findBySpaceId(Long spaceId);
    
    void deleteBySpaceIdAndUserId(Long spaceId, String userId);
}
