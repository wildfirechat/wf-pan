package com.wildfirechat.pan.controller.client;

import com.wildfirechat.pan.config.DocsConfig;
import com.wildfirechat.pan.dto.Result;
import com.wildfirechat.pan.dto.request.CreateDocRequest;
import com.wildfirechat.pan.dto.request.EditorConfigRequest;
import com.wildfirechat.pan.dto.request.FileIdRequest;
import com.wildfirechat.pan.dto.vo.FileVO;
import com.wildfirechat.pan.dto.vo.RecentDocVO;
import com.wildfirechat.pan.filter.ClientAuthFilter;
import com.wildfirechat.pan.service.docs.DocsService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/docs")
public class ClientDocsController {
    
    @Autowired
    private DocsService docsService;

    @Autowired
    private DocsConfig docsConfig;

    /**
     * 用空白模板新建 docx / xlsx / pptx
     */
    @PostMapping("/create")
    public Result<FileVO> create(@Valid @RequestBody CreateDocRequest request, HttpServletRequest httpRequest) {
        String userId = (String) httpRequest.getAttribute(ClientAuthFilter.USER_ID_KEY);
        if (userId == null || userId.isEmpty()) {
            return Result.error(401, "用户未登录");
        }
        return Result.success(docsService.create(request, userId));
    }
    
    /**
     * 打开编辑器：返回签好名的编辑器配置（无编辑权、旧格式只读；手机端按 docs.mobile_edit）
     */
    @PostMapping("/editor-config")
    public Result<Map<String, Object>> editorConfig(@Valid @RequestBody EditorConfigRequest request,
                                                    HttpServletRequest httpRequest) {
        String userId = (String) httpRequest.getAttribute(ClientAuthFilter.USER_ID_KEY);
        if (userId == null || userId.isEmpty()) {
            return Result.error(401, "用户未登录");
        }
        return Result.success(docsService.openEditor(request.getFileId(), userId, request.getPlatform(),
            Boolean.TRUE.equals(request.getView())));
    }
    
    /**
     * 旧格式（doc/xls/ppt/wps…）转成 OOXML，另存为新文件
     */
    @PostMapping("/convert")
    public Result<FileVO> convert(@Valid @RequestBody FileIdRequest request, HttpServletRequest httpRequest) {
        String userId = (String) httpRequest.getAttribute(ClientAuthFilter.USER_ID_KEY);
        if (userId == null || userId.isEmpty()) {
            return Result.error(401, "用户未登录");
        }
        return Result.success(docsService.convert(request.getFileId(), userId));
    }
    
    /**
     * 文档页用的开关：手机上能不能编辑（决定首页是否给手机提供新建）
     */
    @PostMapping("/options")
    public Result<Map<String, Object>> options() {
        return Result.success(Map.of("mobileEdit", docsConfig.isMobileEdit()));
    }

    /**
     * 最近打开
     */
    @PostMapping("/recent")
    public Result<List<RecentDocVO>> recent(HttpServletRequest httpRequest) {
        String userId = (String) httpRequest.getAttribute(ClientAuthFilter.USER_ID_KEY);
        if (userId == null || userId.isEmpty()) {
            return Result.error(401, "用户未登录");
        }
        return Result.success(docsService.recent(userId, 50));
    }

    /**
     * 从最近打开里移除（只删这条记录，不删文件）
     */
    @PostMapping("/recent/remove")
    public Result<Void> removeRecent(@Valid @RequestBody FileIdRequest request, HttpServletRequest httpRequest) {
        String userId = (String) httpRequest.getAttribute(ClientAuthFilter.USER_ID_KEY);
        if (userId == null || userId.isEmpty()) {
            return Result.error(401, "用户未登录");
        }
        docsService.removeRecent(userId, request.getFileId());
        return Result.success();
    }
}
