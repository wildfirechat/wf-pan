# ios-chat 网盘对齐与「在线文档」实现计划

> 目标仓库：`/Users/rain/Workspace/ios-chat`（原生 Objective-C）
> 参考实现：Flutter `/Users/rain/Workspace/wf-enterprise-chat/chat/lib/pan/`
> 目标服务端：`/Users/rain/Workspace/wf-app-server`（合并服务 `app-pan` 模块，`/api/pan` + `authToken`）
> 旧服务端：`/Users/rain/Workspace/wf-pan/wf-pan-server`（独立部署，`/api/v1` + `authCode` header）
>
> **关于 spec**：`/Users/rain/Workspace/wf-pan/docs/client-pan-docs-spec.md` **当前不存在**（`docs/` 为空）。
> 本计划的行为规格全部从 **Flutter 参考实现** 与 **合并服务端源码/在线文档 H5** 反推：
> - `wf-enterprise-chat/chat/lib/pan/pan_service.dart`（接口契约）
> - `wf-enterprise-chat/chat/lib/pan/pan_save.dart`、`pan_docs_screen.dart`、`conversation_controller.dart`（交互行为）
> - `wf-enterprise-chat/chat/lib/workspace/js_api.dart`（JS 桥契约）
> - `wf-app-server/app-pan/src/main/java/.../controller/client/*`（接口路径/入参/出参）
> - `wf-app-server/app-pan/src/main/resources/doc-web/{app.js,open.html}`（在线文档 H5 + 桥方法）
> - `wf-app-server/app-common/.../security/LoginController.java`（authCode→authToken）
>
> 本文只描述实现，**不改动任何代码**。

---

## 0. 结论速览

现有 iOS 网盘是照 **旧的独立 `wf-pan-server`** 写的：路径 `/api/v1/**`、每个请求直接带 `authCode` header、响应读 `data` 字段。新服务把网盘并入了 `wf-app-server` 的 `app-pan` 模块：

1. 路径从 `/api/v1/**` → `/api/pan/**`；
2. 鉴权从「每次都带 `authCode`」→「用 IM `authCode` 调 `POST {应用服务根}/api/auth/login` 换 `authToken`，业务请求带 `authToken` header，失效自动重登重试一次」；
3. 响应体数据字段从 `data` → `result`；
4. 新增分享（shares）、在线文档（docs）、历史版本（versions）、权限（permission）四组接口；
5. 在线文档是服务端自带的 H5（`{应用服务根}/doc/`），靠客户端 WebView + dsbridge 提供 `getAuthCode/setPageHeader/downloadFile/chooseContacts/chooseGroup` 才能登录和操作。

iOS 侧当前**没有**：`authToken` 登录、shares/docs/versions 协议方法、在线文档首页、文件「在线预览/下载」菜单、`setPageHeader/downloadFile/chooseGroup` 三个桥方法。这些是要补的主要缺口。

---

## 1. 现有 iOS 网盘实现盘点（文件路径 + 现状）

### 1.1 服务实现层（wfchat app）

| 文件 | 作用 | 现状 |
|---|---|---|
| `wfchat/WildFireChat/PanService/PanService.h` | 声明 `PanService : NSObject <WFCUPanService>` + `+sharedService` | 单例，实现 `WFCUPanService` 协议 |
| `wfchat/WildFireChat/PanService/PanService.m` | 全部 HTTP 调用（391 行） | 旧服务：`/api/v1/**` + `authCode` header + `data` 字段 |

关键点 `PanService.m`：

- 基址：`-effectiveBaseUrl` → `WFCGetPanServerAddress()`（`PanService.m:357-359`）。
- 路径（全部硬编码 `/api/v1` 前缀，`PanService.m`）：
  `/api/v1/spaces/list`(42)、`/api/v1/spaces/my`(65)、`/api/v1/spaces/user/public`(89)、
  `/api/v1/spaces/files`(112)、`/api/v1/files/folder`(145)、`/api/v1/files`(174)、
  `/api/v1/files/delete`(208)、`/api/v1/files/rename`(226)、`/api/v1/files/url`(246)、
  `/api/v1/files/check-permission`(265)、`/api/v1/files/move`(292)、`/api/v1/files/copy`(320)。
- 鉴权：`-postWithAuth:`（`PanService.m:345-355`）先 `[[WFCCIMService sharedWFCIMService] getAuthCode:@"admin" type:2 host:IM_SERVER_HOST]`，再 `-post:data:authCode:`（361-389）把 authCode 放进 HTTP header `authCode`。
- 响应：`dict[@"code"]` + `dict[@"data"]`（`PanService.m:44-60` 等）。

### 1.2 UI 层（wfuikit）

| 文件 | 作用 | 现状 |
|---|---|---|
| `wfuikit/WFChatUIKit/Pan/WFCUPanService.h` | 13 个方法的 `@protocol WFCUPanService` | 无 shares/docs/versions/permission；无 `isAvailable` |
| `.../Pan/WFCUPanSpace.{h,m}` | 空间模型 | `fromDictionary` 读 `id`/`spaceType`(字符串) |
| `.../Pan/WFCUPanFile.{h,m}` | 文件模型 | 读 `id`，`type` 只认 `"FOLDER"` |
| `.../Pan/WFCUPanViewController.{h,m}` | 空间列表首页（400 行），三种 `viewMode`（All/MySpaces/UserPublic），移动/复制模式 | 无「在线文档」入口 |
| `.../Pan/WFCUPanFileListViewController.{h,m}` | 文件/文件夹列表（887 行），长按/侧滑菜单：分享(Share=转发文件消息)、移动、复制、重命名、删除；点击文件→取下载 URL 交系统打开 | 分享只是把 `storageUrl` 当文件消息转发；无在线文档打开；无 shares 授权 |
| `.../Pan/WFCUPanFilePickerViewController.{h,m}` | 文件选择器（当前未被使用） | 保留 |
| `.../Pan/WFCUPanUploadManager.{h,m}` | 通过 IM SDK 上传媒体文件拿到 `storageUrl`，返回 size/md5 | 可用，无需改 |

