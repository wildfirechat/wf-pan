package com.wildfirechat.pan.service;

import com.wildfirechat.pan.constant.FileType;
import com.wildfirechat.pan.dto.request.CopyRequest;
import com.wildfirechat.pan.dto.request.CreateFileRequest;
import com.wildfirechat.pan.dto.request.CreateFolderRequest;
import com.wildfirechat.pan.dto.request.MoveRequest;
import com.wildfirechat.pan.dto.request.RenameRequest;
import com.wildfirechat.pan.dto.vo.FileVO;
import com.wildfirechat.pan.dto.vo.UserInfoVO;
import com.wildfirechat.pan.entity.PanFile;
import com.wildfirechat.pan.entity.PanSpace;
import com.wildfirechat.pan.exception.BusinessException;
import com.wildfirechat.pan.repository.PanFileRepository;
import com.wildfirechat.pan.repository.PanSpaceRepository;
import com.wildfirechat.pan.service.StorageService.StoredObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static com.wildfirechat.pan.service.OperationLogService.*;

@Service
@Slf4j
@RequiredArgsConstructor
public class FileService {

    /** 目录最大层级，防止异常数据导致死循环 */
    private static final int MAX_DEPTH = 1000;

    private final PanFileRepository fileRepository;
    private final PanSpaceRepository spaceRepository;
    private final PermissionService permissionService;
    private final SpaceQuotaService spaceQuotaService;
    private final StorageService storageService;
    private final IMUserService imUserService;
    private final OperationLogService operationLogService;
    private final ConfigService configService;

    // ---------------------------------------------------------------- 查询

    public List<FileVO> getFileList(Long spaceId, Long parentId, String userId) {
        if (!permissionService.canAccessSpace(userId, requireSpace(spaceId))) {
            throw new BusinessException("无权限访问该空间");
        }
        return listChildren(spaceId, parentId);
    }

    public FileVO getFileDetail(Long fileId, String userId) {
        PanFile file = requireFile(fileId);
        if (!permissionService.canAccessSpace(userId, requireSpace(file.getSpaceId()))) {
            throw new BusinessException("无权限访问该文件");
        }
        return convertToVO(file);
    }

    public List<FileVO> getFileListAsAdmin(Long spaceId, Long parentId) {
        requireConsoleAccess(requireSpace(spaceId));
        return listChildren(spaceId, parentId);
    }

    public FileVO getFileDetailAsAdmin(Long fileId) {
        PanFile file = requireFile(fileId);
        requireConsoleAccess(requireSpace(file.getSpaceId()));
        return convertToVO(file);
    }

    public Page<FileVO> getAllFilesAsAdmin(Pageable pageable) {
        return fileRepository.findAllForConsole(configService.isAdminCanManagePrivateSpace(), pageable)
            .map(this::convertToVO);
    }

    public Page<FileVO> globalSearchAsAdmin(String keyword, Pageable pageable) {
        return fileRepository.globalSearch(keyword, configService.isAdminCanManagePrivateSpace(), pageable)
            .map(this::convertToVO);
    }

    public Page<FileVO> searchBySpaceAsAdmin(Long spaceId, String keyword, Pageable pageable) {
        requireConsoleAccess(requireSpace(spaceId));
        return fileRepository.searchBySpaceId(spaceId, keyword, pageable).map(this::convertToVO);
    }

    // ---------------------------------------------------------------- 创建

    @Transactional
    public FileVO createFolder(CreateFolderRequest request, String userId) {
        Long spaceId = request.getSpaceId();
        requireManage(userId, spaceId, "无权限在该空间创建文件夹");
        Long parentId = resolveParent(spaceId, request.getParentId());
        checkDuplicateName(spaceId, parentId, request.getName(), null);

        PanFile saved = fileRepository.save(newRecord(spaceId, parentId, request.getName(), FileType.FOLDER, userId));
        incrementChildCount(parentId);
        spaceQuotaService.adjustCounts(spaceId, 0, 1);

        operationLogService.log(userId, OP_CREATE_FOLDER, TARGET_FOLDER, saved.getId(), spaceId,
            details("folderName", request.getName(), "spaceId", spaceId, "parentId", parentId));
        return convertToVO(saved);
    }

