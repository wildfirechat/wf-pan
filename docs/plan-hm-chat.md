# hm-chat 客户端「网盘(Pan) + 在线文档(Online Docs)」实现计划

> 目标仓库：`/Users/rain/Workspace/hm-chat`（HarmonyOS / ArkTS / ArkUI，野火 IM 鸿蒙客户端）
> 参考实现：`/Users/rain/Workspace/wf-enterprise-chat/chat/lib/pan/**`（Flutter）、`chat/lib/workspace/js_api.dart`、`chat/lib/settings/me_tab.dart`
> 服务端：两代契约并存——`wf-app-server / app-pan`（合并服务，`/api/pan/**` + authToken + `{APP_SERVER}/doc/`，Flutter 参考实现所用）与 `/Users/rain/Workspace/wf-pan/wf-pan-server`（独立服务，`/api/v1/**` + authCode + `…/doc/`）。见 §3.2。
> 说明：`/Users/rain/Workspace/wf-pan/docs/client-pan-docs-spec.md` 当前**不存在**，本计划以 Flutter 参考实现 + 两代服务端源码为准。
> **本文件只描述实现方案，不修改任何代码。**

---

## 0. 结论速览

- 新增一个网盘业务域，代码放在 **`uikit/src/main/ets/pan/`**（不新建 HAR 模块：消息菜单在 `uikit` 内，若下沉成 `pan` 模块会与 `uikit` 形成 `uikit → pan → uikit` 循环依赖；`moment` 是独立模块是因为朋友圈入口可整体下线，网盘入口要和消息菜单耦合）。
- 网络层复用现有 `@ohos.net.http`；把两代服务端差异收敛为 4 个配置（API 基址 / 文档基址 / `authToken|authCode` / 响应 `result|data`），业务代码只写一份（见 §3.2/§3.3）。
- 在线文档编辑器不自己实现，用现有 `WfcWebView` 承载服务端 `doc-web` 页面（`/doc/open?fileId=`、`/doc/open?url=&name=`），补全 DSBridge 协议方法：`getAuthCode / setPageHeader / downloadFile / chooseContacts / chooseGroup / openUrl / close / toast / config / _dsb.hasNativeMethod`。
- 入口放在「我的」页（`chat/.../MeTab.ets`），由 `Config.PAN_SERVER_ADDRESS` 是否配置做 gating；消息长按菜单按「文件消息 + 网盘已配置」追加「在线预览 / 存到网盘 / 存到网盘并打开 / 下载」。

---

## 1. 项目结构、构建命令、ArkTS/ArkUI 版本

### 1.1 仓库结构（HAR 多模块）
```
/Users/rain/Workspace/hm-chat
├── AppScope/app.json5                     # bundleName cn.wildfirechat.messenger.open, versionName 1.3.0
├── build-profile.json5                    # products: compatibleSdkVersion 5.0.0(12), targetSdkVersion 6.0.0(20), arkTSVersion 1.0
├── oh-package.json5                       # modelVersion 5.0.0; 根依赖 @ohos/pinyin4js
├── hvigor/hvigor-config.json5             # modelVersion 5.0.0
├── hvigorfile.ts                          # export { appTasks } from '@ohos/hvigor-ohos-plugin'
├── local.properties                       # nodejs.dir=/Users/rain/nodejs  hwsdk.dir=/Users/rain/Library/Huawei/Sdk
├── client/    @wfc/client    —— IM SDK 封装 + 全局 Config
├── uikit/     @wfc/uikit     —— 全部 IM UI（会话/消息/联系人/我的相关页 + 工作台 webview）
├── moment/    @wfc/moment    —— 朋友圈（动态加载，可整体下线）
├── chat/      @wfc/chat      —— entry 模块：EntryAbility + 主框架 Tab + 登录/我的/设置
└── entry/                     # 空目录，未在 build-profile 注册
```

依赖链（`chat/oh-package.json5`）：`@wfc/chat → { @wfc/client, @wfc/uikit, @wfc/moment, @wfc/ptt(har), @wfc/avenginekit(har) }`；`@wfc/uikit → { @wfc/client, ptt.har, avenginekit.har, @ohos/webrtc }`。

### 1.2 版本与工具链
| 项 | 值 | 位置 |
|---|---|---|
| ArkTS 版本 | **1.0**（非 1.2 静态） | `build-profile.json5` products[0].arkTSVersion |
| compatibleSdkVersion | `5.0.0(12)` | 同上 |
| targetSdkVersion | `6.0.0(20)` | 同上 |
| runtimeOS | `HarmonyOS` | 同上 |
| Web 内核 | ArkWeb（`@ohos.web.webview`） | `WfcWebView.ets` |
| HTTP | `@ohos.net.http`（未使用 rcp；仅 `client/.../proto.min.ets` 内部用 `@kit.RemoteCommunicationKit`） | `uikit/src/main/ets/helper/httpHelper.ets`、`api/appServer.ets` |
| 下载 | `@kit.BasicServicesKit` 的 `request.downloadFile` | `uikit/src/main/ets/helper/mediaHelper.ets:147` |
| 文件选择 | `@ohos.file.picker` 的 `DocumentViewPicker` | `mediaHelper.selectFile()` |
| node | v22.22.0（`/Users/rain/.nvm/versions/node/v22.22.0/bin/node`） | — |

### 1.3 构建 / 校验命令（本机已装 DevEco）
`hvigorw` 与 `ohpm` 由 DevEco 提供，仓库内没有 wrapper：
- ohpm：`/Applications/DevEco-Studio.app/Contents/tools/ohpm/bin/ohpm`
- hvigorw：`/Applications/DevEco-Studio.app/Contents/tools/hvigor/bin/hvigorw`

```bash
cd /Users/rain/Workspace/hm-chat

# 1) 安装依赖（README 明确要求；换 marswrapper.har 后需删 oh_modules 重装）
ohpm install

# 2) 编译 entry 模块（等价 DevEco 「Build Hap(s)」）
/Applications/DevEco-Studio.app/Contents/tools/hvigor/bin/hvigorw \
  --mode module -p product=default -p module=chat@default assembleHap --no-daemon

# 3) 干净重建 / 只做语法与类型检查
hvigorw clean
hvigorw --mode module -p product=default -p module=chat@default assembleHap --no-daemon

# 4) 单测（ohosTest，hypium 1.0.6）
hvigorw --mode module -p module=chat@default test --no-daemon
```
> 说明：`assembleHap` 会把 `chat` 及其依赖的 `@wfc/client/@wfc/uikit` 一起编译，因此新增的 `uikit/.../pan/**` 会在这条命令里被类型检查；不需要单独编译 uikit。签名配置已在 `build-profile.json5` 的 `signingConfigs.default` 中（本机证书），可直接产出可运行 HAP。

---

## 2. 导航与菜单

### 2.1 现有导航体系（要点）
- 应用是 **Navigation + NavPathStack**（不是旧版 `router.pushUrl`）。根栈在 `chat/src/main/ets/pages/MainPage.ets:71`：
  `@Provide('mainNavPathStack') mainNavPathStack: NavPathStack = uikit.mainNavPathStack`。
