package com.wildfirechat.pan.service;

import com.wildfirechat.pan.constant.SpaceType;
import com.wildfirechat.pan.entity.PanFile;
import com.wildfirechat.pan.entity.PanSpace;
import com.wildfirechat.pan.repository.PanGlobalAdminRepository;
import com.wildfirechat.pan.repository.PanSpaceAdminRepository;
import com.wildfirechat.pan.repository.PanSpaceRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

@Service
@Slf4j
public class PermissionService {
    
    @Autowired
    private PanSpaceRepository spaceRepository;
    
    @Autowired
    private PanSpaceAdminRepository spaceAdminRepository;
    
    @Autowired
    private PanGlobalAdminRepository globalAdminRepository;
    
    @Autowired
    private ConfigService configService;
    
    /**
     * 判断管理员是否可以访问/管理用户私有空间
     * 根据配置 admin.manage.private.space 决定
     * 
     * 注意：此方法用于管理后台，userId 为 "admin" 表示管理后台请求
     */
    public boolean canAdminAccessPrivateSpace(String userId) {
        // "admin" 是管理后台的特殊标识
        if ("admin".equals(userId)) {
            return configService.isAdminCanManagePrivateSpace();
        }
        // 其他情况，首先必须是全局管理员
        if (!isGlobalAdmin(userId)) {
            return false;
        }
        // 然后检查配置是否允许
        return configService.isAdminCanManagePrivateSpace();
    }
    
    /**
     * 【预留】判断用户是否属于某个部门
     * 暂返回false，后续实现时从IM服务或同步表查询
     */
    public boolean isUserInDept(String userId, String deptId) {
        // TODO: 后续实现部门成员判断
        // 方案1: 调用IM服务接口查询
        // 方案2: 查询同步的pan_dept_member表
        log.debug("[预留] 判断用户{}是否在部门{}中", userId, deptId);
        return false;
    }
    
    /**
     * 【预留】获取用户所属的所有部门ID
     */
    public List<String> getUserDeptIds(String userId) {
        // TODO: 后续实现
        log.debug("[预留] 获取用户{}的部门列表", userId);
        return Collections.emptyList();
    }
    
    /**
     * 【预留】判断用户是否是部门管理员
     */
    public boolean isDeptAdmin(String userId, String deptId) {
        // TODO: 后续实现
        log.debug("[预留] 判断用户{}是否是部门{}的管理员", userId, deptId);
        return false;
    }
    
    /**
     * 判断是否是全局管理员
     */
    public boolean isGlobalAdmin(String userId) {
        return globalAdminRepository.existsByUserId(userId);
    }
    
    /**
     * 判断用户是否有权限访问空间
     */
    public boolean canAccessSpace(String userId, Long spaceId) {
        PanSpace space = spaceRepository.findById(spaceId).orElse(null);
        if (space == null) return false;
        
        switch (space.getSpaceType()) {
            case GLOBAL_PUBLIC:
            case USER_PUBLIC:
                return true;
            
            case USER_PRIVATE:
                // 空间所有者可以访问
                if (space.getOwnerId().equals(userId)) {
                    return true;
                }
                // 管理员根据配置决定是否可以访问
                return canAdminAccessPrivateSpace(userId);
            
            case DEPT_PUBLIC:
            case DEPT_PRIVATE:
                // 预留：部门成员可访问
                return isUserInDept(userId, space.getOwnerId());
            
            default:
                return false;
        }
    }
    
    /**
     * 判断用户是否有权限管理空间
     */
    public boolean canManageSpace(String userId, Long spaceId) {
        PanSpace space = spaceRepository.findById(spaceId).orElse(null);
        if (space == null) return false;
        
        // 检查是否是空间管理员（部门空间）
        boolean isSpaceAdmin = spaceAdminRepository.existsBySpaceIdAndUserId(spaceId, userId);
        if (isSpaceAdmin) return true;
        
        switch (space.getSpaceType()) {
            case GLOBAL_PUBLIC:
                return isGlobalAdmin(userId);
            
            case USER_PUBLIC:
                return space.getOwnerId().equals(userId);
            
            case USER_PRIVATE:
                // 空间所有者可以管理
                if (space.getOwnerId().equals(userId)) {
                    return true;
                }
                // 管理员根据配置决定是否可以管理
                return canAdminAccessPrivateSpace(userId);
            
            case DEPT_PUBLIC:
            case DEPT_PRIVATE:
                // 预留：部门管理员可管理
                return isDeptAdmin(userId, space.getOwnerId()) || isSpaceAdmin;
            
            default:
                return false;
        }
    }
    
    /**
     * 判断用户是否可以删除文件
     */
    public boolean canDeleteFile(String userId, PanFile file) {
        // 全局管理员可以删除任何文件
        if (isGlobalAdmin(userId)) {
            return true;
        }
        
        // 文件创建者可以删除
        if (file.getCreatorId().equals(userId)) {
            return true;
        }
        
        // 空间管理员可以删除
        return canManageSpace(userId, file.getSpaceId());
    }
}
