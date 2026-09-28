package com.wildfirechat.pan.controller.client;

import com.wildfirechat.pan.dto.Result;
import com.wildfirechat.pan.dto.request.FileIdRequest;
import com.wildfirechat.pan.dto.request.RestoreVersionRequest;
import com.wildfirechat.pan.dto.vo.FileVersionVO;
import com.wildfirechat.pan.entity.PanFileVersion;
import com.wildfirechat.pan.filter.ClientAuthFilter;
import com.wildfirechat.pan.service.FileVersionService;
import com.wildfirechat.pan.service.IMUserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/versions")
public class ClientVersionController {
    
    @Autowired
    private FileVersionService fileVersionService;
    
    @Autowired
    private IMUserService imUserService;
    
    /**
     * 版本列表（新的在前，第一条是当前版本）
     */
    @PostMapping("/list")
    public Result<List<FileVersionVO>> list(@Valid @RequestBody FileIdRequest request, HttpServletRequest httpRequest) {
        String userId = (String) httpRequest.getAttribute(ClientAuthFilter.USER_ID_KEY);
        if (userId == null || userId.isEmpty()) {
            return Result.error(401, "用户未登录");
        }
        List<PanFileVersion> versions = fileVersionService.list(request.getFileId(), userId);
        int current = versions.isEmpty() ? 0 : versions.get(0).getVersionNo();
        return Result.success(versions.stream().map(v -> toVO(v, current)).collect(Collectors.toList()));
    }
    
    /**
     * 恢复到某个历史版本（记为一个新版本）
     */
    @PostMapping("/restore")
    public Result<FileVersionVO> restore(@Valid @RequestBody RestoreVersionRequest request, HttpServletRequest httpRequest) {
        String userId = (String) httpRequest.getAttribute(ClientAuthFilter.USER_ID_KEY);
        if (userId == null || userId.isEmpty()) {
            return Result.error(401, "用户未登录");
        }
        PanFileVersion v = fileVersionService.restore(request.getFileId(), request.getVersionNo(), userId,
            imUserService.getUserDisplayName(userId));
        return Result.success(toVO(v, v.getVersionNo()));
    }
    
    private FileVersionVO toVO(PanFileVersion v, int current) {
        String editorName = v.getEditorName();
        if (v.getEditorId() != null && (editorName == null || editorName.isEmpty() || editorName.equals(v.getEditorId()))) {
            editorName = imUserService.getUserDisplayName(v.getEditorId());
        }
        return FileVersionVO.builder()
            .fileId(v.getFileId())
            .versionNo(v.getVersionNo())
            .size(v.getSize() != null ? v.getSize() : 0L)
            .source(v.getSource())
            .editorId(v.getEditorId() != null ? v.getEditorId() : "")
            .editorName(editorName != null ? editorName : "")
            .current(v.getVersionNo() == current)
            .createdAt(v.getCreatedAt())
            .build();
    }
}