### 1.3 地址配置与装配

- `wfchat/WildFireChat/WFCConfig.m:52` `PAN_SERVER_ADDRESS = @"https://pan.wildfirechat.net"`；`:54` `PAN_SERVER_BACKUP_ADDRESS = nil`。
- `WFCConfig.m:166-168` `WFCGetPanServerAddress()` = `WFCSelectServer(PAN_SERVER_ADDRESS, PAN_SERVER_BACKUP_ADDRESS)`（双网选择）。
- `wfchat/WildFireChat/AppDelegate.m:175-177`：仅当 `PAN_SERVER_ADDRESS || PAN_SERVER_BACKUP_ADDRESS` 时 `[WFCUConfigManager globalManager].panServiceProvider = [PanService sharedService];`。
- `WFChatUIKit/Utilities/WFCUConfigManager.h:79` `@property(nonatomic, weak) id<WFCUPanService> panServiceProvider;`。

### 1.4 现有入口

| 入口 | 文件:行 | 门控 |
|---|---|---|
| 「发现」页「网盘」 | `wfchat/WildFireChat/Discover/DiscoverViewController.m:45-47`（加行）、`:168-173`（跳 `WFCUPanViewController` viewMode=All） | `if(PAN_SERVER_ADDRESS \|\| PAN_SERVER_BACKUP_ADDRESS)` |
| 用户资料页「他/她的网盘」 | `wfuikit/WFChatUIKit/CommonVC/WFCUProfileTableViewController.m:465-467`（建 cell）、`:616-622`（点击） | `panServiceProvider != nil` |
| 「我」页 | `wfchat/WildFireChat/Me/WFCMeTableViewController.m` | **无网盘/在线文档入口** |

> Flutter 的「我」页（`wf-enterprise-chat/chat/lib/settings/me_tab.dart:83-104`）有「云盘」与「在线文档」两行，均以 `Config.panServerAddress` 非空门控，「在线文档」再要求 `isInlineWebViewSupported`。

### 1.5 消息里的文件动作（现状）

`wfuikit/WFChatUIKit/MessageList/ViewController/WFCUMessageListViewController.m`：

- 文件消息菜单：`SaveToPan`（`:4051-4058` 注册 → `onMenuSaveToPan:` `:4212` → `performSaveToPan:` `:4583`）。
  - `performSaveToPan:` 拉 `getMySpaces`，ActionSheet 选空间 → `saveFileToSpace:`（`:4628`）→ `checkSpaceWritePermission` → `createFile ... copy:YES`，成功后只 toast「已保存到网盘」，**不打开**。
  - 传入 `storageUrl:fileContent.remoteUrl`（未做双网/媒体地址重定向），`md5:@""`。
- 文件消息点击：`:3028-3046` 与 `:3484-3499` 两处，取 `getAuthorizedMediaUrl` 后**一律**用 `WFCUBrowserViewController` 打开（浏览器里直接显示文件，doc 类型不会走在线文档）。
- 有 `-mimeTypeForFileName:`（`:4669`）可复用。

Flutter 侧对文件消息（`conversation_controller.dart:719-753`）的菜单是：
`在线预览`（仅文档扩展名 + 网盘可用）、`保存到我的网盘`、`保存到我的网盘并打开`、`下载`；
文件点击默认走 `Utilities.openFileOnline`（只读在线文档）失败再交系统（`utilities.dart:254-283`）。

---

## 2. 目标服务端契约（旧 → 新 对照）

### 2.1 基址与鉴权

| 项 | 旧（ios 现状） | 新（目标） |
|---|---|---|
| 网盘基址 | `https://pan.wildfirechat.net`（根） | **应用服务根** `APP_SERVER_ADDRESS`，网盘接口在其 `/api/pan` 下 |
| 业务路径 | `/api/v1/spaces/list` … | `/api/pan/spaces/list` … |
| 鉴权 | header `authCode`（每次请求前取） | `POST {应用服务根}/api/auth/login` body `{"authCode":...}` → 响应 header `authToken`；之后每个请求 header `authToken` |
| 响应数据字段 | `data` | `result`（`Result<T>` / `RestResult`，见 `app-common/.../web/Result.java`） |
| 会话失效 | 无 | `code ∈ {13,1001,1002,1004}` 或 HTTP 401/403 → 清 token 重登后**重试一次**（Flutter `AuthCodeApiClient._needRelogin`） |
| 在线文档页 | 无 | `{应用服务根}/doc/open?fileId=N`、`/doc/open?url=&name=`、`/doc/licenses.html`；页面自己调 `/doc/session` 换 authToken |

`LoginController.java:51-58` 确认 `POST /api/auth/login`，`:110-111` 用 header `authToken` 下发会话 id；
`app-server/nginx/app-server.conf:140-153` 确认公网文档/下载路径是 **`/doc/`** 与 **`/dl/`**（不是 `/api/pan/doc/`）。

