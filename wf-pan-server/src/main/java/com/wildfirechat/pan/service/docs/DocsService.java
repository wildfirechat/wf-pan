package com.wildfirechat.pan.service.docs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wildfirechat.pan.config.DocsConfig;
import com.wildfirechat.pan.constant.FilePermission;
import com.wildfirechat.pan.constant.FileType;
import com.wildfirechat.pan.constant.SpaceType;
import com.wildfirechat.pan.constant.VersionSource;
import com.wildfirechat.pan.dto.request.CreateDocRequest;
import com.wildfirechat.pan.dto.request.CreateFileRequest;
import com.wildfirechat.pan.dto.request.CreateFolderRequest;
import com.wildfirechat.pan.dto.vo.FileVO;
import com.wildfirechat.pan.dto.vo.RecentDocVO;
import com.wildfirechat.pan.entity.PanFile;
import com.wildfirechat.pan.entity.PanFileVersion;
import com.wildfirechat.pan.entity.PanRecent;
import com.wildfirechat.pan.entity.PanSpace;
import com.wildfirechat.pan.exception.BusinessException;
import com.wildfirechat.pan.repository.PanFileRepository;
import com.wildfirechat.pan.repository.PanFileVersionRepository;
import com.wildfirechat.pan.repository.PanRecentRepository;
import com.wildfirechat.pan.service.FileService;
import com.wildfirechat.pan.service.FileVersionService;
import com.wildfirechat.pan.service.IMUserService;
import com.wildfirechat.pan.service.ObjectStoreService;
import com.wildfirechat.pan.service.OperationLogService;
import com.wildfirechat.pan.service.PermissionService;
import com.wildfirechat.pan.service.SignService;
import com.wildfirechat.pan.service.StorageService;
import com.wildfirechat.pan.service.StorageService.StoredObject;
import com.wildfirechat.pan.service.UserSpaceInitService;
import com.wildfirechat.pan.util.Hs256Jwt;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 在线文档（ONLYOFFICE Docs）对接：签编辑器配置、处理保存回调、旧格式转换、新建文档、最近打开。
 * <p>
 * 地址约定：浏览器从同源的 {docs.server_public_path} 加载编辑器；ONLYOFFICE 回连本服务走容器内网
 * {docs.callback_base_url}/internal/docs/...（NG 不转发 /internal/）；本服务调 ONLYOFFICE 走 {docs.server_internal_url}。
 */
@Service
@Slf4j
public class DocsService {

    public static final Set<String> WORD = Set.of("doc", "docx", "docm", "dot", "dotx", "dotm", "odt", "ott", "rtf", "txt",
        "wps", "wpt", "fodt", "mht", "mhtml", "htm", "html", "epub", "fb2");
    public static final Set<String> CELL = Set.of("xls", "xlsx", "xlsm", "xlt", "xltx", "xltm", "xlsb", "ods", "ots", "csv",
        "et", "ett", "fods");
    public static final Set<String> SLIDE = Set.of("ppt", "pptx", "pptm", "pot", "potx", "potm", "pps", "ppsx", "ppsm",
        "odp", "otp", "dps", "dpt", "fodp");
    public static final Set<String> PDF = Set.of("pdf", "djvu", "xps", "oxps");
    /** 可直接在线编辑并原格式保存的格式（OOXML） */
    public static final Set<String> EDITABLE = Set.of("docx", "docm", "xlsx", "xlsm", "pptx", "pptm");
    /** 只读打开、提供「转换后编辑」的旧格式 / 其他办公格式 */
    public static final Set<String> CONVERTIBLE = Set.of("doc", "dot", "rtf", "odt", "ott", "wps", "wpt",
        "xls", "xlt", "ods", "ots", "et", "ett", "ppt", "pot", "pps", "odp", "otp", "dps", "dpt");

    private static final String MY_DOCS_FOLDER = "我的文档";
    /** 取文件地址的有效期：ONLYOFFICE 在打开、转换时立即下载，之后用自己的缓存 */
    private static final long EDITOR_FILE_URL_TTL_SECONDS = 24 * 3600;
    private static final Map<String, String> TEMPLATE_MIME = Map.of(
        "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation");
    private static final Map<String, String> DEFAULT_NAME = Map.of(
        "docx", "未命名文档", "xlsx", "未命名表格", "pptx", "未命名演示");

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();

    @Autowired
    private DocsConfig docsConfig;

