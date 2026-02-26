package com.wildfirechat.pan.repository;

import com.wildfirechat.pan.entity.PanDeptMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PanDeptMemberRepository extends JpaRepository<PanDeptMember, Long> {
    
    boolean existsByDeptIdAndUserId(String deptId, String userId);
    
    List<PanDeptMember> findByUserId(String userId);
    
    List<PanDeptMember> findByDeptId(String deptId);
}