### 2.2 接口映射表（以 `app-pan` controller 为准）

| 能力 | 旧 ios 调用 | 新路径 | 入参 | 出参取值 |
|---|---|---|---|---|
| 空间列表 | `/api/v1/spaces/list` | `POST /api/pan/spaces/list` | `{}` | `result[]` |
| 我的空间 | `/api/v1/spaces/my` | `POST /api/pan/spaces/my` | `{}` | `result[]` |
| 他人公共空间 | `/api/v1/spaces/user/public` | `POST /api/pan/spaces/user/public` | `targetUserId` | `result{}` |
| 空间文件 | `/api/v1/spaces/files` | `POST /api/pan/spaces/files` | `spaceId,parentId` | `result[]` |
| 建文件夹 | `/api/v1/files/folder` | `POST /api/pan/files/folder` | `spaceId,parentId?,name` | `result{}` |
| 建文件记录 | `/api/v1/files` | `POST /api/pan/files` | `spaceId,name,size,storageUrl,copy,parentId?,mimeType?,md5?` | `result{}` |
| 删除 | `/api/v1/files/delete` | `POST /api/pan/files/delete` | `fileId` | — |
| 重命名 | `/api/v1/files/rename` | `POST /api/pan/files/rename` | `fileId,newName` | — |
| 移动 | `/api/v1/files/move` | `POST /api/pan/files/move` | `fileId,targetSpaceId,targetParentId` | — |
| 复制 | `/api/v1/files/copy` | `POST /api/pan/files/copy` | `fileId,targetSpaceId,targetParentId` | — |
| 下载地址 | `/api/v1/files/url` | `POST /api/pan/files/url` | `fileId[,versionNo]` | `result.storageUrl` |
| 写权限 | `/api/v1/files/check-permission` | `POST /api/pan/files/check-permission` | `spaceId` | `result`(bool) |
| 分享列表 | 无 | `POST /api/pan/shares/list` | `fileId` | `result[]` |
| 加/改分享 | 无 | `POST /api/pan/shares/add` | `fileId,targetType(USER/GROUP),targetId,permission(VIEW/EDIT)` | — |
| 移除分享 | 无 | `POST /api/pan/shares/remove` | `shareId` | — |
| 共享给我 | 无 | `POST /api/pan/shares/with-me` | `{}` | `result[]`(`file/permission/sharedAt/sources`) |
| 新建文档 | 无 | `POST /api/pan/docs/create` | `type(docx/xlsx/pptx),name?` | `result{}` |
| 编辑器配置 | 无 | `POST /api/pan/docs/editor-config` | `fileId,platform(mobile/pc)` | `result{apiUrl,config,canEdit,viewReason,canShare,convertible,fileName}` |
| 按链接只读 | 无 | `POST /api/pan/docs/view-url` | `url,name,platform` | 同上（只读） |
| 旧格式转换 | 无 | `POST /api/pan/docs/convert` | `fileId` | `result{}` |
| 手机编辑开关 | 无 | `POST /api/pan/docs/options` | `{}` | `result.mobileEdit`(bool) |
| 最近打开 | 无 | `POST /api/pan/docs/recent` | `{}` | `result[]` |
| 移除最近 | 无 | `POST /api/pan/docs/recent/remove` | `fileId` | — |
| 历史版本 | 无 | `POST /api/pan/versions/list` / `versions/restore` | `fileId` / `fileId,versionNo` | `result[]` / `result{}` |
| 空间写权限(GET) | 无 | `GET /api/pan/permission/space/{spaceId}/write` | path | bool |

> 服务端 `FileVO`/`SpaceVO` 字段名是 **`id`**（`FileVO.java`、`SpaceVO.java`），Flutter 读取时兼容 `fileId ?? id` / `spaceId ?? id`（`pan_service.dart:805-907`）。iOS 现在就读 `id`，但要按 Flutter 加 `fileId/spaceId/id` 三级回退，防止后端改字段。

### 2.3 已知字段差异需处理

- 文件类型：服务端 `FileVO.type` 是枚举 `"FOLDER"/"FILE"`；旧接口是 `1/0`。iOS 只认 `"FOLDER"`（`WFCUPanFile.m:33-38`）→ 需兼容数字 `1`。
- 空间类型：服务端 `SpaceVO.spaceType` 是 `"GLOBAL_PUBLIC"/"USER_PUBLIC"/"USER_PRIVATE"`；Flutter 兼容 `spaceType`/`type`（`pan_service.dart:805-846`）。iOS `WFCUPanSpace.m:30-37` 只认 `spaceType` 字符串 → 加 `type` 与数字回退。
- `WFCUPanSpace.canManage`、`creatorName` 等继续用；`canWrite` 逻辑对齐 Flutter `spaceType != userPublic || ownerId == me`。

---

## 3. 现有 HTTP / 鉴权工具（authCode→authToken）

iOS 已有两套「登录换 token + header 带 token」的范式，但没有 `/api/auth/login` 这一套：

