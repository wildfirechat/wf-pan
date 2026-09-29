package com.wildfirechat.pan.controller.admin;

import com.wildfirechat.pan.dto.Result;
import com.wildfirechat.pan.entity.PanOperationLog;
import com.wildfirechat.pan.exception.BusinessException;
import com.wildfirechat.pan.filter.AdminAuthFilter;
import com.wildfirechat.pan.service.OperationLogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 操作日志管理接口
 */
@RestController
@RequestMapping("/api/logs")
public class AdminLogController {

    @Autowired
    private OperationLogService operationLogService;

    @GetMapping
    public Result<Page<PanOperationLog>> list(@RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "20") int size,
                                              @RequestParam(required = false) String userId) {
        PageRequest pageable = AdminPaging.of(page, size);
        if (userId != null && !userId.isEmpty()) {
            return Result.success(operationLogService.findLogsByUser(userId, pageable));
        }
        return Result.success(operationLogService.findLogs(pageable));
    }

    /**
     * 清空所有日志（清空后记录一条本次操作的日志）
     */
    @DeleteMapping("/clear")
    public Result<Void> clearAll(@SessionAttribute(AdminAuthFilter.ADMIN_SESSION_KEY) String adminUser) {
        operationLogService.clearAllLogs();
        operationLogService.log(adminUser, "CLEAR_ALL_LOGS", "SYSTEM", null, null,
            Map.of("description", "清空所有操作日志"));
        return Result.success();
    }

    /**
     * 清理指定天数前的日志
     */
    @DeleteMapping("/clear-before")
    public Result<Map<String, Object>> clearBefore(@RequestParam int days,
                                                   @SessionAttribute(AdminAuthFilter.ADMIN_SESSION_KEY) String adminUser) {
        if (days < 1) {
            throw new BusinessException("天数必须大于0");
        }
        int deleted = operationLogService.clearLogsBeforeDays(days);
        operationLogService.log(adminUser, "CLEAR_OLD_LOGS", "SYSTEM", null, null,
            Map.of("days", days, "deletedCount", deleted, "description", "清理" + days + "天前的操作日志"));
        return Result.success(Map.of("deletedCount", deleted, "days", days));
    }
}
