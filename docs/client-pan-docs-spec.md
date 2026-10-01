# Client "云盘 (Pan)" + "在线文档 (Online Docs / ONLYOFFICE)" Porting Specification

Implementation-ready spec for re-implementing the Flutter client's pan / online-docs
features on **Vue 3 Web, Vue 3 + Electron PC, Android Java, iOS ObjC, uni-app, HarmonyOS ArkTS**.

- **Client source (reference implementation):**
  `/Users/rain/Workspace/wf-enterprise-chat` @ branch `pan-docs`.
  All `chat/lib/...` citations are relative to that repo.
- **Server source (contract confirmation):**
  `/Users/rain/Workspace/wf-app-server` (module `app-pan`).
  Server citations are relative to that repo.
- The pan backend is the `app-pan` module of **wf-app-server** (the "合并服务 / app service").
  Online docs is ONLYOFFICE, served by the same service under `/doc/**`.

> **Path convention used below.** Server endpoints are given as `/api/pan/<sub>`
> (the pan module base) **and** relative to the app-service root `R` (e.g. `R/api/pan/files`).
> The doc pages are `R/doc/...`. All Flutter pan HTTP calls are **POST + JSON + `authToken` header**.

> ⚠️ **IMPORTANT — which server the six target clients talk to.**
> This spec was derived from the **merged** service (`wf-app-server/app-pan`: `/api/pan/**`, authCode→authToken, envelope `result`).
> The six target clients (`vue-chat`, `vue-pc-chat`, `android-chat`, `ios-chat`, `uni-chat-x`, `hm-chat`) belong to the **standalone `wf-pan-server`** (`/Users/rain/Workspace/wf-pan/wf-pan-server`). The two differ only in four mechanical ways — everything else (sub-paths, request/response fields, models, UI/UX, bridge methods) is identical:
>
> | | merged (this spec) | standalone (target) |
> |---|---|---|
> | API base | `R/api/pan` | `{PAN_SERVER}/api/v1` |
> | auth | authCode → `/api/auth/login` → `authToken` header | per-request `authCode` header |
> | envelope | `{code,message,result}` | `{code,message,data}` |
> | doc pages | `R/doc/...` | `{PAN_SERVER}/doc/...` |
>
> Read `docs/doc-web-bridge.md` for the doc-page bridge (native dsbridge **and** web postMessage transport).

---

## 1. Server API contract

### 1.1 Base URLs and how they are derived

| Value | Derivation | Client citation |
|---|---|---|
| App-service root `R` | `R = scheme://MAIN_HOST[:APP_PORT]` (`https` if `IM_USE_TLS`, port omitted for 443/80) | `chat/lib/config.dart:108-124`, `chat/lib/config.dart:170` |
| Pan API base | `R + "/api/pan"` = `Config.PAN_SERVER_ADDRESS` | `chat/lib/config.dart:289`, `chat/lib/config.dart:417` |
| Pan backup base | `R_backup + "/api/pan"` = `Config.PAN_SERVER_BACKUP_ADDRESS` (null if no backup host) | `chat/lib/config.dart:291-294` |
| Selected pan base | `selectServer(PAN_SERVER_ADDRESS, PAN_SERVER_BACKUP_ADDRESS)`; main/backup chosen from `DualNetwork.isMainNetwork` | `chat/lib/config.dart:395-406`, `chat/lib/config.dart:417-418` |
| Doc page base | `Config.appServiceAddress` + `/doc/` (trailing slash normalized) | `chat/lib/pan/pan_service.dart:42-48` |

The pan service address may additionally be rewritten for dual-network media hosts by
`MediaUrlRedirector.redirect()` before use (`chat/lib/pan/pan_service.dart:100-106`,
`chat/lib/utils/media_url_redirector.dart:16-38`).

### 1.2 Authentication — authCode → authToken

There is **one** login entry for the entire app service; all pan business endpoints use the
resulting `authToken`.

1. Client obtains an IM auth code via the IM SDK: `Imclient.getAuthCode(appId, type, host, ok, err)`
   (`imclient/lib/imclient.dart:2079-2086`). Pan uses `appId = "admin"`, `type = 2`
   (`chat/lib/pan/pan_service.dart:26-27`), and `host` = `host[:port]` of the app-service root
   (`chat/lib/utils/app_server_auth.dart:146-147`).
2. Exchange it:

   ```
   POST  {R}/api/auth/login
   Body: {"authCode": "<code>"}                       Content-Type: application/json
   Success: HTTP 200, response headers include  authToken: <token>
            Body: {"code":0,"message":"success","result":{"userId":"..."}}
   Failure: HTTP 200 with body {"code":<err>,"message":"..."} (e.g. 1001 auth code error)
   ```

   Citation: `chat/lib/utils/app_server_auth.dart:26-27,78-111`; server
   `app-common/.../security/LoginController.java:51-117`.
3. Token storage keyed by **host-port** (`authToken:<host>-<port>`), shared by the whole app
   service; main/backup addresses of the same service are linked so switching networks does not
   re-login (`chat/lib/utils/auth_token_http.dart:104-147`).
4. Every subsequent request:

   ```
   POST  {pan base}{sub path}
   Headers: Content-Type: application/json
            authToken: <token>
   ```

   `AuthCodeApiClient` does this and refreshes the token automatically on 401/403 or body
   `code == 13` and retries once (`chat/lib/utils/auth_code_api_client.dart:53-134`,
   `chat/lib/utils/auth_code_api_client.dart:79-101`).

Server side, business endpoints are protected by the Shiro `currentUser` filter, which writes
the IM user id into request attribute `userId`; on failure it returns **HTTP 200** with
`{"code":13,"message":"没有登录"}` (`app-common/.../security/CurrentUserFilter.java:36-82`).

### 1.3 Response envelope and error handling

All endpoints return `{"code":int,"message":string,"result":<T>}` (result omitted/null on error).
`code == 0` is success; `code != 0` is a business error whose `message` must be shown to the user.
(`app-common/.../web/Result.java`; client checks `code != 0` and throws
`PanException(code, message)` at `chat/lib/utils/auth_code_api_client.dart:66-75`.)

Local/network errors use non-positive codes and their messages are *not* shown; only server
messages (`code > 0`) are appended to the localized prefix (`chat/lib/pan/pan_widgets.dart:68-73`).
Important server codes: `13` not logged in (retryable), `16` space not found.

### 1.4 Endpoints called by the Flutter client

All are **POST**, JSON body, `authToken` header, relative to pan base `{R}/api/pan`.
"req" = request fields, "res" = `result`.

