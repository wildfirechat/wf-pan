package com.wildfirechat.pan.controller.web;

import com.wildfirechat.pan.entity.PanFile;
import com.wildfirechat.pan.entity.PanFileVersion;
import com.wildfirechat.pan.repository.PanFileRepository;
import com.wildfirechat.pan.repository.PanFileVersionRepository;
import com.wildfirechat.pan.service.FileVersionService;
import com.wildfirechat.pan.service.ObjectStoreService;
import com.wildfirechat.pan.service.SignService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ContentDisposition;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 签名下载：GET /dl/{fileId}?v=&u=&e=&s=（对外为 /pan/dl/...，不需要 authCode，凭签名，默认 10 分钟有效）
 */
@RestController
@Slf4j
public class DownloadController {

    @Autowired
    private SignService signService;

    @Autowired
    private PanFileRepository fileRepository;

    @Autowired
    private PanFileVersionRepository versionRepository;

    @Autowired
    private ObjectStoreService objectStoreService;

    @GetMapping("/dl/{fileId}")
    public void download(@PathVariable Long fileId,
                         // 缺参数也按签名无效回 403，别落到全局异常处理变成 200 + 系统错误
                         @RequestParam(value = "v", defaultValue = "0") int versionNo,
                         @RequestParam(value = "u", defaultValue = "") String userId,
                         @RequestParam(value = "e", defaultValue = "0") long expire,
                         @RequestParam(value = "s", required = false) String sig,
                         @RequestParam(value = "inline", required = false) String inline,
                         @RequestHeader(value = "Range", required = false) String range,
                         HttpServletResponse response) throws Exception {
        if (!signService.verifyDownload(fileId, versionNo, userId, expire, sig)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "链接无效或已过期");
            return;
        }
        PanFile file = fileRepository.findByIdAndIsDeletedFalse(fileId).orElse(null);
        if (file == null) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "文件不存在");
            return;
        }
        String url = file.getStorageUrl();
        long size = file.getSize() != null ? file.getSize() : -1;
        if (versionNo != FileVersionService.currentVersionNo(file)) {
            PanFileVersion v = versionRepository.findByFileIdAndVersionNo(fileId, versionNo).orElse(null);
            if (v == null) {
                response.sendError(HttpServletResponse.SC_NOT_FOUND, "版本不存在或已被清理");
                return;
            }
            url = v.getStorageUrl();
            size = v.getSize() != null ? v.getSize() : -1;
        }
        if (size < 0) {
            size = objectStoreService.size(url);
        }

        long start = 0;
        long end = size - 1;
        boolean partial = false;
        if (range != null && range.startsWith("bytes=") && !range.contains(",") && size > 0) {
            String[] p = range.substring(6).split("-", 2);
            try {
                if (p[0].isEmpty()) {
                    start = Math.max(0, size - Long.parseLong(p[1]));
                } else {
                    start = Long.parseLong(p[0]);
                    if (p.length > 1 && !p[1].isEmpty()) end = Math.min(end, Long.parseLong(p[1]));
                }
                if (start > end) {
                    response.setHeader("Content-Range", "bytes */" + size);
                    response.sendError(HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE);
                    return;
                }
                partial = true;
            } catch (NumberFormatException ignore) {
                start = 0;
                end = size - 1;
            }
        }

        response.setContentType(file.getMimeType() != null && !file.getMimeType().isEmpty()
            ? file.getMimeType() : "application/octet-stream");
        response.setHeader("Content-Disposition", ContentDisposition.builder(inline != null ? "inline" : "attachment")
            .filename(file.getName(), StandardCharsets.UTF_8).build().toString());
        response.setHeader("Accept-Ranges", "bytes");
        response.setHeader("Cache-Control", "private, no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        long length = Math.max(0, end - start + 1);
        response.setContentLengthLong(length);
        if (partial) {
            response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
            response.setHeader("Content-Range", "bytes " + start + "-" + end + "/" + size);
        }
        if (length == 0) {
            return;
        }
        try (InputStream in = objectStoreService.open(url, partial ? start : null, partial ? length : null)) {
            OutputStream out = response.getOutputStream();
            in.transferTo(out);
        } catch (Exception e) {
            // 客户端中途断开很常见，不当错误刷屏
            log.debug("下载中断 file={} : {}", fileId, e.getMessage());
        }
    }
}
