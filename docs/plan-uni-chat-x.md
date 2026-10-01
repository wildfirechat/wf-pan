# uni-chat-x 网盘(pan) + 在线文档(online docs) 客户端实施计划

> 目标仓库：`/Users/rain/Workspace/uni-chat-x`（uni-app x / uvue / UTS，app + harmony）
> 行为参考：`/Users/rain/Workspace/wf-enterprise-chat/chat/lib/pan/` 与 `chat/lib/settings/me_tab.dart`、`chat/lib/workspace/js_api.dart`、`chat/lib/conversation/conversation_controller.dart`
> 服务端：`/Users/rain/Workspace/wf-pan/wf-pan-server`（Spring Boot，含在线文档 H5 页面 `doc-web/`）
> 行为规格：`docs/client-pan-docs-spec.md` —— **本计划编写时该文件尚不存在**，因此以下契约全部来自 Flutter 参考实现与 `wf-pan-server` 源码；spec 落地后应回填/校正第 0.3 节的地址与响应体约定。

---

## 0. 结论速览（给执行者的 30 秒版）

- **形态**：uni-app **x** 工程，页面是 `<script setup lang="uts">` 的 **uvue**，不是标准 Vue SFC；没有 uni CLI 构建脚本，走 **HBuilderX + 自定义调试基座**（鸿蒙需付费 SDK / 申请试用）。
- **入口**：不改 tabBar，在「我的」(`pages/me/MePage.uvue`) 的文件分组里增加 **门控**的「网盘」「在线文档」两项；门控条件 = `Config.getPanServer() != ''`。
- **网络**：复用现有 `api/authCodeApiClient.uts`（header `authCode`，`getAuthCode('admin', 2, host)`），新增 `api/panServerApi.uts` + `api/panModel.uts`。
- **地址**：在 `config.uts` 增加 `PAN_SERVER` / `PAN_SERVER_BACKUP` + `getPanServer()`，以及文档页 `PAN_DOC_BASE` + `getPanDocBase()`。
- **WebView**：复用现有工作台桥模式（`uni.postMessage` → `@message` → `webviewContext.evalJS`），新增 `pages/pan/PanDocWebViewPage.uvue` + `pages/pan/panDocBridge.uts`，实现 `getAuthCode / setPageHeader / downloadFile / chooseContacts / chooseGroup / openUrl / toast / close`。**注意**：服务端 `doc-web` 目前只认 **dsbridge** 协议，见第 4 节的三选一桥接方案。
- **文件消息**：`FileMessageContentView` 点击 → 文档格式走「按链接只读在线预览」；长按菜单新增「在线预览 / 存到网盘 / 存到网盘并打开」。
- **上传/下载**：上传复用 `wfc.uploadMediaFile(path, MessageContentMediaType.File, …)`（大文件可选 `wfc.isSupportBigFilesUpload()` + `wfc.getUploadMediaUrl`）；下载复用 `uni.downloadFile` + `uni.openDocument`；网盘文件下载地址走 `POST /files/url`。

---

## 1. 工程结构、构建方式与代码形态

### 1.1 目录与职责

```
uni-chat-x/
├── App.uvue                     # 应用入口（onLaunch 初始化 IM/主题/音视频）
├── main.uts                     # createSSRApp
├── manifest.json                # uni-app x 配置（含 app / app-harmony / app-android / app-ios）
├── pages.json                   # 页面路由 + tabBar + 每页 style
├── config.uts                   # 所有服务地址与开关的集中配置（含 getXxx() 主备选择）
├── pages/                       # 页面（.uvue）
│   ├── me/MePage.uvue           # 「我的」——网盘/文档入口落点
│   ├── workspace/               # 工作台 web-view + 开放平台 JS 桥（本计划的桥模板）
│   ├── misc/WebViewPage.uvue    # 通用只读 web-view（无桥）
│   └── conversation/            # 会话页、消息 cell、长按菜单
├── wfc/                         # IM SDK 封装（client/model/messages/av/ptt/util）
├── api/                         # 业务后端 HTTP 客户端（authCodeApiClient / poll / collection / appServer…）
├── common/                      # 跨页工具（nav / picker / mediaSaver / filePath / forward / theme…）
├── components/                  # 复用组件（option-item / form-card / popup-menu / bottom-action-sheet…）
├── i18n/                        # lang-zh-CN.uts / lang-zh-TW.uts / lang-en.uts + i18n.uts
├── static/                      # 图片、iconfont（icomoon = Ionicons，码位见 static/iconfonts/icomoon/ICONS.md）
├── harmony-configs/             # 鸿蒙原生工程配置（module.json5 权限、AppScope、har 依赖）
├── uni_modules/                 # 原生插件（wfc-client / wfc-av-client / wfc-ptt-client / wfc-voice-input）
└── scripts/                     # uts-transpile.js、check-uvue-css.js 等辅助脚本
```

### 1.2 代码形态（重要约束）

- **页面是 uvue + UTS**：`<script setup lang="uts">`，静态类型；不是标准 Vue SFC（`.vue`）。新增页面必须用 `.uvue`。
- **只有 `common/`、`api/`、`wfc/` 是 `.uts`**；纯逻辑不要写成 `.js`（H5 侧脚本除外，见 `pages/workspace/bridgeClientImpl.uni.js`，它是“运行在 webview 里的 H5 SDK”参考副本，不参与应用编译）。
- **uvue 渲染限制**（写页面时遵守仓库既有约定）：
  - `list-view`/`list-item`（鸿蒙 vapor 复用渲染）里**尺寸/字号留在 class**，只有颜色走 `:style`（见 `pages/pick/PickConversationPage.uvue` 注释）。
  - 不支持 `:before`/`content`，图标改用 `loadFontFace` + 文本码位（`App.uvue`）。
  - 百分比 `max-width` 三端不支持，只能算 px（见 `components/option-item/option-item.uvue`）。
  - class 类型 props 在 Android 编译成 Any，模板取成员前要在 script 里转好类型（见 `FileMessageContentView.uvue`）。

