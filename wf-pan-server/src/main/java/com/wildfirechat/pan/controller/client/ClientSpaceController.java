package com.wildfirechat.pan.controller.client;

import com.wildfirechat.pan.constant.SpaceType;
import com.wildfirechat.pan.dto.Result;
import com.wildfirechat.pan.dto.request.GetMySpacesRequest;
import com.wildfirechat.pan.dto.request.GetSpaceFilesRequest;
import com.wildfirechat.pan.dto.request.GetUserPublicSpaceRequest;
import com.wildfirechat.pan.dto.vo.FileVO;
import com.wildfirechat.pan.dto.vo.SpaceVO;
import com.wildfirechat.pan.dto.vo.UserSpacesVO;
import com.wildfirechat.pan.entity.PanSpace;
import com.wildfirechat.pan.filter.ClientAuthFilter;
import com.wildfirechat.pan.repository.PanSpaceRepository;
import com.wildfirechat.pan.service.FileService;
import com.wildfirechat.pan.service.IMUserService;
import com.wildfirechat.pan.service.PermissionService;
import com.wildfirechat.pan.service.UserSpaceInitService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/spaces")
@Slf4j
public class ClientSpaceController {
    
    @Autowired
    private PanSpaceRepository spaceRepository;
    
    @Autowired
    private UserSpaceInitService userSpaceInitService;
    
    @Autowired
    private PermissionService permissionService;
    
    @Autowired
    private FileService fileService;
    
    @Autowired
    private IMUserService imUserService;
    
    /**
     * 获取用户有权限访问的空间列表
     */
    @PostMapping("/list")
    public Result<List<SpaceVO>> list(HttpServletRequest request) {
        String userId = (String) request.getAttribute(ClientAuthFilter.USER_ID_KEY);
        if (userId == null || userId.isEmpty()) {
            return Result.error(401, "用户未登录");
        }
        
        // 自动初始化用户空间
        UserSpacesVO userSpaces = userSpaceInitService.getOrInitUserSpaces(userId, userId);
        
        // 获取所有可访问的空间
        List<PanSpace> spaces = spaceRepository.findAccessibleSpaces(userId);
        
        List<SpaceVO> voList = spaces.stream()
            .map(space -> convertToVO(space, userId))
            .collect(Collectors.toList());
        
        return Result.success(voList);
    }
    
    /**
     * 获取用户自己的空间（只返回公共+私有）
     */
    @PostMapping("/my")
    public Result<List<SpaceVO>> getMySpaces(@Valid @RequestBody GetMySpacesRequest request,
                                              HttpServletRequest httpRequest) {
        String userId = (String) httpRequest.getAttribute(ClientAuthFilter.USER_ID_KEY);
        if (userId == null || userId.isEmpty()) {
            return Result.error(401, "用户未登录");
        }
        
        // 自动初始化用户空间
        userSpaceInitService.getOrInitUserSpaces(userId, userId);
        
        // 只查询用户自己的两个空间
        List<SpaceVO> mySpaces = new ArrayList<>();
        
        Optional<PanSpace> publicSpace = spaceRepository.findBySpaceTypeAndOwnerId(SpaceType.USER_PUBLIC, userId);
        Optional<PanSpace> privateSpace = spaceRepository.findBySpaceTypeAndOwnerId(SpaceType.USER_PRIVATE, userId);
        
        if (publicSpace.isPresent()) {
            mySpaces.add(convertToMySpaceVO(publicSpace.get()));
        }
        if (privateSpace.isPresent()) {
            mySpaces.add(convertToMySpaceVO(privateSpace.get()));
        }
        
        return Result.success(mySpaces);
    }
    
    /**
     * 获取指定用户的公共空间（用于查看其他用户的网盘）
     */
    @PostMapping("/user/public")
    public Result<SpaceVO> getUserPublicSpace(@Valid @RequestBody GetUserPublicSpaceRequest request,
                                               HttpServletRequest httpRequest) {
        String currentUserId = (String) httpRequest.getAttribute(ClientAuthFilter.USER_ID_KEY);
        if (currentUserId == null || currentUserId.isEmpty()) {
            return Result.error(401, "用户未登录");
        }
        
        Optional<PanSpace> publicSpace = spaceRepository.findBySpaceTypeAndOwnerId(SpaceType.USER_PUBLIC, request.getTargetUserId());
        
        if (!publicSpace.isPresent()) {
            return Result.error("用户公共空间不存在");
        }
        
        return Result.success(convertToVO(publicSpace.get(), currentUserId));
    }
    
