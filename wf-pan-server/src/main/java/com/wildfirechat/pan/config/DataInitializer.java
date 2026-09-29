package com.wildfirechat.pan.config;

import com.wildfirechat.pan.constant.OwnerType;
import com.wildfirechat.pan.constant.SpaceType;
import com.wildfirechat.pan.entity.PanSpace;
import com.wildfirechat.pan.repository.PanSpaceRepository;
import com.wildfirechat.pan.service.auth.AdminAuthService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Slf4j
public class DataInitializer implements ApplicationRunner {

    @Autowired
    private AdminAuthService adminAuthService;

    @Autowired
    private PanSpaceRepository spaceRepository;

    @Value("${pan.admin.initial-password:}")
    private String initialAdminPassword;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        log.info("Initializing default data...");

        adminAuthService.ensureInitialAdmin(initialAdminPassword);
        initGlobalSpace();

        log.info("Data initialization complete");
    }

    private void initGlobalSpace() {
        if (!spaceRepository.existsBySpaceType(SpaceType.GLOBAL_PUBLIC)) {
            PanSpace space = new PanSpace();
            space.setSpaceType(SpaceType.GLOBAL_PUBLIC);
            space.setOwnerType(OwnerType.SYSTEM);
            space.setName("公共空间");
            space.setTotalQuota(1099511627776L);  // 1TB
            space.setAutoInit(true);
            spaceRepository.save(space);
            log.info("Initialized global public space");
        }
    }
}
