package com.wildfirechat.pan.controller.client;

import com.wildfirechat.pan.dto.Result;
import com.wildfirechat.pan.dto.request.CreateFileRequest;
import com.wildfirechat.pan.dto.request.CreateFolderRequest;
import com.wildfirechat.pan.dto.request.DeleteFileRequest;
import com.wildfirechat.pan.dto.request.GetFileUrlRequest;
import com.wildfirechat.pan.dto.request.CopyRequest;
import com.wildfirechat.pan.dto.request.MoveRequest;
import com.wildfirechat.pan.dto.request.RenameRequest;
import com.wildfirechat.pan.dto.request.SpaceIdRequest;
import com.wildfirechat.pan.dto.response.FileUrlResponse;
import com.wildfirechat.pan.dto.vo.FileVO;
import com.wildfirechat.pan.filter.ClientAuthFilter;
import com.wildfirechat.pan.service.FileService;
import com.wildfirechat.pan.service.PermissionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/files")
public class ClientFileController {
    
    @Autowired
    private FileService fileService;
    
    @Autowired
    private PermissionService permissionService;
    
    /**
     * 检查文件上传权限
     */
    @PostMapping("/check-permission")
    public Result<Boolean> checkUploadPermission(@Valid @RequestBody SpaceIdRequest request,
                                                  HttpServletRequest httpRequest) {
        String userId = (String) httpRequest.getAttribute(ClientAuthFilter.USER_ID_KEY);
        if (userId == null || userId.isEmpty()) {
            return Result.error(401, "用户未登录");
        }
        boolean canManage = permissionService.canManageSpace(userId, request.getSpaceId());
        return Result.success(canManage);
    }
    
    /**
     * 创建文件夹
     */
    @PostMapping("/folder")
    public Result<FileVO> createFolder(@Valid @RequestBody CreateFolderRequest request,
                                        HttpServletRequest httpRequest) {
        String userId = (String) httpRequest.getAttribute(ClientAuthFilter.USER_ID_KEY);
        if (userId == null || userId.isEmpty()) {
            return Result.error(401, "用户未登录");
        }
        String userName = (String) httpRequest.getAttribute(ClientAuthFilter.USER_NAME_KEY);
        
        return Result.success(fileService.createFolder(request, userId, userName));
    }
    
    /**
     * 创建文件记录（上传完成后调用）
     */
    @PostMapping
    public Result<FileVO> createFile(@Valid @RequestBody CreateFileRequest request,
                                      HttpServletRequest httpRequest) {
        String userId = (String) httpRequest.getAttribute(ClientAuthFilter.USER_ID_KEY);
        if (userId == null || userId.isEmpty()) {
            return Result.error(401, "用户未登录");
        }
        String userName = (String) httpRequest.getAttribute(ClientAuthFilter.USER_NAME_KEY);
        
        return Result.success(fileService.createFile(request, userId, userName));
    }
    
    /**
     * 删除文件/文件夹
     */
    @PostMapping("/delete")
    public Result<Void> delete(@Valid @RequestBody DeleteFileRequest request,
                               HttpServletRequest httpRequest) {
        String userId = (String) httpRequest.getAttribute(ClientAuthFilter.USER_ID_KEY);
        if (userId == null || userId.isEmpty()) {
            return Result.error(401, "用户未登录");
        }
        
        fileService.deleteFile(request.getFileId(), userId);
        return Result.success();
    }
    
    /**
     * 重命名
     */
    @PostMapping("/rename")
    public Result<FileVO> rename(@Valid @RequestBody RenameRequest request,
                                  HttpServletRequest httpRequest) {
        String userId = (String) httpRequest.getAttribute(ClientAuthFilter.USER_ID_KEY);
        if (userId == null || userId.isEmpty()) {
            return Result.error(401, "用户未登录");
        }
        
        return Result.success(fileService.renameFile(request, userId));
    }
    
    /**
     * 移动文件/文件夹
     */
    @PostMapping("/move")
    public Result<FileVO> move(@Valid @RequestBody MoveRequest request,
                                HttpServletRequest httpRequest) {
        String userId = (String) httpRequest.getAttribute(ClientAuthFilter.USER_ID_KEY);
        if (userId == null || userId.isEmpty()) {
            return Result.error(401, "用户未登录");
        }
        
        return Result.success(fileService.moveFile(request, userId));
    }
    
    /**
     * 复制文件/文件夹
     */
    @PostMapping("/copy")
    public Result<FileVO> copy(@Valid @RequestBody CopyRequest request,
                                HttpServletRequest httpRequest) {
        String userId = (String) httpRequest.getAttribute(ClientAuthFilter.USER_ID_KEY);
        if (userId == null || userId.isEmpty()) {
            return Result.error(401, "用户未登录");
        }
        String userName = (String) httpRequest.getAttribute(ClientAuthFilter.USER_NAME_KEY);
        
        return Result.success(fileService.copyFile(request, userId, userName));
    }
    
    /**
     * 获取文件下载URL
     */
    @PostMapping("/url")
    public Result<FileUrlResponse> getDownloadUrl(@Valid @RequestBody GetFileUrlRequest request,
                                                   HttpServletRequest httpRequest) {
        String userId = (String) httpRequest.getAttribute(ClientAuthFilter.USER_ID_KEY);
        if (userId == null || userId.isEmpty()) {
            return Result.error(401, "用户未登录");
        }
        
        FileVO file = fileService.getFileDetail(request.getFileId(), userId);
        
        String storageUrl = file.getStorageUrl();
        if (storageUrl == null) {
            storageUrl = "";
        }
        
        FileUrlResponse response = FileUrlResponse.builder()
            .fileId(file.getId())
            .name(file.getName() != null ? file.getName() : "")
            .storageUrl(storageUrl)
            .build();
        
        return Result.success(response);
    }
}
