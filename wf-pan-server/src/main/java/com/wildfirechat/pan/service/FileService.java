package com.wildfirechat.pan.service;

import com.wildfirechat.pan.constant.FileType;
import com.wildfirechat.pan.config.OssConfig;
import com.wildfirechat.pan.dto.request.CreateFileRequest;
import com.wildfirechat.pan.dto.request.CreateFolderRequest;
import com.wildfirechat.pan.dto.request.CopyRequest;
import com.wildfirechat.pan.dto.request.MoveRequest;
import com.wildfirechat.pan.dto.request.RenameRequest;
import com.wildfirechat.pan.dto.vo.FileVO;
import com.wildfirechat.pan.dto.vo.UserInfoVO;
import com.wildfirechat.pan.entity.PanFile;
import com.wildfirechat.pan.exception.BusinessException;
import com.wildfirechat.pan.repository.PanFileRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Slf4j
public class FileService {
    
    @Autowired
    private PanFileRepository fileRepository;
    
    @Autowired
    private PermissionService permissionService;
    
    @Autowired
    private SpaceQuotaService spaceQuotaService;
    
    @Autowired
    private StorageService storageService;
    
    @Autowired
    private IMUserService imUserService;
    
    @Autowired
    private OperationLogService operationLogService;
    
    /**
     * 获取空间内文件列表
     */
    public List<FileVO> getFileList(Long spaceId, Long parentId, String userId) {
        // 检查访问权限
        if (!permissionService.canAccessSpace(userId, spaceId)) {
            throw new BusinessException("无权限访问该空间");
        }
        
        List<PanFile> files = fileRepository
            .findBySpaceIdAndParentIdAndIsDeletedFalseOrderByTypeDescNameAsc(spaceId, parentId);
        
        return files.stream().map(this::convertToVO).collect(Collectors.toList());
    }
    
    /**
     * 创建文件夹
     */
    @Transactional
    public FileVO createFolder(CreateFolderRequest request, String userId, String username) {
        // 检查管理权限
        if (!permissionService.canManageSpace(userId, request.getSpaceId())) {
            throw new BusinessException("无权限在该空间创建文件夹");
        }
        
        // 检查同名文件夹是否存在
        checkDuplicateName(request.getSpaceId(), request.getParentId(), request.getName());
        
        PanFile folder = new PanFile();
        folder.setSpaceId(request.getSpaceId());
        folder.setParentId(request.getParentId());
        folder.setName(request.getName());
        folder.setType(FileType.FOLDER);
        folder.setSize(0L);
        folder.setCreatorId(userId);
        folder.setCreatorName(username);
        
        PanFile saved = fileRepository.save(folder);
        
        // 更新父文件夹的子文件数
        if (request.getParentId() != null) {
            fileRepository.incrementChildCount(request.getParentId());
        }
        
        // 更新空间文件夹数
        spaceQuotaService.incrementFolderCount(request.getSpaceId());
        
        // 记录操作日志
        Map<String, Object> details = new HashMap<>();
        details.put("folderName", request.getName());
        details.put("spaceId", request.getSpaceId());
        details.put("parentId", request.getParentId());
        operationLogService.log(userId, OperationLogService.OP_CREATE_FOLDER, 
            OperationLogService.TARGET_FOLDER, saved.getId(), request.getSpaceId(), details);
        
        return convertToVO(saved);
    }
    
