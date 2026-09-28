package com.wildfirechat.pan.controller.client;

import com.wildfirechat.pan.dto.Result;
import com.wildfirechat.pan.dto.request.AddShareRequest;
import com.wildfirechat.pan.dto.request.FileIdRequest;
import com.wildfirechat.pan.dto.request.ShareIdRequest;
import com.wildfirechat.pan.dto.vo.ShareVO;
import com.wildfirechat.pan.dto.vo.SharedFileVO;
import com.wildfirechat.pan.filter.ClientAuthFilter;
import com.wildfirechat.pan.service.ShareService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/shares")
public class ClientShareController {
    
    @Autowired
    private ShareService shareService;
    
    /**
     * 文件的分享列表
     */
    @PostMapping("/list")
    public Result<List<ShareVO>> list(@Valid @RequestBody FileIdRequest request, HttpServletRequest httpRequest) {
        String userId = (String) httpRequest.getAttribute(ClientAuthFilter.USER_ID_KEY);
        if (userId == null || userId.isEmpty()) {
            return Result.error(401, "用户未登录");
        }
        return Result.success(shareService.list(request.getFileId(), userId));
    }
    
    /**
     * 分享给某人 / 某群（已分享过则修改权限）
     */
    @PostMapping("/add")
    public Result<ShareVO> add(@Valid @RequestBody AddShareRequest request, HttpServletRequest httpRequest) {
        String userId = (String) httpRequest.getAttribute(ClientAuthFilter.USER_ID_KEY);
        if (userId == null || userId.isEmpty()) {
            return Result.error(401, "用户未登录");
        }
        return Result.success(shareService.add(request, userId));
    }
    
    /**
     * 取消分享
     */
    @PostMapping("/remove")
    public Result<Void> remove(@Valid @RequestBody ShareIdRequest request, HttpServletRequest httpRequest) {
        String userId = (String) httpRequest.getAttribute(ClientAuthFilter.USER_ID_KEY);
        if (userId == null || userId.isEmpty()) {
            return Result.error(401, "用户未登录");
        }
        shareService.remove(request.getShareId(), userId);
        return Result.success();
    }
    
    /**
     * 共享给我的文件
     */
    @PostMapping("/with-me")
    public Result<List<SharedFileVO>> withMe(HttpServletRequest httpRequest) {
        String userId = (String) httpRequest.getAttribute(ClientAuthFilter.USER_ID_KEY);
        if (userId == null || userId.isEmpty()) {
            return Result.error(401, "用户未登录");
        }
        return Result.success(shareService.sharedWithMe(userId));
    }
}
