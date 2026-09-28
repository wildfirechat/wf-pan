package com.wildfirechat.pan.service;

import com.wildfirechat.pan.config.DocsConfig;
import com.wildfirechat.pan.constant.FilePermission;
import com.wildfirechat.pan.constant.VersionSource;
import com.wildfirechat.pan.entity.PanFile;
import com.wildfirechat.pan.entity.PanFileVersion;
import com.wildfirechat.pan.exception.BusinessException;
import com.wildfirechat.pan.repository.PanFileRepository;
import com.wildfirechat.pan.repository.PanFileVersionRepository;
import com.wildfirechat.pan.service.StorageService.StoredObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 文件版本。规则：
 * - 写时复制：新内容一律写新对象，绝不覆盖已有 storage_url（网盘去重会让多条记录共用同一对象）；
 * - 恢复历史版本 = 用旧版本的对象再记一个新版本，历史记录本身不改；
 * - 每个文件只保留最近 pan.version.keep 个版本，超出的删记录，对象按引用计数（storage_key）删除；
 * - 配额 v1 只统计当前版本。
 */
@Service
@Slf4j
public class FileVersionService {

    private static final SecureRandom RANDOM = new SecureRandom();

    @Autowired
    private PanFileRepository fileRepository;

    @Autowired
    private PanFileVersionRepository versionRepository;

    @Autowired
    private SpaceQuotaService spaceQuotaService;

    @Autowired
    private StorageService storageService;

    @Autowired
    private PermissionService permissionService;

    @Autowired
    private OperationLogService operationLogService;

    @Autowired
    private DocsConfig docsConfig;

    @Autowired
    private TransactionTemplate transactionTemplate;

    /**
     * 记一个新版本并切为当前版本
     *
     * @param rotateKey 是否更换在线编辑会话 key（会话结束保存、恢复版本时换；编辑途中的定时保存不换）
     * @return 新版本；文件已被删除时返回 null（调用方负责清理刚写的对象）
     */
    public PanFileVersion addVersion(Long fileId, StoredObject object, long size, String md5, VersionSource source,
                                     String editorId, String editorName, boolean rotateKey) {
        List<String> pruned = new ArrayList<>();
        PanFileVersion saved = transactionTemplate.execute(status -> {
            PanFile file = fileRepository.findByIdForUpdate(fileId).orElse(null);
            if (file == null) {
                return null;
            }
            ensureBaseVersion(file);
            int newNo = currentVersionNo(file) + 1;

            PanFileVersion v = new PanFileVersion();
            v.setFileId(fileId);
            v.setVersionNo(newNo);
            v.setStorageUrl(object.url());
            v.setStorageKey(object.key());
            v.setSize(size);
            v.setMd5(md5);
            v.setEditorId(editorId);
            v.setEditorName(editorName);
            v.setSource(source);
            v.setCreatedAt(LocalDateTime.now());
            versionRepository.save(v);

            long delta = size - (file.getSize() != null ? file.getSize() : 0L);
            spaceQuotaService.adjustUsedQuota(file.getSpaceId(), delta);
            file.setStorageUrl(object.url());
            file.setStorageKey(object.key());
            file.setSize(size);
            file.setMd5(md5);
            file.setVersionNo(newNo);
            if (rotateKey) {
                file.setDocKey(newDocKey(fileId));
            }
            fileRepository.save(file);

            List<PanFileVersion> all = versionRepository.findByFileIdOrderByVersionNoDesc(fileId);
            int keep = Math.max(1, docsConfig.getVersionKeep());
            for (int i = keep; i < all.size(); i++) {
                PanFileVersion old = all.get(i);
                if (old.getVersionNo() == newNo) continue;
                pruned.add(old.getStorageKey());
                versionRepository.delete(old);
            }
            return v;
        });
        pruned.forEach(this::releaseIfUnreferenced);
        if (saved != null) {
            Map<String, Object> details = new HashMap<>();
            details.put("versionNo", saved.getVersionNo());
            details.put("source", source.name());
            details.put("size", size);
            operationLogService.log(editorId != null ? editorId : "system", OperationLogService.OP_NEW_VERSION,
                OperationLogService.TARGET_FILE, fileId, null, details);
        }
        return saved;
    }

    /** 恢复到某个历史版本（记为新版本；需要编辑权限） */
    public PanFileVersion restore(Long fileId, int versionNo, String userId, String userName) {
        PanFile file = fileRepository.findByIdAndIsDeletedFalse(fileId)
            .orElseThrow(() -> new BusinessException("文件不存在"));
        if (!permissionService.effectivePermission(userId, file).atLeast(FilePermission.EDIT)) {
            throw new BusinessException("无权限恢复该文件的版本");
        }
        if (versionNo == currentVersionNo(file)) {
            throw new BusinessException("已是当前版本");
        }
        PanFileVersion old = versionRepository.findByFileIdAndVersionNo(fileId, versionNo)
            .orElseThrow(() -> new BusinessException("版本不存在或已被清理"));
        PanFileVersion v = addVersion(fileId, new StoredObject(old.getStorageUrl(), old.getStorageKey()),
            old.getSize() != null ? old.getSize() : 0L,
            old.getMd5(), VersionSource.RESTORE, userId, userName, true);
        if (v == null) {
            throw new BusinessException("文件不存在");
        }
        return v;
    }

