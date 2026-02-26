package com.wildfirechat.pan.controller.admin;

import com.wildfirechat.pan.dto.Result;
import com.wildfirechat.pan.dto.request.AddGlobalAdminRequest;
import com.wildfirechat.pan.dto.vo.GlobalAdminVO;
import com.wildfirechat.pan.entity.PanGlobalAdmin;
import com.wildfirechat.pan.filter.AdminAuthFilter;
import com.wildfirechat.pan.repository.PanGlobalAdminRepository;
import com.wildfirechat.pan.service.OperationLogService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/global-admins")
@Slf4j
public class AdminGlobalAdminController {
    
    @Autowired
    private PanGlobalAdminRepository globalAdminRepository;
    
    @Autowired
    private OperationLogService operationLogService;
    
    @GetMapping
    public Result<List<GlobalAdminVO>> list() {
        List<PanGlobalAdmin> admins = globalAdminRepository.findAllByOrderByCreatedAtDesc();
        
        List<GlobalAdminVO> voList = admins.stream()
            .map(admin -> GlobalAdminVO.builder()
                .id(admin.getId() != null ? admin.getId() : 0L)
                .userId(admin.getUserId() != null ? admin.getUserId() : "")
                .username(admin.getUsername() != null ? admin.getUsername() : "")
                .createdBy(admin.getCreatedBy() != null ? admin.getCreatedBy() : "")
                .createdAt(admin.getCreatedAt())
                .build())
            .collect(Collectors.toList());
        
        return Result.success(voList);
    }
    
    @PostMapping
    public Result<Void> add(@Valid @RequestBody AddGlobalAdminRequest request, 
                            HttpServletRequest httpRequest) {
        // 检查是否已存在
        if (globalAdminRepository.existsByUserId(request.getUserId())) {
            return Result.error("该用户已是管理员");
        }
        
        // 获取当前管理员
        HttpSession session = httpRequest.getSession(false);
        String currentAdmin = session != null ? 
            (String) session.getAttribute(AdminAuthFilter.ADMIN_SESSION_KEY) : "unknown";
        
        PanGlobalAdmin admin = new PanGlobalAdmin();
        admin.setUserId(request.getUserId());
        admin.setUsername(request.getUsername());
        admin.setCreatedBy(currentAdmin);
        
        PanGlobalAdmin saved = globalAdminRepository.save(admin);
        
        // 记录操作日志
        Map<String, Object> details = new HashMap<>();
        details.put("userId", request.getUserId());
        details.put("username", request.getUsername());
        details.put("createdBy", currentAdmin);
        operationLogService.log(currentAdmin, "ADD_GLOBAL_ADMIN", "ADMIN", 
            saved.getId(), null, details);
        
        log.info("Global admin added: {} by {}", request.getUserId(), currentAdmin);
        return Result.success();
    }
    
    @DeleteMapping("/{userId}")
    public Result<Void> remove(@PathVariable String userId, HttpServletRequest httpRequest) {
        // 检查是否至少保留一个管理员
        long count = globalAdminRepository.count();
        if (count <= 1) {
            return Result.error("至少保留一个管理员");
        }
        
        // 获取当前管理员
        HttpSession session = httpRequest.getSession(false);
        String currentAdmin = session != null ? 
            (String) session.getAttribute(AdminAuthFilter.ADMIN_SESSION_KEY) : "unknown";
        
        globalAdminRepository.deleteByUserId(userId);
        
        // 记录操作日志
        Map<String, Object> details = new HashMap<>();
        details.put("userId", userId);
        details.put("removedBy", currentAdmin);
        operationLogService.log(currentAdmin, "REMOVE_GLOBAL_ADMIN", "ADMIN", 
            null, null, details);
        
        log.info("Global admin removed: {} by {}", userId, currentAdmin);
        return Result.success();
    }
}
