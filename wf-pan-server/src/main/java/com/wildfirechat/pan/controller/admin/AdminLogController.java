package com.wildfirechat.pan.controller.admin;

import com.wildfirechat.pan.dto.Result;
import com.wildfirechat.pan.entity.PanOperationLog;
import com.wildfirechat.pan.filter.AdminAuthFilter;
import com.wildfirechat.pan.service.OperationLogService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 操作日志管理接口
 */
@RestController
@RequestMapping("/api/logs")
@Slf4j
public class AdminLogController {
    
    @Autowired
    private OperationLogService operationLogService;
    
    /**
     * 查询日志列表
     */
    @GetMapping
    public Result<Page<PanOperationLog>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String userId) {
        
        Pageable pageable = PageRequest.of(page, size);
        Page<PanOperationLog> logs;
        
        if (userId != null && !userId.isEmpty()) {
            logs = operationLogService.findLogsByUser(userId, pageable);
        } else {
            logs = operationLogService.findLogs(pageable);
        }
        
        return Result.success(logs);
    }
    
    /**
     * 清空所有日志
     */
    @DeleteMapping("/clear")
    public Result<Void> clearAll(HttpServletRequest request) {
        String adminUser = getCurrentAdminUser(request);
        
        // 先执行清空操作
        operationLogService.clearAllLogs();
        
        // 再记录这次清空操作（这是新产生的唯一一条日志）
        Map<String, Object> details = new HashMap<>();
        details.put("description", "清空所有操作日志");
        details.put("adminUser", adminUser);
        operationLogService.log(adminUser, "CLEAR_ALL_LOGS", "SYSTEM", null, null, details);
        
        return Result.success();
    }
    
    /**
     * 清理指定天数前的日志
     */
    @DeleteMapping("/clear-before")
    public Result<Map<String, Object>> clearBefore(@RequestParam int days, HttpServletRequest request) {
        String adminUser = getCurrentAdminUser(request);
        
        // 先执行清理操作
        int deleted = operationLogService.clearLogsBeforeDays(days);
        
        // 再记录这次清理操作
        Map<String, Object> details = new HashMap<>();
        details.put("days", days);
        details.put("deletedCount", deleted);
        details.put("description", "清理" + days + "天前的操作日志");
        details.put("adminUser", adminUser);
        operationLogService.log(adminUser, "CLEAR_OLD_LOGS", "SYSTEM", null, null, details);
        
        Map<String, Object> data = new HashMap<>();
        data.put("deletedCount", deleted);
        data.put("days", days);
        
        return Result.success(data);
    }
    
    /**
     * 获取当前登录的管理员用户
     */
    private String getCurrentAdminUser(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            String adminUser = (String) session.getAttribute(AdminAuthFilter.ADMIN_SESSION_KEY);
            if (adminUser != null) {
                return adminUser;
            }
        }
        return "unknown";
    }
}
