package com.wildfirechat.pan.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 系统配置服务
 * 配置从 application.properties 读取
 */
@Service
@Slf4j
public class ConfigService {
    
    /**
     * 是否允许管理员管理用户私有空间
     * 配置项: pan.admin.manage-private-space
     * 默认值: false
     */
    @Value("${pan.admin.manage-private-space:false}")
    private boolean adminManagePrivateSpace;
    
    @PostConstruct
    public void init() {
        log.info("配置加载完成: pan.admin.manage-private-space = {}", adminManagePrivateSpace);
    }
    
    /**
     * 管理员是否可以管理用户私有空间
     */
    public boolean isAdminCanManagePrivateSpace() {
        return adminManagePrivateSpace;
    }
}