| # | Method + path (pan base) | Full path | Request body | Result |
|---|---|---|---|---|
| 1 | `POST /spaces/list` | `R/api/pan/spaces/list` | `{}` | `SpaceVO[]` |
| 2 | `POST /spaces/my` | `R/api/pan/spaces/my` | `{}` | `SpaceVO[]` (own public + private, `canManage=true`) |
| 3 | `POST /spaces/user/public` | `R/api/pan/spaces/user/public` | `{targetUserId}` | `SpaceVO` |
| 4 | `POST /spaces/files` | `R/api/pan/spaces/files` | `{spaceId, parentId}` (`parentId=0` root) | `FileVO[]` |
| 5 | `POST /files/folder` | `R/api/pan/files/folder` | `{spaceId, parentId?, name}` | `FileVO` |
| 6 | `POST /files` | `R/api/pan/files` | `{spaceId, name, size, storageUrl, copy, parentId?, mimeType?, md5?}` | `FileVO` |
| 7 | `POST /files/delete` | `R/api/pan/files/delete` | `{fileId}` | `null` |
| 8 | `POST /files/rename` | `R/api/pan/files/rename` | `{fileId, newName}` | `FileVO` |
| 9 | `POST /files/move` | `R/api/pan/files/move` | `{fileId, targetSpaceId, targetParentId}` | `FileVO` |
| 10 | `POST /files/copy` | `R/api/pan/files/copy` | `{fileId, targetSpaceId, targetParentId}` | `FileVO` |
| 11 | `POST /files/url` | `R/api/pan/files/url` | `{fileId}` (H5 also `{fileId, versionNo}`) | `FileUrlResponse` |
| 12 | `POST /files/check-permission` | `R/api/pan/files/check-permission` | `{spaceId}` | `bool` |
| 13 | `POST /shares/list` | `R/api/pan/shares/list` | `{fileId}` | `ShareVO[]` |
| 14 | `POST /shares/add` | `R/api/pan/shares/add` | `{fileId, targetType:"USER"|"GROUP", targetId, permission:"VIEW"|"EDIT"}` | `ShareVO` |
| 15 | `POST /shares/remove` | `R/api/pan/shares/remove` | `{shareId}` | `null` |
| 16 | `POST /shares/with-me` | `R/api/pan/shares/with-me` | `{}` | `SharedFileVO[]` |
| 17 | `POST /docs/recent` | `R/api/pan/docs/recent` | `{}` | `RecentDocVO[]` (server caps at 50) |
| 18 | `POST /docs/recent/remove` | `R/api/pan/docs/recent/remove` | `{fileId}` | `null` |
| 19 | `POST /docs/create` | `R/api/pan/docs/create` | `{type:"docx"|"xlsx"|"pptx", name?}` | `FileVO` |
| 20 | `POST /docs/options` | `R/api/pan/docs/options` | `{}` | `{"mobileEdit":bool}` |
| 21 | `POST /docs/editor-config` | `R/api/pan/docs/editor-config` | `{fileId, platform:"pc"|"mobile", view?:bool}` | map (see §5.4) — **H5 `open.html` only** |
| 22 | `POST /docs/view-url` | `R/api/pan/docs/view-url` | `{url, name?, platform}` | map (read-only config) — **H5 only** |
| 23 | `POST /docs/convert` | `R/api/pan/docs/convert` | `{fileId}` | `FileVO` (new converted file) — **H5 only** |
| 24 | `POST /versions/list` | `R/api/pan/versions/list` | `{fileId}` | `FileVersionVO[]` — **H5 only** |
| 25 | `POST /versions/restore` | `R/api/pan/versions/restore` | `{fileId, versionNo}` | `FileVersionVO` — **H5 only** |
| 26 | `GET /permission/space/{spaceId}/write` | `R/api/pan/permission/space/{id}/write` | path var | `bool` — server-only, **not used by the Flutter client** |

Flutter call sites: endpoints 1–12 in `chat/lib/pan/pan_service.dart:124-719`;
shares 13–16 at `chat/lib/pan/pan_service.dart:597-649`; docs 17–20 at
`chat/lib/pan/pan_service.dart:654-710`. Server controllers:
`app-pan/.../client/ClientSpaceController.java:44-82`,
`ClientFileController.java:45-108`, `ClientShareController.java:28-72`,
`ClientDocsController.java:34-115`, `ClientVersionController.java:32-55`,
`ClientPermissionController.java:23-29`.

Endpoint request/response field contracts confirmed from server DTOs:
`CreateFolderRequest`, `CreateFileRequest`, `GetSpaceFilesRequest`, `GetFileUrlRequest`,
`MoveRequest`, `CopyRequest`, `RenameRequest`, `DeleteFileRequest`, `SpaceIdRequest`,
`AddShareRequest`, `ShareIdRequest`, `FileIdRequest`, `CreateDocRequest`,
`EditorConfigRequest`, `ViewUrlRequest` (all under `app-pan/src/main/java/cn/wildfirechat/app/pan/dto/request/`).

Notable request semantics:
- `CreateFileRequest.copy` (default `false`): `false` = record only (client uploaded itself);
  `true` = server copies the physical object into the pan bucket (used by "存到网盘", §6).
- `GetFileUrlRequest.versionNo` optional; omit for current version.
- `MoveRequest/CopyRequest.targetParentId` required, `0` = root.
- `AddShareRequest.permission` defaults to `VIEW`.

### 1.5 Doc-page (`/doc/**`) routes and the web session

Server: `WebDocController` (`app-pan/.../controller/web/WebDocController.java`), assets mapped by
`WebMvcConfig` (`/doc/**` → `classpath:/doc-web/`, `app-pan/.../config/WebMvcConfig.java:19-24`).

| Route | Purpose |
|---|---|
| `GET /doc` | 302 redirect to `doc/` |
| `GET /doc/` | forwards to `/doc/index.html` (docs home) |
| `GET /doc/open` | forwards to `/doc/open.html` editor page; query `fileId=` **or** `url=`+`name=` |
| `GET /doc/licenses.html` | AGPL/open-source licenses page |
| `POST /doc/session` | body `{"authCode":"..."}` → `{"code":0,"result":{"userId","displayName","docsEnabled","authToken"}}`; also sets response header `authToken` |

Citation: `WebDocController.java:43-92`. The H5 page (`doc-web/app.js`) calls `getAuthCode` bridge,
then `POST {docBase}session` with the code, stores `result.authToken` in `sessionStorage`
(`app-pan/src/main/resources/doc-web/app.js:80-97`) and sends it as `authToken` header on all
`/api/pan/**` calls (`app.js:99-117`). On code `13/1001/1002/1004` it re-logs-in once.

### 1.6 Download URL signing

`POST /files/url` returns `FileUrlResponse{fileId,name,storageUrl,versionNo,permission}`.
`storageUrl` is a server-signed short-lived link (default TTL `pan.download.ttl_seconds = 600`s),
so it must **not** be persisted or forwarded as a message; it is fetched on demand
(`app-pan/.../service/DownloadService.java:36`; `app-pan/.../config/DocsConfig.java:54-56`).
`mediaType`/public-to-private distinction is why chat file messages are copied with `copy:true`
before being shared (`chat/lib/pan/pan_service.dart:223-254`).

---

## 2. Config / feature gating

