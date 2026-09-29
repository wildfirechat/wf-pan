package com.wildfirechat.pan.repository;

import com.wildfirechat.pan.entity.PanSpaceAdmin;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PanSpaceAdminRepository extends JpaRepository<PanSpaceAdmin, Long> {

    boolean existsBySpaceIdAndUserId(Long spaceId, String userId);
}
