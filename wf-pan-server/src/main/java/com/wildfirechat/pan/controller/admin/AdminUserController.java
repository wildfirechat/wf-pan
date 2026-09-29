package com.wildfirechat.pan.controller.admin;

import com.wildfirechat.pan.dto.Result;
import com.wildfirechat.pan.dto.vo.UserInfoVO;
import com.wildfirechat.pan.service.IMUserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户管理接口
 */
@RestController
@RequestMapping("/api/users")
public class AdminUserController {

    @Autowired
    private IMUserService imUserService;

    @GetMapping("/{userId}")
    public Result<UserInfoVO> getUserInfo(@PathVariable String userId) {
        return imUserService.findUserInfo(userId)
            .map(Result::success)
            .orElseGet(() -> Result.error("用户不存在"));
    }
}