### 1.3 构建 / 运行

- **HBuilderX**：制作**自定义调试基座** → 运行到 Android / iOS / 鸿蒙（见 `README.md`「运行」「配置」）。没有 `npm run build` 之类的 uni CLI 流程；`package.json` 只有元信息。
- **蒸汽模式（vapor）** 已开启：`manifest.json` 的 `uni-app-x.vapor = true`、`render-target = bytecode`。这也意味着 `createWebViewContext` 只能写大写 V 的版本（`WorkspacePage.uvue` 注释已说明）。
- **鸿蒙**：`harmony-configs/` 是生成工程的原生配置层；`ohos.permission.INTERNET` 已声明，web-view 可直接用；改 `libs/marswrapper.har` 需按 README 清 `oh_modules` 重装。
- **验证脚本**：`node scripts/uts-transpile.js`、`node scripts/check-uvue-css.js`、`node scripts/check-store-reset.js`（改 css/store 后跑，见第 8 节）。

---

## 2. 导航与菜单

### 2.1 现有路由 / tabBar

- `pages.json` 的 `tabBar.list` 固定 5 项：会话(`ConversationListPage`)、通讯录(`ContactListPage`)、**工作台(`pages/workspace/WorkspacePage`)**、发现(`DiscoveryPage`)、我的(`pages/me/MePage`)。tabBar **不能按配置动态增删**，所以网盘/文档**不新增 tab**。
- `pages.json` 的 `pages` 数组是普通 JSON 对象数组（文件内含 `//` 注释，改动时保持风格）；每页可有 `style.navigationBarTitleText`、`navigationStyle: custom` 等。
- 页面跳转统一用 `uni.navigateTo({ url })`（`common/nav.uts` 的 `navigateToPage(url, options)` 额外支持大对象参数暂存）。web-view 页参数走 query（`encodeURIComponent`）。

### 2.2 「我」页入口（对齐 Flutter `settings/me_tab.dart:85-111`）

Flutter 参考的规则：
1. 「文件」项永远显示。
2. `Config.panServerAddress != ''` 时显示 **云盘(PanHomeScreen)**。
3. 工作台/内置 WebView 可用（`isInlineWebViewSupported`）时显示 **在线文档(PanDocsScreen)**；在线文档是网盘自带的页面，所以还要有网盘。
4. 只有网盘可用时，「文件」行保留底部分隔线；否则它是卡片最后一行。

uni 侧落点：`pages/me/MePage.uvue` 第 23-28 行的 `<form-card>`（收藏 / 文件）。改为：

```
<form-card>
  <option-item favorites ... />
  <option-item files ... :show-bottom-divider="!panEnabled" @tap="open('/pages/me/FileRecordsPage')" />
  <option-item v-if="panEnabled" title="网盘" leftIcon="&#xf37a;" @tap="open('/pages/pan/PanHomePage')"
               :show-bottom-divider="true" />
  <option-item v-if="panEnabled" title="在线文档" leftIcon="&#xf12f;" :show-bottom-divider="false"
               @tap="open('/pages/pan/PanDocsPage')" />
</form-card>
```

- `panEnabled = Config.getPanServer() != ''`（用 `computed` 暴露，和 `MePage` 里 `c`/`portrait` 的写法一致）。
- 图标用 **icomoon（Ionicons）** 码位，别用图片：云盘 `f37a`(ion-android-cloud) 或 `f379`(cloud-outline)、文档 `f12f`(ion-document) / `f12e`(document-text)。码位表：`static/iconfonts/icomoon/ICONS.md`。**加图标前必须在表里确认码位存在**，否则渲染成豆腐块且不报错。
- 可选：从参考仓库拷贝 `chat/assets/images/net_disk.png` 到 `static/image/setting/net_disk.png` 走 `left-image`（与现有 PNG 风格一致）。**二选一即可**，不要既 `left-image` 又 `left-icon`（组件里 image 优先）。

### 2.3 新增路由（追加到 `pages.json` 的 `pages` 数组）

| path | navigationBarTitleText | 说明 |
|---|---|---|
| `pages/pan/PanHomePage` | 网盘 | 空间列表（移动端）；对齐 `pan_home_screen.dart` 的移动分支 |
| `pages/pan/PanFileListPage` | 文件 | 单文件夹一页，进子目录再 push；对齐 `pan_file_list_screen.dart` |
| `pages/pan/PanDocsPage` | 文档 | 最近打开 / 共享给我；对齐 `pan_docs_screen.dart` |
| `pages/pan/PanDocWebViewPage` | 页面加载中... | 托管 ONLYOFFICE 文档 H5 + 桥（参考 `WorkspaceWebViewPage`） |
| `pages/pan/PanDestinationPickerPage` | 选择保存位置 | 存到网盘/移动/复制时的目标选择（对齐 `pan_destination_picker.dart`，可后置） |
| `pages/pan/PickGroupPage` | 选择群聊 | `chooseGroup` 桥要用的群多选页 |

> 说明：网盘首页/文件页也可先做一个最小闭环（空间列表 + 文件列表 + 上传/下载/预览），分享、移动/复制、目标选择按第 7 节排期后置。

---

## 3. 网络与鉴权

### 3.1 现有机制

- **统一封装**：`api/authCodeApiClient.uts` 的 `AuthCodeApiClient(authCodeId, authCodeType, baseUrlProvider)`。
  - 每次请求先 `wfc.getAuthCode(appId, appType, extractHost(baseUrl), …)` 取一次性认证码，放进 **请求头 `authCode`**，再 `uni.request` POST JSON。
  - 返回 **整个响应体**（已校验 `code == 0`）；业务数据在包装字段里。
  - `isAvailable()` 用 `baseUrlProvider().trim() != ''` 判断服务是否配置——**所有入口门控都基于它**。