    /**
     * 创建文件记录（客户端上传完成后调用）
     */
    @Transactional
    public FileVO createFile(CreateFileRequest request, String userId) {
        Long spaceId = request.getSpaceId();
        requireManage(userId, spaceId, "无权限在该空间上传文件");
        Long parentId = resolveParent(spaceId, request.getParentId());
        checkDuplicateName(spaceId, parentId, request.getName(), null);

        boolean copy = Boolean.TRUE.equals(request.getCopy());
        StoredObject object = copy ? importObject(request.getStorageUrl())
                                   : storageService.resolveReference(request.getStorageUrl());
        // 网盘 bucket 中的对象以实际大小为准，不信任客户端上报
        long size = storageService.objectSize(object.key()).orElse(request.getSize());
        spaceQuotaService.reserve(spaceId, size, "空间容量不足");

        PanFile file = newRecord(spaceId, parentId, request.getName(), FileType.FILE, userId);
        file.setSize(size);
        file.setMimeType(request.getMimeType());
        file.setMd5(request.getMd5());
        file.setStorageUrl(object.url());
        file.setStorageKey(object.key());
        PanFile saved = fileRepository.save(file);
        incrementChildCount(parentId);
        spaceQuotaService.adjustCounts(spaceId, 1, 0);

        operationLogService.log(userId, OP_UPLOAD_FILE, TARGET_FILE, saved.getId(), spaceId,
            details("fileName", request.getName(), "fileSize", size, "spaceId", spaceId,
                "parentId", parentId, "ossCopy", copy));
        return convertToVO(saved);
    }

    // ---------------------------------------------------------------- 删除

    /**
     * 删除文件或空文件夹（软删除）；没有其他记录引用的网盘对象在事务提交后删除
     */
    @Transactional
    public void deleteFile(Long fileId, String userId) {
        PanFile file = requireFile(fileId);
        if (!permissionService.canDeleteFile(userId, file)) {
            throw new BusinessException("无权限删除该文件");
        }
        remove(file, userId, OP_DELETE_FILE);
    }

    @Transactional
    public void deleteFileAsAdmin(Long fileId, String adminUser) {
        PanFile file = requireFile(fileId);
        requireConsoleAccess(requireSpace(file.getSpaceId()));
        remove(file, adminUser, OP_ADMIN_DELETE_FILE);
    }

    private void remove(PanFile file, String operator, String operation) {
        if (isFolder(file)) {
            if (fileRepository.countByParentIdAndIsDeletedFalse(file.getId()) > 0) {
                throw new BusinessException("文件夹非空，请先删除内部文件");
            }
            spaceQuotaService.adjustCounts(file.getSpaceId(), 0, -1);
        } else {
            spaceQuotaService.release(file.getSpaceId(), sizeOf(file));
            spaceQuotaService.adjustCounts(file.getSpaceId(), -1, 0);
        }

        file.setIsDeleted(true);
        file.setDeletedAt(LocalDateTime.now());
        file.setDeletedBy(operator);
        fileRepository.save(file);
        decrementChildCount(file.getParentId());

        String key = file.getStorageKey();
        if (StringUtils.hasText(key) && fileRepository.countByStorageKeyAndIsDeletedFalse(key) == 0) {
            runAfterCommit(() -> storageService.deleteObject(key));
        }

        operationLogService.log(operator, operation, targetType(file), file.getId(), file.getSpaceId(),
            details("fileName", file.getName(), "fileType", file.getType().name()));
    }

    // ---------------------------------------------------------------- 重命名 / 移动 / 复制

    @Transactional
    public FileVO renameFile(RenameRequest request, String userId) {
        PanFile file = requireFile(request.getFileId());
        requireManage(userId, file.getSpaceId(), "无权限重命名该文件");

        String oldName = file.getName();
        String newName = request.getNewName();
        if (oldName.equals(newName)) {
            return convertToVO(file);
        }
        checkDuplicateName(file.getSpaceId(), file.getParentId(), newName, file.getId());
        file.setName(newName);
        PanFile saved = fileRepository.save(file);

        operationLogService.log(userId, OP_RENAME_FILE, targetType(file), file.getId(), file.getSpaceId(),
            details("oldName", oldName, "newName", newName, "fileType", file.getType().name()));
        return convertToVO(saved);
    }

