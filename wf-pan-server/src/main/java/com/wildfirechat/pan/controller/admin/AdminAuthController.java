package com.wildfirechat.pan.controller.admin;

import com.wildfirechat.pan.dto.Result;
import com.wildfirechat.pan.dto.request.LoginRequest;
import com.wildfirechat.pan.dto.request.ChangePasswordRequest;
import com.wildfirechat.pan.filter.AdminAuthFilter;
import com.wildfirechat.pan.service.OperationLogService;
import com.wildfirechat.pan.service.auth.AdminAuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
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
        log.info("Admin login attempt: {}", request.getUsername());
        
        if (adminAuthService.validateLogin(request)) {
            HttpSession session = httpRequest.getSession(true);
            session.setAttribute(AdminAuthFilter.ADMIN_SESSION_KEY, request.getUsername());
            
            Map<String, Object> data = new HashMap<>();
            data.put("username", request.getUsername());
            data.put("sessionId", session.getId());
            
            log.info("Admin login success: {}", request.getUsername());
            return Result.success(data);
        }
        
        return Result.error("用户名或密码错误");
    }
    
    @PostMapping("/logout")
    public Result<Void> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        return Result.success();
    }
    
    @GetMapping("/info")
    public Result<Map<String, Object>> getAdminInfo(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        String adminUser = session != null ? 
            (String) session.getAttribute(AdminAuthFilter.ADMIN_SESSION_KEY) : null;
        
        if (adminUser == null) {
            return Result.error("未登录");
        }
        
        Map<String, Object> data = new HashMap<>();
        data.put("username", adminUser);
        return Result.success(data);
    }
    
    @PostMapping("/change-password")
    public Result<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request,
                                        HttpServletRequest httpRequest) {
        HttpSession session = httpRequest.getSession(false);
        String adminUser = session != null ? 
            (String) session.getAttribute(AdminAuthFilter.ADMIN_SESSION_KEY) : null;
        
        if (adminUser == null) {
            return Result.error("未登录");
        }
        
        // 验证旧密码
        LoginRequest validateRequest = new LoginRequest();
        validateRequest.setUsername(adminUser);
        validateRequest.setPassword(request.getOldPassword());
        
        if (!adminAuthService.validateLogin(validateRequest)) {
            return Result.error("旧密码错误");
        }
        
        // 更新密码
        adminAuthService.updatePassword(request.getNewPassword());
        
        // 记录修改密码日志
        operationLogService.log(adminUser, OperationLogService.OP_CHANGE_PASSWORD, 
            "管理员修改密码");
        
        log.info("Admin password changed: {}", adminUser);
        
        return Result.success();
    }
}