- **业务后端**：接龙 `api/collectionServerApi.uts`（`'collection'`）、投票 `api/pollServerApi.uts`（`'poll'`）。网盘照此新增。
- **IM 应用服务**：`api/appServerApi.uts` 走 **authToken**（`api/authToken.uts`，`/api/auth/login` 换、缓存、`authToken` 头）。**网盘不属于这一类**：`wf-pan-server` 的 `ClientAuthFilter` 同时接受 header `authCode` 或文档会话 Cookie，所以默认用 `AuthCodeApiClient` 即可，无需接 authToken。
- **双网重定向**：媒体/远端地址统一走 `common/mediaUrlRedirector.uts` 的 `redirectMediaUrl()`（先主备前缀互换，再 `Config.urlRedirect`）。网盘存储地址（消息 `remotePath`）传入 `saveFileToMyPan` 前要 redirect。

### 3.2 地址与门控：在 `config.uts` 增加

在现有 `COLLECTION_SERVER` / `POLL_SERVER` 附近（约 88-99 行）新增：

```uts
// 网盘服务地址。独立部署的 wf-pan-server 客户端 API 在 {host}{pan.public_path}/api/v1，
// 反代后典型值 'https://host/pan/api/v1'；
// 若并入 wf-app-server（Flutter 参考现状），则为 {应用服务根}/api/pan。
// 置空 = 关闭网盘：不在「我的」显示入口、消息菜单不出现网盘项。
static PAN_SERVER = '';
static PAN_SERVER_BACKUP = '';

// 在线文档 H5 页面根地址，必须以 / 结尾。独立部署典型值 'https://host/pan/doc/'；
// 并入应用服务时为 {应用服务根}/doc/。置空则只隐藏「在线文档」入口。
static PAN_DOC_BASE = '';
static PAN_DOC_BASE_BACKUP = '';
```

派生 getter（紧挨 `getCollectionServer` / `getPollServer`）：

```uts
static getPanServer(): string {
    // 允许两种部署：显式配置优先；未配但配了应用服务时可派生（对齐 Flutter：appServiceAddress + '/api/pan'）
    if (Config.PAN_SERVER != '' || Config.PAN_SERVER_BACKUP != '') {
        return selectServer(Config.PAN_SERVER, Config.PAN_SERVER_BACKUP)
    }
    const app = Config.getAppServer()
    return app != '' ? app + '/api/pan' : ''
}
static getPanDocBase(): string {
    if (Config.PAN_DOC_BASE != '' || Config.PAN_DOC_BASE_BACKUP != '') {
        return selectServer(Config.PAN_DOC_BASE, Config.PAN_DOC_BASE_BACKUP)
    }
    const app = Config.getAppServer()
    return app != '' ? app + '/doc/' : ''
}
static isPanEnabled(): boolean { return Config.getPanServer() != '' }
```

> **地址/响应体两处版本差异（务必按 spec 校正）**
> 1. 前缀：Flutter 参考是合并服务的 `/api/pan/**` + 根路径 `/doc/`；本仓库 `wf-pan-server` 独立部署是 `/api/v1/**` + `/pan/doc/`（`application.properties: pan.public_path=/pan`、`WebDocController` 注释）。用 `PAN_SERVER`/`PAN_DOC_BASE` 两个可配地址同时覆盖两种部署最稳。
> 2. 响应体包装字段：`AuthCodeApiClient` 返回整体；`wf-pan-server` 的 `Result` 用 **`data`**（`dto/Result.java`），而 Flutter `pan_service.dart` 读的是 **`result`**（合并服务 envelope）。数据层统一用 **`body.getJSON('result') ?? body.getJSON('data')`** 容错，spec 明确后收敛到一种。

### 3.3 新增 `api/panModel.uts`（对齐 `pan_service.dart:778-1073`）

- `enum PanSpaceType { globalPublic, userPublic, userPrivate }`（服务端字符串 `GLOBAL_PUBLIC|USER_PUBLIC|USER_PRIVATE`，老版本可能是 int，两种都认）。
- `PanSpace`：`spaceId/spaceType/ownerId/name/totalQuota/usedQuota/fileCount/folderCount/autoInit/createdAt/canManage`；`canWrite` 规则：`spaceType != userPublic || ownerId == wfc.getUserId()`。
- `PanFile`：`fileId/spaceId/parentId/name/type(FILE|FOLDER，兼容 1/0)/size/mimeType/md5/storageUrl/childCount/creatorId/creatorName/createdAt/updatedAt`；`isFolder`、`extension`、`canOpenOnline = isOnlineDocName(name)`、`iconType`。
- `PanDocEntry`（`file` + `canEdit` + `time` + `sources[]`）、`PanShare`（`id/isGroup/targetId/targetName/canEdit/sharerName/time`）、`PanShareSource`。
- `formatPanSize(bytes)`（1 位小数，KB/MB/GB，对齐参考）。
- `PanException(code, message)`。

### 3.4 新增 `api/panServerApi.uts`（单例，方法与端点对齐 `pan_service.dart`）

```uts
export class PanServerApi {
    private client = new AuthCodeApiClient('admin', 2, (): string => Config.getPanServer())
    isAvailable(): boolean { return this.client.isAvailable() }
    private _post(path: string, params: UTSJSONObject): Promise<UTSJSONObject | null>
    private _result(body): UTSJSONObject | null   // result ?? data
    ... // 各方法
}
export default new PanServerApi()
```

方法 → 端点（相对 `PAN_SERVER`；读取 `result ?? data`）：