| 服务 | 文件 | 登录方式 | token 存储 | header |
|---|---|---|---|---|
| 应用服务 | `wfchat/WildFireChat/AppService/AppService.m` | `POST /login` / `/login_pwd`（`isLogin:YES`，`:39-64`），从响应头取 token（`:467-475`） | `WFC_APPSERVER_AUTH_TOKEN`（`:22`、`:1024-1026`） | `authToken`（`:24`） |
| 组织通讯录 | `wfchat/WildFireChat/OrgService/OrgService.m` | `getAuthCode:@"admin" type:2` → `POST /api/user_login {authCode}`（`:39-46`） | `WFC_ORGSERVER_AUTH_TOKEN`（`:19`、`:364`） | `authToken`（`:20`、`:327-329`） |
| 接龙 | `wfchat/WildFireChat/CollectionService/CollectionService.m` | **仍是旧授权**：每次请求前取 authCode，header `authCode`（`:174-198`） | 无 | `authCode` |
| 投票 | `wfchat/WildFireChat/PollService/PollService.m` | **仍是旧授权**：header `authCode`（`:213-235`） | 无 | `authCode` |

结论：**没有任何 iOS 服务调用 `POST /api/auth/login`**；网盘要新增一个共享的 `authCode→authToken` 工具，建议做成一个独立的小类（见 §7.1），供 `PanService` 使用。因为合并服务「一个 authToken 全模块共用」（`pan_service.dart:22-24`），理想情况下该 helper 与后续接龙/投票迁移共用同一存储键。

`OrgService` 的 `-post:`（`:311-361`）是最接近的模板：`isLogin:YES` 时从 `NSHTTPURLResponse` 头取 `authToken` 存 NSUserDefaults，非登录请求带上。

---

## 4. 入口点与「网盘已配置」门控

### 4.1 现状
- 发现页：`DiscoverViewController.m:45` 用编译期/运行期常量 `PAN_SERVER_ADDRESS` 判定。
- 资料页：`WFCUProfileTableViewController.m:465` 用 `panServiceProvider` 判定。
- 「我」页：无入口。

### 4.2 目标门控
Flutter 用 `Config.panServerAddress` 非空（`pan_service.dart:32-35`），而合并后该值由应用服务根地址派生（`config.dart:289,417`），因此**实质上「配置了应用服务就有网盘」**。iOS 应对齐：

1. 让 `WFCGetPanServerAddress()` 派生自应用服务根（见 §6.1），从而「配置了应用服务」即视为网盘已配置；
2. 新增统一判定 `+[PanService isPanConfigured]`（`WFCGetPanServerAddress().length > 0`）并同时在 `WFCUConfigManager` 暴露只读属性，全部入口改用它，不要再各写各的宏判断；
3. 在线文档入口额外要求内置 WebView（iOS 恒为 YES，和 `isInlineWebViewSupported` 对齐），入口行改用本地化串。

> 注意：合并服务的功能开关 `/api/app/admin/features` 只对管理员开放（`FeatureController.java:14-16`、`ShiroConfig.java:101`），客户端拿不到 `pan` 开关。若产品要「服务端关闭网盘时客户端隐藏入口」，需要新增一个客户端可见的轻量 flag；否则以「应用服务地址已配置」为门控即可（与 Flutter 一致）。这一点建议在实现前确认。

### 4.3 要加的入口
- 「发现」页：保留，门控换成 `isPanConfigured`。
- 「我」页 `WFCMeTableViewController.m`：新增「云盘」（跳 `WFCUPanViewController` viewMode=All）与「在线文档」（跳新增 `WFCUPanDocsViewController`）；参照 `me_tab.dart:83-104`。
- 资料页「他/她的网盘」：保留，门控换 `isPanConfigured`。
- 网盘首页 `WFCUPanViewController`：加一行/一个按钮进入「在线文档」首页（对齐 Flutter `PanHomeScreen`）。

---

## 5. WebView + JS 桥

### 5.1 现有能力
- 宿主：`wfuikit/WFChatUIKit/CommonVC/WFCUBrowserViewController.m`，在 `viewDidLoad` 用 `DWKWebView`（`dsbridge`，`Vendor/dsbridge/`），`[self.webView addJavascriptObject:self namespace:nil]`（`:43`），实现 `WKNavigationDelegate`（自签证书，`:214-217`）。
- 已实现桥方法（dsbridge 约定：异步方法签名 `- (void)xxx:(NSDictionary*)message completion:(JSCallback)completionHandler`；同步方法直接返回对象）：
  - `getAuthCode:completion:`（`:117-130`）——已按 `message[@"appId"]/message[@"appType"]` 取码，host 用 `self.webView.URL.host`，**正是文档页 `getAuthCode({appId:'admin',appType:2})` 需要**。
  - `openUrl:`（`:132`）、`close:completion:`（`:140`）、`config:completion:`（`:145`）、`toast:`（`:201`）、`chooseContacts:completion:`（`:167`）。
- dsbridge 自带 `_dsb.hasNativeMethod`（H5 `Bridge.has` 依赖），无需自己实现。
- `JSCallback` 签名 `(int code, id result, BOOL complete)`（`Vendor/dsbridge/DWKWebView.h`）：多次回调必须传 `complete:NO`。

### 5.2 承载在线文档页
文档 H5：`wf-app-server/app-pan/src/main/resources/doc-web/{app.js,index.html,open.html,licenses.html}`，公网路径 `{应用服务根}/doc/`：

- 文档首页：`{root}/doc/`
- 编辑/只读打开：`{root}/doc/open?fileId=N`（网盘文件）或 `{root}/doc/open?url=<encode>&name=<encode>`（按链接只读，文件不在网盘）
- 许可页：`{root}/doc/licenses.html`

用 `WFCUBrowserViewController` push 上述 URL 即可。页面内部 `Api` 相对路径解析为 `{root}/api/pan/`（`app.js:6-7`），登录走 `getAuthCode` → `POST /doc/session`（`app.js:80-97`）。