    @Autowired
    private PermissionService permissionService;

    @Autowired
    private FileService fileService;

    @Autowired
    private FileVersionService fileVersionService;

    @Autowired
    private ObjectStoreService objectStoreService;

    @Autowired
    private StorageService storageService;

    @Autowired
    private SignService signService;

    @Autowired
    private IMUserService imUserService;

    @Autowired
    private UserSpaceInitService userSpaceInitService;

    @Autowired
    private OperationLogService operationLogService;

    @Autowired
    private PanFileRepository fileRepository;

    @Autowired
    private PanFileVersionRepository versionRepository;

    @Autowired
    private PanRecentRepository recentRepository;

    // ------------------------------------------------------------------ 编辑器配置

    /**
     * 打开文档：校验权限、决定编辑/只读、签配置，并记入「最近打开」
     *
     * @param platform pc / mobile（手机端默认只读：社区版的手机网页端不能编辑，见 docs.mobile_edit）
     */
    public Map<String, Object> openEditor(Long fileId, String userId, String platform, boolean forceView) {
        requireEnabled();
        PanFile file = fileService.requireFile(fileId, userId, FilePermission.VIEW);
        String ext = ext(file.getName());
        String documentType = documentType(ext);
        if (documentType == null) {
            throw new BusinessException("该格式不支持在线打开");
        }
        FilePermission perm = permissionService.effectivePermission(userId, file);
        boolean mobile = "mobile".equalsIgnoreCase(platform);

        String viewReason = null;
        if (perm != FilePermission.EDIT) {
            viewReason = "permission";
        } else if (!EDITABLE.contains(ext)) {
            viewReason = CONVERTIBLE.contains(ext) ? "convertible" : "format";
        } else if (mobile && !docsConfig.isMobileEdit()) {
            viewReason = "mobile";
        } else if (forceView) {
            viewReason = "requested";
        }
        boolean canEdit = viewReason == null;

        String userName = imUserService.getUserDisplayName(userId);
        int versionNo = FileVersionService.currentVersionNo(file);
        String key = fileVersionService.ensureDocKey(file);

        Map<String, Object> permissions = new LinkedHashMap<>();
        permissions.put("edit", canEdit);
        permissions.put("comment", canEdit);
        permissions.put("review", canEdit);
        permissions.put("fillForms", canEdit);
        permissions.put("modifyFilter", canEdit);
        permissions.put("modifyContentControl", canEdit);
        permissions.put("download", true);
        permissions.put("print", true);
        permissions.put("copy", true);
        permissions.put("chat", canEdit && !docsConfig.isHideChat());

        Map<String, Object> document = new LinkedHashMap<>();
        document.put("fileType", ext);
        document.put("key", key);
        document.put("title", file.getName());
        document.put("url", internalFileUrl(file.getId(), versionNo));
        document.put("permissions", permissions);

        Map<String, Object> user = new LinkedHashMap<>();
        user.put("id", userId);
        user.put("name", userName);

        Map<String, Object> customization = new LinkedHashMap<>();
        customization.put("autosave", true);
        customization.put("forcesave", false);
        customization.put("feedback", false);
        customization.put("help", false);
        // ONLYOFFICE 社区版不认这两项（品牌定制要授权），其许可附加条款也要求保留标识与署名 ⇒ 默认不关；
        // 部署包用的 Euro-Office 放开了品牌定制，由部署配置打开（署名改放在文档首页的「开源许可」）
        if (docsConfig.isHideBranding()) {
            customization.put("logo", Map.of("visible", false));
            customization.put("about", false);
        }

        Map<String, Object> editorConfig = new LinkedHashMap<>();
        editorConfig.put("mode", canEdit ? "edit" : "view");
        editorConfig.put("lang", "zh-CN");
        editorConfig.put("region", "zh-CN");
        editorConfig.put("user", user);
        editorConfig.put("coEditing", Map.of("mode", "fast", "change", true));
        editorConfig.put("customization", customization);
        if (canEdit) {
            editorConfig.put("callbackUrl", internalBase() + "/internal/docs/callback?fileId=" + file.getId());
        }

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("type", mobile ? "mobile" : "desktop");
        config.put("documentType", documentType);
        config.put("document", document);
        config.put("editorConfig", editorConfig);
        config.put("width", "100%");
        config.put("height", "100%");
        config.put("token", Hs256Jwt.sign(config, docsConfig.getJwtSecret()));

        touchRecent(userId, file.getId());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("apiUrl", docsConfig.getServerPublicPath() + "/web-apps/apps/api/documents/api.js");
        result.put("config", config);
        result.put("fileId", file.getId());
        result.put("fileName", file.getName());
        result.put("versionNo", versionNo);
        result.put("permission", perm.name());
        result.put("canEdit", canEdit);
        result.put("viewReason", viewReason);
        result.put("canShare", permissionService.canShare(userId, file));
        result.put("convertible", CONVERTIBLE.contains(ext) && objectStoreService.isSupported());
        return result;
    }