| 方法 | 端点 | 关键返回 |
|---|---|---|
| `getSpaces()` | `POST /spaces/list` | `PanSpace[]` |
| `getVisibleSpaces()` | 组合：`/spaces/list` + 过滤 | 全局公共 + 自己的空间，个人空间排最前 |
| `getMySpaces()` | `POST /spaces/my` | `PanSpace[]` |
| `getUserPublicSpace(uid)` | `POST /spaces/user/public` `{targetUserId}` | `PanSpace?` |
| `getSpaceFiles(spaceId, parentId)` | `POST /spaces/files` | `PanFile[]` |
| `createFolder(spaceId, name, parentId)` | `POST /files/folder` | `PanFile` |
| `createFile({spaceId,name,size,storageUrl,parentId?,mimeType?,md5?,copy?})` | `POST /files` | `PanFile` |
| `saveFileToMyPan({name,size,storageUrl,mimeType?,md5?})` | `/spaces/my` + `/spaces/files` + `/files{copy:true}` | `PanFile` |
| `uploadFileToSpace({localPath,space,parentId,onProgress})` | upload + `/files` | `PanFile` |
| `deleteFile(fileId)` | `POST /files/delete` | — |
| `renameFile(fileId,newName)` | `POST /files/rename` | — |
| `moveFile(fileId,targetSpaceId,targetParentId)` | `POST /files/move` | — |
| `copyFile(...)` | `POST /files/copy` | — |
| `getFileDownloadUrl(fileId)` | `POST /files/url` | `storageUrl` |
| `getShares(fileId)` | `POST /shares/list` | `PanShare[]` |
| `setShare(fileId,isGroup,targetId,canEdit)` | `POST /shares/add` | — |
| `removeShare(shareId)` | `POST /shares/remove` | — |
| `grantForConversation(fileId,conversation,canEdit,existing)` | `POST /shares/add` | 单聊给对方 / 群聊给群，只升不降 |
| `getRecentDocs()` | `POST /docs/recent` | `PanDocEntry[]` |
| `getSharedWithMe()` | `POST /shares/with-me` | `PanDocEntry[]` |
| `removeRecentDoc(fileId)` | `POST /docs/recent/remove` | — |
| `createDoc(type, name?)` | `POST /docs/create` | `PanFile` |
| `isMobileDocEditEnabled()` | `POST /docs/options` | `mobileEdit` |
| `checkSpaceWritePermission(spaceId)` | `POST /files/check-permission` | `bool` |

### 3.5 新增 `api/panDocUrl.uts`（对齐 `pan_service.dart:37-108`）

- `docOpenUrl(fileId)` → `getPanDocBase() + 'open?fileId=' + fileId`
- `docViewUrl(url, name?)` → `getPanDocBase() + 'open?url=' + encodeURIComponent(url) + '&name=' + encodeURIComponent(name)`（**只读**、服务端代理取内容）
- `docLicensesUrl` → `getPanDocBase() + 'licenses.html'`
- `isDocUrl(url)` → 以主备 `PAN_DOC_BASE`（去掉尾部 `/` 后）`+ '/doc/'` 前缀判断（用于把聊天里的文档链接卡片强制走内置 web-view）
- `isOnlineDocName(name)` → 扩展名白名单（与 `wf-pan-server` `DocsService` 的 WORD/CELL/SLIDE/PDF 四组一致）：`doc docx docm dot dotx dotm odt ott rtf txt wps wpt fodt mht mhtml htm html epub fb2 / xls xlsx xlsm xlt xltx xltm xlsb ods ots csv et ett fods / ppt pptx pptm pot potx potm pps ppsx ppsm odp otp dps dpt fodp / pdf djvu xps oxps`

---

## 4. WebView 与 JS 桥（托管 ONLYOFFICE 文档页）

### 4.1 现有 uni-app x web-view 能力与两种桥协议

- **uni 侧页面写法**（模板见 `pages/workspace/WorkspacePage.uvue` / `WorkspaceWebViewPage.uvue`）：
  - `<web-view id="..." :src="url" @message="onPostMessage" @loaded="onLoaded" />`
  - `onReady` 里 `uni.createWebViewContext('<id>', getCurrentInstance()!.proxy!)` 拿到 `WebviewContext`，用 `evalJS(js)` 反向调用 H5。
  - H5 → uni：H5 调 `uni.postMessage({data:{obj: JSON.stringify(msg)}})`，`@message` 事件里解析（兼容 `Array<UTSJSONObject>` / `Map` / 字符串三种结构，见 `pages/workspace/webViewMessage.uts`）。
  - `web-view` **不能有子节点**；水印要包一层 view 挂在旁边。
- **协议 A（仓库现有，工作台/open-platform 用）**：消息体 `{type:'wf-op-request', requestId, appUrl, handlerName, args}`；uni 回 `{type:'wf-op-response', requestId, args:{code,data}}`，事件 `{type:'wf-op-event', handlerName, args}`；H5 侧副本见 `pages/workspace/bridgeClientImpl.uni.js`，uni 侧实现见 `pages/workspace/bridgeServerImpl.uts`。
- **协议 B（dsbridge，服务端在线文档页用）**：`wf-pan-server/src/main/resources/doc-web/app.js` 的 `Bridge` 只认 `window._dsbridge` 或 `prompt('_dsbridge=方法', JSON{data,_dscbstub})`，异步结果由客户端回调 `window[_dscbstub](结果)`；能力探测同步调 `_dsb.hasNativeMethod`（`{name,type:'all'}` → `{data:true}`）；`setPageHeader` 是**多次回调**（`Bridge.listen`，客户端用“保留回调”的语义回按钮 id）；UA 里含 `WF-DSBridge` 即视为在客户端（`webview_support.dart`）。
- **结论**：**协议不匹配**。uni 侧要实现的桥方法同名，但报文封装不同；需要在“改 H5 适配 uni 协议”与“uni 侧模拟 dsbridge”之间二选一。四个桥方法 `getAuthCode / setPageHeader / downloadFile / chooseContacts / chooseGroup` 的语义完全对齐 Flutter `workspace/js_api.dart`。

