package com.wildfirechat.pan.controller.client;

import com.wildfirechat.pan.dto.Result;
import com.wildfirechat.pan.entity.PanSpace;
import com.wildfirechat.pan.filter.ClientAuthFilter;
import com.wildfirechat.pan.repository.PanSpaceRepository;
import com.wildfirechat.pan.service.PermissionService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/permission")
@Slf4j
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
                                                      HttpServletRequest request) {
        String userId = (String) request.getAttribute(ClientAuthFilter.USER_ID_KEY);
        
        PanSpace space = spaceRepository.findById(spaceId).orElse(null);
        if (space == null) {
            return Result.error("空间不存在");
        }
        
        boolean canManage = permissionService.canManageSpace(userId, spaceId);
        return Result.success(canManage);
    }
}