### 5.3 需要补齐/修正的桥方法

| 方法 | 文档页调用处 | iOS 实现要点 |
|---|---|---|
| `setPageHeader` | `app.js:71-76`、`open.html:43,65` | 新异步方法。解析 `{title,subtitle,actions}`，写进导航栏；按钮点击用 `completionHandler(0, actionId, NO)` **多次回调**（`complete:NO` 保持回调存活）。老客户端没有此方法时页面自己画标题栏，可选实现。注意 `app.js:148` 的 `share` 动作在 Flutter 被客户端接管（`js_api.dart:145-175`），iOS 可先原样回传 id，由页面处理分享。 |
| `downloadFile` | `app.js:200-203`、`open.html:174,290` | 同步或异步皆可；用 `[[UIApplication sharedApplication] openURL:]` 交给系统浏览器/下载，**不要**在应用内 WebView 打开（会白屏）。Flutter 对应 `JsApi.downloadFile`（`js_api.dart:121-137`）。 |
| `chooseContacts` | `open.html:249`（分享加成员） | 已存在，但 `:168-172` 要求 `configDict[host]` 为真（必须先调 `config:`）。文档页**不会**调 `config:`，会导致误判「未配置」→ 需放宽：对文档页（`isPanDocUrl`）跳过 `configDict` 校验，或直接去掉该校验。返回格式按 `open.html:254-255` 是 JSON 串 `[{uid,name,displayName,portrait}]`，现有实现返回 `[{uid,displayName}]`，建议补齐 `name/portrait`。 |
| `chooseGroup` | `open.html:205,258-263` | **新增**。回 `{code:0,data:[{gid,name,portrait}]}`（`js_api.dart:260-302`）。iOS 无现成群多选 VC：可新建一个 `WFCUPanGroupPickerViewController`，用 `[[WFCCIMService sharedWFCIMService] getMyGroups:]`（`WFCChatClient/.../WFCCIMService.h:2752`）拿群 id，再 `getGroupInfos:`（`:2182`）取名/头像，多选后回调。不存在此方法时 H5 会把「添加群」置灰（`open.html:205-211`），所以可选但建议实现。 |
| `getAuthCode` | `app.js:82` | 复用现有实现，无需改。 |

### 5.4 移动端在线文档限制（必须在实现和验收时体现）

源自服务端 `DocsConfig.java:34-36` 与 H5 `open.html`：

1. **社区版 ONLYOFFICE 手机网页端不能编辑**，默认 `docs.mobile_edit=false`：页面在移动端强制只读（`viewReason=mobile`），不显示「转换后编辑」（`open.html:120`）。客户端**手机端新建文档入口应默认隐藏**，仅在 `POST /api/pan/docs/options` 返回 `mobileEdit=true` 时开放（对齐 `pan_docs_screen.dart:49,83`）。
2. 平台参数：页面用 UA 判断移动端（`app.js:174-178`），也可显式 `?platform=mobile`。建议 iOS 打开文档页时统一追加 `platform=mobile`（或在 `editor-config` 里带），保证与 Flutter 一致。
3. **WKWebView 不支持 Service Worker**（`open.html:158-159` 注释）：编辑器 SDK/中文字体不能缓存，首次打开会明显偏慢；这是已知取舍，不要当 bug。
4. 只读按链接打开（`url/name`）时无 fileId，页面不给转换/历史版本/下载/分享（`open.html:117-123`）；要编辑必须先「保存到我的网盘」拿 fileId。
5. 文档页依赖内置 WebView 的桥才能登录，**系统浏览器打不开**（`app.js:109-111`、`open.html:148`）。因此聊天里的文档链接卡片要用内置网页打开，不能 `openURL` 到 Safari。

---

## 6. 文件消息动作

### 6.1 目标行为（对齐 Flutter）
在 `WFCUMessageListViewController.m` 的文件消息菜单（`:4051-4058` 处）补齐为：

1. **在线预览**（仅当 `panConfigured && isOnlineDocName(name)`）：只读打开 `{root}/doc/open?url=<encode(remoteUrl)>&name=<encode(name)>&platform=mobile`，用 `WFCUBrowserViewController`。
2. **保存到我的网盘**：默认存到「我的私有空间」根目录，`copy:YES`，重名自动加 `(1)`（服务端不允许同名，对齐 `pan_save.dart`/`pan_service.dart:279-298`）。
3. **保存到我的网盘并打开**：保存成功后，文档格式走在线文档编辑页 `docOpenUrl(fileId)`，其余格式取签名地址交系统打开（`pan_save.dart:48-66`）。
4. **下载**：把（带双网重定向/`getAuthorizedMediaUrl` 的）地址交系统。

同时把**点击文件消息**的默认行为改成：文档格式且网盘可用时优先只读在线预览，失败再退回现有 `WFCUBrowserViewController`/系统打开（`utilities.dart:254-283`、`conversation_controller.dart:986-999`）。

### 6.2 要改的点
- 菜单注册：`WFCUMessageListViewController.m:4051-4058` 扩展（新增 `docOnlinePreview`、`panSaveOpen`、`download` 三项对应 `KxMenuItem` + `onMenuXxx:` + `performXxx:`）。
- 选择器列表：`:4223` 的 `@selector(...)` 白名单要加上新方法。
- `performSaveToPan:` `:4583` 重构：
  - 默认空间改「我的私有空间」，失败回退第一个（对齐 `pan_service.dart:236-254`）——或保留 ActionSheet 但增加「保存并打开」。
  - `storageUrl` 用 `getAuthorizedMediaUrl` 的结果，不要直接 `fileContent.remoteUrl`（合并服务的 `docs/view-url` 只信任受信存储前缀，Flutter 用 `MediaUrlRedirector.redirect`）。
  - `md5` 传空由服务端处理即可。