### 4.2 推荐方案（首选）：给 `doc-web` 增加 uni 协议适配层

在线文档页本来就由本仓库的服务端提供，改服务端最省事、最稳，且与工作台的 H5 桥形成一套约定：

1. 新增 `wf-pan-server/src/main/resources/doc-web/uni-bridge.js`（**在 `app.js` 之前加载**）：
   - 同步定义 `window._dsbridge = { call(method, argString) { … } }`，内部把请求通过 `uni.postMessage` 转给 uni 侧；
   - `_dsb.hasNativeMethod` 依据编译期能力清单**同步**返回 `{code:0,data:true/false}`（能力清单可由页面 URL 的 `?bridge=...` 或直接硬编码方法名，避免同步往返）；
   - 异步方法（`getAuthCode/chooseContacts/chooseGroup`）把 `_dscbstub` 名字带上，返回 `{code:-1}`（与 dsbridge_flutter 行为一致，`app.js` 已把它当作“异步、结果走回调”）；
   - 同步 fire-and-forget（`openUrl/downloadFile`）转发后返回 `{code:0}`；
   - 需要“多次回调”的 `setPageHeader`：把回调名注册到 `window`，客户端每次按钮都 `evalJS` 回调同名字。
   - 该文件可大量复用 `pages/workspace/bridgeClientImpl.uni.js` 的结构（它就是这样一份“H5 侧桥客户端”参考）。
2. 在 `doc-web/index.html`、`open.html`、`licenses.html` 的 `<head>` 里，于 `app.js` 之前插入 `<script src="./uni-bridge.js"></script>`。
3. uni 侧 `PanDocBridge`（`pages/pan/panDocBridge.uts`）按协议 A 的结构处理 `pan-op-request`，实现下列方法，然后 `evalJS` 回包。

> 若不允许改服务端：退路是在 `PanDocWebViewPage` 的 `onReady` 里 `evalJS` 注入 `window._dsbridge` shim（能力清单硬编码）。缺陷是注入时机晚于 `<head>` 里 `app.js` 执行，`document.documentElement.classList` 的 `in-client` 判定会漏、首帧可能闪一下页面自带标题栏。可接受但不理想，**不推荐**。

### 4.3 uni 侧桥方法实现（对齐 `chat/lib/workspace/js_api.dart`）

| 方法 | 入参 | 行为 | 返回 |
|---|---|---|---|
| `getAuthCode` | `{appId, appType}` | `wfc.getAuthCode(appId, appType, host, ok, fail)`；host 从**页面加载时的原地址** `appUrl` 解析（`extractHost`） | `{code:0, data:authCode}` / 失败 `{code:err}` |
| `config` | `{appId,appType,timestamp,nonceStr,signature}` | `wfc.configApplication(...)`；成功后向页面发事件 `ready`，失败发 `error` | 事件 |
| `setPageHeader` | `{title?, subtitle?, actions:[{id,text,icon?,primary?}]}` | 用 `uni.setNavigationBarTitle` 设标题；维护当前页头按钮；用户点按钮回调页面 `onAction(id)`；`id=='share'` 时客户端自己接分享（见 4.4） | 多次回调按钮 id |
| `downloadFile` | `{url}` 或 字符串 | 交给系统打开（`uni.downloadFile` + `uni.openDocument`，或 `plus`/`uni.openDocument`；鸿蒙用 `uni.chooseFile` 同款文件能力）；**不要**用 `openUrl`（内置网页会白屏） | 无 |
| `chooseContacts` | `{}` | 复用 `common/picker.uts` 的 `pickUsers`（`contactState.friendList`）；取消回 `-1`，每个退出路径都要回 | `{code:0, data: JSON串 [{uid,name,displayName,portrait}]}` |
| `chooseGroup` | `{}` | 新增 `pickGroups`（基于 `store.filterGroupConversation('')` / `contactState.favGroupList`）；取消回 `-1` | `{code:0, data: JSON串 [{gid,name,portrait}]}` |
| `openUrl` | 字符串或 `{url}` | 新开 `PanDocWebViewPage`（或 `WebViewPage`），URL 经 `redirectMediaUrl` | 无 |
| `toast` | 字符串 | `uni.showToast({icon:'none'})` | 无 |
| `close` | — | `uni.navigateBack({delta:1})` 兜底 |

> `chooseContacts`/`chooseGroup` 的“取消/返回/手势返回都要回一次”是 H5 Promise 不悬空的关键（Flutter 注释明确）。uni 侧用 `successCB`/`failCB` 成对回调，并加一个 `navigateBack` 监听兜底。

### 4.4 文档页内「分享」按钮

Flutter 在 `setPageHeader` 里拦截 `id=='share'`，用网盘分享界面（改权限、选聊天、发文件卡片）而不是回页面。uni 侧可复用同一思路：
- 从页面地址解析 `fileId`（`getPanDocBase()`+`open?fileId=`）；
- 打开 `PanSharePage`（或先做“选择会话 + `grantForConversation` + 发在线文档链接卡片”的最小实现）。
- 聊天里发的是**在线文档页链接**（对方点开按分享权限访问），不是 10 分钟有效的签名下载地址。

### 4.5 托管页面地址