- 目的地名称/页面表：
  - `chat/src/main/ets/pages/mainNavigationConfig.ets`
    - `APP_NAV_DESTINATION_PREFIX = 'app-'`，`appNavigationDestinations` + `AppPageMap(name)`；
    - `MainPageMap(name)` 按前缀分派：`app-` → AppPageMap，`uikit-` → UIKitPageMap，`moment_` → 动态 builder。
  - `uikit/src/main/ets/pages/uikitNavigationConfig.ets`
    - `UIKIT_NAV_DESTINATION_PREFIX = 'uikit-'`，`uikitNavigationDestinations` + `UIKitPageMap(name)`。
  - 新页面**必须同时**在常量表和 `UIKitPageMap` 里注册，否则 `NavDestination not found`。
- 跳转工具：`uikit/src/main/ets/util/navUtil.ets` 的 `pushOrReplaceDetail(stack, name, params)`、`isWideScreen()`；平板/宽屏走 `SplitTabPane`（每 tab 独立栈，`tabNavRegistry`）、手机走外层栈。
- 页面参数：`mainNavPathStack.getParamByIndex(size()-1) as Record<string, Object>`（见 `WebViewPage.aboutToAppear`）。
- 跨页异步结果：`LocalStorage.getShared()` + 共享 key（如 `PickMultiContactModal.PICK_RESULT = 'pickContactResult'`），在 `.onShown()` 里读取。

### 2.2 主 Tab 结构
`chat/src/main/ets/pages/MainPage.ets:59`：`tabTitles = ['野火','通讯录','工作台','发现','我的']`。
- 手机 `PhoneTabs()`：`Tabs` 底部 5 个（工作台由 `Config.getWorkspaceUrl()` 决定是否插入）。
- 平板 `TabletTabs()`：`SplitTabPane` 分栏。
- 工作台是「H5 工作台」，用 `WorkSpaceTab → WfcWebView + DSBridge`（见第 4 节）。

### 2.3 「我」(me) 页与新增入口
- 页面：`chat/src/main/ets/pages/MeTab.ets`（手机/平板共用；平板由 `SplitTabPane` 包壳）。
- 现有条目用 `OptionItemView`（`uikit/src/main/ets/view/OptionItemView.ets`）：`icon / title / desc / subTitle / showDivider`。
- **新增（仅此一处改动）**：在「文件 / 收藏」这一组 `Column()` 之后，插入受 gating 的网盘 / 在线文档条目：

```ets
// chat/src/main/ets/pages/MeTab.ets（示意）
import Config from '@wfc/client/src/main/ets/config'
import { uikitNavigationDestinations } from '@wfc/uikit/src/main/ets/pages/uikitNavigationConfig'

if (Config.isPanEnabled()) {
    Column() {
        OptionItemView({ icon: $r('app.media.ic_pan_drive'), title: '网盘' })
            .onClick(() => {
                pushOrReplaceDetail(this.mainNavPathStack, uikitNavigationDestinations.PanHomePage)
            })
        OptionItemView({ icon: $r('app.media.ic_pan_doc'), title: '在线文档', showDivider: false })
            .onClick(() => {
                pushOrReplaceDetail(this.mainNavPathStack, uikitNavigationDestinations.PanDocsPage)
            })
    }
    .margin({ top: 12 })
    .backgroundColor($r('app.color.wf_background'))
}
```
- gating 语义（与 Flutter `me_tab.dart:78-102` 对齐）：
  - **网盘**：`Config.isPanEnabled()`（`getPanApiBase()` 与 `getPanDocBase()` 都能派生）为真即显示。
  - **在线文档**：Flutter 里额外要求 `isInlineWebViewSupported`（ohos 联邦实现缺失时为 false）。鸿蒙 ArkWeb **始终可用**，因此等价于「网盘地址已配置」；是否真能编辑由服务端 `docs.enabled` / `docs.mobile_edit` 决定（H5 内自行降级），客户端不额外判断。
- 图标注：新增 `uikit/src/main/resources/base/media/ic_pan_drive.svg`、`ic_pan_doc.svg`（dark 目录按需同步），或先复用现有 `ic_settings_file`。**不要**直接 `Image.asset` 之类 Flutter API。

### 2.4 需要新增的 NavDestination
在 `uikitNavigationDestinations` 追加：
```
PanHomePage            = 'uikit-panHomePage'
PanFileListPage        = 'uikit-panFileListPage'
PanDocsPage            = 'uikit-panDocsPage'
PanDocWebPage          = 'uikit-panDocWebPage'      // 承载 ONLYOFFICE 文档 H5
PanSharePage           = 'uikit-panSharePage'
PanDestinationPickPage = 'uikit-panDestinationPickPage'
PanFileVersionsPage    = 'uikit-panFileVersionsPage' // 可选：历史版本
PickMultiGroupPage     = 'uikit-pickMultiGroupPage'  // 见 4.4
```
并在 `UIKitPageMap()` 中逐个 `else if` 分派新组件（照抄现有写法）。

---

## 3. 网络与鉴权

### 3.1 现有 HTTP 栈
- 通用工具：`uikit/src/main/ets/helper/httpHelper.ets`（`post/get`，callback 风格，返回原始 `data.result`，**不解析业务 code**）。
- 业务服务类（推荐照抄的范式）：
  - `uikit/src/main/ets/api/pollService.ets:postWithAuth`：`wfc.getAuthCode('poll', 2, host)` → header `authCode` + `Content-Type: application/json`，校验 `result.code === 0`，取 `result.data`。
  - `uikit/src/main/ets/api/collectionService.ets:88`、`api/archiveServer.ets:203` 同型。
  - `uikit/src/main/ets/api/appServer.ets`：**authToken 模式**（`_post` 带 `authToken` header，响应头 `authtoken` 回写并落 `wfcstore` 的 `authToken:<host>-<port>`）。网盘的 `merged` 形态要复用这套换 token/重登逻辑，`authCode` 形态参考 `pollService`。
- 双网选择：`client/src/main/ets/config.ets:selectServer(main, backup)`（连接主/备网自动切换）；`Config.urlRedirect` 只处理媒体前缀重定向。
- 地址解析：`uikit/src/main/ets/api/Util.ets:parseHostAndPort`。
- `wfc.getAuthCode(appId, appType, host, successCB, failCB)` 定义在 `client/src/main/ets/wfc/client/wfc.ets:2978`。

### 3.2 两代服务端契约（**实现前必须先确认部署形态**）
仓库里同时存在两代网盘后端，REST 子路径完全一样，只有「前缀 / 鉴权 / 响应字段 / 文档页根」不同。Flutter 参考实现对接的是**合并服务**，本仓库 `wf-pan` 是**独立服务**：