    /**
     * 创建文件记录（上传完成后调用）
     */
    @Transactional
    public FileVO createFile(CreateFileRequest request, String userId, String username) {
        // 检查管理权限
        if (!permissionService.canManageSpace(userId, request.getSpaceId())) {
            throw new BusinessException("无权限在该空间上传文件");
        }
        
        // 检查配额
        if (!spaceQuotaService.checkQuota(request.getSpaceId(), request.getSize())) {
            throw new BusinessException("空间容量不足");
        }
        
        // 检查同名文件是否存在
        checkDuplicateName(request.getSpaceId(), request.getParentId(), request.getName());
        
        // 处理OSS文件复制（如果copy参数为true且有storageUrl）
        String targetStorageUrl = request.getStorageUrl();
        if (Boolean.TRUE.equals(request.getCopy()) && request.getStorageUrl() != null && !request.getStorageUrl().isEmpty()) {
            targetStorageUrl = storageService.copyObjectIfNeeded(request.getStorageUrl());
        }
        
        PanFile file = new PanFile();
        file.setSpaceId(request.getSpaceId());
        file.setParentId(request.getParentId());
        file.setName(request.getName());
        file.setType(FileType.FILE);
        file.setSize(request.getSize());
        file.setMimeType(request.getMimeType());
        file.setMd5(request.getMd5());
        file.setStorageUrl(targetStorageUrl);
        file.setCreatorId(userId);
        file.setCreatorName(username);
        
        PanFile saved = fileRepository.save(file);
        
        // 更新父文件夹的子文件数
        if (request.getParentId() != null) {
            fileRepository.incrementChildCount(request.getParentId());
        }
        
        // 更新空间配额和文件数
        spaceQuotaService.increaseUsedQuota(request.getSpaceId(), request.getSize());
        spaceQuotaService.incrementFileCount(request.getSpaceId());
        
        // 记录操作日志
        Map<String, Object> details = new HashMap<>();
        details.put("fileName", request.getName());
        details.put("fileSize", request.getSize());
        details.put("spaceId", request.getSpaceId());
        details.put("parentId", request.getParentId());
        details.put("ossCopy", Boolean.TRUE.equals(request.getCopy()));
        operationLogService.log(userId, OperationLogService.OP_UPLOAD_FILE, 
            OperationLogService.TARGET_FILE, saved.getId(), request.getSpaceId(), details);
        
        return convertToVO(saved);
    }
    
    /**
     * 删除文件或文件夹
     * 规则：文件夹非空不能删除
     * 
     * 删除逻辑：
     * 1. 先软删除数据库记录
     * 2. 检查是否还有其他文件引用相同的storageUrl
     * 3. 如果没有其他引用，再删除OSS对象
     */
    @Transactional
    public void deleteFile(Long fileId, String userId) {
        PanFile file = fileRepository.findByIdAndIsDeletedFalse(fileId)
            .orElseThrow(() -> new BusinessException("文件不存在"));
        
        // 检查权限
        if (!permissionService.canDeleteFile(userId, file)) {
            throw new BusinessException("无权限删除该文件");
        }
        
        if (file.getType() == FileType.FOLDER) {
            // 检查文件夹是否为空
            long childCount = fileRepository.countByParentIdAndIsDeletedFalse(fileId);
            if (childCount > 0) {
                throw new BusinessException("文件夹非空，请先删除内部文件");
            }
            
            // 更新空间文件夹数
            spaceQuotaService.decrementFolderCount(file.getSpaceId());
        } else {
            // 更新空间配额和文件数
            spaceQuotaService.decreaseUsedQuota(file.getSpaceId(), file.getSize());
            spaceQuotaService.decrementFileCount(file.getSpaceId());
        }
        
        // 保存storageUrl用于后续检查
        String storageUrl = file.getStorageUrl();
        boolean isFile = file.getType() == FileType.FILE;
        
        // 软删除
        file.setIsDeleted(true);
        file.setDeletedAt(LocalDateTime.now());
        file.setDeletedBy(userId);
        fileRepository.save(file);
        
        // 更新父文件夹的子文件数
        if (file.getParentId() != null) {
            fileRepository.decrementChildCount(file.getParentId());
        }
        
        // 如果是文件，检查是否还有其他文件引用相同的storageUrl，如果没有则删除OSS对象
        if (isFile && storageUrl != null && !storageUrl.isEmpty()) {
            long refCount = fileRepository.countByStorageUrlAndIsDeletedFalse(storageUrl);
            if (refCount == 0) {
                storageService.deleteObject(storageUrl);
                log.info("OSS对象已删除，无其他文件引用: {}", storageUrl);
            } else {
                log.info("保留OSS对象，仍有 {} 个文件引用: {}", refCount, storageUrl);
            }
        }
        
        // 记录操作日志
        Map<String, Object> details = new HashMap<>();
        details.put("fileName", file.getName());
        details.put("fileType", file.getType().name());
        details.put("isAdmin", "admin".equals(userId));
        String operation = "admin".equals(userId) ? 
            OperationLogService.OP_ADMIN_DELETE_FILE : OperationLogService.OP_DELETE_FILE;
        operationLogService.log(userId, operation, 
            file.getType() == FileType.FOLDER ? OperationLogService.TARGET_FOLDER : OperationLogService.TARGET_FILE, 
            fileId, file.getSpaceId(), details);
    }
    