    /**
     * 获取空间内文件列表
     */
    @PostMapping("/files")
    public Result<List<FileVO>> getFiles(@Valid @RequestBody GetSpaceFilesRequest request,
                                          HttpServletRequest httpRequest) {
        String userId = (String) httpRequest.getAttribute(ClientAuthFilter.USER_ID_KEY);
        if (userId == null || userId.isEmpty()) {
            return Result.error(401, "用户未登录");
        }
        Long parentId = request.getParentId() != null && request.getParentId() > 0 ? request.getParentId() : null;
        
        return Result.success(fileService.getFileList(request.getSpaceId(), parentId, userId));
    }
    
    /**
     * 转换为我自己的空间VO（显示自定义名称）
     */
    private SpaceVO convertToMySpaceVO(PanSpace space) {
        String displayName = space.getName() != null ? space.getName() : "";
        
        // 我自己的空间显示为"我的公共空间"、"我的私有空间"
        if (space.getSpaceType() == SpaceType.USER_PUBLIC) {
            displayName = "我的公共空间";
        } else if (space.getSpaceType() == SpaceType.USER_PRIVATE) {
            displayName = "我的私有空间";
        }
        
        return SpaceVO.builder()
            .id(space.getId() != null ? space.getId() : 0L)
            .spaceType(space.getSpaceType())
            .ownerId(space.getOwnerId() != null ? space.getOwnerId() : "")
            .name(displayName)
            .totalQuota(space.getTotalQuota() != null ? space.getTotalQuota() : 0L)
            .usedQuota(space.getUsedQuota() != null ? space.getUsedQuota() : 0L)
            .fileCount(space.getFileCount() != null ? space.getFileCount() : 0)
            .folderCount(space.getFolderCount() != null ? space.getFolderCount() : 0)
            .autoInit(space.getAutoInit() != null ? space.getAutoInit() : false)
            .createdAt(space.getCreatedAt())
            .updatedAt(space.getUpdatedAt())
            .canManage(true)  // 自己的空间一定可以管理
            .build();
    }
    
    private SpaceVO convertToVO(PanSpace space, String currentUserId) {
        String displayName = space.getName() != null ? space.getName() : "";
        
        // 对于用户空间，根据是否是当前用户显示不同名称
        if ((space.getSpaceType() == SpaceType.USER_PUBLIC || space.getSpaceType() == SpaceType.USER_PRIVATE) 
                && space.getOwnerId() != null && !space.getOwnerId().isEmpty()) {
            boolean isMySpace = space.getOwnerId().equals(currentUserId);
            if (space.getSpaceType() == SpaceType.USER_PUBLIC) {
                if (isMySpace) {
                    displayName = "我的公共空间";
                } else {
                    String userName = imUserService.getUserDisplayName(space.getOwnerId());
                    displayName = userName + "的公共空间";
                }
            } else {
                if (isMySpace) {
                    displayName = "我的私有空间";
                } else {
                    String userName = imUserService.getUserDisplayName(space.getOwnerId());
                    displayName = userName + "的私有空间";
                }
            }
        }
        
        return SpaceVO.builder()
            .id(space.getId() != null ? space.getId() : 0L)
            .spaceType(space.getSpaceType())
            .ownerId(space.getOwnerId() != null ? space.getOwnerId() : "")
            .name(displayName)
            .totalQuota(space.getTotalQuota() != null ? space.getTotalQuota() : 0L)
            .usedQuota(space.getUsedQuota() != null ? space.getUsedQuota() : 0L)
            .fileCount(space.getFileCount() != null ? space.getFileCount() : 0)
            .folderCount(space.getFolderCount() != null ? space.getFolderCount() : 0)
            .autoInit(space.getAutoInit() != null ? space.getAutoInit() : false)
            .createdAt(space.getCreatedAt())
            .updatedAt(space.getUpdatedAt())
            .canManage(permissionService.canManageSpace(currentUserId, space.getId()))
            .build();
    }
}