    @Transactional
    public FileVO moveFile(MoveRequest request, String userId) {
        PanFile file = requireFile(request.getFileId());
        requireManage(userId, file.getSpaceId(), "无权限移动该文件");
        Long targetSpaceId = request.getTargetSpaceId();
        requireManage(userId, targetSpaceId, "无权限移动到目标空间");
        Long targetParentId = resolveParent(targetSpaceId, request.getTargetParentId());
        if (isSelfOrDescendant(file, targetParentId)) {
            throw new BusinessException("不能将文件夹移动到自身或其子目录内");
        }

        Long oldSpaceId = file.getSpaceId();
        Long oldParentId = file.getParentId();
        if (oldSpaceId.equals(targetSpaceId) && Objects.equals(oldParentId, targetParentId)) {
            return convertToVO(file);
        }
        checkDuplicateName(targetSpaceId, targetParentId, file.getName(), file.getId());

        if (!oldSpaceId.equals(targetSpaceId)) {
            moveTreeToSpace(file, targetSpaceId);
        }
        file.setParentId(targetParentId);
        PanFile saved = fileRepository.save(file);
        decrementChildCount(oldParentId);
        incrementChildCount(targetParentId);

        operationLogService.log(userId, OP_MOVE_FILE, targetType(file), file.getId(), targetSpaceId,
            details("fileName", file.getName(), "fileType", file.getType().name(),
                "oldSpaceId", oldSpaceId, "newSpaceId", targetSpaceId,
                "oldParentId", oldParentId, "newParentId", targetParentId));
        return convertToVO(saved);
    }

    /**
     * 复制文件/文件夹（文件夹连同全部子项）。copy=true 时把不在网盘 bucket 中的对象复制进来，
     * 网盘 bucket 中的对象直接共享（按引用数删除）。
     */
    @Transactional
    public FileVO copyFile(CopyRequest request, String userId) {
        PanFile source = requireFile(request.getFileId());
        if (!permissionService.canAccessSpace(userId, requireSpace(source.getSpaceId()))) {
            throw new BusinessException("无权限访问该文件");
        }
        Long targetSpaceId = request.getTargetSpaceId();
        requireManage(userId, targetSpaceId, "无权限复制到目标空间");
        Long targetParentId = resolveParent(targetSpaceId, request.getTargetParentId());
        if (isSelfOrDescendant(source, targetParentId)) {
            throw new BusinessException("不能将文件夹复制到自身或其子目录内");
        }
        checkDuplicateName(targetSpaceId, targetParentId, source.getName(), null);

        List<PanFile> tree = collectTree(source);
        TreeStats stats = TreeStats.of(tree);
        spaceQuotaService.reserve(targetSpaceId, stats.size(), "目标空间容量不足");
        spaceQuotaService.adjustCounts(targetSpaceId, stats.files(), stats.folders());

        // 子项在复制前一次性取出，复制过程中新插入的记录不会被再次遍历
        Map<Long, List<PanFile>> childrenByParent = tree.stream()
            .filter(file -> file != source)
            .collect(Collectors.groupingBy(PanFile::getParentId));
        boolean copyObjects = Boolean.TRUE.equals(request.getCopy());
        PanFile copy = copyNode(source, targetSpaceId, targetParentId, userId, copyObjects, childrenByParent);
        incrementChildCount(targetParentId);

        operationLogService.log(userId, OP_COPY_FILE, targetType(source), copy.getId(), targetSpaceId,
            details("fileName", source.getName(), "fileType", source.getType().name(),
                "sourceSpaceId", source.getSpaceId(), "targetSpaceId", targetSpaceId,
                "targetParentId", targetParentId, "ossCopy", copyObjects));
        return convertToVO(copy);
    }

    private PanFile copyNode(PanFile source, Long spaceId, Long parentId, String userId,
                             boolean copyObjects, Map<Long, List<PanFile>> childrenByParent) {
        PanFile node = newRecord(spaceId, parentId, source.getName(), source.getType(), userId);
        node.setSize(sizeOf(source));
        node.setMimeType(source.getMimeType());
        node.setMd5(source.getMd5());
        node.setStorageUrl(source.getStorageUrl());
        node.setStorageKey(source.getStorageKey());
        if (copyObjects && !isFolder(source) && StringUtils.hasText(source.getStorageUrl())
                && !StringUtils.hasText(source.getStorageKey())) {
            copyObjectInto(node, source.getStorageUrl());
        }

        List<PanFile> children = childrenByParent.getOrDefault(source.getId(), List.of());
        node.setChildCount(children.size());
        PanFile saved = fileRepository.save(node);
        for (PanFile child : children) {
            copyNode(child, spaceId, saved.getId(), userId, copyObjects, childrenByParent);
        }
        return saved;
    }