    /**
     * 重命名
     */
    @Transactional
    public FileVO renameFile(RenameRequest request, String userId) {
        PanFile file = fileRepository.findByIdAndIsDeletedFalse(request.getFileId())
            .orElseThrow(() -> new BusinessException("文件不存在"));
        
        // 检查权限
        if (!permissionService.canManageSpace(userId, file.getSpaceId())) {
            throw new BusinessException("无权限重命名该文件");
        }
        
        // 检查同名
        checkDuplicateName(file.getSpaceId(), file.getParentId(), request.getNewName());
        
        String oldName = file.getName();
        file.setName(request.getNewName());
        PanFile saved = fileRepository.save(file);
        
        // 记录操作日志
        Map<String, Object> details = new HashMap<>();
        details.put("oldName", oldName);
        details.put("newName", request.getNewName());
        details.put("fileType", file.getType().name());
        operationLogService.log(userId, OperationLogService.OP_RENAME_FILE, 
            file.getType() == FileType.FOLDER ? OperationLogService.TARGET_FOLDER : OperationLogService.TARGET_FILE, 
            request.getFileId(), file.getSpaceId(), details);
        
        return convertToVO(saved);
    }
    
    /**
     * 移动文件/文件夹
     */
    @Transactional
    public FileVO moveFile(MoveRequest request, String userId) {
        PanFile file = fileRepository.findByIdAndIsDeletedFalse(request.getFileId())
            .orElseThrow(() -> new BusinessException("文件不存在"));
        
        // 检查源空间权限
        if (!permissionService.canManageSpace(userId, file.getSpaceId())) {
            throw new BusinessException("无权限移动该文件");
        }
        
        // 检查目标空间权限
        if (!permissionService.canManageSpace(userId, request.getTargetSpaceId())) {
            throw new BusinessException("无权限移动到目标空间");
        }
        
        Long oldParentId = file.getParentId();
        Long oldSpaceId = file.getSpaceId();
        
        // 检查循环引用：如果是文件夹，不能移动到自身或其子目录下
        if (file.getType() == FileType.FOLDER) {
            // 不能移动到自身
            if (request.getTargetParentId() != null && request.getTargetParentId().equals(file.getId())) {
                throw new BusinessException("不能将文件夹移动到自身内");
            }
            // 同一空间内移动时，检查是否移动到了子目录
            if (oldSpaceId.equals(request.getTargetSpaceId())) {
                Long targetParentId = request.getTargetParentId() == 0 ? null : request.getTargetParentId();
                if (isDescendant(file.getId(), targetParentId)) {
                    throw new BusinessException("不能将文件夹移动到其子目录内");
                }
            }
        }
        
        // 如果是跨空间移动，检查配额
        if (!oldSpaceId.equals(request.getTargetSpaceId()) && file.getType() == FileType.FILE) {
            if (!spaceQuotaService.checkQuota(request.getTargetSpaceId(), file.getSize())) {
                throw new BusinessException("目标空间容量不足");
            }
        }
        
        // 检查目标位置同名
        checkDuplicateName(request.getTargetSpaceId(), 
            request.getTargetParentId() == 0 ? null : request.getTargetParentId(), 
            file.getName());
        
        // 执行移动
        file.setSpaceId(request.getTargetSpaceId());
        file.setParentId(request.getTargetParentId() == 0 ? null : request.getTargetParentId());
        PanFile saved = fileRepository.save(file);
        
        // 更新旧父文件夹的子文件数
        if (oldParentId != null) {
            fileRepository.decrementChildCount(oldParentId);
        }
        
        // 更新新父文件夹的子文件数
        if (file.getParentId() != null) {
            fileRepository.incrementChildCount(file.getParentId());
        }
        
        // 跨空间时更新配额
        if (!oldSpaceId.equals(request.getTargetSpaceId())) {
            if (file.getType() == FileType.FILE) {
                spaceQuotaService.decreaseUsedQuota(oldSpaceId, file.getSize());
                spaceQuotaService.decreaseUsedQuota(request.getTargetSpaceId(), -file.getSize());
                spaceQuotaService.decrementFileCount(oldSpaceId);
                spaceQuotaService.incrementFileCount(request.getTargetSpaceId());
            }
        }
        
        // 记录操作日志
        Map<String, Object> details = new HashMap<>();
        details.put("fileName", file.getName());
        details.put("fileType", file.getType().name());
        details.put("oldSpaceId", oldSpaceId);
        details.put("newSpaceId", request.getTargetSpaceId());
        details.put("oldParentId", oldParentId);
        details.put("newParentId", request.getTargetParentId());
        operationLogService.log(userId, OperationLogService.OP_MOVE_FILE, 
            file.getType() == FileType.FOLDER ? OperationLogService.TARGET_FOLDER : OperationLogService.TARGET_FILE, 
            request.getFileId(), request.getTargetSpaceId(), details);
        
        return convertToVO(saved);
    }
    