| Concern | Rule | Citation |
|---|---|---|
| Pan configured? | `PanService.isAvailable == Config.panServerAddress != null && isNotEmpty`; in practice `PAN_SERVER_ADDRESS` is derived and always non-null, so an unconfigured deployment sets `PAN_SERVER_ADDRESS` to `null` (compile/config-time ability). | `chat/lib/pan/pan_service.dart:32-35` |
| Pan base getter | `Config.PAN_SERVER_ADDRESS => "$apiBase/pan"`, `apiBase = "$APP_SERVER_ADDRESS/api"` | `chat/lib/config.dart:184`, `chat/lib/config.dart:289` |
| Doc base | `Config.appServiceAddress` (main/backup selected) + `/doc/`; throws `PanException(-1,"应用服务地址未配置")` when app-service root empty | `chat/lib/pan/pan_service.dart:42-48` |
| Doc open URL | `R/doc/open?fileId=<id>` | `chat/lib/pan/pan_service.dart:51` |
| Doc read-only link URL | `R/doc/open?url=<enc>&name=<enc>` (name optional) | `chat/lib/pan/pan_service.dart:58-64` |
| Licenses URL | `R/doc/licenses.html` | `chat/lib/pan/pan_service.dart:54` |
| Is a doc URL? | Starts with `R/doc/` or `R_backup/doc/` | `chat/lib/pan/pan_service.dart:69-76` |
| Mobile "我" page entries | Rendered only when `(Config.panServerAddress ?? '').isNotEmpty`; docs entry additionally only when `isInlineWebViewSupported` | `chat/lib/settings/me_tab.dart:85-111` |
| PC sidebar entries | Pan tab shown iff `panServerAddress` non-empty; docs tab additionally iff `isInlineWebViewSupported` | `chat/lib/pc/pc_home.dart:983-1003` |
| Pan home failure state | `!isAvailable` → `panServiceNotConfigured` placeholder | `chat/lib/pan/pan_home_screen.dart:55-61,102-106` |

Notes for other clients:
- `isInlineWebViewSupported` is "the platform has a webview implementation"
  (`chat/lib/workspace/webview_support.dart:28`). On Windows/Linux it can be false, in which case
  the docs entry is hidden and doc opening degrades (see §7).
- `Config.APP_SERVER_BACKUP_ADDRESS` is `null` when no backup host; `isDocUrl` must accept both.

---

## 3. Data models

JSON shapes below are what the server actually emits; the Dart model shows tolerant parsing.

### 3.1 `PanSpace` (`SpaceVO`)

| Field | Type | JSON key | Notes |
|---|---|---|---|
| spaceId | int | `id` (fallback `spaceId`) | `SpaceVO.id` |
| spaceType | enum | `spaceType` | `GLOBAL_PUBLIC` / `USER_PUBLIC` / `USER_PRIVATE`; client also tolerates int index |
| ownerId | string | `ownerId` | `""` fallback |
| name | string | `name` | server sends display name ("我的公共空间"/"xxx的公共空间"/space name); UI **overrides** with localized name per `spaceType` |
| totalQuota | int | `totalQuota` | bytes |
| usedQuota | int | `usedQuota` | bytes |
| fileCount | int | `fileCount` | |
| folderCount | int | `folderCount` | |
| autoInit | bool | `autoInit` | |
| createdAt | string | `createdAt` | LocalDateTime serialized |
| canManage | bool | `canManage` | server-computed |
| (ignored) | | `updatedAt` | |

Derived helpers to port:
- `canWrite = (spaceType != USER_PUBLIC) || ownerId == currentUserId`
  (`chat/lib/pan/pan_service.dart:832-833`). Controls new-folder / upload buttons.
- Localized display name by type (`chat/lib/pan/pan_widgets.dart:46-56`).

Source: `chat/lib/pan/pan_service.dart:777-847`; `app-pan/.../dto/vo/SpaceVO.java`;
`app-pan/.../constant/SpaceType.java`.

### 3.2 `PanFile` (`FileVO`)

| Field | Type | JSON key | Notes |
|---|---|---|---|
| fileId | int | `id` (fallback `fileId`) | |
| spaceId | int | `spaceId` | |
| parentId | int | `parentId` | 0 = root |
| name | string | `name` | |
| type | enum | `type` | `"FILE"` / `"FOLDER"`; client also tolerates `1` = folder (legacy) |
| size | int | `size` | bytes |
| mimeType | string? | `mimeType` | |
| md5 | string? | `md5` | |
| storageUrl | string? | `storageUrl` | |
| childCount | int | `childCount` | for folders |
| creatorId | string | `creatorId` | |
| creatorName | string? | `creatorName` | |
| createdAt | string | `createdAt` | |
| updatedAt | string | `updatedAt` | |
| (ignored) | | `versionNo`, `creatorPortrait` | |

Derived helpers:
- `isFolder`, `sizeText` (`formatPanSize`), `extension` (lowercased, no dot)
- `canOpenOnline = !isFolder && PanService.isOnlineDocName(name)`
- `iconType` mapping to `PanIconType` (image/video/audio/pdf/word/excel/ppt/html/text/exe/xml/archive/file)

Source: `chat/lib/pan/pan_service.dart:849-983`; `app-pan/.../dto/vo/FileVO.java`;
`app-pan/.../constant/FileType.java`.

`formatPanSize(bytes)` (one decimal, 1024-based):
`>=1GiB "x.x GB"`, `>=1MiB "x.x MB"`, `>=1KiB "x.x KB"`, else `"n B"`
(`chat/lib/pan/pan_service.dart:727-734`).

### 3.3 Docs / shares

`PanDocEntry` (`chat/lib/pan/pan_service.dart:985-1004`):
`file:PanFile`, `canEdit:bool` (server `permission == "EDIT"`), `time:string`
(`openedAt` for recent, `sharedAt` for shared), `sources:List<PanShareSource>`.

`PanShare` (`chat/lib/pan/pan_service.dart:1006-1039`):
`id:int`, `isGroup:bool` (`targetType == "GROUP"`), `targetId:string`, `targetName:string`
(server fallback name), `canEdit:bool` (`permission == "EDIT"`), `sharerName:string`
(`createdByName`), `time:string` (`createdAt`).

`PanShareSource` (`chat/lib/pan/pan_service.dart:1041-1056`):
`isGroup:bool`, `groupName:string` (`targetName`), `sharerName:string` (`createdByName`).

`PanFileVersion` (`FileVersionVO`, used by H5 only):
`fileId`, `versionNo`, `size`, `source` (`UPLOAD`/`EDIT`/`RESTORE`/`CONVERT`), `editorId`,
`editorName`, `current:bool`, `createdAt`
(`app-pan/.../dto/vo/FileVersionVO.java`, `app-pan/.../constant/VersionSource.java`).

### 3.4 Enums / permission model

- `SpaceType`: `GLOBAL_PUBLIC`, `USER_PUBLIC`, `USER_PRIVATE`.
- `FileType`: `FILE`, `FOLDER`.
- `FilePermission`: `NONE < VIEW < EDIT` (ordinal order; effective permission = max of space rule,
  direct share, group share) (`app-pan/.../constant/FilePermission.java`).
- `ShareTargetType`: `USER`, `GROUP`.
- `PanUploadTask`: UI-only `{name, progress:ValueNotifier<double>, cancelToken}`.

