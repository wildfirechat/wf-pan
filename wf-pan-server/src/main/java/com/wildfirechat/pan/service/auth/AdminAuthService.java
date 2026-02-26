package com.wildfirechat.pan.service.auth;

import com.wildfirechat.pan.dto.request.LoginRequest;
import com.wildfirechat.pan.entity.SysConfig;
import com.wildfirechat.pan.repository.PanGlobalAdminRepository;
import com.wildfirechat.pan.repository.SysConfigRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@Slf4j
public class AdminAuthService {
    
    @Autowired
    private SysConfigRepository sysConfigRepository;
    
    @Autowired
    private PanGlobalAdminRepository globalAdminRepository;
    
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    
    /**
     * 验证管理员登录
     * 首先检查是否是全局管理员，然后验证配置的密码
     */
    public boolean validateLogin(LoginRequest request) {
        // 1. 先检查是否是全局管理员
        if (!globalAdminRepository.existsByUserId(request.getUsername())) {
            log.warn("Login attempt for non-admin user: {}", request.getUsername());
            return false;
        }
        
        // 2. 验证密码
        Optional<SysConfig> passwordConfig = sysConfigRepository.findByConfigKey("admin.default.password_hash");
        if (passwordConfig.isPresent()) {
            String storedHash = passwordConfig.get().getConfigValue();
            return passwordEncoder.matches(request.getPassword(), storedHash);
        }
        
        return false;
    }
    
    /**
     * 更新管理员密码
     */
    public void updatePassword(String newPassword) {
        String hashedPassword = passwordEncoder.encode(newPassword);
        
        Optional<SysConfig> config = sysConfigRepository.findByConfigKey("admin.default.password_hash");
        if (config.isPresent()) {
            config.get().setConfigValue(hashedPassword);
            sysConfigRepository.save(config.get());
        } else {
            SysConfig newConfig = new SysConfig();
            newConfig.setConfigKey("admin.default.password_hash");
            newConfig.setConfigValue(hashedPassword);
            newConfig.setDescription("管理员密码");
            sysConfigRepository.save(newConfig);
        }
    }
    
    /**
     * 生成密码哈希
     */
    public String generatePasswordHash(String password) {
        return passwordEncoder.encode(password);
    }
}