    /**
     * 复制文件/文件夹
     * 
     * @param request 复制请求
     * @param userId 用户ID
     * @param userName 用户名
     * @return 复制后的文件VO
     */
    @Transactional
    public FileVO copyFile(CopyRequest request, String userId, String userName) {
        PanFile sourceFile = fileRepository.findByIdAndIsDeletedFalse(request.getFileId())
            .orElseThrow(() -> new BusinessException("文件不存在"));
        
        // 检查源空间访问权限（复制只需要读权限）
        if (!permissionService.canAccessSpace(userId, sourceFile.getSpaceId())) {
            throw new BusinessException("无权限访问该文件");
        }
        
        // 检查目标空间写入权限
        if (!permissionService.canManageSpace(userId, request.getTargetSpaceId())) {
            throw new BusinessException("无权限复制到目标空间");
        }
        
        // 检查目标位置同名
        checkDuplicateName(request.getTargetSpaceId(), 
            request.getTargetParentId() == 0 ? null : request.getTargetParentId(), 
            sourceFile.getName());
        
        // 如果是文件，检查目标空间配额
        if (sourceFile.getType() == FileType.FILE) {
            if (!spaceQuotaService.checkQuota(request.getTargetSpaceId(), sourceFile.getSize())) {
                throw new BusinessException("目标空间容量不足");
            }
        }
        
        // 处理OSS文件复制（如果copy参数为true且是文件类型）
        String targetStorageUrl = sourceFile.getStorageUrl();
        if (Boolean.TRUE.equals(request.getCopy()) && sourceFile.getType() == FileType.FILE) {
            targetStorageUrl = copyFileToOss(sourceFile);
        }
        
        // 创建新文件记录（复制）
        PanFile newFile = new PanFile();
        newFile.setSpaceId(request.getTargetSpaceId());
        newFile.setParentId(request.getTargetParentId() == 0 ? null : request.getTargetParentId());
        newFile.setName(sourceFile.getName());
        newFile.setType(sourceFile.getType());
        newFile.setSize(sourceFile.getSize());
        newFile.setMimeType(sourceFile.getMimeType());
        newFile.setMd5(sourceFile.getMd5());
        newFile.setStorageUrl(targetStorageUrl);
        newFile.setChildCount(0); // 子文件数初始为0，如果是文件夹会在递归复制中更新
        newFile.setCreatorId(userId);
        newFile.setCreatorName(userName);
        newFile.setIsDeleted(false);
        newFile.setCreatedAt(LocalDateTime.now());
        newFile.setUpdatedAt(LocalDateTime.now());
        
        PanFile saved = fileRepository.save(newFile);
        
        // 如果是文件夹，递归复制子文件
        if (sourceFile.getType() == FileType.FOLDER) {
            copyChildren(sourceFile.getId(), saved.getId(), request.getTargetSpaceId(), userId, userName, request.getCopy());
            // 更新子文件数
            long childCount = fileRepository.countByParentIdAndIsDeletedFalse(saved.getId());
            saved.setChildCount((int) childCount);
            saved = fileRepository.save(saved);
        }
        
        // 更新目标空间的配额
        if (sourceFile.getType() == FileType.FILE) {
            spaceQuotaService.decreaseUsedQuota(request.getTargetSpaceId(), -sourceFile.getSize());
            spaceQuotaService.incrementFileCount(request.getTargetSpaceId());
        }
        
        // 更新目标父文件夹的子文件数
        if (newFile.getParentId() != null) {
            fileRepository.incrementChildCount(newFile.getParentId());
        }
        
        // 记录操作日志
        Map<String, Object> details = new HashMap<>();
        details.put("fileName", sourceFile.getName());
        details.put("fileType", sourceFile.getType().name());
        details.put("sourceSpaceId", sourceFile.getSpaceId());
        details.put("targetSpaceId", request.getTargetSpaceId());
        details.put("targetParentId", request.getTargetParentId());
        details.put("ossCopy", Boolean.TRUE.equals(request.getCopy()));
        operationLogService.log(userId, OperationLogService.OP_COPY_FILE, 
            sourceFile.getType() == FileType.FOLDER ? OperationLogService.TARGET_FOLDER : OperationLogService.TARGET_FILE, 
            saved.getId(), request.getTargetSpaceId(), details);
        
        return convertToVO(saved);
    }
    
