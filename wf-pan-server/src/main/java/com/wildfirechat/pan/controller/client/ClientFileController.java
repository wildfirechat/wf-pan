package com.wildfirechat.pan.controller.client;

import com.wildfirechat.pan.constant.FilePermission;
import com.wildfirechat.pan.dto.Result;
import com.wildfirechat.pan.dto.request.CopyRequest;
import com.wildfirechat.pan.dto.request.CreateFileRequest;
import com.wildfirechat.pan.dto.request.CreateFolderRequest;
import com.wildfirechat.pan.dto.request.DeleteFileRequest;
import com.wildfirechat.pan.dto.request.GetFileUrlRequest;
import com.wildfirechat.pan.dto.request.MoveRequest;
import com.wildfirechat.pan.dto.request.RenameRequest;
import com.wildfirechat.pan.dto.request.SpaceIdRequest;
import com.wildfirechat.pan.dto.response.FileUrlResponse;
import com.wildfirechat.pan.dto.vo.FileVO;
import com.wildfirechat.pan.entity.PanFile;
import com.wildfirechat.pan.filter.ClientAuthFilter;
import com.wildfirechat.pan.service.DownloadService;
import com.wildfirechat.pan.service.FileService;
import com.wildfirechat.pan.service.FileVersionService;
import com.wildfirechat.pan.service.PermissionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * userId 由 {@link ClientAuthFilter} 校验 authCode 后写入请求属性
 */
@RestController
@RequestMapping("/api/v1/files")
public class ClientFileController {

    @Autowired
    private FileService fileService;

    @Autowired
    private PermissionService permissionService;

    @Autowired
    private DownloadService downloadService;

    /**
     * 检查文件上传权限
     */
    @PostMapping("/check-permission")
    public Result<Boolean> checkUploadPermission(@Valid @RequestBody SpaceIdRequest request,
                                                 @RequestAttribute(ClientAuthFilter.USER_ID_KEY) String userId) {
        return Result.success(permissionService.canManageSpace(userId, request.getSpaceId()));
    }

    @PostMapping("/folder")
    public Result<FileVO> createFolder(@Valid @RequestBody CreateFolderRequest request,
                                       @RequestAttribute(ClientAuthFilter.USER_ID_KEY) String userId) {
        return Result.success(fileService.createFolder(request, userId));
    }

    /**
     * 创建文件记录（上传完成后调用）
     */
    @PostMapping
    public Result<FileVO> createFile(@Valid @RequestBody CreateFileRequest request,
                                     @RequestAttribute(ClientAuthFilter.USER_ID_KEY) String userId) {
        return Result.success(fileService.createFile(request, userId));
    }

    @PostMapping("/delete")
    public Result<Void> delete(@Valid @RequestBody DeleteFileRequest request,
                               @RequestAttribute(ClientAuthFilter.USER_ID_KEY) String userId) {
        fileService.deleteFile(request.getFileId(), userId);
        return Result.success();
    }

    @PostMapping("/rename")
    public Result<FileVO> rename(@Valid @RequestBody RenameRequest request,
                                 @RequestAttribute(ClientAuthFilter.USER_ID_KEY) String userId) {
        return Result.success(fileService.renameFile(request, userId));
    }

    @PostMapping("/move")
    public Result<FileVO> move(@Valid @RequestBody MoveRequest request,
                               @RequestAttribute(ClientAuthFilter.USER_ID_KEY) String userId) {
        return Result.success(fileService.moveFile(request, userId));
    }

    @PostMapping("/copy")
    public Result<FileVO> copy(@Valid @RequestBody CopyRequest request,
                               @RequestAttribute(ClientAuthFilter.USER_ID_KEY) String userId) {
        return Result.success(fileService.copyFile(request, userId));
    }

    /**
     * 获取文件下载URL
     */
    @PostMapping("/url")
    public Result<FileUrlResponse> getDownloadUrl(@Valid @RequestBody GetFileUrlRequest request,
                                                  @RequestAttribute(ClientAuthFilter.USER_ID_KEY) String userId,
                                                  HttpServletRequest httpRequest) {
        PanFile file = fileService.requireFile(request.getFileId(), userId, FilePermission.VIEW);
        int versionNo = request.getVersionNo() != null ? request.getVersionNo() : FileVersionService.currentVersionNo(file);
        String url = downloadService.downloadUrl(file, versionNo, userId, httpRequest);
        return Result.success(FileUrlResponse.builder()
            .fileId(file.getId())
            .name(file.getName())
            .storageUrl(url != null ? url : "")
            .versionNo(versionNo)
            .permission(permissionService.effectivePermission(userId, file).name())
            .build());
    }
}