- 文件点击分支：`:3028-3046`、`:3484-3499` 加在线文档优先逻辑。
- 新增工具方法（建议放 `WFCUPanDocUtils`，见 §7）：`+isOnlineDocName:`、`+docViewUrl:name:`、`+docOpenUrl:`、`+isDocUrl:`。

---

## 7. 在线文档功能（首页/新建/最近/共享）

对齐 Flutter `pan_docs_screen.dart`，在 iOS 新增一个文档首页 `WFCUPanDocsViewController`：

- 两个分段/页签：**最近打开**（`POST /api/pan/docs/recent`）与**共享给我**（`POST /api/pan/shares/with-me`）。
- 新建：仅当 `POST /api/pan/docs/options` 的 `mobileEdit==true` 才显示；弹出输入名字（预填「未命名文档」），类型 docx/xlsx/pptx，调 `POST /api/pan/docs/create`，成功后打开编辑页。
- 最近打开：右滑/长按可「移除」（`POST /api/pan/docs/recent/remove`，只删记录不删文件）与「分享」。
- 共享给我：只读展示来源（直接分享/经群）+ 打开。
- 点行：用 `WFCUBrowserViewController` 打开 `docOpenUrl(fileId)`，返回后刷新列表（对齐 `pan_docs_screen.dart:127-150`）。
- 底部或右上角给「开源许可」入口 → `/doc/licenses.html`（AGPL 要求，`pan_service.dart:53-54`）。

分享面板（网盘文件菜单与文档页都用）：新增 `WFCUPanShareViewController`，调 `shares/list|add|remove`，选人用 `chooseContacts` 同款联系人选择器，选群用新建的群选择器；「发到会话」时先 `shares/add` 授权单聊对象/群，再把在线文档链接卡片当消息发出（对齐 `pan_service.dart:629-649`、`js_api.dart:_shareDoc`）。纯 H5 的分享面板也由 `chooseContacts/chooseGroup` 支撑。

---

## 8. 逐文件实现清单

### 8.1 新增文件

| 文件 | 内容 |
|---|---|
| `wfchat/WildFireChat/PanService/PanAuthHelper.{h,m}` | 共享 `authCode→authToken`：`getAuthCode:@"admin" type:2 host:IM_SERVER_HOST` → `POST {appRoot}/api/auth/login` body `{authCode}` → 读响应头 `authToken` 存 NSUserDefaults（键建议 `WFC_PANSERVER_AUTH_TOKEN` 或与应用服务共用）；提供 `ensureAuthToken:`、`refreshAuthToken:`；失效重登重试一次。做成通用类，便于接龙/投票后续复用。 |
| `wfuikit/WFChatUIKit/Pan/WFCUPanDocUtils.{h,m}` | `isOnlineDocName:`、`docViewUrl:name:`、`docOpenUrl:`、`isDocUrl:`、`isPanConfigured`、`mimeTypeForFileName:`（可迁移现有实现）。 |
| `wfuikit/WFChatUIKit/Pan/WFCUPanDocsViewController.{h,m}` | 在线文档首页（最近/共享/新建/许可）。 |
| `wfuikit/WFChatUIKit/Pan/WFCUPanShareViewController.{h,m}` | 分享管理（列表/加/改/移除；发到会话）。 |
| `wfuikit/WFChatUIKit/Pan/WFCUPanShare.{h,m}` | 分享模型（`id,targetType,targetId,targetName,permission,createdByName,createdAt`）。 |
| `wfuikit/WFChatUIKit/Pan/WFCUPanGroupPickerViewController.{h,m}` | JS 桥 `chooseGroup` 的群多选 UI（`getMyGroups:` + `getGroupInfos:`）。 |
| `wfuikit/WFChatUIKit/Pan/WFCUPanDocViewController.{h,m}`（可选） | 若不想把文档逻辑塞进通用浏览器，可用薄封装承载 `setPageHeader` 状态。 |
| `wfuikit/WFChatUIKit/Pan/WFCUPanRecentDoc.{h,m}`（可选） | `docs/recent` 与 `shares/with-me` 的行模型。 |

### 8.2 修改文件