    /**
     * 递归复制子文件/文件夹
     * 
     * @param copy 是否进行OSS复制，true表示跨空间复制时需要复制物理文件
     */
    private void copyChildren(Long sourceParentId, Long targetParentId, Long targetSpaceId, String userId, String userName, Boolean copy) {
        List<PanFile> children = fileRepository.findByParentIdAndIsDeletedFalse(sourceParentId);
        
        for (PanFile child : children) {
            // 处理OSS文件复制（如果copy参数为true且是文件类型）
            String targetStorageUrl = child.getStorageUrl();
            if (Boolean.TRUE.equals(copy) && child.getType() == FileType.FILE) {
                targetStorageUrl = copyFileToOss(child);
            }
            
            // 创建新的子文件记录
            PanFile newChild = new PanFile();
            newChild.setSpaceId(targetSpaceId);
            newChild.setParentId(targetParentId);
            newChild.setName(child.getName());
            newChild.setType(child.getType());
            newChild.setSize(child.getSize());
            newChild.setMimeType(child.getMimeType());
            newChild.setMd5(child.getMd5());
            newChild.setStorageUrl(targetStorageUrl);
            newChild.setChildCount(0);
            newChild.setCreatorId(userId);
            newChild.setCreatorName(userName);
            newChild.setIsDeleted(false);
            newChild.setCreatedAt(LocalDateTime.now());
            newChild.setUpdatedAt(LocalDateTime.now());
            
            PanFile savedChild = fileRepository.save(newChild);
            
            // 如果是文件夹，递归复制其子文件
            if (child.getType() == FileType.FOLDER) {
                copyChildren(child.getId(), savedChild.getId(), targetSpaceId, userId, userName, copy);
                // 更新子文件数
                long childCount = fileRepository.countByParentIdAndIsDeletedFalse(savedChild.getId());
                savedChild.setChildCount((int) childCount);
                fileRepository.save(savedChild);
            }
            
            // 更新配额
            if (child.getType() == FileType.FILE) {
                spaceQuotaService.decreaseUsedQuota(targetSpaceId, -child.getSize());
                spaceQuotaService.incrementFileCount(targetSpaceId);
            }
        }
    }
    
    /**
     * 复制文件到OSS（如果配置了OSS）
     * 
     * @param sourceFile 源文件
     * @return 目标存储URL
     */
    private String copyFileToOss(PanFile sourceFile) {
        if (sourceFile.getStorageUrl() == null || sourceFile.getStorageUrl().isEmpty()) {
            return sourceFile.getStorageUrl();
        }
        
        try {
            // 使用StorageService复制文件到OSS
            return storageService.copyObjectIfNeeded(sourceFile.getStorageUrl());
        } catch (Exception e) {
            log.error("复制文件到OSS失败: {}", sourceFile.getStorageUrl(), e);
            // 如果OSS复制失败，返回原URL，继续用原来的存储
            return sourceFile.getStorageUrl();
        }
    }
    
