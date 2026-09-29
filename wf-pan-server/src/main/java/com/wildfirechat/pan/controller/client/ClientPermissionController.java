package com.wildfirechat.pan.controller.client;

import com.wildfirechat.pan.dto.Result;
import com.wildfirechat.pan.filter.ClientAuthFilter;
import com.wildfirechat.pan.repository.PanSpaceRepository;
import com.wildfirechat.pan.service.PermissionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/permission")
public class ClientPermissionController {

    @Autowired
    private PermissionService permissionService;

    @Autowired
    private PanSpaceRepository spaceRepository;

    /**
     * 检查用户是否有空间的写入权限
     */
    @GetMapping("/space/{spaceId}/write")
    public Result<Boolean> checkSpaceWritePermission(@PathVariable Long spaceId,
                                                     @RequestAttribute(ClientAuthFilter.USER_ID_KEY) String userId) {
        return spaceRepository.findById(spaceId)
            .map(space -> Result.success(permissionService.canManageSpace(userId, space)))
            .orElseGet(() -> Result.error("空间不存在"));
    }
}
