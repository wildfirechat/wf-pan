package com.wildfirechat.pan.repository;

import com.wildfirechat.pan.entity.PanGlobalAdmin;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PanGlobalAdminRepository extends JpaRepository<PanGlobalAdmin, Long> {
    
    boolean existsByUserId(String userId);
    
    void deleteByUserId(String userId);
    
    long count();
    
    List<PanGlobalAdmin> findAllByOrderByCreatedAtDesc();
}