    // ------------------------------------------------------------------ 保存回调

    /**
     * ONLYOFFICE 回调。只有保存成功才回 error 0；失败回非 0，ONLYOFFICE 会保留内容并重试。
     *
     * @param data 已校验过 JWT 的回调内容
     */
    public Map<String, Object> handleCallback(Long fileId, Map<String, Object> data) {
        int status = data.get("status") instanceof Number n ? n.intValue() : -1;
        String key = String.valueOf(data.get("key"));
        List<String> users = toStringList(data.get("users"));
        switch (status) {
            case 1 -> log.info("在线编辑 file={} key={} 在线: {}", fileId, key, users);
            case 2, 6 -> {
                String url = (String) data.get("url");
                if (url == null || url.isEmpty()) {
                    log.error("回调 status={} 缺少下载地址 file={} key={}", status, fileId, key);
                    return Map.of("error", 1);
                }
                try {
                    saveFromEditor(fileId, key, status, url, users.isEmpty() ? null : users.get(0));
                } catch (Exception e) {
                    log.error("保存在线编辑结果失败 file={} key={} status={}", fileId, key, status, e);
                    return Map.of("error", 1);
                }
            }
            case 3, 7 -> log.error("ONLYOFFICE 保存出错 file={} key={} status={} users={}", fileId, key, status, users);
            case 4 -> log.info("在线编辑结束、无改动 file={} key={}", fileId, key);
            default -> log.warn("未知回调 status={} file={} key={}", status, fileId, key);
        }
        return Map.of("error", 0);
    }

