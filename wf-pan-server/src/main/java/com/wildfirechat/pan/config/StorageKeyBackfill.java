package com.wildfirechat.pan.config;

import com.wildfirechat.pan.entity.PanFile;
import com.wildfirechat.pan.repository.PanFileRepository;
import com.wildfirechat.pan.service.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 为旧版本创建的文件记录回填 storageKey（对象引用计数依赖它）。已回填过的记录不会重复处理。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class StorageKeyBackfill implements ApplicationRunner {

    private final PanFileRepository fileRepository;
    private final StorageService storageService;

    @Override
    public void run(ApplicationArguments args) {
        int total = 0;
        List<PanFile> batch;
        while (!(batch = fileRepository.findTop500ByStorageKeyIsNullAndStorageUrlIsNotNull()).isEmpty()) {
            batch.forEach(file -> file.setStorageKey(storageService.ownedKey(file.getStorageUrl()).orElse("")));
            fileRepository.saveAll(batch);
            total += batch.size();
        }
        if (total > 0) {
            log.info("Backfilled storage key for {} files", total);
        }
    }
}