| 维度 | 合并服务 `wf-app-server` 的 `app-pan`（Flutter 参考实现） | 独立 `wf-pan-server`（`/Users/rain/Workspace/wf-pan`） |
|---|---|---|
| 业务前缀 | `POST {APP_SERVER}/api/pan/**` | `POST {网盘入口}/api/v1/**` |
| 鉴权 | `authCode` 换 `authToken`：`POST {APP_SERVER}/api/auth/login` body `{authCode}`，token 在响应头 `authToken`，之后每请求带 header `authToken` | **每请求**带 header `authCode`（IM application authCode） |
| 鉴权失败 | 401/403 或 body `code==13`（没有登录）→ 重登一次 | 401 `code=1001`（缺）/`1002`（无效） |
| 响应体 | `{code,message,result}` | `{code,message,data}` |
| 文档 H5 | `{APP_SERVER}/doc/open`；`POST {APP_SERVER}/doc/session` body `{authCode}` 换 token（存 `sessionStorage`） | `{网盘入口}/doc/open`；`POST {网盘入口}/doc/session` 换 `PAN_WS` Cookie，之后页面内接口用 Cookie + `X-Pan-Web: 1` |
| 子路径 | `/spaces/list`、`/spaces/my`、`/spaces/files`、`/files/*`、`/shares/*`、`/docs/*`、`/versions/*` | 同左（一致） |
| 下载 | `/files/url` → 签名 `storageUrl`（≈600s，支持 Range），再 GET | 同 |
| 上传 | 无客户端上传接口：先用 IM 媒体通道上传，再 `POST /files` 注册 | 同 |

**结论**：把差异收敛成 4 个配置（API 基址 / 文档基址 / 鉴权模式 / 响应字段名），业务接口与模型代码只写一份。默认取 `merged`（与 Flutter 参考实现一致，也是当前上游方向）；独立 `wf-pan-server` 作为可切换形态。

鉴权细节（两种都要实现，代码量很小）：
- **authToken（merged）**：`wfc.getAuthCode('admin', 2, host)` → `POST {appServerRoot}/api/auth/login` `{authCode}` → 读响应头 `authToken`（大小写两种都试），按 `host:port` 缓存到 `wfcstore`（`panAuthToken:<host>-<port>`）；后续请求 header `authToken`。遇 HTTP 401/403 或 body `code==13` 清缓存重登并重试 1 次（`code==14` 无权限不重试）。
- **authCode（standalone）**：每次请求前 `wfc.getAuthCode('admin', 2, panHost)`，header `authCode`；服务端有 5 分钟 authCode 缓存，无需客户端缓存。

### 3.3 Config 新增（`client/src/main/ets/config.ets`）
紧挨 `APP_SERVER` / `ASR_SERVER_URL` 一带追加：

```ets
// 网盘部署形态：
//  'merged'     = 网盘并入 wf-app-server，API 在 {APP_SERVER}/api/pan，文档页在 {APP_SERVER}/doc/，鉴权 authToken
//  'standalone' = 独立 wf-pan-server，API 在 {PAN_SERVER_ADDRESS}/api/v1，文档页在 {PAN_SERVER_ADDRESS}/doc/，鉴权 authCode
static PAN_SERVER_KIND: string = 'merged';   // 'merged' | 'standalone'

// 独立部署时的网盘入口根地址（含 nginx 前缀，如 'https://pan.example.com/pan'）；
// 合并部署时保持 null。不需要网盘功能时，把 KIND 对应的来源置空即可（见 isPanEnabled）。
static PAN_SERVER_ADDRESS: string | null = null;
static PAN_SERVER_BACKUP_ADDRESS: string | null = null;

static getPanServerAddress(): string | null {
    return Config.selectServer(Config.PAN_SERVER_ADDRESS, Config.PAN_SERVER_BACKUP_ADDRESS);
}

// 业务 API 基址（已含版本段，之后直接拼 /spaces/list、/files/... ）
static getPanApiBase(): string | null {
    if (Config.PAN_SERVER_KIND === 'standalone') {
        let root = Config.getPanServerAddress();
        if (!root) { return null; }
        return (root.endsWith('/') ? root.substring(0, root.length - 1) : root) + '/api/v1';
    }
    let app = Config.getAppServer();
    return app ? (app.endsWith('/') ? app.substring(0, app.length - 1) : app) + '/api/pan' : null;
}

// 在线文档 H5 根地址（以 / 结尾）。合并部署挂在应用服务根下；独立部署挂在网盘入口下。
static getPanDocBase(): string | null {
    let root: string | null = null;
    if (Config.PAN_SERVER_KIND === 'standalone') {
        root = Config.getPanServerAddress();
    } else {
        root = Config.getAppServer();
    }
    if (!root) { return null; }
    return (root.endsWith('/') ? root.substring(0, root.length - 1) : root) + '/doc/';
}

// 'authToken'（merged）| 'authCode'（standalone）
static getPanAuthMode(): string {
    return Config.PAN_SERVER_KIND === 'standalone' ? 'authCode' : 'authToken';
}
static isPanEnabled(): boolean {
    return !!Config.getPanApiBase() && !!Config.getPanDocBase();
}
```
> 双网：`getAppServer()`（merged）与 `getPanServerAddress()`（standalone）都走 `Config.selectServer(主, 备)`，主备网切换即时生效，API 与文档地址同步切换。

### 3.4 网盘服务类（新增 `uikit/src/main/ets/pan/panService.ets`）
`postWithAuth(path, params)` 统一处理「取 authCode / 换 authToken / 拼 header / 解析响应」；`baseUrl = Config.getPanApiBase()`；响应体 `result` 与 `data` 都存在时优先 `result`（兼容两代）。方法清单（路径相对 `{panApiBase}`，两代通用）：

| 方法 | HTTP | 路径 | body | 返回 |
|---|---|---|---|---|
| `getSpaces()` | POST | `/spaces/list` | `{}` | `PanSpace[]` |
| `getMySpaces()` | POST | `/spaces/my` | `{}` | `PanSpace[]` |
| `getUserPublicSpace(uid)` | POST | `/spaces/user/public` | `{targetUserId}` | `PanSpace` |
| `getSpaceFiles(spaceId,parentId=0)` | POST | `/spaces/files` | `{spaceId,parentId}` | `PanFile[]` |
| `createFolder(spaceId,name,parentId=0)` | POST | `/files/folder` | `{spaceId,parentId,name}` | `PanFile` |
| `createFile({spaceId,name,size,storageUrl,parentId,mimeType,md5,copy})` | POST | `/files` | 同上 | `PanFile` |
| `saveFileToMyPan({name,size,storageUrl,mimeType})` | — | 组合 | 取私有空间 + 去重名 + `copy:true` | `PanFile` |
| `deleteFile(fileId)` | POST | `/files/delete` | `{fileId}` | nil |
| `renameFile(fileId,newName)` | POST | `/files/rename` | `{fileId,newName}` | `PanFile` |
| `moveFile(fileId,targetSpaceId,targetParentId)` | POST | `/files/move` | 同上 | `PanFile` |
| `copyFile(fileId,targetSpaceId,targetParentId)` | POST | `/files/copy` | 同上 | `PanFile` |
| `getFileDownloadUrl(fileId,versionNo?)` | POST | `/files/url` | `{fileId,versionNo?}` | `{storageUrl,...}` |
| `checkSpaceWrite(spaceId)` | POST | `/files/check-permission` | `{spaceId}` | `boolean` |
| `getShares(fileId)` | POST | `/shares/list` | `{fileId}` | `PanShare[]` |
| `setShare(fileId,isGroup,targetId,canEdit)` | POST | `/shares/add` | `{fileId,targetType,targetId,permission}` | `PanShare` |
| `removeShare(shareId)` | POST | `/shares/remove` | `{shareId}` | nil |
| `getSharedWithMe()` | POST | `/shares/with-me` | `{}` | `SharedFileVO[]` |
| `getRecentDocs()` | POST | `/docs/recent` | `{}` | `RecentDocVO[]` |
| `removeRecentDoc(fileId)` | POST | `/docs/recent/remove` | `{fileId}` | nil |
| `createDoc(type,name?)` | POST | `/docs/create` | `{type,name?}` | `PanFile` |
| `isMobileDocEditEnabled()` | POST | `/docs/options` | `{}` | `boolean` |
| `getVersions(fileId)` | POST | `/versions/list` | `{fileId}` | `VersionVO[]` |
| `restoreVersion(fileId,versionNo)` | POST | `/versions/restore` | 同上 | `VersionVO` |

