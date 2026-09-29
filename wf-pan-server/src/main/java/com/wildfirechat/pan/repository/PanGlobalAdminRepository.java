package com.wildfirechat.pan.repository;

import com.wildfirechat.pan.entity.PanGlobalAdmin;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PanGlobalAdminRepository extends JpaRepository<PanGlobalAdmin, Long> {

    boolean existsByUserId(String userId);

    Optional<PanGlobalAdmin> findByUserId(String userId);

    List<PanGlobalAdmin> findAllByOrderByCreatedAtDesc();
}
