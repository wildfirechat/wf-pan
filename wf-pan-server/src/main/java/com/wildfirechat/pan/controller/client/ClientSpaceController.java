package com.wildfirechat.pan.controller.client;

import com.wildfirechat.pan.constant.SpaceType;
import com.wildfirechat.pan.dto.Result;
import com.wildfirechat.pan.dto.request.GetSpaceFilesRequest;
import com.wildfirechat.pan.dto.request.GetUserPublicSpaceRequest;
import com.wildfirechat.pan.dto.vo.FileVO;
import com.wildfirechat.pan.dto.vo.SpaceVO;
import com.wildfirechat.pan.entity.PanSpace;
import com.wildfirechat.pan.filter.ClientAuthFilter;
import com.wildfirechat.pan.repository.PanSpaceRepository;
import com.wildfirechat.pan.service.FileService;
import com.wildfirechat.pan.service.IMUserService;
import com.wildfirechat.pan.service.PermissionService;
import com.wildfirechat.pan.service.UserSpaceInitService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/spaces")
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
    public Result<List<SpaceVO>> list(@RequestAttribute(ClientAuthFilter.USER_ID_KEY) String userId) {
        userSpaceInitService.getOrInitUserSpaces(userId);
        List<SpaceVO> spaces = spaceRepository.findAccessibleSpaces(userId).stream()
            .map(space -> toVO(space, userId))
            .toList();
        return Result.success(spaces);
    }

    /**
     * 获取用户自己的空间（公共 + 私有）
     */
    @PostMapping("/my")
    public Result<List<SpaceVO>> getMySpaces(@RequestAttribute(ClientAuthFilter.USER_ID_KEY) String userId) {
        List<SpaceVO> spaces = userSpaceInitService.getOrInitUserSpaces(userId).stream()
            .map(space -> SpaceVO.of(space, displayName(space, userId), true))
            .toList();
        return Result.success(spaces);
    }

    /**
     * 获取指定用户的公共空间（用于查看其他用户的网盘）
     */
    @PostMapping("/user/public")
    public Result<SpaceVO> getUserPublicSpace(@Valid @RequestBody GetUserPublicSpaceRequest request,
                                              @RequestAttribute(ClientAuthFilter.USER_ID_KEY) String userId) {
        return spaceRepository.findBySpaceTypeAndOwnerId(SpaceType.USER_PUBLIC, request.getTargetUserId())
            .map(space -> Result.success(toVO(space, userId)))
            .orElseGet(() -> Result.error("用户公共空间不存在"));
    }

    /**
     * 获取空间内文件列表
     */
    @PostMapping("/files")
    public Result<List<FileVO>> getFiles(@Valid @RequestBody GetSpaceFilesRequest request,
                                         @RequestAttribute(ClientAuthFilter.USER_ID_KEY) String userId) {
        return Result.success(fileService.getFileList(request.getSpaceId(), request.getParentId(), userId));
    }

    private SpaceVO toVO(PanSpace space, String userId) {
        return SpaceVO.of(space, displayName(space, userId), permissionService.canManageSpace(userId, space));
    }

    /**
     * 用户空间显示为"我的公共空间"或"xxx的公共空间"，全局空间显示自身名称
     */
    private String displayName(PanSpace space, String currentUserId) {
        String suffix = switch (space.getSpaceType()) {
            case USER_PUBLIC -> "的公共空间";
            case USER_PRIVATE -> "的私有空间";
            case GLOBAL_PUBLIC -> null;
        };
        if (suffix == null || space.getOwnerId() == null) {
            return space.getName();
        }
        String owner = space.getOwnerId().equals(currentUserId) ? "我" : imUserService.getUserDisplayName(space.getOwnerId());
        return owner + suffix;
    }
}