---

## 4. UI / UX flows

Common helpers used by both platforms: `showPanFileMenu` (PC context menu / mobile bottom sheet),
`showPanNameDialog`, `PanStatusView`, `PanQuotaBar`, `PanSpaceIcon`, `PanFileIcon`, `PanBreadcrumb`,
`PanUploadStrip` (`chat/lib/pan/pan_widgets.dart`).

### 4.1 Mobile entry ("我" page)

- `me_tab.dart`, only when `!isDesktopStyle`. Order: 收藏 (favorites) → 文件 (file records) →
  **云盘** → **文档** (docs only when webview supported). Divider logic: the 文件 row's bottom
  divider is shown iff pan is configured; 云盘's divider shown iff webview supported.
  (`chat/lib/settings/me_tab.dart:61-111`.)
- labels: `l10n.cloudDrive` = **云盘**, `l10n.onlineDocs` = **文档**
  (`chat/lib/l10n/app_localizations_zh.dart:3373,3410`).
- 云盘 → `openPage(context, PanHomeScreen())`; 文档 → `PanDocsScreen()`
  (`me_tab.dart:97,108`).

### 4.2 Mobile pan home / file list

- `PanHomeScreen` mobile: AppBar title 云盘; a `ListView` of spaces styled as rows
  (44px `PanSpaceIcon`, name, `panFileCount` = "n个文件", quota bar, chevron); pull-to-refresh.
  (`chat/lib/pan/pan_home_screen.dart:207-234,246-296`.)
- Spaces shown: `getVisibleSpaces()` = global public + own spaces (ownerId == me), sorted
  private → public → global (`chat/lib/pan/pan_service.dart:134-147`).
- Tap space → `PanFileListScreen(space)` (`pan_home_screen.dart:236-243`).
- `PanFileListScreen` (mobile): one page per folder; AppBar title = folder name or space name;
  actions (only when `space.canWrite`): `Icons.create_new_folder_outlined` (新建文件夹) and
  `Icons.upload_rounded` (上传) (`chat/lib/pan/pan_file_list_screen.dart:44-60`).
  Body: loading spinner / `loadFailedRetry`+重试 / empty `PanStatusView` (这里还没有文件 +
  mobile hint) / `ListView.separated` (divider indent 70). Row: 40px icon, name, subtitle
  `"{n项 | size}  ·  creator  ·  time"`, `more_horiz` menu; long-press also opens the menu
  (`pan_file_list_screen.dart:76-124,126-182`).
- Folders sort before files, otherwise server order (`chat/lib/pan/pan_folder_state.dart:63-83`).
- Tap: folder → push next page; online doc + webview → open editor; else → download
  (`pan_folder_state.dart:94-102`).

### 4.3 PC entry (sidebar / full-pane tabs / keep-alive)

- Sidebar tabs (`chat/lib/pc/pc_home.dart:983-1003`): 云盘 icon `cloud_rounded`/`cloud_outlined`,
  tab id `tabPan = 7`; 文档 icon `description_rounded`/`description_outlined`, tab id `tabDocs = 8`
  (`chat/lib/pc/pc_shell_view_model.dart:16-17`).
- Gating identical to mobile: pan shown iff `panServerAddress` non-empty; docs only if webview
  supported.
- `tabPan`, `tabDocs` (with `tabWork`, `tabConference`) are **full-pane tabs**: no middle column;
  the right pane becomes the whole page (`chat/lib/pc/pc_home.dart:68-74`).
- **Keep-alive**: each full-pane tab gets a persistent `GlobalKey<NavigatorState>`
  (`_fullPaneNavKeys`) and is rendered inside a `Visibility(maintainState: true)` so switching
  away only hides it; state (open folders, docs tabs) survives (`pc_home.dart:76-79,625-654`,
  `pc_home.dart:262-273`). When returning, pan/docs screens refresh silently via
  `PanRefreshOnReshow` (`chat/lib/pan/pan_widgets.dart:24-42`; `pan_home_screen.dart:47-49`;
  `pan_docs_screen.dart:157-158`).
- `_fullPaneHome`: `tabPan → PanHomeScreen`, else `PanDocsScreen` (`pc_home.dart:656-661`).

### 4.4 PC pan home

- `PanHomeScreen` desktop: left 232px space nav (`PcNavCell` per space + header 云盘 +
  `PanQuotaBar` pinned bottom) and right file area (`chat/lib/pan/pan_home_screen.dart:124-203`).
- Right side is `PanPcFolderView`, keyed by `spaceId/lastFolderId` so changing folder/space reloads.
  Breadcrumb navigates back; folders open in place instead of pushing pages
  (`pan_home_screen.dart:190-203`).
- `PanPcFolderView` = `PcPageHeader` with `PanBreadcrumb` + back button (only when not root) +
  actions 新建文件夹 / 上传 (when `space.canWrite`); table columns 名称 / 大小 / 创建者 /
  修改时间 / trailing; column visibility by width (`>=680` creator, `>=480` size+time)
  (`chat/lib/pan/pan_pc_folder_view.dart:77-122,191-286`).
- Row: 28px icon, name (`PcTheme.cellTitle`), size (folders show `n项`), creator, time,
  hover/context-menu `more_horiz`; double action: tap opens, right-click opens menu
  (`pan_pc_folder_view.dart:288-385`).
- **Drag & drop upload**: `DropTarget`; dropped directories are skipped (no whole-directory
  upload); overlay hint `panDropToUpload(folder)` (`pan_pc_folder_view.dart:144-189`).

### 4.5 File operations (shared logic in `pan_folder_state.dart`)

`PanFolderState` mixin is the single implementation for mobile list and PC table
(`chat/lib/pan/pan_folder_state.dart:31-355`).

| Operation | Behavior | Citation |
|---|---|---|
| New folder | name dialog (`newFolder`/`folderName`), `POST /files/folder`, toast `createSuccess`/`createFail` | `pan_folder_state.dart:207-220` |
| Upload | file picker multi-select; queue; sequential upload with per-file `PanUploadStrip` progress + cancel; toasts `uploadSuccess`/`uploadFail`/`uploadCancelled`; continues if page left | `pan_folder_state.dart:134-203` |
| Download / open non-doc | `POST /files/url` → `Utilities.openLink(context, url)`; on failure toast `panGetDownloadUrlFailed` | `pan_folder_state.dart:121-130` |
| Rename | name dialog prefilled (extension-pre part selected), skip if empty/unchanged, `POST /files/rename` | `pan_folder_state.dart:222-235`; `pan_widgets.dart:510-577` |
| Delete | confirm dialog `deleteFileConfirm(name)` with danger "删除", `POST /files/delete` | `pan_folder_state.dart:237-266` |
| Share | `showPanShare` (§4.6) | `pan_folder_state.dart:271-274` |
| Move / Copy | destination picker, `POST /files/move` / `/files/copy` | `pan_folder_state.dart:278-302` |
| 转存 (duplicate) | copy into own space only (`ownSpacesOnly`), `POST /files/copy` | `pan_folder_state.dart:305-323` |