- 在线文档首页：`PanDocWebViewPage?url=<encodeURIComponent(getPanDocBase())>`
- 打开某文件：`getPanDocBase() + 'open?fileId=' + fileId`
- 只读按链接打开：`docViewUrl(mediaUrl, name)`
- 开源许可：`getPanDocBase() + 'licenses.html'`
- 加载时对页面地址做 `redirectMediaUrl`（双网主备），但 `getAuthCode` 用的 host 必须是**页面实际所在的地址**，与 `bridgeServerImpl.uts` 的 `appUrl` 处理一致。
- 文档页需要登录态 Cookie（`POST {docBase}session` 用 authCode 换），这步在 H5 内完成，客户端只提供 `getAuthCode`。因此 **web-view 必须允许 Cookie 同源请求**（uni-app x 默认允许）。

---

## 5. 文件消息的渲染与操作

### 5.1 点击文件消息：文档格式 → 在线只读预览

- 现状：`pages/conversation/message/content/FileMessageContentView.uvue:48-72` 点击后 `uni.downloadFile` + `uni.openDocument`。
- 目标（对齐 `conversation_controller.dart:357-364` + `utilities.dart` `openFileByDefault`/`openFileOnline`）：
  - 若 `panServerApi.isAvailable() && isOnlineDocName(content.name)` → 打开 `docViewUrl(redirectMediaUrl(content.remotePath), content.name)`（**只读，不占网盘**；要编辑先「存到网盘」）。
  - 否则维持系统打开（下载 + `openDocument`）。
- 建议把这段判断抽到 `common/mediaSaver.uts`（或新增 `common/panFileOpen.uts`）导出 `openFileByDefault(name, url)`，让会话、文件记录、收藏列表共用（对齐 Flutter 的 `Utilities.openFileByDefault`）。

### 5.2 长按菜单新增项

落点：`pages/conversation/ConversationPage.uvue` 的 `showMessageContextMenu`（1127-1158 行）与 `onContextMenuItemSelect`（1199-1246 行）。现有顺序：删除/复制/保存/转文字/转发/撤回/多选/引用/收藏/举报（注释要求对齐 Flutter）。

在 `isDownloadAble` 之后、`isForwardable` 之前插入文件消息专属项（`message.messageContent instanceof FileMessageContent && remotePath != ''`）：

| 菜单项 | tag | 显示条件 | 行为 |
|---|---|---|---|
| 在线预览 | `docOnlinePreview` | `panAvailable && isOnlineDocName(name)` | `docViewUrl` 打开文档页（与点击同一入口） |
| 存到网盘 | `panSave` | `panAvailable` | `saveFileMessageToMyPan(..., openAfterSave:false)` |
| 存到网盘并打开 | `panSaveOpen` | `panAvailable` | `saveFileMessageToMyPan(..., openAfterSave:true)` |

- 图标用 icomoon：预览 `f133`(ion-eye)、存网盘 `f40a`(cloud-upload-outline)/`f378`(cloud-done)、下载 `f2dd`。`PopupMenuItem(title, tag, icon)` 见 `common/popupMenu.uts`。
- `saveFileMessageToMyPan`（新增 `common/panSave.uts`）对齐 `pan/pan_save.dart`：
  1. `panServerApi.saveFileToMyPan({name,size,storageUrl: redirectMediaUrl(remotePath), mimeType})`（服务端 `copy:true` 把文件拷进网盘 bucket）；
  2. toast 成功/失败；
  3. `openAfterSave` 时：能在线打开的走 `docOpenUrl(file.fileId)`，否则 `getFileDownloadUrl` 后交系统打开。

### 5.3 聊天里的文档链接卡片

`pages/conversation/message/content/LinkMessageContentView.uvue` 的 `openLink`：若 `isDocUrl(url)`，强制走内置 web-view（`PanDocWebViewPage`）而不是系统浏览器——这类页面要靠客户端桥取认证码，系统浏览器打不开（对齐 `pan_service.dart:69` 注释）。

### 5.4 只读 / 可编辑的边界

- 聊天文件消息 → 只读（`docViewUrl`，服务端代理取内容、不回写）。
- 网盘内文件 → `docOpenUrl(fileId)`，编辑/只读由服务端按权限与平台决定；`PanDocsPage` 里「最近打开」能分享/移除，「共享给我」只能打开。
- 手机新建文档：先 `isMobileDocEditEnabled()`（`docs.options.mobileEdit`），关着就不给新建（对齐 `pan_docs_screen.dart:49`）。

---

## 6. 上传 / 下载原语

### 6.1 上传（移动端）

- **小文件（默认路径）**：`wfc.uploadMediaFile(path, MessageContentMediaType.File /*=4*/, (remoteUrl)=>{}, (err)=>{}, (cur,total)=>{})`（`wfc/client/wfc.uts:1636`；`MessageContentMediaType.File` 见 `wfc/messages/messageContentMediaType.uts:10`）。拿到 `remoteUrl` 后 `POST /files`（`createFile`）注册记录。对齐 Flutter `_uploadSmallFile`（`Imclient.uploadMediaFile`）。
- **大文件（可选）**：`wfc.isSupportBigFilesUpload()`（`wfc.uts:1666`）+ `wfc.getUploadMediaUrl(fileName, mediaType, contentType, ok, fail)`（`wfc.uts:1673`）取预签名地址，再用 `uni.uploadFile`（multipart）或 `uni.request` PUT 上传，最后 `POST /files`。Flutter 仅在桌面端走大文件；移动端可先不做，spec 要求时再补。
- **选择本地文件**：`wfc.chooseFile('all', (file)=>{ path/name/size }, failCB)`（`wfc.uts:2074`，鸿蒙分支用 `uni.chooseFile`）。路径要过 `toLocalFilePath`（`common/filePath.uts`，鸿蒙媒体库 uri 不能去 scheme）。
- **重名处理**：上传/创建前先 `getSpaceFiles`，按 `base(1).ext` 规则改唯一名（对齐 `_uniqueFileName`）。

### 6.2 下载 / 打开