    /**
     * 复制失败时保留原地址，与复制前的行为一致
     */
    private void copyObjectInto(PanFile node, String sourceUrl) {
        try {
            StoredObject object = importObject(sourceUrl);
            node.setStorageUrl(object.url());
            node.setStorageKey(object.key());
        } catch (BusinessException e) {
            log.warn("Keep original storage url, copy failed: {} ({})", sourceUrl, e.getMessage());
        }
    }

    /**
     * 跨空间移动：整棵子树一起换空间，并迁移配额和计数
     */
    private void moveTreeToSpace(PanFile root, Long targetSpaceId) {
        Long sourceSpaceId = root.getSpaceId();
        List<PanFile> tree = collectTree(root);
        TreeStats stats = TreeStats.of(tree);

        spaceQuotaService.reserve(targetSpaceId, stats.size(), "目标空间容量不足");
        spaceQuotaService.release(sourceSpaceId, stats.size());
        spaceQuotaService.adjustCounts(sourceSpaceId, -stats.files(), -stats.folders());
        spaceQuotaService.adjustCounts(targetSpaceId, stats.files(), stats.folders());

        tree.forEach(file -> file.setSpaceId(targetSpaceId));
        fileRepository.saveAll(tree);
    }

    // ---------------------------------------------------------------- 内部工具

    private List<FileVO> listChildren(Long spaceId, Long parentId) {
        return fileRepository
            .findBySpaceIdAndParentIdAndIsDeletedFalseOrderByTypeDescNameAsc(spaceId, normalizeParentId(parentId))
            .stream()
            .map(this::convertToVO)
            .toList();
    }

