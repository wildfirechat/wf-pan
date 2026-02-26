package com.wildfirechat.pan.controller.admin;

import cn.wildfirechat.pojos.InputOutputUserInfo;
import com.wildfirechat.pan.dto.Result;
import com.wildfirechat.pan.service.IMUserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 用户管理接口
 */
@RestController
@RequestMapping("/api/users")
@Slf4j
public class AdminUserController {
    
    @Autowired
    private IMUserService imUserService;
    
    /**
     * 获取用户详情
     */
    @GetMapping("/{userId}")
    public Result<Map<String, Object>> getUserInfo(@PathVariable String userId) {
        InputOutputUserInfo userInfo = imUserService.getUserInfo(userId);
        
        if (userInfo == null) {
            return Result.error("用户不存在");
        }
        
        Map<String, Object> data = new HashMap<>();
        data.put("userId", userInfo.getUserId());
        data.put("name", userInfo.getName());
        data.put("displayName", userInfo.getDisplayName());
        data.put("portrait", userInfo.getPortrait());
        data.put("mobile", userInfo.getMobile());
        data.put("email", userInfo.getEmail());
        data.put("address", userInfo.getAddress());
        data.put("company", userInfo.getCompany());
        data.put("extra", userInfo.getExtra());
        data.put("updateDt", userInfo.getUpdateDt());
        
        return Result.success(data);
    }
}