- **消息文件**：`uni.downloadFile` + `uni.openDocument`（`common/mediaSaver.uts`、`FileMessageContentView.uvue`）。
- **网盘文件**：`getFileDownloadUrl(fileId)` → `storageUrl` → 交系统打开 / 保存（`uni.downloadFile` + `uni.openDocument`；鸿蒙保存面板用 uni 的文件接口）。
- **文档页内下载**：由 H5 调桥 `downloadFile({url})`，uni 侧走系统打开（第 4.3 节）。
- **进度/取消**：`uploadMediaFile`/`downloadFile` 都有 progress 回调，可挂 `uni.showLoading`/自绘进度；Flutter 的 `PanUploadCancelToken` 在 uni 侧可用一个 `ref<boolean>` 取消标志近似（SDK 不提供真正中断时的语义要写清）。

---

## 7. 文件级实施清单

### 7.1 新增文件

| 文件 | 内容 | 依赖/参考 |
|---|---|---|
| `api/panModel.uts` | 数据模型与解析、`formatPanSize`、`PanException` | `pan_service.dart:778-1073` |
| `api/panServerApi.uts` | 单例 API 客户端（上文端点表） | `pan_service.dart`、`api/collectionServerApi.uts` |
| `api/panDocUrl.uts` | `docOpenUrl/docViewUrl/docLicensesUrl/isDocUrl/isOnlineDocName` | `pan_service.dart:37-108` |
| `common/panSave.uts` | `saveFileMessageToMyPan(...)` | `pan/pan_save.dart` |
| `common/panFileOpen.uts`（可选） | `openFileByDefault/openFileOnline`，供会话/文件记录/收藏共用 | `utilities.dart` |
| `pages/pan/PanHomePage.uvue` | 空间列表（移动端） | `pan_home_screen.dart` 移动分支 |
| `pages/pan/PanFileListPage.uvue` | 文件/文件夹列表、操作菜单、上传、新建文件夹 | `pan_file_list_screen.dart`、`pan_widgets.dart` |
| `pages/pan/PanDocsPage.uvue` | 最近打开 / 共享给我 / 新建 / 打开 | `pan_docs_screen.dart` |
| `pages/pan/PanDocWebViewPage.uvue` | 托管文档 H5 + 桥宿主 | `WorkspaceWebViewPage.uvue` |
| `pages/pan/panDocBridge.uts` | 桥方法实现（第 4.3 节） | `pages/workspace/bridgeServerImpl.uts` |
| `pages/pan/PickGroupPage.uvue` | 群多选（`chooseGroup` 用） | `PickConversationPage` + `store.filterGroupConversation` |
| `pages/pan/PanDestinationPickerPage.uvue` | 保存/移动/复制目标选择（可后置） | `pan_destination_picker.dart` |
| `pages/pan/PanSharePage.uvue`（可后置） | 分享列表 + 选人/选群 + 发链接卡片 | `pan_share.dart`、`panDocBridge.setPageHeader` 的 share |

### 7.2 修改文件

| 文件 | 改动 |
|---|---|
| `config.uts` | 新增 `PAN_SERVER(_BACKUP)`、`PAN_DOC_BASE(_BACKUP)`、`getPanServer()`、`getPanDocBase()`、`isPanEnabled()` |
| `pages.json` | `pages` 追加第 2.3 节路由；不改 tabBar |
| `pages/me/MePage.uvue` | 文件卡片内新增门控「网盘 / 在线文档」（第 2.2 节） |
| `pages/conversation/ConversationPage.uvue` | 长按菜单新增 `docOnlinePreview/panSave/panSaveOpen` 及处理；import `panServerApi`/`panSave`/`panDocUrl` |
| `pages/conversation/message/content/FileMessageContentView.uvue` | 点击优先走在线只读预览 |
| `pages/conversation/message/content/LinkMessageContentView.uvue` | `isDocUrl` 时走内置文档 web-view |
| `common/mediaSaver.uts` | 抽出 `openFileByDefault`；或改为调用 `common/panFileOpen.uts` |
| `common/picker.uts` | 新增 `pickGroups` / `PickGroupsOptions`（配合 `chooseGroup`） |
| `i18n/lang-zh-CN.uts`、`lang-zh-TW.uts`、`lang-en.uts` | 新增 `me.net_disk`、`me.online_docs`、`pan.*`（保存成功/失败、空间、重命名、删除、新建文件夹、上传、下载、分享、在线预览…） |
| `static/image/setting/`（可选） | 拷贝 `net_disk.png`（用 icomoon 图标则跳过） |
| **服务端（若采用 4.2 首选方案）** `wf-pan-server/src/main/resources/doc-web/uni-bridge.js`（新增） + `index.html`/`open.html`/`licenses.html`（各加一行 `<script>`） | dsbridge → uni 协议适配层 |

### 7.3 建议实施顺序（可独立验证的里程碑）

1. **M1 数据层**：`config.uts` 地址 + `panModel` + `panServerApi`（先用 ApiTestPage 或临时入口验证 `spaces/list`）。
2. **M2 入口与首页**：`pages.json` + `MePage` 门控入口 + `PanHomePage`（空间列表）。
3. **M3 文件浏览/上传/下载**：`PanFileListPage`、新建文件夹、`wfc.chooseFile` 上传、下载打开。
4. **M4 在线文档桥**：`PanDocWebViewPage` + `panDocBridge` + 服务端 `uni-bridge.js`；先验证 `getAuthCode`→登录→打开 `fileId` 文档。
5. **M5 文档首页**：`PanDocsPage`（最近打开/共享给我/新建/移除）。
6. **M6 文件消息动作**：点击在线预览 + 长按三项 + `panSave`。
7. **M7 分享与目标选择**（可后置）：`setPageHeader` 的 share、`chooseContacts`/`chooseGroup`、`PanSharePage`、`PanDestinationPickerPage`。