    /** 版本列表（需要查看权限）；老文件没有版本记录时补上当前版本 */
    public List<PanFileVersion> list(Long fileId, String userId) {
        PanFile file = fileRepository.findByIdAndIsDeletedFalse(fileId)
            .orElseThrow(() -> new BusinessException("文件不存在"));
        if (!permissionService.effectivePermission(userId, file).atLeast(FilePermission.VIEW)) {
            throw new BusinessException("无权限访问该文件");
        }
        List<PanFileVersion> versions = versionRepository.findByFileIdOrderByVersionNoDesc(fileId);
        if (versions.isEmpty() || versions.get(0).getVersionNo() != currentVersionNo(file)) {
            List<PanFileVersion> withCurrent = new ArrayList<>();
            withCurrent.add(baseVersionOf(file));
            withCurrent.addAll(versions);
            return withCurrent;
        }
        return versions;
    }

    /** 文件被删除时：删掉它的版本记录，对象按引用计数删除 */
    public void releaseAllVersions(PanFile file) {
        Set<String> keys = new LinkedHashSet<>();
        for (PanFileVersion v : versionRepository.findByFileIdOrderByVersionNoDesc(file.getId())) {
            keys.add(v.getStorageKey());
            versionRepository.delete(v);
        }
        versionRepository.flush();
        keys.add(file.getStorageKey());
        keys.forEach(this::releaseIfUnreferenced);
    }

    /**
     * 网盘 bucket 中的对象已无任何文件记录、版本记录引用时删除；在事务中调用时，事务提交后才删（回滚时不会误删）
     */
    public void releaseIfUnreferenced(String storageKey) {
        if (!StringUtils.hasText(storageKey)) {
            return;
        }
        long fileRefs = fileRepository.countByStorageKeyAndIsDeletedFalse(storageKey);
        long versionRefs = versionRepository.countByStorageKey(storageKey);
        if (fileRefs > 0 || versionRefs > 0) {
            log.debug("保留对象，文件引用 {}、版本引用 {}: {}", fileRefs, versionRefs, storageKey);
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    storageService.deleteObject(storageKey);
                }
            });
        } else {
            storageService.deleteObject(storageKey);
        }
    }

    /** 在线编辑会话 key：没有就生成一个并保存 */
    public String ensureDocKey(PanFile file) {
        if (file.getDocKey() != null && !file.getDocKey().isEmpty()) {
            return file.getDocKey();
        }
        return transactionTemplate.execute(status -> {
            PanFile locked = fileRepository.findByIdForUpdate(file.getId())
                .orElseThrow(() -> new BusinessException("文件不存在"));
            if (locked.getDocKey() == null || locked.getDocKey().isEmpty()) {
                locked.setDocKey(newDocKey(locked.getId()));
                fileRepository.save(locked);
            }
            file.setDocKey(locked.getDocKey());
            return locked.getDocKey();
        });
    }

    /** 会话结束但内容没变时只换 key（仍是 expectedKey 时才换，避免覆盖别处刚换的） */
    public void rotateDocKey(Long fileId, String expectedKey) {
        transactionTemplate.executeWithoutResult(status -> {
            PanFile locked = fileRepository.findByIdForUpdate(fileId).orElse(null);
            if (locked != null && expectedKey.equals(locked.getDocKey())) {
                locked.setDocKey(newDocKey(fileId));
                fileRepository.save(locked);
            }
        });
    }

    public static int currentVersionNo(PanFile file) {
        return file.getVersionNo() != null ? file.getVersionNo() : 1;
    }

    /** 首次产生新版本前，把原内容补记为一个版本（老数据、上传的文件都没有版本记录） */
    private void ensureBaseVersion(PanFile file) {
        int cur = currentVersionNo(file);
        if (file.getStorageUrl() == null || versionRepository.existsByFileIdAndVersionNo(file.getId(), cur)) {
            return;
        }
        versionRepository.save(baseVersionOf(file));
    }

    private PanFileVersion baseVersionOf(PanFile file) {
        PanFileVersion base = new PanFileVersion();
        base.setFileId(file.getId());
        base.setVersionNo(currentVersionNo(file));
        base.setStorageUrl(file.getStorageUrl());
        base.setStorageKey(file.getStorageKey());
        base.setSize(file.getSize());
        base.setMd5(file.getMd5());
        base.setEditorId(file.getCreatorId());
        base.setEditorName(file.getCreatorName());
        base.setSource(VersionSource.UPLOAD);
        base.setCreatedAt(file.getUpdatedAt() != null ? file.getUpdatedAt() : file.getCreatedAt());
        return base;
    }

    /** f{fileId}-{随机}：带随机段，重装后 fileId 被复用也不会撞上 ONLYOFFICE 的旧缓存 */
    private static String newDocKey(Long fileId) {
        byte[] b = new byte[9];
        RANDOM.nextBytes(b);
        return "f" + fileId + "-" + Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