**Action menu composition** (`actionsFor`, `pan_folder_state.dart:327-354`), depends on
`space.canManage` (server `canManage`) not `canWrite`:
- Folder: `打开`.
- Online-doc file: `在线打开` (`panOpenOnline`), and `下载` (if online) else `下载/打开`
  (`downloadOrOpen`).
- Other file: `下载/打开`.
- If `space.canManage`: file→`分享`; then `移动`, `复制`, `重命名`, `删除`(danger).
- Else: single `转存` action.

### 4.6 Share ("分享")

`showPanShare(context, fileId, fileName, sizeText)` — desktop centered dialog 560×600, mobile full
page (`chat/lib/pan/pan_share.dart:28-41`).
- Loads `POST /shares/list`; lists existing shares with portrait/name/permission dropdown
  (可查看/可编辑) and 移除; changing permission calls `POST /shares/add` with the same target.
- Bottom permission selector (default 可查看) then `分享给…` opens the standard "pick forward
  target" screen (`showPickForwardTarget`) with the same permission option inline.
  (`pan_share.dart:91-140,356-434`.)
- On confirm: for each chosen conversation, `grantForConversation` → `POST /shares/add`
  (skip self single-chat and channels); on grant failure the message is **not** sent; then send one
  `LinkMessageContent` card per conversation, plus an optional text comment
  (`pan_share.dart:142-178`).
- Card: `title = fileName`, `contentDigest = panShareCardDigest(size)` or
  `panShareCardDigestNoSize`, `url = R/doc/open?fileId=<id>` (`pan_share.dart:120-139`).
- `grantForConversation` semantics: single→`USER` (skip self), group→`GROUP`, others ignored;
  **never downgrade** (`EDIT` stays `EDIT`) (`chat/lib/pan/pan_service.dart:629-649`).
- `canShare` server-side: file is a FILE and user can manage its space
  (`app-pan/.../service/PermissionService.java:111-112`).
- Share hint text (`panShareHint`): "按群分享时，此刻在群里的人才有权限；退群后最长 1 分钟内失效。"

### 4.7 "存到网盘" / "存到网盘并打开" and "send file to chat"

- Message-file save: `saveFileMessageToMyPan` (`chat/lib/pan/pan_save.dart:18-67`):
  1. `saveFileToMyPan` → `getMySpaces()` → prefer `USER_PRIVATE` (else first) → list root →
     unique name → `POST /files` with `copy: true`.
  2. toast `panSaveToMyPanSuccess` / `panSaveToMyPanFailed`.
  3. if `openAfterSave`: online doc + webview → open `docOpenUrl(fileId)`; else `POST /files/url`
     and open the signed URL.
- `_uniqueFileName`: if name exists, insert `(1)`, `(2)`… before the extension
  (`chat/lib/pan/pan_service.dart:279-298`).
- "Send to chat" from pan is the share flow (§4.6): a link card, **never** the raw signed URL
  (it expires and the pan bucket is private) (`pan_share.dart:116-119`).

### 4.8 Docs home (`PanDocsScreen`)

Native list; ONLYOFFICE editor is always in a webview.

- Two lists: **最近打开** (`recent`) and **共享给我** (`shared`); last tab remembered in a static
  field for the session (`chat/lib/pan/pan_docs_screen.dart:20,41-43`).
- New doc button visible only if `_canCreate`: desktop always, mobile only after
  `POST /docs/options` returns `mobileEdit == true` (`pan_docs_screen.dart:50,81-88,705-710`).
- Mobile: `AppBar` 文档 + add action + `TabBar` (最近打开 / 共享给我); body list rows with icon,
  name, subtitle time·who, permission tag, `more_horiz` (menu only on recent tab); 开源许可 link
  at the bottom (`pan_docs_screen.dart:439-509`).
- PC: left 232px nav with 最近打开 / 共享给我 + 开源许可 link; right `PcPageHeader`
  (title = active list title; 新建 button when `_canCreate`) + table columns 名称 / 创建者 or 共享自
  / 最近打开 or 共享时间 / 权限 / trailing (`pan_docs_screen.dart:302-435,534-705`).
- 新建: choose 新建文档(docx) / 新建表格(xlsx) / 新建演示(pptx) → name dialog prefilled with
  未命名文档/未命名表格/未命名演示 (extension part selected) → `POST /docs/create` → open the new
  file immediately (`pan_docs_screen.dart:182-237`).
- Recent row actions: 打开, 分享 (only when `_canShare`: non-folder and `canManage` on its space),
  从列表中移除 (`POST /docs/recent/remove`; optimistic removal)
  (`pan_docs_screen.dart:160-260`).
- Licenses page: `WFWebViewScreen(PanService.docLicensesUrl, title: 开源许可)`
  (`pan_docs_screen.dart:169-177`).
- Empty states: recent empty shows hint depending on `_canCreate`; shared empty fixed hint
  (`pan_docs_screen.dart:277-291`).
- Opening a doc: `docOpenUrl(file.id)`; if `DocWindowManager.supported` (PC) → independent doc
  window; else push `WFWebViewScreen` and refresh lists on return
  (`pan_docs_screen.dart:126-155`). After opening in the PC doc window it re-loads lists ~1.5s
  later (no "back" event).

### 4.9 PC independent doc window & multi-tab

- `DocWindowManager.supported = WfcPlatform.isNativeDesktop && isInlineWebViewSupported`
  (`chat/lib/pc/doc_window/doc_window_manager.dart:23-24`).
- One shared independent window; docs are **tabs**. Window kind `doc`, reuse policy
  `updateContent`; open event `doc.open`; payload `{url, title?}`
  (`doc_window_manager.dart:34-94`, `doc_window_ipc.dart:5-13`,
  `wf_webview_window_ipc.dart` for the payload).
- Preferred window 1200×840, capped to 90% of screen; min 720×560
  (`doc_window_manager.dart:44-56`, `doc_window_app.dart:59`).
- Tabs: **max 8** (`DocTabsViewModel.maxTabCount`); duplicates detected by **path+query only**
  (so main/backup hosts still count as the same doc); when full, evict the earliest non-active tab;
  closing the active tab selects the one to its left; closing the last tab closes the window
  (`chat/lib/pc/doc_window/doc_tabs_view_model.dart:28-94,157-163`).
- Only the active tab's WebView is mounted; closed tabs' hosts are recycled (`about:blank`) into a
  pool and re-bound on reuse (`doc_tabs_view_model.dart:134-155`, `doc_window_app.dart:205-268`).
- Window title follows the active tab (set by `setPageHeader` title, else `document.title`, else
  localized 文档) (`doc_window_app.dart:76-89,253-263`).
- `Ctrl/Cmd+W` closes the active tab (`doc_window_app.dart:98-107`).
- Page links opening a URL open a new tab (`onOpenUrl`) and page `close` closes its tab
  (`doc_window_app.dart:229-242`).
- The window hosts `JsApi` itself, so link picking / sharing dialogs open inside the doc window
  (`doc_window_manager.dart:15`, `doc_window_app.dart`).