    private void saveFromEditor(Long fileId, String key, int status, String url, String editorId) throws Exception {
        PanFile file = fileRepository.findByIdAndIsDeletedFalse(fileId).orElse(null);
        if (file == null) {
            // 文件在编辑途中被删了：内容无处可存，回 0 让 ONLYOFFICE 结束会话
            log.warn("在线编辑保存时文件已删除 file={} key={}", fileId, key);
            return;
        }
        boolean sameSession = key.equals(file.getDocKey());
        if (!sameSession) {
            // 例如编辑途中有人恢复了历史版本（key 已换）：这份编辑仍然保存为新版本，不丢内容
            log.warn("回调 key 与当前会话 key 不一致，仍保存为新版本 file={} callbackKey={} currentKey={}",
                fileId, key, file.getDocKey());
        }
        Path tmp = download(toInternal(url));
        try {
            String md5 = md5(tmp);
            long size = Files.size(tmp);
            if (md5.equalsIgnoreCase(file.getMd5() == null ? "" : file.getMd5())) {
                // 内容与当前版本相同（重试的回调、或只开没改）：不另记版本
                log.info("在线编辑结果与当前版本相同，不记新版本 file={} status={}", fileId, status);
                if (status == 2 && sameSession) {
                    fileVersionService.rotateDocKey(fileId, key);
                }
                return;
            }
            StoredObject stored = objectStoreService.put("f" + fileId, file.getName(), tmp, file.getMimeType());
            String editorName = editorId != null ? imUserService.getUserDisplayName(editorId) : null;
            PanFileVersion v = fileVersionService.addVersion(fileId, stored, size, md5, VersionSource.EDIT,
                editorId, editorName, status == 2 && sameSession);
            if (v == null) {
                storageService.deleteObject(stored.key());
                log.warn("在线编辑保存时文件已删除 file={}", fileId);
                return;
            }
            log.info("在线编辑已保存 file={} v{} status={} size={} editor={}", fileId, v.getVersionNo(), status, size, editorId);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    // ------------------------------------------------------------------ 新建、转换

    /** 用空白模板新建 docx / xlsx / pptx；不指定位置时放在个人私有空间的「我的文档」 */
    public FileVO create(CreateDocRequest request, String userId) {
        requireEnabled();
        requireStorage();
        String type = request.getType().toLowerCase().replace(".", "");
        if (!TEMPLATE_MIME.containsKey(type)) {
            throw new BusinessException("只支持新建 docx / xlsx / pptx");
        }
        Long spaceId = request.getSpaceId();
        Long parentId = request.getParentId() != null && request.getParentId() > 0 ? request.getParentId() : null;
        if (spaceId == null) {
            spaceId = privateSpaceId(userId);
            parentId = myDocsFolderId(spaceId, userId);
        } else if (!permissionService.canManageSpace(userId, spaceId)) {
            throw new BusinessException("无权限在该空间新建文档");
        }
        String base = request.getName() != null && !request.getName().isBlank()
            ? sanitizeName(request.getName().trim()) : DEFAULT_NAME.get(type);
        if (base.toLowerCase().endsWith("." + type)) {
            base = base.substring(0, base.length() - type.length() - 1);
        }
        String name = uniqueName(spaceId, parentId, base + "." + type);

        Path tmp = null;
        try (InputStream in = new ClassPathResource("doc-templates/new." + type).getInputStream()) {
            tmp = Files.createTempFile("pan-new-", "." + type);
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            FileVO vo = createRecord(spaceId, parentId, name, tmp, TEMPLATE_MIME.get(type), userId);
            Map<String, Object> details = new HashMap<>();
            details.put("fileName", name);
            details.put("type", type);
            operationLogService.log(userId, OperationLogService.OP_CREATE_DOC,
                OperationLogService.TARGET_FILE, vo.getId(), spaceId, details);
            return vo;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("新建文档失败: " + e.getMessage(), e);
        } finally {
            deleteQuietly(tmp);
        }
    }

    /**
     * 旧格式转 OOXML，另存为新文件：能管理原文件所在空间时放在同目录，否则放进自己的「我的文档」
     */
    public FileVO convert(Long fileId, String userId) {
        requireEnabled();
        requireStorage();
        PanFile file = fileService.requireFile(fileId, userId, FilePermission.VIEW);
        String ext = ext(file.getName());
        String docType = documentType(ext);
        if (!CONVERTIBLE.contains(ext) || docType == null) {
            throw new BusinessException("该格式无需转换");
        }
        String target = switch (docType) {
            case "word" -> "docx";
            case "cell" -> "xlsx";
            default -> "pptx";
        };
        Long spaceId = file.getSpaceId();
        Long parentId = file.getParentId();
        if (!permissionService.canManageSpace(userId, spaceId)) {
            spaceId = privateSpaceId(userId);
            parentId = myDocsFolderId(spaceId, userId);
        }
        String base = file.getName().substring(0, file.getName().length() - ext.length() - 1);
        String name = uniqueName(spaceId, parentId, base + "." + target);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("async", false);
        body.put("filetype", ext);
        body.put("outputtype", target);
        body.put("key", "c" + fileVersionService.ensureDocKey(file) + "-" + target);
        body.put("title", file.getName());
        body.put("url", internalFileUrl(file.getId(), FileVersionService.currentVersionNo(file)));
        body.put("token", Hs256Jwt.sign(body, docsConfig.getJwtSecret()));

        Path tmp = null;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(docsConfig.getServerInternalUrl() + "/converter"))
                .timeout(Duration.ofMinutes(3))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(body)))
                .build();
            HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            @SuppressWarnings("unchecked")
            Map<String, Object> res = mapper.readValue(resp.body(), Map.class);
            if (!Boolean.TRUE.equals(res.get("endConvert")) || res.get("fileUrl") == null) {
                log.error("转换失败 file={} http={} result={}", fileId, resp.statusCode(), res);
                throw new BusinessException("转换失败" + (res.get("error") != null ? "（错误码 " + res.get("error") + "）" : ""));
            }
            tmp = download(toInternal((String) res.get("fileUrl")));
            FileVO vo = createRecord(spaceId, parentId, name, tmp, TEMPLATE_MIME.get(target), userId);
            Map<String, Object> details = new HashMap<>();
            details.put("sourceFileId", fileId);
            details.put("sourceName", file.getName());
            details.put("fileName", name);
            operationLogService.log(userId, OperationLogService.OP_CONVERT_DOC,
                OperationLogService.TARGET_FILE, vo.getId(), spaceId, details);
            return vo;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("转换失败: " + e.getMessage(), e);
        } finally {
            deleteQuietly(tmp);
        }
    }

    // ------------------------------------------------------------------ 最近打开

    public List<RecentDocVO> recent(String userId, int limit) {
        List<PanRecent> rows = recentRepository.findByUserIdOrderByOpenedAtDesc(userId, PageRequest.of(0, Math.min(limit, 100)));
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, PanFile> files = fileRepository.findByIdInAndIsDeletedFalse(
                rows.stream().map(PanRecent::getFileId).collect(Collectors.toList()))
            .stream().collect(Collectors.toMap(PanFile::getId, f -> f));
        List<RecentDocVO> result = new ArrayList<>();
        for (PanRecent r : rows) {
            PanFile f = files.get(r.getFileId());
            if (f == null) continue;
            // 分享被取消、退群后不再列出
            FilePermission perm = permissionService.effectivePermission(userId, f);
            if (perm == FilePermission.NONE) continue;
            result.add(RecentDocVO.builder().file(fileService.toVO(f)).permission(perm).openedAt(r.getOpenedAt()).build());
        }
        return result;
    }

    private void touchRecent(String userId, Long fileId) {
        try {
            PanRecent r = recentRepository.findByUserIdAndFileId(userId, fileId).orElseGet(() -> {
                PanRecent n = new PanRecent();
                n.setUserId(userId);
                n.setFileId(fileId);
                return n;
            });
            r.setOpenedAt(LocalDateTime.now());
            recentRepository.save(r);
        } catch (Exception e) {
            // 并发下唯一键冲突等，不影响打开
            log.debug("记录最近打开失败 user={} file={}: {}", userId, fileId, e.getMessage());
        }
    }

    // ------------------------------------------------------------------ 供内部接口使用

    /** ONLYOFFICE 取文件：返回该版本的存储地址 */
    public String storageUrlForEditor(Long fileId, Integer versionNo) {
        PanFile file = fileRepository.findByIdAndIsDeletedFalse(fileId)
            .orElseThrow(() -> new BusinessException("文件不存在"));
        if (versionNo == null || versionNo == FileVersionService.currentVersionNo(file)) {
            return file.getStorageUrl();
        }
        return versionRepository.findByFileIdAndVersionNo(fileId, versionNo)
            .map(PanFileVersion::getStorageUrl)
            .orElseThrow(() -> new BusinessException("版本不存在"));
    }

    /**
     * 校验 ONLYOFFICE 发来的 JWT：头里 Bearer（内容包在 payload 里）或请求体里的 token，二者都认
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> verifyInbound(String authorization, Map<String, Object> body) {
        if (authorization != null && authorization.startsWith("Bearer ")) {
            Map<String, Object> p = Hs256Jwt.verify(authorization.substring(7).trim(), docsConfig.getJwtSecret());
            if (p != null) {
                Object inner = p.get("payload");
                return inner instanceof Map ? (Map<String, Object>) inner : p;
            }
        }
        if (body != null && body.get("token") instanceof String t) {
            return Hs256Jwt.verify(t, docsConfig.getJwtSecret());
        }
        return null;
    }

    // ------------------------------------------------------------------ 工具

    public static String ext(String name) {
        if (name == null) return "";
        int i = name.lastIndexOf('.');
        return i < 0 ? "" : name.substring(i + 1).toLowerCase();
    }

    public static String documentType(String ext) {
        if (WORD.contains(ext)) return "word";
        if (CELL.contains(ext)) return "cell";
        if (SLIDE.contains(ext)) return "slide";
        if (PDF.contains(ext)) return "pdf";
        return null;
    }

    private String internalBase() {
        String b = docsConfig.getCallbackBaseUrl();
        return b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
    }

    /**
     * ONLYOFFICE 取文件的地址，带本服务的签名（绑定文件、版本、有效期）
     */
    private String internalFileUrl(Long fileId, int versionNo) {
        long expire = System.currentTimeMillis() / 1000 + EDITOR_FILE_URL_TTL_SECONDS;
        return internalBase() + "/internal/docs/file/" + fileId + "?v=" + versionNo
            + "&e=" + expire + "&s=" + signService.signEditorFile(fileId, versionNo, expire);
    }

    /**
     * 回调/转换结果里的下载地址按浏览器入口拼（https://<IM_HOST>/docs/cache/...），
     * 服务端不绕回对外入口（还要信任自签 CA），改成直连 ONLYOFFICE 内网地址。只取路径，主机一律换掉（防 SSRF）。
     */
    String toInternal(String url) {
        URI u = URI.create(url);
        String path = u.getRawPath();
        String pub = docsConfig.getServerPublicPath();
        if (pub != null && !pub.isEmpty() && path.startsWith(pub + "/")) {
            path = path.substring(pub.length());
        }
        if (!path.startsWith("/cache/")) {
            throw new BusinessException("不是 ONLYOFFICE 的结果地址: " + path);
        }
        return docsConfig.getServerInternalUrl() + path + (u.getRawQuery() != null ? "?" + u.getRawQuery() : "");
    }

    private Path download(String url) throws Exception {
        Path tmp = Files.createTempFile("pan-docs-", ".bin");
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(5)).GET().build();
        HttpResponse<Path> resp = http.send(req, HttpResponse.BodyHandlers.ofFile(tmp));
        if (resp.statusCode() != 200) {
            Files.deleteIfExists(tmp);
            throw new BusinessException("从 ONLYOFFICE 取结果失败: HTTP " + resp.statusCode());
        }
        return tmp;
    }

    private FileVO createRecord(Long spaceId, Long parentId, String name, Path content, String mime, String userId) throws Exception {
        StoredObject stored = objectStoreService.put("new", name, content, mime);
        try {
            CreateFileRequest req = new CreateFileRequest();
            req.setSpaceId(spaceId);
            req.setParentId(parentId);
            req.setName(name);
            req.setSize(Files.size(content));
            req.setMimeType(mime);
            req.setMd5(md5(content));
            req.setStorageUrl(stored.url());
            req.setCopy(false);
            return fileService.createFile(req, userId);
        } catch (Exception e) {
            storageService.deleteObject(stored.key());
            throw e;
        }
    }

    private Long privateSpaceId(String userId) {
        return userSpaceInitService.getOrInitUserSpaces(userId).stream()
            .filter(space -> space.getSpaceType() == SpaceType.USER_PRIVATE)
            .findFirst()
            .map(PanSpace::getId)
            .orElseThrow(() -> new BusinessException("个人空间不存在"));
    }

    private Long myDocsFolderId(Long spaceId, String userId) {
        for (PanFile f : fileRepository.findBySpaceIdAndParentIdAndIsDeletedFalseOrderByTypeDescNameAsc(spaceId, null)) {
            if (f.getType() == FileType.FOLDER && MY_DOCS_FOLDER.equals(f.getName())) {
                return f.getId();
            }
        }
        CreateFolderRequest req = new CreateFolderRequest();
        req.setSpaceId(spaceId);
        req.setParentId(null);
        req.setName(MY_DOCS_FOLDER);
        return fileService.createFolder(req, userId).getId();
    }

    private String uniqueName(Long spaceId, Long parentId, String name) {
        Set<String> names = fileRepository.findBySpaceIdAndParentIdAndIsDeletedFalseOrderByTypeDescNameAsc(spaceId, parentId).stream()
            .map(f -> f.getName().toLowerCase()).collect(Collectors.toSet());
        if (!names.contains(name.toLowerCase())) return name;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; ; i++) {
            String candidate = base + "(" + i + ")" + ext;
            if (!names.contains(candidate.toLowerCase())) return candidate;
        }
    }

    private static String sanitizeName(String name) {
        String s = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
        return s.length() > 200 ? s.substring(0, 200) : s;
    }

    private void requireEnabled() {
        if (!docsConfig.isEnabled() || docsConfig.getJwtSecret() == null || docsConfig.getJwtSecret().isEmpty()) {
            throw new BusinessException("在线文档未启用");
        }
    }

    private void requireStorage() {
        if (!objectStoreService.isSupported()) {
            throw new BusinessException("当前存储类型不支持在线文档");
        }
    }

    private static String md5(Path p) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        try (InputStream in = Files.newInputStream(p)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        return HexFormat.of().formatHex(md.digest());
    }

    private static void deleteQuietly(Path p) {
        if (p == null) return;
        try {
            Files.deleteIfExists(p);
        } catch (Exception ignore) {
            // 临时文件删不掉不影响业务
        }
    }

    private static List<String> toStringList(Object o) {
        if (!(o instanceof List<?> l)) return List.of();
        return l.stream().map(String::valueOf).collect(Collectors.toList());
    }
}
