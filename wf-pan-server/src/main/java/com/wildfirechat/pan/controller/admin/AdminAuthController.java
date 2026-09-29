package com.wildfirechat.pan.controller.admin;

import com.wildfirechat.pan.dto.Result;
import com.wildfirechat.pan.dto.request.ChangePasswordRequest;
import com.wildfirechat.pan.dto.request.LoginRequest;
import com.wildfirechat.pan.filter.AdminAuthFilter;
import com.wildfirechat.pan.service.OperationLogService;
import com.wildfirechat.pan.service.auth.AdminAuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@Slf4j
public class AdminAuthController {

    @Autowired
    private AdminAuthService adminAuthService;

    @Autowired
    private OperationLogService operationLogService;

    @PostMapping("/login")
    public Result<Map<String, Object>> login(@Valid @RequestBody LoginRequest request,
                                             HttpServletRequest httpRequest) {
        String username = request.getUsername();
        if (!adminAuthService.login(username, request.getPassword(), httpRequest.getRemoteAddr())) {
            return Result.error("用户名或密码错误");
        }

        // 登录成功后换一个新会话，防止会话固定攻击
        HttpSession oldSession = httpRequest.getSession(false);
        if (oldSession != null) {
            oldSession.invalidate();
        }
        httpRequest.getSession(true).setAttribute(AdminAuthFilter.ADMIN_SESSION_KEY, username);

        operationLogService.log(username, OperationLogService.OP_LOGIN, "管理员登录");
        log.info("Admin login success: {}", username);
        return Result.success(Map.of("username", username));
    }

    @PostMapping("/logout")
    public Result<Void> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            String adminUser = (String) session.getAttribute(AdminAuthFilter.ADMIN_SESSION_KEY);
            session.invalidate();
            if (adminUser != null) {
                operationLogService.log(adminUser, OperationLogService.OP_LOGOUT, "管理员退出登录");
            }
        }
        return Result.success();
    }

    @GetMapping("/info")
    public Result<Map<String, Object>> getAdminInfo(
            @SessionAttribute(AdminAuthFilter.ADMIN_SESSION_KEY) String adminUser) {
        return Result.success(Map.of("username", adminUser));
    }

    @PostMapping("/change-password")
    public Result<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request,
                                       @SessionAttribute(AdminAuthFilter.ADMIN_SESSION_KEY) String adminUser) {
        adminAuthService.changePassword(adminUser, request.getOldPassword(), request.getNewPassword());
        operationLogService.log(adminUser, OperationLogService.OP_CHANGE_PASSWORD, "管理员修改密码");
        log.info("Admin password changed: {}", adminUser);
        return Result.success();
    }
}