---

## 5. Webview / JS bridge contract

The server pages use **dsbridge**; the native side must inject a JS namespace whose methods are the
names below. On Flutter: `JsApi extends JavaScriptNamespaceInterface` and `registerFunction(...)`
(`chat/lib/workspace/js_api.dart:23,83-97`).

### 5.1 Transport (must match)

- The WebView UA **must** contain `WF-DSBridge` before the page loads, otherwise the page falls back
  to a non-native "web bridge" (`chat/lib/workspace/webview_support.dart:100-127`; page check
  `doc-web/app.js:15-19`).
- Call convention: `window._dsbridge.call(method, JSON.stringify({data, _dscbstub}))` or, as
  fallback, `prompt("_dsbridge="+method, arg)`.
- Async methods: the native side completes by invoking `window[_dscbstub](result)`; repeated
  callbacks (e.g. `setPageHeader`) use dsbridge's `setProgressData` and keep the callback alive
  (`doc-web/app.js:20-60`; `chat/lib/workspace/js_api.dart:139-151`).
- Capability probe: sync method `_dsb.hasNativeMethod` returning `true` (used before
  `setPageHeader`, `downloadFile`, `chooseGroup`) (`doc-web/app.js:56-60`; dsbridge built-in).

### 5.2 Registered bridge methods

| Method | Sync/Async | Params (from page) | Response / behavior |
|---|---|---|---|
| `getAuthCode` | async | `{appId, appType}` (page sends `{appId:"admin", appType:2}`) | `{code:0, data:"<authCode>"}`; error `{code:<err>}`. Native calls `Imclient.getAuthCode(appId, appType, hostOfDocumentHost)` where `host` = host of the bound app URL. | 
| `setPageHeader` | async, repeatable | `{title?, subtitle?, actions:[{id, text, icon?, primary?}]}` | Native renders title/subtitle/buttons in its own title bar and calls back `window[_dscbstub](actionId)` **every** time a button is tapped (setProgressData). `icon` names recognized: `share`, `history`, `download`, `convert`. Special id `share` on an online-doc URL is intercepted natively (opens native share) and does not call back. |
| `downloadFile` | sync | `{url}` (page) / string or `{url,name}` accepted | Native opens the URL with the system (desktop default browser / mobile system app). Used for doc "下载" and history-version download. |
| `openUrl` | sync | url string or `{url, name}` | Open a new page/tab; on PC doc window opens a new tab, otherwise push a web view. |
| `close` | sync | none | Close the current page/tab. |
| `toast` | sync | string text | Native toast. |
| `chooseContacts` | async | `{}` | `{code:0, data:"<JSON string>"}` where data is `[{"uid","name","displayName","portrait"}]`; cancel → `code:-1`; pre-check failed (page navigated) → `code:-2`. |
| `chooseGroup` | async | `{}` | `{code:0, data:"<JSON string>"}` where data is `[{"gid","name","portrait"}]`; cancel `-1`, pre-check fail `-2`. H5 gates the group button on `hasNativeMethod("chooseGroup")`. |
| `config` | async | `{appId, appType, timestamp, nonceStr, signature}` | Calls `Imclient.configApplication`; on success invokes page handler `ready`, on failure `error`. (Used by the workspace H5, not by doc-web.) |

Citations: `chat/lib/workspace/js_api.dart:83-302` (all methods, result shapes, `_preCheck`
`appUrl == currentUrl` gate at `318-328`), `doc-web/app.js:82-88,249-263`,
`doc-web/open.html:63-85,204-266`.

### 5.3 Page → native header payload (`WebPageHeader`)

Parsed from `{title?, subtitle?, actions}`; unknown action items skipped; icon map to native icons
(`chat/lib/workspace/web_page_header.dart:10-58`). Host rendering: desktop draws buttons in the
page header (primary button filled), mobile collapses actions into a `…` menu with primary first
(`web_page_header.dart:134-242`); the doc window draws `subtitle` as a chip and actions beside the
tab strip (`doc_window_app.dart:188-203`).

### 5.4 Editor page result map (`/api/pan/docs/editor-config` / `view-url`)

`open.html` consumes:
`apiUrl` (ONLYOFFICE `api.js` URL), `config` (ONLYOFFICE `DocEditor` config, JWT-signed),
`fileId?`, `fileName`, `permission?` (`VIEW`/`EDIT`), `canEdit`, `viewReason`
(`permission|convertible|format|mobile|requested|link` → localized read-only note),
`canShare`, `convertible` (`doc-web/open.html:108-150`). Server construction:
`DocsService.openEditor` (`app-pan/.../service/docs/DocsService.java:147-249`) and
`openReadOnlyUrl` (`:260-337`).

- Read-only reasons text (page): permission=只读 · 你只有查看权限, convertible=只读 · 旧格式，转换后可编辑,
  format=只读 · 该格式不支持在线编辑, mobile=只读 · 手机上只能查看，请在电脑上编辑,
  requested=只读, link=只读 · 按链接打开 (`doc-web/open.html:44-51`).
- Header actions built by the page: `convert`(转换后编辑, only when convertible && !mobile),
  `versions`(历史版本), `download`(下载), `share`(分享, primary, only when `canShare`);
  none for URL read-only (`open.html:118-124`).
- On mobile the page also uses `platform=mobile`; Android WebView registers a service worker for
  the ~20MB editor assets because the WebView HTTP cache is ~20MB (`open.html:152-171`).

### 5.5 Docs home page (`/doc/` index)

Uses the same bridge: `login()` via `getAuthCode` + `POST session`, `openDoc(fileId)` via
`Bridge.callSync('openUrl', url)`, `licenses` link via `openLink`, and, on mobile, disables the
create buttons until `POST docs/options` says `mobileEdit`
(`doc-web/index.html:32-146`; `doc-web/app.js:180-209`).

### 5.6 "media download" clarification

The Flutter bridge exposes **no** method named `mediaDownload`. All file/media downloads from
web pages go through `downloadFile` (system hand-off) after obtaining a signed URL
(`/api/pan/files/url`), and chat media/file messages are downloaded by the native chat client
(not the bridge). Ports should provide `downloadFile` and route it to the platform's system
download/open, matching `JsApi.downloadFile` (`js_api.dart:118-137`).

### 5.7 Web view host details

- `WFWebViewScreen` builds a `DWebViewController`, calls `configureDsBridgeWebView`, sets the UA,
  then `loadRequest(MediaUrlRedirector.redirect(url))`
  (`chat/lib/workspace/wf_webview_screen.dart:51-91`).
- The online-doc page omits the 18px grey strip that other web pages show
  (`wf_webview_screen.dart:163-172`).
- On Linux the native webview is a separate GTK overlay; hosts must detach the `WebViewWidget`
  before pushing a full-screen overlay and re-attach after
  (`chat/lib/workspace/webview_support.dart:30-45`, `js_api.dart:41-53`, `wf_webview_screen.dart:109-123`).

---

## 6. File-message integration

### 6.1 Which files can be previewed online