    /**
     * 导入对象；若本次新复制了对象而事务回滚，则删除该对象
     */
    private StoredObject importObject(String sourceUrl) {
        boolean alreadyOwned = storageService.ownedKey(sourceUrl).isPresent();
        StoredObject object = storageService.importObject(sourceUrl);
        if (!alreadyOwned && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status == STATUS_ROLLED_BACK) {
                        storageService.deleteObject(object.key());
                    }
                }
            });
        }
        return object;
    }

    private static void runAfterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    /**
     * 校验父目录并返回规范化后的 parentId（根目录为 null）：
     * 父目录必须存在、未删除、是文件夹，且与目标在同一空间
     */
    private Long resolveParent(Long spaceId, Long parentId) {
        Long normalized = normalizeParentId(parentId);
        if (normalized == null) {
            return null;
        }
        PanFile parent = fileRepository.findByIdAndIsDeletedFalse(normalized)
            .orElseThrow(() -> new BusinessException("目标文件夹不存在"));
        if (!isFolder(parent) || !parent.getSpaceId().equals(spaceId)) {
            throw new BusinessException("目标文件夹无效");
        }
        return normalized;
    }

    /**
     * 根目录在不同接口中用 null 或 0 表示，统一为 null
     */
    private static Long normalizeParentId(Long parentId) {
        return parentId == null || parentId <= 0 ? null : parentId;
    }

    /**
     * targetParentId 是否为 folder 本身或其子孙
     */
    private boolean isSelfOrDescendant(PanFile folder, Long targetParentId) {
        if (!isFolder(folder)) {
            return false;
        }
        Long current = targetParentId;
        for (int depth = 0; current != null; depth++) {
            if (current.equals(folder.getId())) {
                return true;
            }
            if (depth >= MAX_DEPTH) {
                throw new BusinessException("目录层级过深");
            }
            current = fileRepository.findById(current).map(PanFile::getParentId).orElse(null);
        }
        return false;
    }

    /**
     * root 及其全部未删除的子孙，按层级顺序
     */
    private List<PanFile> collectTree(PanFile root) {
        List<PanFile> tree = new ArrayList<>();
        tree.add(root);
        List<Long> level = isFolder(root) ? List.of(root.getId()) : List.of();
        for (int depth = 0; !level.isEmpty(); depth++) {
            if (depth >= MAX_DEPTH) {
                throw new BusinessException("目录层级过深");
            }
            List<PanFile> children = fileRepository.findByParentIdInAndIsDeletedFalse(level);
            tree.addAll(children);
            level = children.stream().filter(FileService::isFolder).map(PanFile::getId).toList();
        }
        return tree;
    }

    private record TreeStats(long size, int files, int folders) {

        static TreeStats of(List<PanFile> tree) {
            long size = 0;
            int files = 0;
            int folders = 0;
            for (PanFile file : tree) {
                if (isFolder(file)) {
                    folders++;
                } else {
                    files++;
                    size += sizeOf(file);
                }
            }
            return new TreeStats(size, files, folders);
        }
    }

    private void checkDuplicateName(Long spaceId, Long parentId, String name, Long excludeId) {
        boolean duplicate = excludeId == null
            ? fileRepository.existsBySpaceIdAndParentIdAndNameIgnoreCaseAndIsDeletedFalse(spaceId, parentId, name)
            : fileRepository.existsBySpaceIdAndParentIdAndNameIgnoreCaseAndIsDeletedFalseAndIdNot(spaceId, parentId, name, excludeId);
        if (duplicate) {
            throw new BusinessException("该目录下已存在同名文件或文件夹");
        }
    }

    private PanSpace requireSpace(Long spaceId) {
        return spaceRepository.findById(spaceId).orElseThrow(() -> new BusinessException("空间不存在"));
    }

    private PanFile requireFile(Long fileId) {
        return fileRepository.findByIdAndIsDeletedFalse(fileId).orElseThrow(() -> new BusinessException("文件不存在"));
    }

    private void requireManage(String userId, Long spaceId, String message) {
        if (!permissionService.canManageSpace(userId, requireSpace(spaceId))) {
            throw new BusinessException(message);
        }
    }

    private void requireConsoleAccess(PanSpace space) {
        if (!permissionService.canConsoleAccessSpace(space)) {
            throw new BusinessException("无权限访问用户私有空间");
        }
    }

    private void incrementChildCount(Long parentId) {
        if (parentId != null) {
            fileRepository.incrementChildCount(parentId);
        }
    }

    private void decrementChildCount(Long parentId) {
        if (parentId != null) {
            fileRepository.decrementChildCount(parentId);
        }
    }

    private PanFile newRecord(Long spaceId, Long parentId, String name, FileType type, String userId) {
        PanFile file = new PanFile();
        file.setSpaceId(spaceId);
        file.setParentId(parentId);
        file.setName(name);
        file.setType(type);
        file.setCreatorId(userId);
        file.setCreatorName(imUserService.getUserDisplayName(userId));
        return file;
    }

    private static boolean isFolder(PanFile file) {
        return file.getType() == FileType.FOLDER;
    }

    private static long sizeOf(PanFile file) {
        return file.getSize() != null ? file.getSize() : 0L;
    }

    private static String targetType(PanFile file) {
        return isFolder(file) ? TARGET_FOLDER : TARGET_FILE;
    }

    /**
     * 日志详情（允许 null 值，Map.of 不允许）
     */
    private static Map<String, Object> details(Object... keyValues) {
        Map<String, Object> details = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            details.put((String) keyValues[i], keyValues[i + 1]);
        }
        return details;
    }

    private FileVO convertToVO(PanFile file) {
        String creatorId = file.getCreatorId() != null ? file.getCreatorId() : "";
        UserInfoVO creator = imUserService.getUserInfoVO(creatorId);

        return FileVO.builder()
            .id(file.getId())
            .spaceId(file.getSpaceId())
            .parentId(file.getParentId() != null ? file.getParentId() : 0L)
            .name(file.getName())
            .type(file.getType())
            .size(sizeOf(file))
            .mimeType(file.getMimeType() != null ? file.getMimeType() : "")
            .md5(file.getMd5() != null ? file.getMd5() : "")
            .storageUrl(file.getStorageUrl() != null ? file.getStorageUrl() : "")
            .childCount(file.getChildCount() != null ? file.getChildCount() : 0)
            .creatorId(creatorId)
            .creatorName(creator.getDisplayName())
            .creatorPortrait(creator.getPortrait() != null ? creator.getPortrait() : "")
            .createdAt(file.getCreatedAt())
            .updatedAt(file.getUpdatedAt())
            .build();
    }
}
