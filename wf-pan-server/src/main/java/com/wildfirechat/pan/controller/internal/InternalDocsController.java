package com.wildfirechat.pan.controller.internal;

import com.wildfirechat.pan.service.ObjectStoreService;
import com.wildfirechat.pan.service.docs.DocsService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import com.wildfirechat.pan.config.ProxiedRequestValve;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.io.InputStream;
import java.util.Map;

/**
 * 只给 ONLYOFFICE 在容器网络内调用的接口（NG 不转发 /internal/）。一律校验 ONLYOFFICE 签的 JWT。
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

    @Value("${server.port}")
    private int clientPort;

    /** ONLYOFFICE 取文件（打开、转换时） */
    @GetMapping("/file/{fileId}")
    public void file(@PathVariable Long fileId,
                     @RequestParam(value = "v", required = false) Integer versionNo,
                     @RequestHeader(value = "Authorization", required = false) String authorization,
                     HttpServletRequest request, HttpServletResponse response) throws Exception {
        if (!fromInside(request)) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        Map<String, Object> payload = docsService.verifyInbound(authorization, null);
        if (payload == null) {
            log.warn("ONLYOFFICE 取文件 JWT 校验失败 file={}", fileId);
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        // 令牌里带了地址时，必须就是这个文件（防止拿别的文件的令牌来取）
        Object url = payload.get("url");
        if (url instanceof String u && !u.contains("/internal/docs/file/" + fileId + "?") && !u.endsWith("/internal/docs/file/" + fileId)) {
            log.warn("ONLYOFFICE 取文件令牌与文件不符 file={} tokenUrl={}", fileId, u);
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
