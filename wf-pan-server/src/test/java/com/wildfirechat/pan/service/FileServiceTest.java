package com.wildfirechat.pan.service;

import com.wildfirechat.pan.dto.request.CopyRequest;
import com.wildfirechat.pan.dto.request.CreateFileRequest;
import com.wildfirechat.pan.dto.request.CreateFolderRequest;
import com.wildfirechat.pan.dto.request.MoveRequest;
import com.wildfirechat.pan.dto.request.RenameRequest;
import com.wildfirechat.pan.dto.vo.FileVO;
import com.wildfirechat.pan.entity.PanFile;
import com.wildfirechat.pan.entity.PanSpace;
import com.wildfirechat.pan.exception.BusinessException;
import com.wildfirechat.pan.repository.PanFileRepository;
import com.wildfirechat.pan.repository.PanSpaceRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class FileServiceTest {

    @Autowired
    private FileService fileService;

    @Autowired
    private UserSpaceInitService userSpaceInitService;

    @Autowired
    private PanFileRepository fileRepository;

    @Autowired
    private PanSpaceRepository spaceRepository;

    @Autowired
    private EntityManager entityManager;

    private String alice;
    private PanSpace alicePublic;
    private PanSpace alicePrivate;
    private PanSpace bobPrivate;

    @BeforeEach
    void setUp() {
        alice = "alice-" + UUID.randomUUID();
        List<PanSpace> aliceSpaces = userSpaceInitService.getOrInitUserSpaces(alice);
        alicePublic = aliceSpaces.get(0);
        alicePrivate = aliceSpaces.get(1);
        bobPrivate = userSpaceInitService.getOrInitUserSpaces("bob-" + UUID.randomUUID()).get(1);
    }

    private FileVO folder(PanSpace space, Long parentId, String name) {
        CreateFolderRequest request = new CreateFolderRequest();
        request.setSpaceId(space.getId());
        request.setParentId(parentId);
        request.setName(name);
        return fileService.createFolder(request, alice);
    }

    private FileVO file(PanSpace space, Long parentId, String name, long size) {
        CreateFileRequest request = new CreateFileRequest();
        request.setSpaceId(space.getId());
        request.setParentId(parentId);
        request.setName(name);
        request.setSize(size);
        request.setStorageUrl("https://files.example.com/" + name);
        return fileService.createFile(request, alice);
    }

    private PanSpace reload(PanSpace space) {
        entityManager.flush();
        entityManager.clear();
        return spaceRepository.findById(space.getId()).orElseThrow();
    }

    @Test
    void copyingFolderIntoItsOwnSubfolderIsRejected() {
        FileVO a = folder(alicePublic, null, "a");
        FileVO b = folder(alicePublic, a.getId(), "b");

        CopyRequest request = new CopyRequest();
        request.setFileId(a.getId());
        request.setTargetSpaceId(alicePublic.getId());
        request.setTargetParentId(b.getId());

        assertThatThrownBy(() -> fileService.copyFile(request, alice))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("自身或其子目录");
    }

    @Test
    void copyingFolderCopiesWholeTreeAndChargesQuota() {
        FileVO a = folder(alicePublic, null, "a");
        FileVO b = folder(alicePublic, a.getId(), "b");
        file(alicePublic, b.getId(), "x.txt", 100);
        file(alicePublic, a.getId(), "y.txt", 50);

        CopyRequest request = new CopyRequest();
        request.setFileId(a.getId());
        request.setTargetSpaceId(alicePrivate.getId());
        request.setTargetParentId(0L);
        FileVO copy = fileService.copyFile(request, alice);

        assertThat(copy.getChildCount()).isEqualTo(2);
        PanSpace target = reload(alicePrivate);
        assertThat(target.getUsedQuota()).isEqualTo(150);
        assertThat(target.getFileCount()).isEqualTo(2);
        assertThat(target.getFolderCount()).isEqualTo(2);
    }

    @Test
    void parentMustBeFolderInSameSpace() {
        PanFile othersFolder = new PanFile();
        othersFolder.setSpaceId(bobPrivate.getId());
        othersFolder.setName("secret");
        othersFolder.setType(com.wildfirechat.pan.constant.FileType.FOLDER);
        othersFolder.setCreatorId("bob");
        Long othersFolderId = fileRepository.save(othersFolder).getId();

        assertThatThrownBy(() -> folder(alicePublic, othersFolderId, "intrude"))
            .isInstanceOf(BusinessException.class)
            .hasMessage("目标文件夹无效");

        FileVO aFile = file(alicePublic, null, "a.txt", 1);
        assertThatThrownBy(() -> folder(alicePublic, aFile.getId(), "under-file"))
            .isInstanceOf(BusinessException.class)
            .hasMessage("目标文件夹无效");
    }

    @Test
    void movingFolderAcrossSpacesMovesDescendantsAndQuota() {
        FileVO a = folder(alicePublic, null, "a");
        FileVO b = folder(alicePublic, a.getId(), "b");
        FileVO x = file(alicePublic, b.getId(), "x.txt", 100);

        MoveRequest request = new MoveRequest();
        request.setFileId(a.getId());
        request.setTargetSpaceId(alicePrivate.getId());
        request.setTargetParentId(0L);
        fileService.moveFile(request, alice);

        entityManager.flush();
        entityManager.clear();
        assertThat(fileRepository.findById(b.getId()).orElseThrow().getSpaceId()).isEqualTo(alicePrivate.getId());
        assertThat(fileRepository.findById(x.getId()).orElseThrow().getSpaceId()).isEqualTo(alicePrivate.getId());

        PanSpace source = reload(alicePublic);
        PanSpace target = reload(alicePrivate);
        assertThat(source.getUsedQuota()).isZero();
        assertThat(source.getFileCount()).isZero();
        assertThat(source.getFolderCount()).isZero();
        assertThat(target.getUsedQuota()).isEqualTo(100);
        assertThat(target.getFileCount()).isEqualTo(1);
        assertThat(target.getFolderCount()).isEqualTo(2);
    }

    @Test
    void quotaCannotBeExceeded() {
        long quota = alicePublic.getTotalQuota();
        file(alicePublic, null, "big.bin", quota);

        assertThatThrownBy(() -> file(alicePublic, null, "one-more-byte.bin", 1))
            .isInstanceOf(BusinessException.class)
            .hasMessage("空间容量不足");
    }

    @Test
    void storageUrlMustBeHttp() {
        CreateFileRequest request = new CreateFileRequest();
        request.setSpaceId(alicePublic.getId());
        request.setName("evil.html");
        request.setSize(1L);
        request.setStorageUrl("javascript:alert(document.cookie)");

        assertThatThrownBy(() -> fileService.createFile(request, alice)).isInstanceOf(BusinessException.class);
    }

    @Test
    void rootCanBeNullOrZero() {
        CreateFolderRequest request = new CreateFolderRequest();
        request.setSpaceId(alicePublic.getId());
        request.setParentId(0L);
        request.setName("root-folder");
        fileService.createFolder(request, alice);

        assertThat(fileService.getFileList(alicePublic.getId(), null, alice))
            .extracting(FileVO::getName)
            .containsExactly("root-folder");
    }

    @Test
    void renameCanChangeCaseOnly() {
        FileVO a = file(alicePublic, null, "a.txt", 1);
        file(alicePublic, null, "b.txt", 1);

        RenameRequest request = new RenameRequest();
        request.setFileId(a.getId());
        request.setNewName("A.txt");
        assertThat(fileService.renameFile(request, alice).getName()).isEqualTo("A.txt");

        request.setNewName("B.TXT");
        assertThatThrownBy(() -> fileService.renameFile(request, alice)).isInstanceOf(BusinessException.class);
    }

    @Test
    void privateSpaceIsNotAccessibleToOthers() {
        assertThatThrownBy(() -> fileService.getFileList(bobPrivate.getId(), null, alice))
            .isInstanceOf(BusinessException.class);
        // 默认配置下管理后台也不能查看用户私有空间
        assertThatThrownBy(() -> fileService.getFileListAsAdmin(bobPrivate.getId(), null))
            .isInstanceOf(BusinessException.class);
        // IM 中 userId 恰好为 admin 的用户不再有特殊权限
        assertThatThrownBy(() -> fileService.getFileList(bobPrivate.getId(), null, "admin"))
            .isInstanceOf(BusinessException.class);
    }
}