附工具：
```ets
static isOnlineDocName(name: string): boolean   // 扩展名白名单，见 4.5
static docOpenUrl(fileId: number, platform: string): string
// `${Config.getPanDocBase()}open?fileId=${fileId}&platform=${platform}`（platform 由调用方按 isWideScreen() 传 'pc'/'mobile'）
static docViewUrl(url: string, name: string | undefined, platform: string): string
// `${docBase}open?url=${encodeURIComponent(url)}&name=${encodeURIComponent(name)}&platform=${platform}`
static isDocUrl(url: string): boolean           // 以 docBase 开头（主/备都认）
```
- `platform` 说明：Flutter 参考实现的 URL 里**没有** `platform`，由页面按 UA 判 `mobile/pc`；独立 `wf-pan-server` 的 `open.html` 会优先读 `?platform=`。两端都兼容：拼上 `platform`（HarmonyOS UA 含 `HarmonyOS/OpenHarmony/Mobile`，即使不拼也会落到 mobile），宽屏设备按 `isWideScreen()` 传 `pc`（服务端 `docs.mobile_edit=false` 时手机会被强制只读）。
- 模型类（可同文件或 `panModels.ets`）：`PanSpace`、`PanFile`、`PanDocEntry`、`PanShare`、`SharedFileVO`、`VersionVO`，全部从 `Record<string, Object>` 手工解析（ArkTS 无反射/JSON 自动映射；参考 `poll`/`collection` 的 `fromJson`）。
  - **主键兼容**：`PanSpace.spaceId = json['spaceId'] ?? json['id']`；`PanFile.fileId = json['fileId'] ?? json['id']`（服务端 `SpaceVO/FileVO` 主键字段叫 `id`）。
  - `PanFile.type` 兼容字符串 `'FILE'/'FOLDER'` 与旧数值 `0/1`；`PanSpace.spaceType` 兼容字符串枚举与 int。
  - `PanFile.canOpenOnline = !isFolder && PanService.isOnlineDocName(name)`；`PanSpace.canWrite = spaceType !== 'USER_PUBLIC' || ownerId === wfc.getUserId()`。
- 错误类：新增 `PanError extends Error { code: number }`（对标 `api/appServerError.ets`），`postWithAuth` 在 `code !== 0` 时抛出；本地错误码 `-1`（配置/网络/空数据）、`-2`（上传已取消）。

### 3.5 上传/下载在鉴权上的差异
- 上传：**没有**网盘上传接口；先走 IM 媒体通道（`wfc.uploadMediaFile` / `getUploadMediaUrl`），拿到 `remoteUrl` 后再 `POST {panApiBase}/files` 注册；两代服务端一致。
- 下载：`POST /files/url` 返回的签名 `storageUrl` 直接 GET（带 Range），不需要任何 auth header；两代一致。签名有效期独立服务端 600s、合并服务端约 10min，**不可持久化**。
- 因此网盘业务请求数量不多（列表/增删改/文档最近/分享），authToken 重登一次与 authCode 每请求取码的开销都可接受。

---

## 4. Web 组件 + JS 桥

### 4.1 现有 Web / 桥
- 组件：`uikit/src/main/ets/pages/workspace/WfcWebView.ets`
  - `Web({src, controller})`，开启 `javaScriptAccess`、`domStorageAccess`、`darkMode`、`metaViewport`、`textZoomRatio`；
  - `.onControllerAttached()` 里 `setCustomUserAgent(Config.buildWebUserAgent(...))`（**关键**：UA 追加 `WF-DSBridge`，doc-web 的 `app.js` 靠 `window._dsbridge || UA` 判定「在客户端内」）；
  - `.javaScriptProxy({ object: this.dsBridge, name: '_dsbridge', methodList: ['call'], controller })`。
- 桥协议：DSBridge（同步用返回值，异步用 `_dscbstub` 回调名）。现有实现：
  - `uikit/src/main/ets/pages/workspace/dsBridge.ets`（方法：`toast/getAuthCode/config/openUrl/close/chooseContacts`；`_callbackJs` 拼 `window[cb]({code,data})`，`complete=true` 时 `delete window[cb]`）；
  - `uikit/src/main/ets/pages/misc/WebViewPage.ets`（NavDestination；`onShown()` 读 `LocalStorage` 的 `pickContactResult` 回调 `onPickContact`）。
- 宿主页：`uikit/src/main/ets/pages/WorkSpaceTab.ets`。

### 4.2 服务端文档页对桥的要求（来自 `wf-pan-server/.../doc-web/app.js` + `open.html`）
| 方法 | 同步/异步 | 入参 | 客户端行为 | 返回 |
|---|---|---|---|---|
| `getAuthCode` | 异步 | `{data:{appId:'admin',appType:2}, _dscbstub}` | `wfc.getAuthCode('admin',2,docUrlHost,ok,fail)` | `{code:0,data:authCode}` / `{code:err}` |
| `setPageHeader` | 异步+多次回调 | `{data:{title?,subtitle?,actions:[{id,text,icon,primary}]}, _dscbstub}` | 把标题栏交给宿主画；点按钮时**直接** `window[cb](buttonId)`（**不能 delete**，用 `setProgressData` 语义） | 页面按 id 触发 |
| `downloadFile` | 同步 | `{data:{url}}` 或字符串 | 下载并交给系统打开 | `{}` |
| `chooseContacts` | 异步 | `{data:{}, _dscbstub}` | 打开选人页，选完回 `code:0,data:JSON.stringify([{uid,name,displayName,portrait}])`；取消/失败回 `-1` | 同上 |
| `chooseGroup` | 异步 | 同上 | 打开选群页，回 `[{gid,name,portrait}]`（`gid` 也可用 `target`）；取消 `-1` | 同上 |
| `openUrl` | 同步 | 字符串或 `{url,name}` | 文档地址→`PanDocWebPage`；普通 http→`WebViewPage`；非 http→`startAbility(viewData)` | `{}` |
| `close` | 异步/同步 | `{_dscbstub}` | `navStack.pop(true)` | `{code:0}` |
| `toast` | 同步 | 字符串 | `showToast` | `{}` |
| `config` | 同步 | `{appId,appType,timestamp,nonceStr,signature}` | `wfc.configApplication(...)`，成功回调 JS `ready`，失败 `error` | `{}` |
| `_dsb.hasNativeMethod` | 同步 | `{data:{name,type}}` | 返回该桥方法是否实现 | `{code:0,data:boolean}` |