---

## 8. 构建与验证

### 8.1 构建/静态检查

- HBuilderX：制作自定义基座 → 运行到 Android / iOS / 鸿蒙（真实设备）。网盘/文档需要网络，必须在**配了 `PAN_SERVER` 与 `PAN_DOC_BASE`** 的构建上验证。
- 静态脚本：
  - `node scripts/uts-transpile.js`（UTS 转译/类型检查）
  - `node scripts/check-uvue-css.js`（新增 `.uvue` 的 CSS 合法性；注意 `content`/`:before` 不支持）
  - `node scripts/check-store-reset.js`（若动了 `store.uts`）
- 新页面样式遵守 vapor 约定，避免 list-view 内用 `:style` 设尺寸。

### 8.2 功能验证清单

| 编号 | 验证点 | 期望 |
|---|---|---|
| V1 | 未配 `PAN_SERVER` | 「我的」不显示网盘/文档入口；文件消息菜单无网盘项；点击文件仍走原系统打开 |
| V2 | 配了 `PAN_SERVER` | 入口出现；`/spaces/list` 成功（检查 authCode 头与 `result/data` 解析） |
| V3 | 空间/文件夹浏览 | 列表、面包屑、空态、错误态正确；只读空间不显示上传/新建 |
| V4 | 上传 | `wfc.chooseFile` → 上传 → `POST /files` 记录出现；重名自动 `(1)` |
| V5 | 下载/打开 | `POST /files/url` 拿签名地址 → 系统打开；文档格式走在线预览 |
| V6 | 文档页登录 | `getAuthCode('admin',2,host)` → `/doc/session` 成功；文档能在 ONLYOFFICE 打开 |
| V7 | 桥方法 | `setPageHeader` 标题/按钮生效且按钮回调到页面；`downloadFile` 交系统不白屏；`chooseContacts`/`chooseGroup` 选完回 `code:0`、取消回 `-1` |
| V8 | 文件消息 | 点击文档消息 → 只读在线预览；长按 → 在线预览/存到网盘/存到网盘并打开；「存到网盘」后文件出现在个人空间且 `copy:true` |
| V9 | 链接卡片 | 文档链接卡片走内置 web-view，系统浏览器打不开的地址也能正常 |
| V10 | 双网 | 主备地址切换后，网盘接口与文档页地址都跟着切（`selectServer` + `redirectMediaUrl`） |
| V11 | 三端 | Android / iOS / 鸿蒙行为一致；鸿蒙 `chooseFile` 的 `file://docs/...` uri 经 `toLocalFilePath` 后上传成功 |
| V12 | 水印/主题 | web-view 页水印与明暗主题正确（`watermark-overlay`、`colors()`） |

### 8.3 风险与待确认（spec 落地后回填）

1. **桥协议**：服务端 `doc-web` 是 dsbridge，uni 是 `postMessage/evalJS`。首选改服务端加适配层；若禁止改服务端，用 `onReady` 注入 shim（首帧标题栏可能闪）。
2. **地址与响应体 envelope**：`/api/pan` vs `/api/v1`、`result` vs `data`、authCode vs authToken 三处版本差异，需按实际部署与 spec 收敛（第 3.2/3.4 节已给容错写法）。
3. **`chooseGroup` 数据源**：uni 侧现无独立“群选择”页；先基于 `store.filterGroupConversation('')` / `contactState.favGroupList` 实现。若需要“全部群”，确认 `wfc.getFavGroupList()` 是否覆盖（`getMyGroupList()` 目前就是它）。
4. **大文件上传**：移动端是否必须支持（>100MB / 强制预签名）；`wfc.getUploadMediaUrl` 只返回单个 URL，无 backup/type，需 spec 明确是否走 multipart。
5. **分享闭环**：`setPageHeader` 的 `share` 与 `PanSharePage` 是否首版必须；它涉及“发在线文档链接卡片”的消息构造。
6. **在线文档页面路径**：独立 `wf-pan-server` 是 `/pan/doc/`，合并服务是根 `/doc/`；用两个可配地址覆盖，spec 里应写明生产值。

---

## 附：关键参考位置速查

| 主题 | 参考路径 |
|---|---|
| 网盘服务（会话重建） | `chat/lib/pan/pan_service.dart` |
| 文档首页 | `chat/lib/pan/pan_docs_screen.dart` |
| 网盘首页/文件列表 | `chat/lib/pan/pan_home_screen.dart`、`pan_file_list_screen.dart`、`pan_folder_state.dart` |
| 保存到网盘 | `chat/lib/pan/pan_save.dart` |
| 分享 / 目标选择 | `chat/lib/pan/pan_share.dart`、`pan_destination_picker.dart`、`pan_widgets.dart` |
| 「我」页入口 | `chat/lib/settings/me_tab.dart:85-111` |
| 客户端 JS 桥 | `chat/lib/workspace/js_api.dart`、`wf_webview_screen.dart` |
| 文件消息动作 | `chat/lib/conversation/conversation_controller.dart:357-364,721-783,984-1025`、`chat/lib/utilities.dart`(`openFileByDefault`/`openFileOnline`/`downloadFile`) |
| uni 侧桥模板 | `uni-chat-x/pages/workspace/{WorkspacePage,WorkspaceWebViewPage,bridgeServerImpl,webViewMessage}.uvue/uts` |
| uni 侧 authCode 网络层 | `uni-chat-x/api/authCodeApiClient.uts`、`api/collectionServerApi.uts` |
| 服务端文档 H5 | `wf-pan-server/src/main/resources/doc-web/{app.js,index.html,open.html,licenses.html}` |
| 服务端文档接口 | `wf-pan-server/src/main/java/com/wildfirechat/pan/controller/{web/WebDocController,client/ClientDocsController}.java`、`wf-pan-server/README.md` |
