package com.wildfirechat.pan.service;

import com.wildfirechat.pan.constant.FileType;
import com.wildfirechat.pan.constant.VersionSource;
import com.wildfirechat.pan.entity.PanFile;
import com.wildfirechat.pan.entity.PanSpace;
import com.wildfirechat.pan.repository.PanFileRepository;
import com.wildfirechat.pan.repository.PanFileVersionRepository;
import com.wildfirechat.pan.service.StorageService.StoredObject;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 不加 @Transactional：对象在事务提交后才删除，需要真实提交
 */
@SpringBootTest
@ActiveProfiles("test")
class FileVersionServiceTest {

    @Autowired
    private FileVersionService fileVersionService;

    @Autowired
    private FileService fileService;

    @Autowired
    private UserSpaceInitService userSpaceInitService;

    @Autowired
    private PanFileRepository fileRepository;

    @Autowired
    private PanFileVersionRepository versionRepository;

    @MockBean
    private StorageService storageService;

    private PanFile fileRecord(PanSpace space, String userId, String name, String key) {
        PanFile file = new PanFile();
        file.setSpaceId(space.getId());
        file.setName(name);
        file.setType(FileType.FILE);
        file.setSize(10L);
        file.setStorageUrl("http://minio.example.com/wf-pan/" + key);
        file.setStorageKey(key);
        file.setCreatorId(userId);
        return fileRepository.save(file);
    }

    @Test
    void objectsAreDeletedOnlyWhenNoFileOrVersionReferencesThem() {
        String alice = "alice-" + UUID.randomUUID();
        PanSpace space = userSpaceInitService.getOrInitUserSpaces(alice).get(1);
        String original = "k-" + UUID.randomUUID();
        String edited = "k-" + UUID.randomUUID();
        PanFile file = fileRecord(space, alice, "a.docx", original);
        // 复制出来的文件与原文件共用对象
        PanFile copy = fileRecord(space, alice, "a-copy.docx", original);

        fileVersionService.addVersion(file.getId(), new StoredObject("http://minio.example.com/wf-pan/" + edited, edited),
            20L, "md5", VersionSource.EDIT, alice, alice, true);
        assertThat(versionRepository.findByFileIdOrderByVersionNoDesc(file.getId()))
            .extracting(v -> v.getStorageKey())
            .containsExactly(edited, original);
        assertThat(fileRepository.findById(file.getId()).orElseThrow().getStorageKey()).isEqualTo(edited);

        fileService.deleteFile(file.getId(), alice);
        verify(storageService).deleteObject(edited);
        verify(storageService, never()).deleteObject(original);
        assertThat(versionRepository.findByFileIdOrderByVersionNoDesc(file.getId())).isEmpty();

        fileService.deleteFile(copy.getId(), alice);
        verify(storageService).deleteObject(original);
    }

    @Test
    void objectsNotInPanBucketAreNeverDeleted() {
        String alice = "alice-" + UUID.randomUUID();
        PanSpace space = userSpaceInitService.getOrInitUserSpaces(alice).get(1);
        PanFile file = fileRecord(space, alice, "b.docx", "");

        fileService.deleteFile(file.getId(), alice);
        verify(storageService, never()).deleteObject(anyString());
    }
}
