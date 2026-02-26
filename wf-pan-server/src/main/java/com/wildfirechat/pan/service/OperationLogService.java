package com.wildfirechat.pan.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wildfirechat.pan.entity.PanOperationLog;
import com.wildfirechat.pan.repository.PanOperationLogRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Service
@Slf4j
public class OperationLogService {
    
    @Autowired
    private PanOperationLogRepository logRepository;
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    // 操作类型常量
    public static final String OP_CREATE_FOLDER = "CREATE_FOLDER";
    public static final String OP_UPLOAD_FILE = "UPLOAD_FILE";
    public static final String OP_DELETE_FILE = "DELETE_FILE";
    public static final String OP_RENAME_FILE = "RENAME_FILE";
    public static final String OP_MOVE_FILE = "MOVE_FILE";
    public static final String OP_COPY_FILE = "COPY_FILE";
    public static final String OP_ADMIN_DELETE_FILE = "ADMIN_DELETE_FILE";
    public static final String OP_LOGIN = "LOGIN";
    public static final String OP_LOGOUT = "LOGOUT";
    public static final String OP_CHANGE_PASSWORD = "CHANGE_PASSWORD";
    
    // 目标类型常量
    public static final String TARGET_FILE = "FILE";
    public static final String TARGET_FOLDER = "FOLDER";
    public static final String TARGET_SPACE = "SPACE";
    
    /**
     * 记录操作日志
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void log(String userId, String operation, String targetType, Long targetId, 
                    Long spaceId, Object details) {
        try {
            PanOperationLog operationLog = new PanOperationLog();
            operationLog.setUserId(userId);
            operationLog.setOperation(operation);
            operationLog.setTargetType(targetType);
            operationLog.setTargetId(targetId);
            operationLog.setSpaceId(spaceId);
            operationLog.setCreatedAt(LocalDateTime.now());
            
            // 获取IP地址
            String ipAddress = getClientIpAddress();
            operationLog.setIpAddress(ipAddress);
            
            // 转换details为JSON
            if (details != null) {
                if (details instanceof String) {
                    operationLog.setDetails((String) details);
                } else {
                    operationLog.setDetails(objectMapper.writeValueAsString(details));
                }
            }
            
            logRepository.save(operationLog);
            log.debug("Operation logged: {} - {} - {}", userId, operation, targetId);
        } catch (Exception e) {
            log.error("Failed to log operation", e);
        }
    }
    
    /**
     * 简化的日志记录方法
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void log(String userId, String operation, String description) {
        Map<String, Object> details = new HashMap<>();
        details.put("description", description);
        log(userId, operation, null, null, null, details);
    }
    
    /**
     * 查询日志列表
     */
    public Page<PanOperationLog> findLogs(Pageable pageable) {
        return logRepository.findAllByOrderByCreatedAtDesc(pageable);
    }
    
    /**
     * 根据用户查询日志
     */
    public Page<PanOperationLog> findLogsByUser(String userId, Pageable pageable) {
        return logRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable);
    }
    
    /**
     * 清空所有日志
     */
    @Transactional
    public void clearAllLogs() {
        logRepository.deleteAll();
        log.info("All operation logs cleared");
    }
    
    /**
     * 清理指定天数前的日志
     */
    @Transactional
    public int clearLogsBeforeDays(int days) {
        LocalDateTime beforeTime = LocalDateTime.now().minusDays(days);
        int deleted = logRepository.deleteByCreatedAtBefore(beforeTime);
        log.info("Cleared {} logs before {}", deleted, beforeTime);
        return deleted;
    }
    
    /**
     * 获取客户端IP地址
     */
    private String getClientIpAddress() {
        try {
            ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attributes != null) {
                HttpServletRequest request = attributes.getRequest();
                String ip = request.getHeader("X-Forwarded-For");
                if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
                    ip = request.getHeader("Proxy-Client-IP");
                }
                if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
                    ip = request.getHeader("WL-Proxy-Client-IP");
                }
                if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
                    ip = request.getRemoteAddr();
                }
                // 多个代理情况，取第一个IP
                if (ip != null && ip.contains(",")) {
                    ip = ip.split(",")[0].trim();
                }
                return ip;
            }
        } catch (Exception e) {
            log.error("Failed to get client IP", e);
        }
        return null;
    }
}