| 文件 | 改动 |
|---|---|
| `wfchat/WildFireChat/WFCConfig.{h,m}` | `PAN_SERVER_ADDRESS`/`PAN_SERVER_BACKUP_ADDRESS` 改为由应用服务地址派生：`APP_SERVER_ADDRESS + "/api/pan"`；新增 `WFCGetDocServerAddress()`（应用服务根，用于 `/doc/`、`/api/auth/login`）。保留 `WFCGetPanServerAddress()` 双网选择语义。 |
| `wfchat/WildFireChat/PanService/PanService.{h,m}` | ① 路径 `/api/v1/**`→`/api/pan/**`（基址已含 `/api/pan` 则为 `/spaces/list` 等）；② `postWithAuth:` 改为用 `PanAuthHelper` 取 `authToken` 并设 header `authToken`；③ 响应取值 `dict[@"data"]`→`dict[@"result"]`，并处理失效码重试；④ 新增 shares/docs/versions/permission 方法；⑤ 新增 `+isPanConfigured`。 |
| `wfuikit/WFChatUIKit/Pan/WFCUPanService.h` | 协议新增：`isAvailable`、`getShares/setShare/removeShare/getSharedWithMe`、`createDoc/getRecentDocs/removeRecentDoc/isMobileDocEditEnabled`、`getFileVersions/restoreVersion`、`getPermission`（保持向后兼容，旧方法签名不变）。 |
| `wfuikit/WFChatUIKit/Pan/WFCUPanFile.{h,m}` | `fileId` 三级回退（`fileId/id`）、`spaceId` 回退；`type` 兼容 `"FOLDER"` 与数字 `1`；新增 `extension/canOpenOnline/isFolder/sizeText`。 |
| `wfuikit/WFChatUIKit/Pan/WFCUPanSpace.{h,m}` | `spaceId` 回退；`spaceType` 兼容字符串 `spaceType`/`type` 与数字；新增 `canWrite`。 |
| `wfuikit/WFChatUIKit/Pan/WFCUPanViewController.m` | 门控换 `isPanConfigured`；`onAdd/onUpload` 增加「新建文档」；工具栏/行进入文档首页；todo：`showFilePicker` 目前是占位提示（`:315-320`），顺手接系统文档选择器或指明保留。 |
| `wfuikit/WFChatUIKit/Pan/WFCUPanFileListViewController.m` | 点击文档类型文件→在线文档编辑页；长按/侧滑菜单加「在线文档/下载/分享(shares)」；shares 面板替代当前「转发文件消息」分享；空实现 `showFilePicker`（`:480-485` 已有 UIDocumentPicker）保持一致。 |
| `wfuikit/WFChatUIKit/CommonVC/WFCUBrowserViewController.{h,m}` | 新增 `setPageHeader:completion:`（多次回调）、`downloadFile:`、`chooseGroup:completion:`；修正 `chooseContacts` 的 `configDict` 门控以支持文档页；可选暴露 `isPanDocPage` 以接管 `share` 按钮。 |
| `wfuikit/WFChatUIKit/CommonVC/WFCUProfileTableViewController.m` | 资料页网盘 cell 门控换 `isPanConfigured`。 |
| `wfchat/WildFireChat/Discover/DiscoverViewController.m` | 网盘行门控换 `isPanConfigured`；可选加「在线文档」行。 |
| `wfchat/WildFireChat/Me/WFCMeTableViewController.m` | 新增「云盘」「在线文档」两行（门控 `isPanConfigured`，文档再要求内置 WebView）。 |
| `wfchat/WildFireChat/AppDelegate.m` | 装配改为 `isPanConfigured`；退出登录时清网盘 `authToken`（参照 `clearAppServiceAuthInfos`/`clearOrgServiceAuthInfos`）。 |
| `wfchat/WildFireChat/MessageList/.../WFCUMessageListViewController.m` | 文件消息菜单/点击行为按 §6 修改；白名单（`:4223`）加新 selector。 |
| `wfuikit/WFChatUIKit/WFChatUIKit.h` | 导出新增头文件（`WFCUPanDocsViewController.h`、`WFCUPanShareViewController.h`、`WFCUPanDocUtils.h` 等）。 |
| `wfuikit/WFChatUIKit/Resources/{zh-Hans,zh-Hant,en}.lproj/wfc.strings` | 新增本地化键：`Pan/CloudDrive`、`OnlineDocs`、`DocOnlinePreview`、`SaveToPan`、`SaveToPanAndOpen`、`Download`、`NewDoc`、`RecentDocs`、`SharedWithMe`、`Versions` 等（现有 `WFCString` 用 `table:@"wfc"`，`Predefine.h:50`）。 |
| `wfuikit/WFChatUIKit.xcodeproj/project.pbxproj` | 把新增 `.h/.m` 加到 `Pan` group + `Sources/Headers` build phase（该工程用显式 fileRef，不会自动收目录）。 |

### 8.3 迁移顺序建议

1. 先做**契约层**：`WFCConfig` 地址派生 + `PanAuthHelper` + `PanService` 路径/字段/auth 切换，跑通空间/文件 CRUD。
2. 再做**模型兼容**：`WFCUPanFile`/`WFCUPanSpace` 字段回退。
3. 再做**入口/门控**：「我」页 + 发现页 + 资料页。
4. 再做**在线文档**：`WFCUBrowserViewController` 桥方法 → 文档首页 → 分享/版本。
5. 最后做**消息动作**与本地化。

---

## 9. 构建与验证命令

工作区：`/Users/rain/Workspace/ios-chat/ios-chat.xcworkspace`；主 scheme：`WildFireChat`（另有 `WFChatUIKit`、`WFChatClient`）。
本机 `xcodebuild -version` = Xcode 27.0。工程无 Podfile（依赖为 vendored/子工程）。

```bash
# 0) 列出 scheme / 配置，确认可用
xcodebuild -list -workspace /Users/rain/Workspace/ios-chat/ios-chat.xcworkspace

# 1) 编 WFChatUIKit（网盘 UI 大部分改动在这里，最快暴露编译错）
xcodebuild -workspace /Users/rain/Workspace/ios-chat/ios-chat.xcworkspace \
  -scheme WFChatUIKit \
  -configuration Debug \
  -destination 'generic/platform=iOS Simulator' \
  build

# 2) 编主 App（含 PanService / Discover / Me / AppDelegate / 消息列表改动）
xcodebuild -workspace /Users/rain/Workspace/ios-chat/ios-chat.xcworkspace \
  -scheme WildFireChat \
  -configuration Debug \
  -sdk iphonesimulator \
  -destination 'platform=iOS Simulator,name=iPhone 16' \
  build

# 3) 只做静态编译检查（不跑签名/打包，CI 友好）
xcodebuild -workspace /Users/rain/Workspace/ios-chat/ios-chat.xcworkspace \
  -scheme WildFireChat -configuration Debug \
  -destination 'generic/platform=iOS Simulator' \
  CODE_SIGNING_ALLOWED=NO build
```