关键差异点（现实现不满足）：
1. `app.js` 用 `Bridge.has('setPageHeader')`/`Bridge.has('downloadFile')`/`Bridge.has('chooseGroup')` 探测能力，**必须**支持同步的 `_dsb.hasNativeMethod`。
2. `setPageHeader` 是**常驻回调**（`Bridge.listen`），回调参数是**裸的按钮 id**，且不能被 `delete`。
3. `chooseContacts/chooseGroup` 的 `data` 必须是 **JSON 字符串**（Flutter 用 `json.encode`；HarmonyOS 需 `JSON.stringify`），而现有 `DSBridge.onPickContact` 直接把 `UserInfo[]` 对象塞给 JS。
4. 现有桥用 `url.URL.parseURL(this.webUrl).hostname` 取 host 是对的；doc 页请沿用。

### 4.3 新增桥：`uikit/src/main/ets/pan/panDocBridge.ets`
一个独立类（避免动工作台桥的既有行为），构造：
```ets
constructor(docUrl: string, controller: WebviewController, stack: NavPathStack,
            onHeader?: (h: PanPageHeader) => void,
            onHeaderAction?: (id: string) => void)
```
- `call(method: string, arg: string): string` 同步分发；`_dsb.hasNativeMethod`、`openUrl`、`downloadFile`、`toast`、`config`、`close` 返回 JSON 字符串；异步方法返回 `'{"code":-1}'`（因为 `has()` 为 true，页面不会误判）。
- `_callbackJs(cb, code, data, complete)`：`runJavaScript(window[cb]({code,data}))`，`complete=false` 用于 `setPageHeader`。
- `_callbackRaw(cb, arg)`：`runJavaScript(`${cb}(${JSON.stringify(arg)})`)`，只给 `setPageHeader` 用。
- **选人/选群的跨页回传**：`chooseContacts` 记下 `pendingCb`，`pushPathByName(uikitNavigationDestinations.PickMultiContactPage)`；`PanDocWebPage.onShown()` 读 `LocalStorage` 的 `PickMultiContactModal.PICK_RESULT` → `onPickContact(users)`；若本次会话没有结果（取消）则回 `-1`（用 `picking` 标志区分首次 onShown）。
- `chooseGroup` 依赖新的多选群页（4.4）。
- `downloadFile`：调 `panFileService.downloadToCache(url)`（见第 6 节）后 `filePreview.openPreview`，失败 `showToast('无法打开')`。
- `setPageHeader`：解析 `PanPageHeader`，交给宿主 `onHeader`；宿主渲染标题栏后，用户点按钮回调 `onHeaderAction(id)`，桥再 `_callbackRaw(cb, id)`。

### 4.4 选人 / 选群页
- 选人：**直接复用** `uikit/src/main/ets/pages/picker/PickMultiContactPage.ets` + `PickMultiContactModal.ets`（已支持多选、返回 `UserInfo[]`、写 `LocalStorage['pickContactResult']`）。
- 选群：现有只有单选 `PickFavGroupPage`/`PickFavGroupModal`。新增
  - `uikit/src/main/ets/pages/picker/PickMultiGroupPage.ets`
  - `uikit/src/main/ets/pages/picker/PickMultiGroupModal.ets`
  仿 `PickMultiContactModal` 的勾选 + `PICK_RESULT='pickGroupResult'`，返回 `GroupInfo[]`，注册 `uikitNavigationDestinations.PickMultiGroupPage`。
  （降级方案：先用 `PickFavGroupPage` 单选包成单元素数组，文档页「添加群」仍可用。）

### 4.5 文档页宿主：`uikit/src/main/ets/pan/panDocWebPage.ets`
```
NavDestination {
  WfcWebView({ url: this.url, controller: this.controller, dsBridge: this.bridge })
}
.title(this.headerBuilder())            // 有 header 时画自定义标题：文件名 + 只读说明 + 按钮
.hideTitleBar(this.header == null)
.onShown(() => this.onShown())          // 回收选人/选群结果
```
- `aboutToAppear`：从 `mainNavPathStack` 取 `{fileId?, url?, name?}`，拼 URL：
  - 网盘文件：`PanService.docOpenUrl(fileId)`
  - 链接只读：`PanService.docViewUrl(url, name)`
- `headerBuilder()` 渲染 `title/subtitle/actions`，按钮 `onClick` → `bridge.onHeaderAction(action.id)`。
- 页面返回（`onBackPressed` 或关闭按钮）时 `navStack.pop(true)`。

### 4.6 文档链接卡片的去向
`uikit/src/main/ets/pages/conversation/message/LinkMessageContentView.ets:doOpenLink` 目前一律 `pushPathByName(WebViewPage)`。修改为：若 `PanService.isDocUrl(url)` → `pushPathByName(PanDocWebPage, {url})`，否则保持 `WebViewPage`（因为文档页必须走带桥的宿主）。分享卡片（第 5.4 节）生成的就是 doc URL。

### 4.7 扩展名白名单（与服务端 `DocsService` 对齐）
把 `wf-pan-server` 的四组扩展名移植为常量：WORD / CELL / SLIDE / PDF（完整列表见服务端报告 §5），`isOnlineDocName()` 只做扩展名判断；`EDITABLE={docx,docm,xlsx,xlsm,pptx,pptm}` 仅用于本地提示，真正权限由服务端 `editor-config` 的 `config.editorConfig.mode` 决定。

---

## 5. 文件消息渲染 / 长按操作

### 5.1 现状
- 消息体渲染：`uikit/src/main/ets/pages/conversation/message/NormalMessageContentView.ets`
  - `build()` 按 `messageContent.type` 分派；`File` → `FileMessageContentView({ message: $message })`（第 148-149 行）。
  - **长按菜单统一挂在 NormalMessageContentView 最外层**：`.bindContextMenu(this.MessageContextMenuBuilder, ResponseType.LongPress, {...})`（第 203 行）；菜单数据来自 `messageContextMenus()`（第 244 行）→ `MessageContextMenuItem { title, tag, icon, action, filter? }`（`.../message/MessageContextMenuItem.ets`），渲染在 `MessageContextMenuBuilder()`（第 409 行，5 列网格）。
- 文件气泡点击：`uikit/src/main/ets/pages/conversation/message/FileMessageContentView.ets:onClick` —— 校验 `DISABLED_RECEIVE_FILE_TYPES` → `downloadMedia()` → `filePreview.canPreview()` → `filePreview.openPreview()`。

### 5.2 菜单新增（改 `NormalMessageContentView.messageContextMenus()`）
在「转发 / 多选 / 引用」之前，按 Flutter `conversation_controller.dart:721-757` 的语义插入：

