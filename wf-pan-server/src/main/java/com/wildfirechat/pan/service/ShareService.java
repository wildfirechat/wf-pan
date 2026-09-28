package com.wildfirechat.pan.service;

import com.wildfirechat.pan.constant.FilePermission;
import com.wildfirechat.pan.constant.ShareTargetType;
import com.wildfirechat.pan.dto.request.AddShareRequest;
import com.wildfirechat.pan.dto.vo.ShareVO;
import com.wildfirechat.pan.dto.vo.SharedFileVO;
import com.wildfirechat.pan.dto.vo.UserInfoVO;
import com.wildfirechat.pan.entity.PanFile;
import com.wildfirechat.pan.entity.PanShare;
import com.wildfirechat.pan.exception.BusinessException;
import com.wildfirechat.pan.repository.PanFileRepository;
import com.wildfirechat.pan.repository.PanShareRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 文件分享（按人 / 按群，权限分可查看 / 可编辑）
 */
@Service
@Slf4j
public class ShareService {

    @Autowired
    private PanShareRepository shareRepository;

    @Autowired
    private PanFileRepository fileRepository;

    @Autowired
    private PermissionService permissionService;

    @Autowired
    private IMUserService imUserService;

    @Autowired
    private IMGroupService groupService;

    @Autowired
    private FileService fileService;

    @Autowired
    private OperationLogService operationLogService;

    /** 文件的分享列表（只有能分享的人可以看） */
    public List<ShareVO> list(Long fileId, String userId) {
        PanFile file = requireFile(fileId);
        if (!permissionService.canShare(userId, file)) {
            throw new BusinessException("无权限管理该文件的分享");
        }
        return shareRepository.findByFileIdOrderByCreatedAtAsc(fileId).stream()
            .map(this::toVO).collect(Collectors.toList());
    }

    /** 新增或修改分享（同一对象重复分享 = 改权限） */
    @Transactional
    public ShareVO add(AddShareRequest request, String userId) {
        PanFile file = requireFile(request.getFileId());
        if (!permissionService.canShare(userId, file)) {
            throw new BusinessException("无权限分享该文件");
        }
        FilePermission perm = request.getPermission() != null ? request.getPermission() : FilePermission.VIEW;
        if (perm == FilePermission.NONE) {
            throw new BusinessException("分享权限只能是可查看或可编辑");
        }
        String targetId = request.getTargetId().trim();
        if (request.getTargetType() == ShareTargetType.USER) {
            if (targetId.equals(userId)) {
                throw new BusinessException("不能分享给自己");
            }
            if (imUserService.findUserInfo(targetId).isEmpty()) {
                throw new BusinessException("用户不存在");
            }
        } else if (!groupService.exists(targetId)) {
            throw new BusinessException("群不存在");
        }

        PanShare share = shareRepository
            .findByFileIdAndTargetTypeAndTargetId(file.getId(), request.getTargetType(), targetId)
            .orElseGet(PanShare::new);
        boolean isNew = share.getId() == null;
        if (isNew) {
            share.setFileId(file.getId());
            share.setTargetType(request.getTargetType());
            share.setTargetId(targetId);
            share.setCreatedBy(userId);
            share.setCreatedAt(LocalDateTime.now());
        }
        share.setPermission(perm);
        share = shareRepository.save(share);

        Map<String, Object> details = new HashMap<>();
        details.put("fileName", file.getName());
        details.put("targetType", request.getTargetType().name());
        details.put("targetId", targetId);
        details.put("permission", perm.name());
        details.put("update", !isNew);
        operationLogService.log(userId, OperationLogService.OP_SHARE_FILE,
            OperationLogService.TARGET_FILE, file.getId(), file.getSpaceId(), details);
        return toVO(share);
    }

    @Transactional
    public void remove(Long shareId, String userId) {
        PanShare share = shareRepository.findById(shareId)
            .orElseThrow(() -> new BusinessException("分享不存在"));
        PanFile file = requireFile(share.getFileId());
        if (!permissionService.canShare(userId, file)) {
            throw new BusinessException("无权限管理该文件的分享");
        }
        shareRepository.delete(share);

        Map<String, Object> details = new HashMap<>();
        details.put("fileName", file.getName());
        details.put("targetType", share.getTargetType().name());
        details.put("targetId", share.getTargetId());
        operationLogService.log(userId, OperationLogService.OP_UNSHARE_FILE,
            OperationLogService.TARGET_FILE, file.getId(), file.getSpaceId(), details);
    }

    /**
     * 共享给我：直接分享给我的，加上分享给我所在群的。同一文件去重，取最高权限。
     * 自己能管理的文件（自己空间里的）不列。
     */
    public List<SharedFileVO> sharedWithMe(String userId) {
        List<PanShare> shares = new ArrayList<>(shareRepository.findByTargetTypeAndTargetId(ShareTargetType.USER, userId));
        List<String> groupIds = groupService.getUserGroupIds(userId);
        if (!groupIds.isEmpty()) {
            shares.addAll(shareRepository.findByTargetTypeAndTargetIdIn(ShareTargetType.GROUP, groupIds));
        }
        if (shares.isEmpty()) {
            return List.of();
        }
        Map<Long, List<PanShare>> byFile = new LinkedHashMap<>();
        for (PanShare s : shares) {
            byFile.computeIfAbsent(s.getFileId(), k -> new ArrayList<>()).add(s);
        }
        Map<Long, PanFile> files = fileRepository.findByIdInAndIsDeletedFalse(byFile.keySet()).stream()
            .collect(Collectors.toMap(PanFile::getId, f -> f));

        List<SharedFileVO> result = new ArrayList<>();
        for (Map.Entry<Long, List<PanShare>> e : byFile.entrySet()) {
            PanFile file = files.get(e.getKey());
            if (file == null || permissionService.canManageSpace(userId, file.getSpaceId())) {
                continue;
            }
            FilePermission perm = FilePermission.NONE;
            LocalDateTime sharedAt = null;
            for (PanShare s : e.getValue()) {
                perm = FilePermission.max(perm, s.getPermission());
                if (sharedAt == null || s.getCreatedAt().isAfter(sharedAt)) {
                    sharedAt = s.getCreatedAt();
                }
            }
            result.add(SharedFileVO.builder()
                .file(fileService.toVO(file))
                .permission(perm)
                .sources(e.getValue().stream().map(this::toVO).collect(Collectors.toList()))
                .sharedAt(sharedAt)
                .build());
        }
        result.sort(Comparator.comparing(SharedFileVO::getSharedAt, Comparator.nullsLast(Comparator.reverseOrder())));
        return result;
    }

    private PanFile requireFile(Long fileId) {
        return fileRepository.findByIdAndIsDeletedFalse(fileId)
            .orElseThrow(() -> new BusinessException("文件不存在"));
    }

    private ShareVO toVO(PanShare s) {
        String name;
        String portrait = "";
        if (s.getTargetType() == ShareTargetType.USER) {
            UserInfoVO u = imUserService.getUserInfoVO(s.getTargetId());
            name = u.getDisplayName();
            portrait = u.getPortrait() != null ? u.getPortrait() : "";
        } else {
            name = groupService.getGroupName(s.getTargetId());
        }
        return ShareVO.builder()
            .id(s.getId())
            .fileId(s.getFileId())
            .targetType(s.getTargetType())
            .targetId(s.getTargetId())
            .targetName(name)
            .targetPortrait(portrait)
            .permission(s.getPermission())
            .createdBy(s.getCreatedBy())
            .createdByName(imUserService.getUserDisplayName(s.getCreatedBy()))
            .createdAt(s.getCreatedAt())
            .build();
    }
}