    /**
     * 获取文件详情
     */
    public FileVO getFileDetail(Long fileId, String userId) {
        PanFile file = fileRepository.findByIdAndIsDeletedFalse(fileId)
            .orElseThrow(() -> new BusinessException("文件不存在"));
        
        // 检查访问权限
        if (!permissionService.canAccessSpace(userId, file.getSpaceId())) {
            throw new BusinessException("无权限访问该文件");
        }
        
        return convertToVO(file);
    }
    
    /**
     * 获取所有文件（管理员用）
     */
    public Page<FileVO> getAllFiles(Pageable pageable) {
        Page<PanFile> files = fileRepository.findByIsDeletedFalseOrderByCreatedAtDesc(pageable);
        return files.map(this::convertToVO);
    }
    
    /**
     * 全局搜索（管理员用）
     */
    public Page<FileVO> globalSearch(String keyword, Pageable pageable) {
        Page<PanFile> files = fileRepository.globalSearch(keyword, pageable);
        return files.map(this::convertToVO);
    }
    
    /**
     * 空间内搜索
     */
    public Page<FileVO> searchBySpace(Long spaceId, String keyword, Pageable pageable, String userId) {
        // 检查访问权限
        if (!permissionService.canAccessSpace(userId, spaceId)) {
            throw new BusinessException("无权限访问该空间");
        }
        
        Page<PanFile> files = fileRepository.searchBySpaceId(spaceId, keyword, pageable);
        return files.map(this::convertToVO);
    }
    
    private void checkDuplicateName(Long spaceId, Long parentId, String name) {
        List<PanFile> existing = fileRepository.findBySpaceIdAndParentIdAndIsDeletedFalse(spaceId, parentId);
        boolean duplicate = existing.stream()
            .anyMatch(f -> f.getName().equalsIgnoreCase(name));
        if (duplicate) {
            throw new BusinessException("该目录下已存在同名文件或文件夹");
        }
    }
    
    /**
     * 检查 targetId 是否是 ancestorId 的后代（子孙）
     * @param ancestorId 祖先文件夹ID
     * @param targetId 目标文件夹ID
     * @return 如果 targetId 是 ancestorId 的后代，返回 true
     */
    private boolean isDescendant(Long ancestorId, Long targetId) {
        if (targetId == null) {
            return false;
        }
        if (targetId.equals(ancestorId)) {
            return true;
        }
        
        // 向上遍历父目录链
        Long currentId = targetId;
        int maxDepth = 100; // 防止死循环
        int depth = 0;
        
        while (currentId != null && depth < maxDepth) {
            PanFile current = fileRepository.findByIdAndIsDeletedFalse(currentId).orElse(null);
            if (current == null) {
                return false;
            }
            if (current.getParentId() == null) {
                return false;
            }
            if (current.getParentId().equals(ancestorId)) {
                return true;
            }
            currentId = current.getParentId();
            depth++;
        }
        return false;
    }
    
    private FileVO convertToVO(PanFile file) {
        // 从IM服务获取用户信息（带缓存）
        String creatorId = file.getCreatorId() != null ? file.getCreatorId() : "";
        UserInfoVO userInfo = imUserService.getUserInfoVO(creatorId);
        
        String creatorName = userInfo != null ? userInfo.getDisplayName() : creatorId;
        String creatorPortrait = userInfo != null ? userInfo.getPortrait() : "";
        
        return FileVO.builder()
            .id(file.getId() != null ? file.getId() : 0L)
            .spaceId(file.getSpaceId() != null ? file.getSpaceId() : 0L)
            .parentId(file.getParentId() != null ? file.getParentId() : 0L)
            .name(file.getName() != null ? file.getName() : "")
            .type(file.getType())
            .size(file.getSize() != null ? file.getSize() : 0L)
            .mimeType(file.getMimeType() != null ? file.getMimeType() : "")
            .md5(file.getMd5() != null ? file.getMd5() : "")
            .storageUrl(file.getStorageUrl() != null ? file.getStorageUrl() : "")
            .childCount(file.getChildCount() != null ? file.getChildCount() : 0)
            .creatorId(creatorId)
            .creatorName(creatorName)
            .creatorPortrait(creatorPortrait)
            .createdAt(file.getCreatedAt())
            .updatedAt(file.getUpdatedAt())
            .build();
    }
}