```ets
// 文件消息：网盘相关项（未配网盘则不显示）
let fc = this.message.messageContent
if (fc.type === MessageContentType.File) {
    let file = fc as FileMessageContent
    let panOk = Config.isPanEnabled() && !!file.remotePath
    let online = panOk && PanService.isOnlineDocName(file.name)
    if (online)          menus.push({ title: '在线预览', tag: 'docOnlinePreview', icon: $r('app.media.ic_pan_preview'), action: () => this.openFileOnline(file) })
    if (panOk) {
        menus.push({ title: '存到网盘', tag: 'panSave', icon: $r('app.media.ic_pan_upload'), action: () => this.saveToPan(file, false) })
        menus.push({ title: '存到网盘并打开', tag: 'panSaveOpen', icon: $r('app.media.ic_pan_save_open'), action: () => this.saveToPan(file, true) })
    }
    menus.push({ title: '下载', tag: 'fileDownload', icon: $r('app.media.ic_msg_download'), action: () => this.downloadFileMessage(file) })
}
```
- `filter` 可用 `MessageContextMenuItem.filter`；由于 `file` 类型判断是硬条件，直接不 push 即可（与 Flutter 的 `menuItems.addAll` 等价）。
- `openFileOnline(file)`：`navStack.pushPathByName(PanDocWebPage, { url: PanService.docViewUrl(file.remotePath, file.name) })`。
- `saveToPan(file, openAfter)`：见 5.3。
- `downloadFileMessage(file)`：`PanService.downloadToCache`/`downloadMedia` 后 `filePreview` 或系统分享。
- 注意：文件消息内容在 `FileMessageContentView` 内，本方法在父组件，直接 `import FileMessageContent from '@wfc/client/src/main/ets/wfc/messages/fileMessageContent'` 并 `as` 转换即可（ArkTS 允许）。

### 5.3 存到网盘（新增 `uikit/src/main/ets/pan/panSave.ets`）
```
saveFileMessageToMyPan({ name, size, storageUrl, mimeType, openAfter }):
  1) PanService.saveFileToMyPan(...)   // 取私有空间 + 根目录 + 去重名 + copy:true
  2) showToast('已保存到我的网盘')
  3) openAfter 时：
     - 可在线打开 && ArkWeb 可用 → push PanDocWebPage(PanService.docOpenUrl(file.fileId))
     - 否则 PanService.getFileDownloadUrl(file.fileId) → download+system open
  异常：showToast('保存到我的网盘失败：' + msg)
```
物理拷贝由服务端完成（`copy:true` → `media.trusted_url_prefixes` 校验 + `importObject`）；客户端只传消息里的 `remotePath`（注意先走 `Config.urlRedirect` 做双网重定向）。

### 5.4 分享（改/新增 `PanSharePage.ets`）
- 接口：`shares/list`、`shares/add`、`shares/remove`、`shares/with-me`。
- 交互（对标 `pan_share.dart`）：
  1. 列出已分享的人/群，可改 `VIEW/EDIT`，可移除；
  2. 选「可查看/可编辑」→ 选会话（复用 `ForwardMessagePage` 的选会话能力或 `PickMultiContactModal`）→ 对每个会话 `shares/add` 授权；
  3. 授权成功后发 **LinkMessageContent** 卡片：`title=fileName`、`contentDigest=大小`、`url=PanService.docOpenUrl(fileId)`；发不出文件消息（网盘桶私有、签名 URL 会过期）。
- 文档 H5 的 `setPageHeader` 里按钮 `share` 由宿主接管：`PanDocWebPage` 收到 id `share` 时不再回页面，直接 `push PanSharePage`（Flutter `js_api.dart:_shareDoc` 同语义），fileId 从 doc URL 的 query 取。

### 5.5 只读打开（链接 / 文件消息）
- 统一入口 `PanService.docViewUrl(url, name)` → `PanDocWebPage`。
- 只读由服务端保证（`docs/view-url` 永远 `mode:'view'`，不注册回调），客户端不传 `view`。
- 地址必须在 `media.trusted_url_prefixes` 之下，否则服务端报 `不允许引用该存储地址`，页面 `fatal()` 里给「下载文件」按钮。

---

## 6. 上传 / 下载基础能力

### 6.1 上传（无网盘上传接口，必须走 IM 媒体通道 + 注册）
1. 选文件：`uikit/src/main/ets/helper/mediaHelper.ets:selectFile()`（`picker.DocumentViewPicker`，返回 uri）。多选需新建 `selectFiles(max)`（`DocumentSelectOptions.maxSelectNumber`）。
2. 拷进沙箱：`copyFileUri(uri, context.cacheDir + '/' + Date.now() + name)`（`common/utils/FileUtil.ets`）。
3. 上传：`wfc.uploadMediaFile(cachePath, MessageContentMediaType.File, (remoteUrl)=>{}, (err)=>{}, (sent,total)=>{})`（`client/.../wfc.ets:2605`）。大文件可选 `wfc.isSupportBigFilesUpload()` + `wfc.getUploadMediaUrl(fileName, mediaType, contentType, cb)`（`wfc.ets:2641`）再自行 PUT，首期可不做。
4. 计算 md5：`@ohos.file.hash` 的 `hash.hash(path,'md5')`；mimeType 用 `uniformTypeDescriptor` 或按扩展名小表。
5. 注册记录：`PanService.createFile({spaceId,parentId,name,size,storageUrl:remoteUrl,mimeType,md5,copy:true})`。
6. 进度/取消：`uploadMediaFile` 的 progressCB 更新 `@State` 进度条；取消用一个 `PanUploadCancelToken` 标志（注意 IM 上传无真正 abort，仅忽略结果），Flutter 也如此。
7. 上传前用 `PanService.checkSpaceWrite(spaceId)`（或 `SpaceVO.canManage`）判断写权限。

### 6.2 下载
- 取签名地址：`POST /files/url` → `storageUrl`（600s、支持 Range）。
- 通用下载：在 `mediaHelper.ets` 旁新增
  ```ets
  export function downloadUrlToCache(context, url, fileName, progressCB?): Promise<string>
  ```
  用 `request.downloadFile(context, {url, filePath: context.cacheDir + '/' + 安全名})`（现成 `downloadMedia` 的 `messageUid` 参数化，见 `mediaHelper.ets:147-180`）。
- 打开/保存：
  - 沙箱内预览：`fileUri.getUriFromPath` + `filePreview.canPreview/openPreview`（现成，见 `FileMessageContentView.ets:52-70`）。
  - 保存到系统：`picker.DocumentSaveOptions` + `fileIo.copy`；图片/视频用 `photoAccessHelper.createAsset`（`saveMedia` 已有）；或用已引入的 `@kit.ShareKit` `systemShare` 拉起分享面板（`EntryAbility.ets` 已 import）。
  - `downloadFile` 桥方法复用同一套。
- 下载 URL 已签名，`authCode` 不需要带；不要给 `request.downloadFile` 加 header。

---

## 7. 逐文件实施计划

