package com.wildfirechat.pan.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class StartupInitializer implements ApplicationRunner {
    
    @Override
    public void run(ApplicationArguments args) {
        log.info("wf-pan-server started successfully");
        log.info("Admin port: 8080, Client port: 8081");
    }
}
