package com.wildfirechat.pan.controller.admin;

import com.wildfirechat.pan.dto.Result;
import com.wildfirechat.pan.dto.request.AddGlobalAdminRequest;
import com.wildfirechat.pan.dto.vo.GlobalAdminVO;
import com.wildfirechat.pan.entity.PanGlobalAdmin;
import com.wildfirechat.pan.filter.AdminAuthFilter;
import com.wildfirechat.pan.service.OperationLogService;
import com.wildfirechat.pan.service.auth.AdminAuthService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/global-admins")
@Slf4j
public class AdminGlobalAdminController {

    @Autowired
    private AdminAuthService adminAuthService;

    @Autowired
    private OperationLogService operationLogService;

    @GetMapping
    public Result<List<GlobalAdminVO>> list() {
        List<GlobalAdminVO> voList = adminAuthService.listAdmins().stream()
            .map(admin -> GlobalAdminVO.builder()
                .id(admin.getId())
                .userId(admin.getUserId())
                .username(admin.getUsername() != null ? admin.getUsername() : "")
                .createdBy(admin.getCreatedBy() != null ? admin.getCreatedBy() : "")
                .createdAt(admin.getCreatedAt())
                .build())
            .toList();
        return Result.success(voList);
    }

    @PostMapping
    public Result<Void> add(@Valid @RequestBody AddGlobalAdminRequest request,
                            @SessionAttribute(AdminAuthFilter.ADMIN_SESSION_KEY) String currentAdmin) {
        PanGlobalAdmin saved = adminAuthService.addAdmin(
            request.getUserId(), request.getUsername(), request.getPassword(), currentAdmin);

        operationLogService.log(currentAdmin, "ADD_GLOBAL_ADMIN", "ADMIN", saved.getId(), null,
            Map.of("userId", saved.getUserId()));
        log.info("Global admin added: {} by {}", saved.getUserId(), currentAdmin);
        return Result.success();
    }

    @DeleteMapping("/{userId}")
    public Result<Void> remove(@PathVariable String userId,
                               @SessionAttribute(AdminAuthFilter.ADMIN_SESSION_KEY) String currentAdmin) {
        PanGlobalAdmin removed = adminAuthService.removeAdmin(userId);

        operationLogService.log(currentAdmin, "REMOVE_GLOBAL_ADMIN", "ADMIN", removed.getId(), null,
            Map.of("userId", userId));
        log.info("Global admin removed: {} by {}", userId, currentAdmin);
        return Result.success();
    }
}
