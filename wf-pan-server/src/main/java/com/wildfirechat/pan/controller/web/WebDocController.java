package com.wildfirechat.pan.controller.web;

import com.wildfirechat.pan.config.DocsConfig;
import com.wildfirechat.pan.dto.Result;
import com.wildfirechat.pan.dto.request.WebSessionRequest;
import com.wildfirechat.pan.filter.ClientAuthFilter;
import com.wildfirechat.pan.service.IMUserService;
import com.wildfirechat.pan.service.docs.DocsService;
import com.wildfirechat.pan.service.SignService;
import com.wildfirechat.pan.service.auth.ClientAuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 在线文档页面（H5，在客户端的工作台 webview 里打开；对外路径 /pan/doc/...）。
 * 页面先经 dsbridge 的 getAuthCode 取码，再调 /doc/session 换成会话 Cookie，authCode 不进 URL。
 */
@Controller
public class WebDocController {

    @Autowired
    private ClientAuthService clientAuthService;

    @Autowired
    private SignService signService;

    @Autowired
    private IMUserService imUserService;

    @Autowired
    private DocsConfig docsConfig;

    @Autowired
    private DocsService docsService;

    @GetMapping("/doc")
    public void root(HttpServletResponse response) {
        // 相对地址：对外是 /pan/doc → /pan/doc/
        response.setStatus(HttpServletResponse.SC_FOUND);
        response.setHeader("Location", "doc/");
    }

    @GetMapping("/doc/")
    public String index() {
        return "forward:/doc/index.html";
    }

    @GetMapping("/doc/open")
    public String open() {
        return "forward:/doc/open.html";
    }

    /** 只读 PDF 预览页：手机端打开文档走这里，省掉十几 MB 的编辑器静态资源 */
    @GetMapping("/doc/preview")
    public String preview() {
        return "forward:/doc/preview.html";
    }

    /** 输出缓存的预览 PDF；链接由 /api/v1/docs/preview-pdf 签发，自带签名与有效期 */
    @GetMapping("/doc/preview.pdf")
    public void previewPdf(@RequestParam(value = "f", required = false) Long fileId,
                           @RequestParam(value = "v", required = false) Integer versionNo,
                           @RequestParam(value = "u", required = false) String encodedUrl,
                           @RequestParam("e") long expire,
                           @RequestParam("s") String sign,
                           HttpServletResponse response) throws Exception {
        docsService.servePreviewPdf(fileId, versionNo, encodedUrl, expire, sign, response);
    }

    @PostMapping("/doc/session")
    @ResponseBody
    public Result<Map<String, Object>> session(@Valid @RequestBody WebSessionRequest request,
                                               HttpServletRequest httpRequest, HttpServletResponse response) {
        String userId = clientAuthService.validateAuthCode(request.getAuthCode());
        if (userId == null || userId.isEmpty()) {
            return Result.error(1002, "无效的 authCode");
        }
        boolean https = "https".equalsIgnoreCase(httpRequest.getHeader("X-Forwarded-Proto")) || httpRequest.isSecure();
        ResponseCookie cookie = ResponseCookie.from(ClientAuthFilter.WEB_SESSION_COOKIE, signService.issueWebSession(userId))
            .path(docsConfig.getPanPublicPath() + "/")
            .httpOnly(true)
            .secure(https)
            .sameSite("Strict")
            .maxAge(docsConfig.getWebSessionTtlSeconds())
            .build();
        response.addHeader("Set-Cookie", cookie.toString());
        Map<String, Object> me = new LinkedHashMap<>();
        me.put("userId", userId);
        me.put("displayName", imUserService.getUserDisplayName(userId));
        me.put("docsEnabled", docsConfig.isEnabled());
        return Result.success(me);
    }
}
