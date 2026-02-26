package com.wildfirechat.pan.config;

import com.wildfirechat.pan.constant.OwnerType;
import com.wildfirechat.pan.constant.SpaceType;
import com.wildfirechat.pan.entity.PanGlobalAdmin;
import com.wildfirechat.pan.entity.PanSpace;
import com.wildfirechat.pan.entity.SysConfig;
import com.wildfirechat.pan.repository.PanGlobalAdminRepository;
import com.wildfirechat.pan.repository.PanSpaceRepository;
import com.wildfirechat.pan.repository.SysConfigRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Slf4j
public class DataInitializer implements ApplicationRunner {
    
    @Autowired
    private SysConfigRepository sysConfigRepository;
    
    @Autowired
    private PanGlobalAdminRepository globalAdminRepository;
    
    @Autowired
    private PanSpaceRepository spaceRepository;
    
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    
    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        log.info("Initializing default data...");
        
        initSysConfig();
        initGlobalAdmin();
        initGlobalSpace();
        
        log.info("Data initialization complete");
    }
    
    private void initSysConfig() {
        // 初始化默认管理员账号配置
        if (!sysConfigRepository.findByConfigKey("admin.default.username").isPresent()) {
            SysConfig config = new SysConfig();
            config.setConfigKey("admin.default.username");
            config.setConfigValue("admin");
            config.setDescription("默认管理员账号");
            sysConfigRepository.save(config);
            log.info("Initialized admin.default.username");
        }
        
        // 初始化默认管理员密码（admin123）
        if (!sysConfigRepository.findByConfigKey("admin.default.password_hash").isPresent()) {
            String hashedPassword = passwordEncoder.encode("admin123");
            SysConfig config = new SysConfig();
            config.setConfigKey("admin.default.password_hash");
            config.setConfigValue(hashedPassword);
            config.setDescription("默认管理员密码(admin123)");
            sysConfigRepository.save(config);
            log.info("Initialized admin.default.password_hash");
        }
    }
    
    private void initGlobalAdmin() {
        // 如果没有全局管理员，创建默认管理员
        if (globalAdminRepository.count() == 0) {
            PanGlobalAdmin admin = new PanGlobalAdmin();
            admin.setUserId("admin");
            admin.setUsername("系统管理员");
            admin.setCreatedBy("SYSTEM");
            globalAdminRepository.save(admin);
            log.info("Initialized default global admin: admin");
        }
    }
    
    private void initGlobalSpace() {
        // 创建全局公共空间
        if (!spaceRepository.findBySpaceType(SpaceType.GLOBAL_PUBLIC).isPresent()) {
            PanSpace space = new PanSpace();
            space.setSpaceType(SpaceType.GLOBAL_PUBLIC);
            space.setOwnerId(null);
            space.setOwnerType(OwnerType.SYSTEM);
            space.setName("公共空间");
            space.setTotalQuota(1099511627776L);  // 1TB
            space.setAutoInit(true);
            spaceRepository.save(space);
            log.info("Initialized global public space");
        }
    }
}