### 7.1 新增文件
| 文件（相对 `/Users/rain/Workspace/hm-chat`） | 内容 |
|---|---|
| `uikit/src/main/ets/pan/panService.ets` | HTTP 客户端 + 所有接口 + 地址/URL 工具（`docOpenUrl/docViewUrl/isDocUrl/isOnlineDocName`）+ 错误类 |
| `uikit/src/main/ets/pan/panModels.ets` | `PanSpace / PanFile / PanDocEntry / PanShare / SharedFileVO / VersionVO` 及 `fromJson` |
| `uikit/src/main/ets/pan/panHomePage.ets` | 空间列表（`spaces/list` 过滤可见空间，个人空间排前）；点进 `PanFileListPage` |
| `uikit/src/main/ets/pan/panFileListPage.ets` | 文件列表（文件夹在前）；面包屑/子目录；新建文件夹、上传；长按操作菜单（打开/在线打开/下载/分享/移动/复制/转存/重命名/删除，按 `canManage`） |
| `uikit/src/main/ets/pan/panDocsPage.ets` | 在线文档首页：最近打开 / 共享给我两个 Tab + 新建（docx/xlsx/pptx），`docs/create`、`docs/recent`、`shares/with-me` |
| `uikit/src/main/ets/pan/panDocWebPage.ets` | 承载 ONLYOFFICE H5（`WfcWebView` + `PanDocBridge`）+ 自定义标题栏 |
| `uikit/src/main/ets/pan/panDocBridge.ets` | DSBridge 协议实现（第 4.3 节） |
| `uikit/src/main/ets/pan/panSharePage.ets` | 分享管理页（第 5.4 节） |
| `uikit/src/main/ets/pan/panDestinationPickPage.ets` | 移动/复制/转存目标文件夹选择（空间 + 目录树，禁用自身/子目录/原位置） |
| `uikit/src/main/ets/pan/panSave.ets` | `saveFileMessageToMyPan` |
| `uikit/src/main/ets/pan/panFileVersionsPage.ets` |（可选）历史版本列表/下载/恢复 |
| `uikit/src/main/ets/pan/panWidgets.ets` | 共享 Builder：`PanFileIcon`（按扩展名）、`PanQuotaBar`、`PanStatusView`、`showPanNameDialog`、`showPanFileMenu`、`formatPanSize` |
| `uikit/src/main/ets/pages/picker/PickMultiGroupPage.ets` | 多选群选择页（4.4） |
| `uikit/src/main/ets/pages/picker/PickMultiGroupModal.ets` | 多选群弹层 |
| `uikit/src/main/resources/base/media/ic_pan_drive.svg` 等 | 入口/菜单/文件类型图标（`ic_pan_doc.svg`、`ic_pan_preview.svg`、`ic_pan_upload.svg`、`ic_msg_download.svg`…），dark 目录按需同步 |

### 7.2 修改文件
| 文件 | 改动 |
|---|---|
| `client/src/main/ets/config.ets` | 新增 `PAN_SERVER_KIND`、`PAN_SERVER_ADDRESS/BACKUP` + `getPanServerAddress/getPanApiBase/getPanDocBase/getPanAuthMode/isPanEnabled`（第 3.3 节） |
| `uikit/src/main/ets/pages/uikitNavigationConfig.ets` | 新增 destination 常量 + import 新页面 + `UIKitPageMap` 分派 |
| `chat/src/main/ets/pages/MeTab.ets` | 受 gating 的「网盘 / 在线文档」入口（第 2.3 节） |
| `uikit/src/main/ets/pages/conversation/message/NormalMessageContentView.ets` | `messageContextMenus()` 追加文件消息的网盘/文档/下载项；新增对应 `action` 方法 |
| `uikit/src/main/ets/pages/conversation/message/FileMessageContentView.ets` | 点击：文档格式先 `PanDocWebPage` 只读预览，否则保持「下载 + `filePreview`」 |
| `uikit/src/main/ets/pages/conversation/message/LinkMessageContentView.ets` | `doOpenLink`：`PanService.isDocUrl` → `PanDocWebPage` |
| `uikit/src/main/ets/helper/mediaHelper.ets` | 新增 `selectFiles(max)`、`downloadUrlToCache(...)`（可复用 `downloadMedia` 逻辑抽公共） |
| `uikit/src/main/ets/common/utils/FileUtil.ets` |（按需）`sanitizeFileName`、`ensureDir` |
| `uikit/src/main/ets/pages/workspace/dsBridge.ets` |（可选）把 `downloadFile/chooseGroup/_dsb.hasNativeMethod` 抽到共享基类，供工作台桥复用；不改行为 |

### 7.3 不建议改动
- `MainPage.ets` 的 Tab 结构：网盘入口放「我」，不新增一级 Tab（对齐 Flutter）。
- `DSBridge`、`WfcWebView` 的既有工作台行为：新增桥独立实现，避免回归。
- `appServer.ets` 的 authToken 逻辑：`merged` 形态可抽公共函数复用（换 token/重登/按 host-port 缓存），但不要改动其既有行为。

### 7.4 行为约定（移植约束，Flutter 实测结论）
1. **主键字段**：服务端 `SpaceVO`/`FileVO` 主键是 `id`（不是 `spaceId`/`fileId`）；`type`/`spaceType` 是字符串枚举（旧版是数值）。解析必须两者兼容。
2. **无分页、无本地缓存**：`/spaces/files` 一次返回该目录全部；`/docs/recent` 服务端固定 50 条；`/shares/with-me` 全量。列表每次进入重新请求（List 页 `aboutToAppear` 拉取；可选：页面重新 show 时静默刷新，参考 Flutter `PanRefreshOnReshow`）。
3. **排序**：网盘首页空间 `USER_PRIVATE → USER_PUBLIC → GLOBAL_PUBLIC`；文件夹列表「文件夹在前，其余保持服务端顺序」。
4. **重名**：上传/存网盘前先查同目录文件名并生成 `base(1).ext`；`docs/create` 重名由服务端加 `(1)`，空名用「未命名文档/表格/演示」。
5. **权限**：`space.canManage` 决定是否有「分享/移动/复制/重命名/删除」，否则只给「转存」；写权限前端用 `space.canWrite`，服务端才是权威（错误码 `7000`，message 是中文原因）。分享「只升不降」（已 EDIT 不因这次 VIEW 而降级）。
6. **文档首页「分享」**：只在「我能管理的空间」的文件上出现；`canShare` 以服务端 `editor-config` 返回为准。
7. **签名 URL 不可持久化**：`/files/url` 的 `storageUrl`（600s~10min）不能存库、不能直接发消息；聊天里发的是 `/doc/open?fileId=` 链接卡片。
8. **国际化**：Flutter 的 `cloudDrive/onlineDocs/panSaveToMyPan/panSaveToMyPanAndOpen/panSaveToMyPanSuccess/panSaveToMyPanFailed/docOnlinePreview/docsRecent/docsSharedWithMe/docsNew*/docsCanEdit/docsCanView/docsLicenses/panShare*/panMoveTo/panCopyTo/panDuplicate/...` 需在 ArkTS 资源（`uikit`/`chat` 的 `resources/*/element/string.json`）补齐或先写字面量。

---

## 8. 分阶段实施与验证

