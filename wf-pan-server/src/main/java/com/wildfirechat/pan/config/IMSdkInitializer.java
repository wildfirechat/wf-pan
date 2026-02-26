package com.wildfirechat.pan.config;

import cn.wildfirechat.sdk.AdminConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class IMSdkInitializer implements ApplicationRunner {

    @Autowired
    private IMServerConfig imServerConfig;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        log.info("Initializing Wildfire IM SDK...");
        
        if (imServerConfig.getAdminUrl() == null || imServerConfig.getAdminUrl().isEmpty()) {
            log.warn("IM Server admin URL not configured, skipping IM SDK initialization");
            return;
        }
        
        log.info("IM Server Admin URL: {}", imServerConfig.getAdminUrl());

        // 初始化野火IM SDK
        AdminConfig.initAdmin(imServerConfig.getAdminUrl(), imServerConfig.getAdminSecret());

        log.info("Wildfire IM SDK initialized successfully");
    }
}
