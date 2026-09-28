package com.wildfirechat.pan.config;

import com.wildfirechat.pan.entity.PanFile;
import com.wildfirechat.pan.entity.PanFileVersion;
import com.wildfirechat.pan.repository.PanFileRepository;
import com.wildfirechat.pan.repository.PanFileVersionRepository;
import com.wildfirechat.pan.service.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 为旧版本创建的文件、版本记录回填 storageKey（对象引用计数依赖它）。已回填过的记录不会重复处理。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class StorageKeyBackfill implements ApplicationRunner {

    private final PanFileRepository fileRepository;
    private final PanFileVersionRepository versionRepository;
    private final StorageService storageService;

    @Override
    public void run(ApplicationArguments args) {
        int files = 0;
        List<PanFile> fileBatch;
        while (!(fileBatch = fileRepository.findTop500ByStorageKeyIsNullAndStorageUrlIsNotNull()).isEmpty()) {
            fileBatch.forEach(file -> file.setStorageKey(ownedKey(file.getStorageUrl())));
            fileRepository.saveAll(fileBatch);
            files += fileBatch.size();
        }
        int versions = 0;
        List<PanFileVersion> versionBatch;
        while (!(versionBatch = versionRepository.findTop500ByStorageKeyIsNull()).isEmpty()) {
            versionBatch.forEach(version -> version.setStorageKey(ownedKey(version.getStorageUrl())));
            versionRepository.saveAll(versionBatch);
            versions += versionBatch.size();
        }
        if (files > 0 || versions > 0) {
            log.info("Backfilled storage key for {} files, {} versions", files, versions);
        }
    }

    private String ownedKey(String url) {
        return storageService.ownedKey(url).orElse("");
    }
}
