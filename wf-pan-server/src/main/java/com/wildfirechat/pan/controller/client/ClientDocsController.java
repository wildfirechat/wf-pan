package com.wildfirechat.pan.controller.client;

import com.wildfirechat.pan.config.DocsConfig;
import com.wildfirechat.pan.dto.Result;
import com.wildfirechat.pan.dto.request.CreateDocRequest;
import com.wildfirechat.pan.dto.request.EditorConfigRequest;
import com.wildfirechat.pan.dto.request.FileIdRequest;
import com.wildfirechat.pan.dto.request.PreviewPdfRequest;
import com.wildfirechat.pan.dto.request.ViewUrlRequest;
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
     * 按链接只读打开在线文档：文件不在网盘里（聊天里的文件消息、外部链接），
     * 地址须在受信任的存储前缀下；内容由服务端代理给 ONLYOFFICE，只读、不回写。
     */
    @PostMapping("/view-url")
    public Result<Map<String, Object>> viewUrl(@Valid @RequestBody ViewUrlRequest request,
                                               HttpServletRequest httpRequest) {
        String userId = (String) httpRequest.getAttribute(ClientAuthFilter.USER_ID_KEY);
        if (userId == null || userId.isEmpty()) {
            return Result.error(401, "用户未登录");
        }
        return Result.success(docsService.openReadOnlyUrl(request.getUrl(), request.getName(),
            userId, request.getPlatform()));
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
     * 只读 PDF 预览（手机端省流量）：把文档转成 PDF 再显示，返回带签名的 PDF 地址。
     * 手机端打开文档默认走这里，转不了再退回编辑器。网盘文件传 fileId，按链接只读传 url(+name)。
     */
    @PostMapping("/preview-pdf")
    public Result<Map<String, Object>> previewPdf(@Valid @RequestBody PreviewPdfRequest request,
                                                  HttpServletRequest httpRequest) {
        String userId = (String) httpRequest.getAttribute(ClientAuthFilter.USER_ID_KEY);
        if (userId == null || userId.isEmpty()) {
            return Result.error(401, "用户未登录");
        }
        return Result.success(docsService.previewPdf(request.getFileId(), request.getUrl(), request.getName(),
            userId, httpRequest));
    }

    /**
     * 文档页用的开关：手机上能不能编辑（决定首页是否给手机提供新建）
     */
    @PostMapping("/options")
    public Result<Map<String, Object>> options() {
        return Result.success(Map.of(
            "mobileEdit", docsConfig.isMobileEdit(),
            "mobilePdfPreview", docsConfig.isMobilePdfPreview()));
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