`PanService.isOnlineDocName(name)` — case-insensitive extension in the set matching the server's
`DocsService` WORD/CELL/SLIDE/PDF groups (`chat/lib/pan/pan_service.dart:78-97`;
server `DocsService.java:72-78`):

- word: `doc, docx, docm, dot, dotx, dotm, odt, ott, rtf, txt, wps, wpt, fodt, mht, mhtml, htm, html, epub, fb2`
- cell: `xls, xlsx, xlsm, xlt, xltx, xltm, xlsb, ods, ots, csv, et, ett, fods`
- slide: `ppt, pptx, pptm, pot, potx, potm, pps, ppsx, ppsm, odp, otp, dps, dpt, fodp`
- pdf: `pdf, djvu, xps, oxps`

Only a **file** (not a folder) with such an extension is "online". Server distinguishes
`EDITABLE` (OOXML: docx/docm/xlsx/xlsm/pptx/pptm) from `CONVERTIBLE`
(`DocsService.java:79-83`) — only EDITABLE can be edited in place.

### 6.2 Per-platform file-message action menu

Built in `buildMessageMenuItems` (`chat/lib/conversation/conversation_controller.dart:721-757`).
For a `FileMessageContent` with non-empty `remoteUrl`:

- If `panAvailable && isOnlineDocName(name)`: **在线预览** (`docOnlinePreview`) →
  `_openFileMessageOnline`.
- If `panAvailable`: **存到网盘** (`panSaveToMyPan`) and **存到网盘并打开**
  (`panSaveToMyPanAndOpen`).
- Always: **下载** (`download`).

`_openFileMessageOnline` calls `Utilities.openFileOnline(context, name, url)` — read-only link
open (`R/doc/open?url=&name=`), PC independent doc window, mobile in-app webview; falls back to
system open if pan unconfigured or app-service address missing
(`conversation_controller.dart:984-996`, `chat/lib/utilities.dart:255-272`).

`_downloadFileMessage` → `Utilities.downloadFile`:
- **Desktop**: save-as dialog, stream to disk, toast `saveSuccess`.
- **Mobile**: write to sandbox temp dir, then system share/save sheet; fallback to system open.
(`chat/lib/utilities.dart:313-380`).

Menu labels: 在线预览 / 存到网盘 / 存到网盘并打开 / 下载
(`chat/lib/l10n/app_localizations_zh.dart:3428,3416,3419,3560`).

### 6.3 Old `/pan/doc/` link rewriting

Document pages moved from `<root>/pan/doc/` to `<root>/doc/` when pan merged into wf-app-server.
A compatibility rewrite (`PanService.upgradeLegacyDocUrl`: rewrite `<root>/pan/doc/` →
`<root>/doc/` keeping path/query, called at the top of `Utilities.openLink`) was added in commit
`3a35df19` and then **reverted** in commit `8057a338` ("不考虑兼容性"): current code does **no**
rewriting; old `/pan/doc/...` cards will not open and users must upgrade.

> Porting decision: decide explicitly. If backward compatibility is required, implement
> `upgradeLegacyDocUrl` for both main and backup roots before `isDocUrl`/open, exactly as the
> reverted patch did (`git show 3a35df19 -- chat/lib/pan/pan_service.dart`).

### 6.4 Link opening rules (`Utilities.openLink`)

1. Prefix `www.` without scheme → prepend `https://`.
2. Apply `MediaUrlRedirector.redirect`.
3. If PC `DocWindowManager.supported` and `isDocUrl` → open independent doc window.
4. Else if `(!isDesktopStyle || isDocUrl)` and scheme is http/https and webview supported → push
   `WFWebViewScreen`; **doc URLs must use the in-app webview because they need the bridge**.
5. Else launch externally (`url_launcher`), toast `cannotOpenLink` on failure.

Citation: `chat/lib/utilities.dart:221-253`.

`Utilities.openFileByDefault` (file records, favorites): resolve URL, try `openFileOnline`, else
system open (`utilities.dart:284-311`).

---

## 7. Platform differences and edge cases

### 7.1 PC vs mobile summary

| Aspect | PC (desktop) | Mobile |
|---|---|---|
| Entry | sidebar full-pane tabs 云盘(7) / 文档(8), keep-alive | "我" page rows 云盘 / 文档 |
| Pan layout | 232px space nav + breadcrumb + table + drag-drop upload | space list page → folder pages (push) |
| File menu | right-click / hover `…` context menu | long-press / `…` bottom sheet |
| Docs home layout | left nav + table, new-doc menu from a button | AppBar tabs + `…` |
| Open online doc | independent doc window (1 window, ≤8 tabs) | in-app `WFWebViewScreen` full page |
| New doc on mobile | always allowed | only when `/docs/options.mobileEdit == true` |
| Download | save-as dialog + stream to file | temp file + system share sheet |
| Share dialog | centered 560×600 dialog | full page |
| Destination picker | dialog 520×560 | full-screen dialog |
| Name dialog | `PcDialogFrame` 400×196 | `AlertDialog` |
| Upload | file picker + drag&drop | file picker only |
| Linux quirk | must detach native webview for overlays | n/a |

### 7.2 Permissions

- `space.canWrite` controls new-folder/upload buttons; `space.canManage` (server `canManage`)
  controls share/move/copy/rename/delete. A file menu in a non-manageable space offers only 转存.
- Server remains the authority: `POST /files/check-permission` exists, and every mutation is
  re-validated (e.g. `PermissionService.canManageSpace`, `canShare`).
- Sharing can only be done by a manager of the file's space; the docs screen computes
  `_canShare` from `getSpaces()`'s `canManage` (`pan_docs_screen.dart:56-58,90-101,160-162`).
- Effective permission is `max(space rule, direct share, group share)`; `EDIT` is required to edit.

### 7.3 Quota

- `PanQuotaBar` shows `已用 {used} / {total}` + progress; ratio forced to a minimum visible 1% when
  `used > 0`; bar turns danger color above 90% (`chat/lib/pan/pan_widgets.dart:241-279`;
  locale string `panQuotaUsage` = "已用 x / y").
- Quota is refreshed after file changes via `onFilesChanged` → silent `_loadSpaces(silent:true)`
  (`pan_folder_state.dart:40-41,85-89`; `pan_home_screen.dart:190-202`).

### 7.4 Non-empty folder deletion and server errors

Server rejects deleting a non-empty folder (message e.g. "文件夹非空，请先删除内部文件").
`panFailMessage` appends the server message only for `code > 0`; local/network errors
(`code <= 0`) show the generic localized prefix (`chat/lib/pan/pan_widgets.dart:66-73`).

### 7.5 Share expiry / permissions

- The signed download URL expires (default 600s), which is why chat sharing uses a **link card**,
  not a file message (`pan_share.dart:116-119`).
- Group shares are computed through a group-membership cache (default 60s); leaving a group may
  take up to `pan.group_cache_seconds` to lose access (`DocsConfig.java:58-60`; hint in
  `pan_share.dart`).