### 8.1 里程碑
| 阶段 | 内容 | 可验证结果 |
|---|---|---|
| M1 配置与网络 | `Config` 地址、`panService` + `panModels`、`PanError` | 单元/临时页调用 `spaces/list` 成功；未配置时入口不显示 |
| M2 网盘 UI | `PanHomePage` / `PanFileListPage` / `panWidgets` + 导航注册 + MeTab 入口 | 浏览空间/目录；新建文件夹；打开/下载 |
| M3 上传 | `selectFiles` + `uploadMediaFile` + `files` 注册 + 进度 | 从系统文件选择器上传到当前目录，列表刷新 |
| M4 文档 H5 | `PanDocBridge` + `PanDocWebPage` + 标题栏 + 链接卡片改道 | 打开 `doc/open?fileId=`；`setPageHeader` 标题/只读说明/分享按钮正常；`chooseContacts/chooseGroup/downloadFile` 生效 |
| M5 文档首页 | `PanDocsPage`（最近/共享/新建）、`/docs/options` gating | 新建并直接打开；最近打开排序 |
| M6 消息集成 | 菜单项 + `panSave` + 只读预览 + 下载；`PanSharePage` | 长按文件消息四项可用；分享授权后卡片可被对方打开 |
| M7 打磨 | 移动/复制/转存、历史版本、错误码文案、双网 | 权限控制正确；主备网切换地址即时生效 |

### 8.2 构建与静态校验
```bash
cd /Users/rain/Workspace/hm-chat
ohpm install
hvigorw --mode module -p product=default -p module=chat@default assembleHap --no-daemon
```
- ArkTS 严格模式注意：`Record<string, Object>` 取值要显式 `as`；可选值用 `?: T | undefined`；禁止 `any`/`ESObject` 滥用（现有代码在动态 import 处用 `ESObject`，业务代码不要）。
- 每次新增 NavDestination 后必须跑一次 `assembleHap`，否则 `NavDestination not found` 只在运行时暴露。

### 8.3 手工测试矩阵
| 场景 | 期望 |
|---|---|
| 未开网盘（`PAN_SERVER_KIND` 对应地址为空） | 「我」页无网盘/文档入口；文件消息无网盘菜单项 |
| 配好地址、无网 | 列表页错误态 + 重试；不崩溃 |
| 空间列表 | GLOBAL_PUBLIC + 自己的空间；私有空间在前；容量条正确 |
| 上传 | 同名自动加 (1)；失败/取消有提示；容量刷新 |
| 打开文档 | `doc/open?fileId=` 正常；手机 `viewReason=mobile` 时只读并可下载；宽屏按 `isWideScreen()` 传 `pc` |
| `setPageHeader` | 标题=文件名；只读说明；「分享/历史版本/下载/转换」按钮出现在宿主标题栏，点击生效 |
| 选人/选群 | 文档页「分享」里添加成员/群，`shares/add` 成功；取消不会卡住 Promise（回 -1） |
| 消息菜单 | 文档格式出现「在线预览」；「存到网盘」后在网盘根目录可见；「存到网盘并打开」直接进编辑器 |
| 链接卡片 | 文档卡片用内置网页（带桥）打开，而不是系统浏览器 |
| 两种形态 | `merged` 与 `standalone` 各跑一遍 M1~M6；主/备网切换后 `getPanApiBase()` 立即变化 |

### 8.4 验证依赖
- 需要与所选 `PAN_SERVER_KIND` 一致的网盘后端：`merged` 需可达的 `wf-app-server`（含 `app-pan`、`docs.enabled=true`、ONLYOFFICE 与 nginx `/docs/` 反代），`standalone` 需可达的 `wf-pan-server`（8081 + `im.server.admin_url/admin_secret` + `docs.enabled=true` + nginx `/pan/`→8081、`/docs/`→ONLYOFFICE）。
- 至少一个可用空间；`authToken` 形态需已登录且 IM 连接正常（换 token 依赖连接态）。
- `wfc.getAuthCode` 需要登录态与 IM 连接；离线时网盘接口应报「获取认证码失败」。

---

## 9. 风险与待确认

1. **服务端形态必须二选一（最高优先级）**：`Config.PAN_SERVER_KIND` 取 `'merged'`（wf-app-server `app-pan`，`/api/pan` + authToken + `{APP_SERVER}/doc/`，Flutter 参考实现）或 `'standalone'`（wf-pan-server，`/api/v1` + authCode + `…/doc/`）。两者的子路径、模型一致，差异已收敛到 §3.3 的 4 个配置；但**鉴权与文档会话机制不同**，必须按线上部署选定并实测，不能只改地址。
2. **nginx 前缀与 Cookie path**：独立服务端 `pan.public_path` 默认 `/pan`，仓库内 nginx 示例却是「根路径→8081」。独立形态必须让 `PAN_SERVER_ADDRESS` 带上真实公共前缀（`/pan`），否则 H5 内相对 API 与 `/doc/session` 的 Cookie path 不匹配会 404；合并形态则确认 `{APP_SERVER}` 已被 nginx 正确反代 `/api/pan/` 与 `/doc/`。
3. **多选群页缺失**：`chooseGroup` 需要多选群页；首期若不做 `PickMultiGroupPage`，文档页「添加群」不可用（`hasNativeMethod('chooseGroup')` 返回 false 时页面按钮置灰，属可接受降级）。
4. **DSBridge 同步返回值**：ArkWeb `javaScriptProxy` 的返回值语义需在真机验证（尤其 `_dsb.hasNativeMethod` 必须同步拿到 `{data:true}`）；若拿不到，需实测「页面靠 `code:-1` 判定不支持」的兜底路径。
5. **`setPageHeader` 多次回调**：必须避免 `delete window[cb]`；否则第二次点按钮无响应。回调参数是**裸的按钮 id**（不是 `{code,data}`）。
6. **上传无真正取消 / 无分片**：IM `uploadMediaFile` 不支持 abort；大文件走 `getUploadMediaUrl` 的预签名 PUT 需要额外实现（可选，Flutter 也只在桌面端开启，且 `WfcPlatform.isDesktop` 含 ohos PC）。
7. **authToken 缓存与重登**：合并形态 token 按 `host:port` 缓存，主备网各自一份；退出登录要清。判失效不能只看 HTTP：服务端可能返回 HTTP 200 + `code==13`。
8. **文档页 platform**：Flutter 参考 URL 不带 `platform`（页面按 UA 判）；独立 `wf-pan-server` 的 `open.html` 优先读 `?platform=`。宿主拼 URL 时带上 `platform=mobile|pc`（宽屏按 `isWideScreen()` 传 `pc`）最稳，但不能把平台判断只押在这一个参数上。
9. **`OPEN_LINK_POLICY` 与文档卡片**：链接卡片打开受 `Config.OPEN_LINK_POLICY`（0 不限制/1 提醒/2 禁止）限制；Flutter 对在线文档做了例外。建议 `PanService.isDocUrl()` 命中时在策略判断之前直接进 `PanDocWebPage`，否则「提醒/禁止」会挡掉自家文档。需产品确认。
10. **响应字段名**：合并服务 `result`、独立服务 `data`，服务层要兼容两者（优先 `result`），并注意业务错误多为 HTTP 200 + `code!=0`。
