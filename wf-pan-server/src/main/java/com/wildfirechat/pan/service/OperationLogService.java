package com.wildfirechat.pan.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wildfirechat.pan.entity.PanOperationLog;
import com.wildfirechat.pan.repository.PanOperationLogRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;
import java.util.Map;

@Service
@Slf4j
public class OperationLogService {

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
    public static final String OP_NEW_VERSION = "NEW_VERSION";
    public static final String OP_SHARE_FILE = "SHARE_FILE";
    public static final String OP_UNSHARE_FILE = "UNSHARE_FILE";
    public static final String OP_CREATE_DOC = "CREATE_DOC";
    public static final String OP_CONVERT_DOC = "CONVERT_DOC";

    // 目标类型常量
    public static final String TARGET_FILE = "FILE";
    public static final String TARGET_FOLDER = "FOLDER";

    private final PanOperationLogRepository logRepository;
    private final TransactionTemplate requiresNew;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OperationLogService(PanOperationLogRepository logRepository, PlatformTransactionManager transactionManager) {
        this.logRepository = logRepository;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * 记录操作日志。在事务中调用时，事务提交后才写入（回滚的操作不留日志）；
     * 写日志失败只记录错误，不影响业务。
     */
    public void log(String userId, String operation, String targetType, Long targetId,
                    Long spaceId, Object details) {
        PanOperationLog entry = new PanOperationLog();
        entry.setUserId(userId);
        entry.setOperation(operation);
        entry.setTargetType(targetType);
        entry.setTargetId(targetId);
        entry.setSpaceId(spaceId);
        entry.setCreatedAt(LocalDateTime.now());
        entry.setIpAddress(getClientIpAddress());
        entry.setDetails(toJson(details));

        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    save(entry);
                }
            });
        } else {
            save(entry);
        }
    }

    public void log(String userId, String operation, String description) {
        log(userId, operation, null, null, null, Map.of("description", description));
    }

    public Page<PanOperationLog> findLogs(Pageable pageable) {
        return logRepository.findAllByOrderByCreatedAtDesc(pageable);
    }

    public Page<PanOperationLog> findLogsByUser(String userId, Pageable pageable) {
        return logRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable);
    }

    @Transactional
    public void clearAllLogs() {
        logRepository.deleteAllInBatch();
        log.info("All operation logs cleared");
    }

    @Transactional
    public int clearLogsBeforeDays(int days) {
        LocalDateTime beforeTime = LocalDateTime.now().minusDays(days);
        int deleted = logRepository.deleteByCreatedAtBefore(beforeTime);
        log.info("Cleared {} logs before {}", deleted, beforeTime);
        return deleted;
    }

    private void save(PanOperationLog entry) {
        try {
            requiresNew.executeWithoutResult(status -> logRepository.save(entry));
        } catch (Exception e) {
            log.error("Failed to save operation log: {} - {}", entry.getUserId(), entry.getOperation(), e);
        }
    }

    private String toJson(Object details) {
        if (details == null || details instanceof String) {
            return (String) details;
        }
        try {
            return objectMapper.writeValueAsString(details);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize log details", e);
            return null;
        }
    }

    /**
     * 客户端 IP。反向代理头由容器按 server.forward-headers-strategy 处理（只信任内网代理），
     * 这里不能直接读 X-Forwarded-For，否则客户端可以伪造。
     */
    private static String getClientIpAddress() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest().getRemoteAddr();
        }
        return null;
    }
}