- The share dialog **does not downgrade** an existing EDIT share to VIEW; downgrade is only via the
  share list (`pan_service.dart:641-648`).

### 7.6 Mobile webview editor limitations

- ONLYOFFICE community mobile web cannot edit; the mobile web editing feature is a **commercial-license**
  capability of ONLYOFFICE Docs. Server forces read-only and the reason is `mobile`
  unless `docs.mobile_edit=true` (`DocsService.java:156-168`; `DocsConfig.java:34-36`).
  Customers who need mobile editing must buy an ONLYOFFICE commercial license and then set `docs.mobile_edit=true`.
- Opening the docs editor also has a bandwidth cost: the first open pulls the ONLYOFFICE engine
  (`fonts/217` gzip ≈ 9.26MB, `sdk-all.js` gzip ≈ 4.51MB, …). Serving `/docs/` static assets from a
  **CDN** is recommended for low-bandwidth hosts (WKWebView also re-downloads over-sized entries).
- The docs home hides the create buttons until it confirms `mobileEdit` (`pan_docs_screen.dart:50,81-88`;
  `doc-web/index.html:143-145`).
- Android WebView's ~20MB HTTP cache is too small for the editor; the page registers a service
  worker to cache editor SDK/fonts (`doc-web/open.html:152-171`).
- `isInlineWebViewSupported` false (some Windows/Linux builds, HarmonyOS without a fork) hides the
  docs entry and makes `PanService` treat online docs as non-openable; browse/download still work
  via system.

### 7.7 Dual network

- Main/backup app-service addresses are both registered for the auth token so switching networks
  does not require re-login (`chat/lib/service_config/service_config_store.dart:134-148`).
- `isDocUrl` accepts both roots; doc-window dedupe ignores host, so a doc shared with a backup-host
  URL still maps to the same tab (`pan_service.dart:69-76`; `doc_tabs_view_model.dart:157-163`).
- `MediaUrlRedirector` rewrites main/backup media prefixes based on the active network; pan base and
  doc `url=` argument pass through it (`pan_service.dart:105,265`).

### 7.8 Upload details to replicate

- Small file: IM SDK `Imclient.uploadMediaFile(path, Media_Type_FILE, ...)` returns the storage URL
  (`pan_service.dart:370-403`).
- Large file (desktop only, when big-file upload supported and either forced or size > 100MiB):
  `Imclient.getMediaUploadUrl` → `{uploadUrl, downloadUrl, backupUploadUrl, type}`; use backup URL on
  the backup network; `type == 1` = Qiniu multipart POST with `key`/`token` fields, else HTTP `PUT`
  with `Content-Type` = MIME (`pan_service.dart:300-554`).
- File name uniqueness computed client-side before upload (`pan_service.dart:256-298`).
- MD5 computed locally and MIME sniffed; passed to `POST /files` (`pan_service.dart:317-355`).

---

## 8. Porting checklist (per non-Flutter client)

1. **Config**: expose `MAIN_HOST/APP_PORT/IM_USE_TLS`, derive `appRoot`, `panBase = appRoot + "/api/pan"`,
   `docBase = appRoot + "/doc/"`, and backup equivalents. Gate pan/docs entries on pan base being
   configured; gate docs entry on webview availability.
2. **Auth**: implement `POST {appRoot}/api/auth/login` with `{authCode}` and store the `authToken`
   response header keyed by host-port (share across features, link main/backup). Add `authToken`
   header to every pan request and auto-relogin on 13/401/403 with one retry.
3. **Models**: implement PanSpace / PanFile / PanDocEntry / PanShare / PanShareSource / FileVersion
   parsing tolerantly (`id` vs `fileId`, enum-or-int `spaceType`, string-or-int `type`).
4. **Screens**: reproduce the mobile and PC layouts/menus per §4, with the exact labels in §9.
5. **Docs**: native docs home (recent/shared/new/licenses) + webview editor. On PC/Electron use one
   window with ≤8 tabs, dedupe by path+query, title from `setPageHeader`.
6. **Bridge**: inject the dsbridge namespace with `getAuthCode`, `setPageHeader` (repeatable),
   `downloadFile`, `openUrl`, `close`, `toast`, `chooseContacts`, `chooseGroup`; append
   `WF-DSBridge` to the UA before load; support `_dsb.hasNativeMethod`.
7. **Chat integration**: extension allow-list, per-platform file-message menu, `copy:true` save to
   pan, share via link card, legacy `/pan/doc/` decision.
8. **Edge cases**: quota bar, permission vs manage split, non-empty folder deletion message, share
   non-downgrade, 600s download TTL, mobile read-only editor, native-overlay webview handling on
   Linux/Linux-like renderers.

---

## 9. Label reference (zh-CN source of truth)

Mobile/PC entries: 云盘 (`cloudDrive`), 文档 (`onlineDocs`).
Pan: 云盘服务未配置, 没有网盘空间, 全局公共空间, 我的公共空间, 我的个人空间, "n个文件", "n项",
新建文件夹, 文件夹名称, 上传, 上传成功/上传已取消/上传失败, 正在上传 {name}, 松开鼠标，上传到「{folder}」,
这里还没有文件, 把文件拖到这里，或点右上角「上传」, 点右上角上传文件或新建文件夹, 名称, 大小, 创建者, 修改时间,
已用 {used} / {total}, 打开, 在线打开, 下载, 下载/打开, 分享, 移动, 复制, 重命名, 删除, 转存,
移动到, 复制到, 转存到, 全部空间, 没有子文件夹, 没有可转存的空间, 移动成功/移动失败, 复制成功/复制失败,
转存成功/转存失败, 重命名成功/重命名失败, 创建成功/创建失败, 确认要删除"{name}"吗？, 获取下载链接失败,
存到网盘, 存到网盘并打开, 已保存到我的网盘, 保存到我的网盘失败, 在线预览, 在新窗口打开,
分享「{name}」, 分享给…, 分享给, 对方权限, 可查看, 可编辑, 已修改权限, 修改分享失败, 已分享, 还没有分享给任何人,
"云盘文件 {size}，点开查看", "云盘文件，点开查看", "已分享给（{n}）", "{sharer} 分享于 {time}",
按群分享时，此刻在群里的人才有权限；退群后最长 1 分钟内失效。, "{n} 个会话授权失败，未发送".
Docs: 最近打开, 共享给我, 新建, 新建文档, 新建表格, 新建演示, 未命名文档, 未命名表格, 未命名演示,
文件名, 共享自, 最近打开, 共享时间, 权限, 可编辑, 可查看, 还没有打开过文档,
新建一个，或从云盘、聊天里打开文档, 从云盘、聊天里打开的文档会出现在这里, 暂时没有共享给你的文件,
别人把文件分享给你或你所在的群后，会出现在这里, 群「{name}」, 从列表中移除, 移除失败, 开源许可.
Buttons: 确定, 取消, 重试, 加载失败，请稍后重试.

Citation: `chat/lib/l10n/app_localizations_zh.dart:3373-3703` (all of the above);
English equivalents at `chat/lib/l10n/app_localizations_en.dart:3441-...`.
