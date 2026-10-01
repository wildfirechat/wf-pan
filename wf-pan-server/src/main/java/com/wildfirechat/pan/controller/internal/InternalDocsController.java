package com.wildfirechat.pan.controller.internal;

import com.wildfirechat.pan.config.ProxiedRequestValve;
import com.wildfirechat.pan.service.ObjectStoreService;
import com.wildfirechat.pan.service.SignService;
import com.wildfirechat.pan.service.docs.DocsService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * 只给 ONLYOFFICE 在容器网络内调用的接口（NG 不转发 /internal/）。一律校验 ONLYOFFICE 签的 JWT。
 * 取文件还要校验地址上本服务的签名：ONLYOFFICE 也会为用户指定的地址（如按地址插入图片）签令牌，
 * JWT 只能证明请求来自 ONLYOFFICE，不能证明这个文件是签给它的。
 * 额外防一层：只认客户端端口、且不是经代理转来的请求（经 NG 转来的都会带 X-Forwarded-For）。
 */
@RestController
@RequestMapping("/internal/docs")
@Slf4j
public class InternalDocsController {

    @Autowired
    private DocsService docsService;

    @Autowired
    private ObjectStoreService objectStoreService;

    @Autowired
    private SignService signService;

    @Value("${server.port}")
    private int clientPort;

    /** ONLYOFFICE 取文件（打开、转换时） */
    @GetMapping("/file/{fileId}")
    public void file(@PathVariable Long fileId,
                     // 缺参数按签名无效处理
                     @RequestParam(value = "v", defaultValue = "0") int versionNo,
                     @RequestParam(value = "e", defaultValue = "0") long expire,
                     @RequestParam(value = "s", required = false) String sig,
                     @RequestHeader(value = "Authorization", required = false) String authorization,
                     HttpServletRequest request, HttpServletResponse response) throws Exception {
        if (!fromInside(request)) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        if (!signService.verifyEditorFile(fileId, versionNo, expire, sig)) {
            log.warn("ONLYOFFICE 取文件地址签名无效或已过期 file={} v={}", fileId, versionNo);
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        if (docsService.verifyInbound(authorization, null) == null) {
            log.warn("ONLYOFFICE 取文件 JWT 校验失败 file={}", fileId);
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        String storageUrl;
        try {
            storageUrl = docsService.storageUrlForEditor(fileId, versionNo);
        } catch (Exception e) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        response.setContentType("application/octet-stream");
        try (InputStream in = objectStoreService.open(storageUrl, null, null)) {
            in.transferTo(response.getOutputStream());
        }
    }

    /**
     * ONLYOFFICE 按链接只读打开时取文件：内容由本服务代理（不让 ONLYOFFICE 直连来源地址）。
     * 地址上的签名绑定来源地址与有效期；来源前缀在组装配置时与这里各校验一次。
     */
    @GetMapping("/raw")
    public void raw(@RequestParam(value = "u", required = false) String encodedUrl,
                    @RequestParam(value = "e", defaultValue = "0") long expire,
                    @RequestParam(value = "s", required = false) String sig,
                    @RequestHeader(value = "Authorization", required = false) String authorization,
                    HttpServletRequest request, HttpServletResponse response) throws Exception {
        if (!fromInside(request)) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        String url;
        try {
            url = encodedUrl == null || encodedUrl.isEmpty() ? null
                : new String(Base64.getUrlDecoder().decode(encodedUrl), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            url = null;
        }
        if (url == null || !signService.verifyEditorUrl(url, expire, sig)) {
            log.warn("ONLYOFFICE 按链接取文件：地址签名无效或已过期");
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        if (docsService.verifyInbound(authorization, null) == null) {
            log.warn("ONLYOFFICE 按链接取文件：JWT 校验失败");
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        response.setContentType("application/octet-stream");
        try (InputStream in = docsService.openReadOnlySource(url)) {
            in.transferTo(response.getOutputStream());
        } catch (Exception e) {
            log.warn("ONLYOFFICE 按链接取文件失败: {}", e.getMessage());
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
        }
    }

    /** 保存回调。只有保存成功才回 error 0 */
    @PostMapping("/callback")
    public Map<String, Object> callback(@RequestParam("fileId") Long fileId,
                                        @RequestHeader(value = "Authorization", required = false) String authorization,
                                        @RequestBody(required = false) Map<String, Object> body,
                                        HttpServletRequest request, HttpServletResponse response) {
        if (!fromInside(request)) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return Map.of("error", 1);
        }
        Map<String, Object> data = docsService.verifyInbound(authorization, body);
        if (data == null) {
            log.warn("ONLYOFFICE 回调 JWT 校验失败 file={}", fileId);
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return Map.of("error", 1);
        }
        return docsService.handleCallback(fileId, data);
    }

    /**
     * X-Forwarded-For 可能已被 RemoteIpValve 删掉（server.forward-headers-strategy=native），
     * 所以以 {@link ProxiedRequestValve} 在它之前做的标记为准
     */
    private boolean fromInside(HttpServletRequest request) {
        return request.getLocalPort() == clientPort
            && request.getAttribute(ProxiedRequestValve.PROXIED_ATTRIBUTE) == null
            && request.getHeader("X-Forwarded-For") == null;
    }
}
