package com.wildfirechat.pan.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class StartupInitializer implements ApplicationRunner {
    @Value("${server.port:8081}")
    private int port;

    @Value("${server.admin-port:8080}")
    private int admin_port;

    @Override
    public void run(ApplicationArguments args) {
        log.info("wf-pan-server started successfully");
        log.info("Admin port: {}, Client port: {}", admin_port, port);
    }
}