> 若 CI 需要指定模拟器机型，先用 `xcrun simctl list devices available` 选一个存在的 `name`。
> 真机编译需要 `-allowProvisioningUpdates` 与有效签名；本计划默认模拟器验证。

### 9.1 功能验收清单（对齐 Flutter）

- [ ] 空间/文件 CRUD 走 `/api/pan/**` 成功，且抓包确认请求头是 `authToken`、响应读 `result`。
- [ ] 手动构造失效 token（改 NSUserDefaults）后，首个请求能自动重登并成功一次。
- [ ] 「我」页云盘/在线文档入口在网盘未配置时不显示。
- [ ] 文档页在应用内 WebView 打开能自动登录（`getAuthCode`→`/doc/session`），标题栏由 `setPageHeader` 绘制。
- [ ] 文档页「下载」走系统（不白屏）。
- [ ] 文档页分享「添加成员/添加群」能返回选择结果（`chooseContacts` 在无 `config:` 时也生效；`chooseGroup` 多选）。
- [ ] 手机上新建文档默认隐藏；`options.mobileEdit=true` 时出现。
- [ ] 文件消息：在线预览（文档格式）、保存到我的网盘、保存并打开、下载 四项可用；非文档格式只有保存/下载。
- [ ] 旧格式编辑页在手机上只读且不出现「转换后编辑」；PC/真机横屏行为与 H5 一致。
- [ ] 后端 `app.feature.pan=false`（接口 404）时客户端有可读错误提示、不崩溃。

---

## 10. 风险与开放问题

1. **spec 缺失**：`client-pan-docs-spec.md` 不存在，本计划以 Flutter + 合并服务源码为准；若后续补上 spec，应逐条复核 §2 映射与 §5.3 桥契约。
2. **单入口地址**：合并后文档页在 `{应用服务根}/doc/`，网盘接口在 `{应用服务根}/api/pan`。iOS 需要同时拿到「应用服务根」（登录/文档页）与「网盘前缀」（业务接口），不要再用独立的 `PAN_SERVER_ADDRESS` 站点根。
3. **客户端可见的功能开关缺失**：`pan` 开关只有管理员接口；若要服务端驱动隐藏入口，需服务端新增客户端可见 flag，或接受「配置了应用服务即显示」。
4. **`chooseContacts` 的历史门控**：现有实现要求先 `config:`，文档页不调 `config:`，必须放宽，否则分享选人静默失败（H5 会提示「选人不可用」）。
5. **ONLYOFFICE 手机限制**是产品级取舍：手机只读、不新建；文档中已列明，避免被当成缺陷。
6. **共享 authToken 的收尾**：接龙/投票目前仍是旧 `authCode`，若本次把 token 键做成通用并复用，需注意它们尚未迁移到 `/api/auth/login`；不要误改其现有行为。
7. **`project.pbxproj` 手工维护**：工程用显式 fileRef，新增文件漏加会导致「找不到符号」；改动后务必跑 §9 的编译。

---

## 附：关键参考位置速查

- Flutter 接口契约：`wf-enterprise-chat/chat/lib/pan/pan_service.dart`
- Flutter 保存并打开：`wf-enterprise-chat/chat/lib/pan/pan_save.dart`
- Flutter 文档首页：`wf-enterprise-chat/chat/lib/pan/pan_docs_screen.dart`
- Flutter 文件消息菜单：`wf-enterprise-chat/chat/lib/conversation/conversation_controller.dart:719-753`
- Flutter 在线打开：`wf-enterprise-chat/chat/lib/utilities.dart:254-283`
- Flutter JS 桥：`wf-enterprise-chat/chat/lib/workspace/js_api.dart`
- 合并服务客户端接口：`wf-app-server/app-pan/src/main/java/cn/wildfirechat/app/pan/controller/client/*`
- 登录换 token：`wf-app-server/app-common/src/main/java/cn/wildfirechat/app/common/security/LoginController.java`
- 文档 H5 与桥：`wf-app-server/app-pan/src/main/resources/doc-web/{app.js,open.html,index.html}`
- 文档配置项（mobile_edit）：`wf-app-server/app-pan/src/main/java/cn/wildfirechat/app/pan/config/DocsConfig.java`
- 反向代理 `/doc/ /dl/`：`wf-app-server/nginx/app-server.conf:137-153`
- iOS 现有网盘服务：`ios-chat/wfchat/WildFireChat/PanService/PanService.m`
- iOS 现有网盘 UI：`ios-chat/wfuikit/WFChatUIKit/Pan/`
- iOS 浏览器 + 桥：`ios-chat/wfuikit/WFChatUIKit/CommonVC/WFCUBrowserViewController.m`
- iOS token 范式参考：`ios-chat/wfchat/WildFireChat/OrgService/OrgService.m`、`.../AppService/AppService.m`
- iOS 文件消息：`ios-chat/wfuikit/WFChatUIKit/MessageList/ViewController/WFCUMessageListViewController.m`
