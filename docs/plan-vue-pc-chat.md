# vue-pc-chat 接入「网盘(pan)」+「在线文档(online docs)」实施方案

> 目标仓库：`/Users/rain/Workspace/vue-pc-chat`（Vue 3 + Electron 的野火 IM PC 客户端）
> 行为参照：`/Users/rain/Workspace/wf-enterprise-chat/chat/lib/pan/`（Flutter 客户端网盘/文档功能）
> 与 `/Users/rain/Workspace/wf-enterprise-chat/chat/lib/pc/`（PC 侧栏、整栏 tab 常驻、独立文档窗口、webview/JS 桥）
> 服务端：`/Users/rain/Workspace/wf-pan/wf-pan-server`（独立网盘服务，客户端口 `/api/v1`，文档页 `/doc/`）
> 说明：`/Users/rain/Workspace/wf-pan/docs/client-pan-docs-spec.md` 当前**不存在**，本方案的行为契约全部由 Flutter 参考代码与两端服务端源码反推得出，已在下文标注来源文件与行号。**本方案只描述改动，不改任何代码。**

---

## 0. 结论摘要（给实现者的最短路径）

1. 新增 `src/api/panApi.js`（HTTP 客户端，鉴权沿用现有 `wfc.getAuthCode` + `authCode` header 模式），配置项 `Config.PAN_SERVER` / `Config.PAN_BACKUP_SERVER`（默认 `null`，为空即整个功能隐藏）。
2. 主窗口左侧栏 `HomePage.vue` 增加两个受 `isPanConfigured()` 门控的入口：**网盘**、**在线文档**；路由 `/home/pan`、`/home/docs`，复用现有 `<keep-alive>` 做 tab 常驻。
3. 在线文档编辑页是 ONLYOFFICE 的 H5 页面（`<panBase>/doc/open?...`）。该页面用 **dsbridge** 协议与客户端通信，而 vue-pc-chat 现有 webview 桥是自研 WebSocket 协议 —— 需要新增一个 **dsbridge 兼容 shim**（注入 `window._dsbridge`）把 `getAuthCode / setPageHeader / downloadFile / chooseContacts / chooseGroup / openUrl / _dsb.hasNativeMethod` 转发到现有桥总线。
4. 独立文档窗口**可行**：完全复用 `background.js` 的 `createWindow()` 与集合/投票窗口那套单例 IPC 模式，新增 `SHOW_DOC_WINDOW` + `docWindow`，加载 `#/doc-window`，窗口内用 `electron-tabs` 一篇文档一个页签（对齐 Flutter `pc/doc_window/`）。
5. 文件消息的在线预览/存网盘：改 `ConversationView.vue` 右键菜单 + `FileMessageContentView.vue` 点击行为；上传下载复用现有 `wfc.uploadMedia` / `wfc.getUploadMediaUrl` 与 `IPCEventType.DOWNLOAD_FILE`。
6. 默认不改服务端。但要真正可用，服务端需部署 `wf-pan-server` 且 `docs.enabled=true`、`media.trusted_url_prefixes` 覆盖 IM 媒体桶（见 §3.5、§7.3）。

---

## 1. 技术栈、构建运行与目录结构

### 1.1 技术栈与入口

| 项 | 事实（含路径） |
|---|---|
| 框架 | Vue 3.4.19、Vue Router 4.3（**hash** 模式）、Pinia 已装但项目实际用自定义 store |
| 构建 | Vue CLI 5 + `@matthijsburgh/vue-cli-plugin-electron-builder` 3.x |
| 桌面 | Electron 22.3.27，Chromium（`webview` 标签可用） |
| HTTP | axios 1.6.7；另有原生 `fetch`（ASR 用） |
| 状态 | `src/store.js`（约 130KB，`pstore` 持久化的自定义响应式 store，非 Pinia module） |
| 主进程 | `src/background.js`（`package.json.main = "background.js"`，构建后为 `dist_electron/background.js`） |
| 渲染进程 | `src/main.js` → `src/App.vue` → `src/routers.js` |
| 预加载 | `vue.config.js:39` `pluginOptions.electronBuilder.preload = 'src/ui/workspace/bridgeClientImpl.js'`（构建为 `dist_electron/preload.js`），**全应用唯一 preload** |
| 关键编译开关 | `vue.config.js:101-103` `contextIsolation:false`、`nodeIntegration:true`、`webSecurity:false`；`webviewTag` 在新窗口里显式开启（`background.js:1330` 附近） |

### 1.2 目录结构（与本次改动相关的部分）

```
src/
├── background.js            # Electron 主进程：窗口创建、IPC、下载、WebSocket 桥服务
├── config.js                # 所有服务地址与功能开关（静态字段 + xxx_BACKUP_SERVER + _selectServer）
├── routers.js               # 全部路由（hash）
├── main.js                  # 渲染入口；按 hash 决定 wfc.init() 或 wfc.attach()
├── store.js                 # 自定义 store（state.misc / conversation / contact / search / pick）
├── ipcEventType.js          # 主/渲染进程 IPC 事件名常量
├── platform.js / platformHelper.js  # isElectron()、ipcRenderer、shell、downloadFile()
├── api/                     # 各后端 HTTP 客户端（collectionApi/pollApi/organizationServerApi/...）
├── ui/
│   ├── main/
│   │   ├── HomePage.vue     # 主界面外壳：左侧 60px 侧栏 + 会话列表 + <keep-alive> 路由视图
│   │   ├── SubWindowHost.vue# Web(非 Electron) 下的子窗浮层（registry 路由→组件）
│   │   └── conversation/    # 会话与消息：ConversationView.vue（右键菜单）、message/content/…
│   ├── workspace/           # 开放平台工作台：electron-tabs + webview + 自研桥
│   │   ├── WorkspacePage.vue
│   │   ├── bridgeServerImpl.js   # 宿主渲染进程侧的桥 handler
│   │   └── bridgeClientImpl.js   # webview 内 preload，暴露 window.__wf_bridge_
│   ├── collection/ poll/ …  # 已有同类功能模块，可作模板
│   └── util/helper.js       # humanSize / getFiletypeIcon 等
└── assets/lang/{zh-CN,zh-TW,en}.json   # i18n：按业务分顶层 key（common/collection/poll/…）
```

### 1.3 构建/运行命令（`package.json`）

```bash
# 开发（electron 模式，自动 validate + copy-proto）
npm run dev

# 仅以 electron 方式启动（跳过 validate）
npm run electron:dev

# 打包当前平台
npm run package

# 交叉打包
npm run cross-package-win        # win x64
npm run cross-package-linux      # linux x64
npm run cross-package-all        # win x64 + win ia32 + linux x64 + linux arm64

# 校验（validate 脚本校验运行环境/native 模块）
npm run validate
```

> `scripts/validate.js`、`scripts/copy-proto.js`、`marswrapper.node` 是原生协议栈；本功能不触碰。

### 1.4 多窗口处理

- 统一工厂：`src/background.js:1322` `createWindow(url, w, h, mw, mh, resizable=true, maximizable=true, showTitle=true, webSecurity=false, minimizable=true)`。
- 现有窗口：`mainWindow`、`fileWindow`（`SHOW_FILE_WINDOW`，background.js:981）、`collectionWindow`（`SHOW_COLLECTION_WINDOW`，background.js:1120）、`pollWindow`（background.js:1143）、`compositeMessageWindows`、`conversationWindowMap`、`multimediaPreviewWindow`。
- 现有窗口通过"渲染进程拼好 URL → `ipcRenderer.send(事件,{url})` → 主进程单例创建/reuse → `win.on('close')` 置 null"这一段式实现。**文档窗口照抄 `collectionWindow` 模式即可**。
- 渲染进程侧窗口身份：`window.getMediaSourceId()`（`ipcMain.handle('getMediaSourceId')`，background.js:1270）用于桥的窗口过滤。

---

## 2. 导航/菜单注册：侧栏与 tab

### 2.1 侧栏位置

`src/ui/main/HomePage.vue`：

- 侧栏 DOM：`<section class="menu-container">`（第 7 行）→ `<nav class="menu"><ul>`（第 37-92 行），每个入口是一个 `<li><div class="i-button-wrapper" @click="go2Xxx"><i class="icon-..."/></div></li>`。
- 已有门控样例：
  - 文件：`v-if="sharedMiscState.isElectron && sharedMiscState.isCommercialServer"`（第 59 行），点击走 IPC 开独立窗口（`go2Files()`，第 266-279 行）。
  - 工作台：`v-if="sharedMiscState.enableOpenWorkSpace"`（第 65 行），`this.$router.replace('/home/h-wp')`。
  - AI：`v-if="aiPortalUrl"`（第 77 行，`Config.AI_PORTAL_URL`）。
- 路由内容区：`<router-view v-slot="{ Component, route }"><keep-alive ...><component :is="Component" :key="route.path"/></keep-alive>...`（第 94-99 行）。**tab 常驻直接复用，无需额外实现**（对齐 Flutter `_fullPaneTabs` 的整栏常驻）。

### 2.2 新增入口

在 `<ul>` 内、设置按钮（第 83 行）之前插入：

```html
<!-- 网盘：配置了才显示 -->
<li v-if="panConfigured">
    <div class="i-button-wrapper" @click="go2Pan">
        <i class="icon-ion-ios-folder"
           :class="{active: $router.currentRoute.value.path === '/home/pan'}"></i>
    </div>
</li>
<!-- 在线文档：网盘在且宿主支持内嵌 webview 才显示 -->
<li v-if="panConfigured && docWebviewSupported">
    <div class="i-button-wrapper" @click="go2Docs">
        <i class="icon-ion-document-text"
           :class="{active: $router.currentRoute.value.path === '/home/docs'}"></i>
    </div>
</li>
```

- `panConfigured` 为 `computed: () => Config.isPanConfigured()`（见 §4）。
- `go2Pan()` → `this.$router.replace('/home/pan')`；`go2Docs()` → `this.$router.replace('/home/docs')`（保持与工作台一致的整栏 tab 语义）。
- 图标：优先复用 icomoon（`src/assets/fonts/icomoon/style.css`）里已有 glyph（`icon-ion-ios-folder` 已被"文件"占用，可用 `icon-ion-android-cloud`/`icon-ion-code-working` 等）；若无合适，需向 icomoon 字体补 glyph 后更新 `src/assets/fonts/icomoon/`。**不新增图片依赖**。
- 底部设置按钮的 `:last-of-type { margin-top:auto }` 布局（第 527-530 行）要求新 `<li>` 插在它之前，否则"设置"贴底失效。

### 2.3 路由注册

`src/routers.js` 在 `/home` 的 `children` 中追加（与 `h-wp` 同级）：

```js
import PanHomePage from './ui/pan/PanHomePage.vue'
import PanDocsPage from './ui/pan/PanDocsPage.vue'
import DocWindowPage from './ui/pan/DocWindowPage.vue'
...
{ path: 'pan',  name: 'pan',  component: PanHomePage },
{ path: 'docs', name: 'docs', component: PanDocsPage },
```

并新增顶层路由（供独立文档窗口加载，类似 `/files`、`/message`）：

```js
{ path: '/doc-window', name: 'doc-window', component: DocWindowPage },
```

### 2.4 Web(非 Electron) 兜底

`src/ui/main/SubWindowHost.vue` 的 `registry`（第 40-49 行）是 Web 端浮层子窗注册表。网盘/文档在 Web 端可以：
- 最简单：`go2Pan/go2Docs` 在 `!isElectron()` 时 `window.open(<web 站内地址>)` 或整页跳转，不入 `SubWindowHost`；
- 若要与接龙/投票一致体验：把 `'/pan'`、`'/docs'` 注册进 `registry`，并在打开方发 `$eventBus.$emit('sub-window-open', {route:'/pan'})`。**推荐先做 Electron，Web 走新窗口跳转**，因为文档编辑器依赖桥。

---

## 3. HTTP 客户端与鉴权

### 3.1 两种服务端形态（必须先定部署）

| 形态 | 源码 | 客户端接口前缀 | 鉴权 | 文档页 |
|---|---|---|---|---|
| **A. 独立网盘服务（本仓库 `/Users/rain/Workspace/wf-pan`，推荐本期）** | `wf-pan-server` | `POST <panBase>/api/v1/**` | 直接带 header `authCode`（`ClientAuthFilter.java:66-79`） | `<panBase>/doc/open`（`WebDocController` + `doc-web/`） |
| B. 合并服务 `wf-app-server` 的 `app-pan` 模块（Flutter 参考当前对接的形态） | `/Users/rain/Workspace/wf-app-server/app-pan` | `POST <appBase>/api/pan/**` | `authCode` 调 `/api/auth/login` 换 `authToken`，业务请求带 header `authToken` | `<appBase>/doc/open` |

Flutter `pan_service.dart:22-24` 注释明确其对接的是形态 B；但本仓库旁的部署是形态 A，且 vue-pc-chat 现有 `collectionApi.js`/`pollApi.js` 用的就是「`wfc.getAuthCode` + `authCode` header」的形态 A 风格。

**方案：客户端以形态 A 为主实现，服务地址与鉴权通过配置解耦，形态 B 只改 base 与是否换 token（在 §3.4 给出差异）。**

> ⚠️ **契约来源提醒**：Flutter 参考实现（`pan_service.dart:22-24`、`config.dart:289`）以及将要写在 `wf-pan/docs/` 的 spec，描述的是**形态 B**（网盘 API 在 `{appService}/api/pan`、文档页在 `{appService}/doc/`）。而本仓库旁的部署 `/Users/rain/Workspace/wf-pan` 是**形态 A**（`/api/v1` + `/doc/`）。两者 UI 行为完全一致，只有 base 与鉴权头不同。**实现前先以最终 spec 为准锁定形态**；若 spec 采用形态 B，则 §3.2 的 `PAN_SERVER` 设为 `{appService}/api/pan`、`PAN_DOC_BASE` 设为 `{appService}/doc/`，§3.3 的 `_post` 换成 §3.4 的 token 流程，其余章节（UI/桥/文件消息）不变。

### 3.2 配置项（`src/config.js`）

在 `COLLECTION_SERVER`/`POLL_SERVER` 附近新增（默认 `null` 表示未配置、功能隐藏）：

```js
// 网盘服务地址（独立 wf-pan 客户端口，含 /api/v1 前缀），未部署置 null
static PAN_SERVER = null;            // 例如 'https://pan.example.com/pan/api/v1'
static PAN_BACKUP_SERVER = null;     // 双网备选
```

并补充 getter（沿用 `Config._selectServer`，见 config.js:166-215）：

```js
static getPanServer() {
    return Config._selectServer(Config.PAN_SERVER, Config.PAN_BACKUP_SERVER);
}
// 在线文档 H5 根地址：由网盘地址推出（.../api/v1 → .../doc/）
static getDocBaseUrl() {
    const pan = Config.getPanServer();
    if (!pan) return null;
    return pan.replace(/\/api\/v1\/?$/, '/doc/');
}
static isPanConfigured() {
    return !!Config.getPanServer();
}
```

> 若部署形态 B：`PAN_SERVER = '<appBase>/api/pan'`，`getDocBaseUrl()` 需改为 `<appBase>/doc/`（去掉 `/api/pan` 前缀，参考 Flutter `Config.appServiceAddress + '/doc/'`，`pan_service.dart:37-49`）。建议把"网盘根地址"和"文档根地址"拆成两个显式配置项 `PAN_SERVER` 与 `PAN_DOC_BASE`，避免正则猜测，兼容两种形态。

### 3.3 客户端实现（新文件 `src/api/panApi.js`）

以 `src/api/collectionApi.js` 为模板（这是最贴近形态 A 的现有代码）：

```js
import axios from 'axios';
import Config from '../config';
import wfc from '../wfc/client/wfc';
import PanError from './panError';

export class PanApi {
    async _post(path, data = {}) {
        const baseUrl = Config.getPanServer();
        if (!baseUrl) throw new PanError(-1, '网盘服务未配置');
        const host = baseUrl.replace(/^https?:\/\//, '').split('/')[0];
        const authCode = await new Promise((resolve, reject) => {
            wfc.getAuthCode('admin', 2, host, resolve, e => reject(new PanError(e, '获取认证码失败')));
        });
        const resp = await axios.post(baseUrl + path, data, {
            headers: { authCode },
            withCredentials: false,
        });
        if (resp.data && resp.data.code === 0) return resp.data.data;
        throw new PanError(resp.data ? resp.data.code : -1, resp.data ? resp.data.message : '请求失败');
    }
}
```

- `appId='admin'`、`appType=2` 与 Flutter `PanService._authCodeId/_authCodeType`（`pan_service.dart:26-27`）以及组织通讯录一致。
- 所有接口 `POST`，返回 `{code,message,data}`（服务端 `dto/Result.java`）；`code!==0` 抛 `PanError`。
- 每个请求前重新取 `authCode`（1 分钟有效，与 `asrServerApi.js:16-23` 注释一致）。
- **双网**：`Config.getPanServer()` 已按 `wfc.connectedToMainNetwork()` 选主备；`authCode` 的 host 用实际选中的 host（照抄 `collectionApi.js:44-45`）。媒体地址重定向沿用 `Config.urlRedirect()`。

### 3.4 形态 B 差异（若客户选择合并服务）

- `_post` 改为：启动/首次请求时 `POST <appBase>/api/auth/login` body `{authCode}`，从响应 `authtoken`/`authToken` 头取 token 并缓存（参考 `organizationServerApi.js:40-62` 的 token 存储，键 `authToken-<host>`），随后业务请求带 header `authToken`，遇 401/1001 重新 login。
- base 换成 `<appBase>/api/pan`，其余路径不变（服务端 `app-pan/.../ClientSpaceController.java:23` `@RequestMapping("/api/pan/spaces")`，与独立版 `/api/v1/spaces` 仅前缀不同）。

### 3.5 需要用到的接口清单（客户端口，均为 POST）

来源：`wf-pan-server/.../controller/client/*.java`，字段来自 `dto/request`、`dto/vo`。

| 模块 | 路径 | body | 返回 data |
|---|---|---|---|
| 空间 | `/spaces/list` | `{}` | `SpaceVO[]` |
| 空间 | `/spaces/my` | `{}` | `SpaceVO[]`（自己公共+私有） |
| 空间 | `/spaces/user/public` | `{targetUserId}` | `SpaceVO` |
| 空间 | `/spaces/files` | `{spaceId,parentId}` | `FileVO[]` |
| 权限 | `/files/check-permission` | `{spaceId}` | `bool` |
| 文件 | `/files/folder` | `{spaceId,parentId?,name}` | `FileVO` |
| 文件 | `/files` | `{spaceId,parentId?,name,size,storageUrl,mimeType?,md5?,copy?}` | `FileVO` |
| 文件 | `/files/delete` | `{fileId}` | – |
| 文件 | `/files/rename` | `{fileId,newName}` | `FileVO` |
| 文件 | `/files/move` | `{fileId,targetSpaceId,targetParentId}` | `FileVO` |
| 文件 | `/files/copy` | `{fileId,targetSpaceId,targetParentId}` | `FileVO` |
| 文件 | `/files/url` | `{fileId,versionNo?}` | `{fileId,name,storageUrl,versionNo,permission}` |
| 分享 | `/shares/list` | `{fileId}` | `ShareVO[]` |
| 分享 | `/shares/add` | `{fileId,targetType:'USER'|'GROUP',targetId,permission:'VIEW'|'EDIT'}` | `ShareVO` |
| 分享 | `/shares/remove` | `{shareId}` | – |
| 分享 | `/shares/with-me` | `{}` | `SharedFileVO[]` |
| 文档 | `/docs/create` | `{type:'docx'|'xlsx'|'pptx',name?}` | `FileVO` |
| 文档 | `/docs/editor-config` | `{fileId,platform:'pc',view?}` | `{fileName,canEdit,viewReason,convertible,canShare,apiUrl,config}` |
| 文档 | `/docs/view-url` | `{url,name?,platform:'pc'}` | 同上（只读） |
| 文档 | `/docs/convert` | `{fileId}` | `FileVO` |
| 文档 | `/docs/options` | `{}` | `{mobileEdit}` |
| 文档 | `/docs/recent` | `{}` | `RecentDocVO[]`（`{file,permission,openedAt}`） |
| 文档 | `/docs/recent/remove` | `{fileId}` | – |
| 版本 | `/versions/list` | `{fileId}` | `FileVersionVO[]` |
| 版本 | `/versions/restore` | `{fileId,versionNo}` | `FileVersionVO` |

VO 字段（`FileVO`/`SpaceVO`/`ShareVO`/`RecentDocVO`/`SharedFileVO`）：`FileVO{id,spaceId,parentId,name,type:'FILE'|'FOLDER',size,mimeType,md5,storageUrl,childCount,versionNo,creatorId,creatorName,creatorPortrait,createdAt,updatedAt}`；`SpaceVO{id,spaceType,ownerId,name,totalQuota,usedQuota,fileCount,folderCount,autoInit,createdAt,updatedAt,canManage}`。**注意**：服务端 `type` 序列化为字符串（`FileVO.type` 是枚举），而 Flutter 兼容老版 `1/0`（`pan_service.dart:886-896`），客户端做兼容解析。

### 3.6 模块拆分建议

`src/api/panApi.js` 按领域再拆也行，但与现有 `api/` 一文件一服务的风格一致的做法是：
- `src/api/panApi.js`：`_post` + 空间/文件/分享/版本方法；
- `src/api/panDocsApi.js`：文档相关（或并入 `panApi.js`）；
- `src/api/panError.js`：`class PanError extends Error { code; message }`。

---

## 4. 功能开关/配置门控

对齐 Flutter：「`Config.panServerAddress` 为空则侧栏不显示网盘；在线文档是网盘自带页面，网盘在且有内嵌 webview 才显示」（`pc_home.dart:984-1000`）。

1. `Config.PAN_SERVER = null` 默认关闭；部署时填地址。
2. `HomePage.vue` 用 `panConfigured` computed 门控；`docWebviewSupported` = `isElectron() && 支持 <webview>`（Electron 22 恒 true），Web 端可另判。
3. 消息右键菜单同样用 `panConfigured` 门控「存到网盘/在线预览」，否则老用户界面完全不变（降低回归风险）。
4. 可选：登录后在 `store.js` 的 `misc` 写入 `panConfigured`（`this.state.misc.panConfigured = Config.isPanConfigured()`），便于模板里使用；不改 store 也能工作（模板直接 `$t`/computed 读 Config）。
5. i18n：`src/assets/lang/{zh-CN,zh-TW,en}.json` 增加顶层 `pan`、`docs` 两个分组，并在 `common` 补 `download/open/share/...` 缺省项。`main.js:107-116` 已按 `zh-CN/zh-TW/en` 装载，键用 `$t('pan.xxx')`。

---

## 5. Webview + JS 桥（本方案技术难点）

### 5.1 现有机制

- 工作台 `WorkspacePage.vue` 用 `electron-tabs` 创建 `<webview>`（`WorkspacePage.vue:102-117`），`preload` 在 dev 指向源码 `bridgeClientImpl.js`、prod 指向 `preload.js`，并设置 `nodeintegration/contextIsolation:false`。
- webview 内 `bridgeClientImpl.js` 暴露 `window.__wf_bridge_ = {call, register}`，通过 **本机 WebSocket**（`ws://127.0.0.1:7983/`）与宿主渲染进程通信；消息体 `{type:'wf-op-request', requestId, windowId, appUrl, handlerName, args}`，响应 `wf-op-response`。
- WebSocket 服务端在**主进程**：`background.js:1670 startOpenPlatformServer(port)`，端口由 `Config.OPEN_PLATFORM_SERVE_PORT=7983` 决定（background.js:1266-1268 由渲染层 `ipcRenderer.send(...)` 启动），实现是"广播所有消息到所有连接"。
- 宿主渲染进程侧 handler：`src/ui/workspace/bridgeServerImpl.js`，已注册 `toast/openUrl/getAuthCode/config/chooseContacts/close`，并用 `getMediaSourceId` 过滤窗口。

### 5.2 文档页用的是 dsbridge，不是 `__wf_bridge_`

`wf-pan-server/src/main/resources/doc-web/app.js`（**已随服务端发布，客户端不能改**）：

- `Bridge.available()`（app.js:14-17）：`window._dsbridge || window._dswk || /WF-DSBridge|_dsbridge/.test(navigator.userAgent)`；
- 同步调用：`window._dsbridge.call(method, JSON.stringify({data}))`，其**同步返回值**必须是 JSON 字符串。
- 异步调用：参数带 `_dscbstub`（回调函数名），客户端完成后调用 `window[_dscbstub](JSON.stringify({code,data}))`（app.js:23-46）。
- 多次回调：`Bridge.listen`（app.js:48-53）用于 `setPageHeader`，客户端通过"进度数据"多次回调同一 `_dscbstub`。
- 探测：`_dsb.hasNativeMethod`（app.js:54-58），同步返回 `{code:0,data:true|false}`。
- 页面实际用到的桥方法：
  - `getAuthCode {appId:'admin',appType:2}` → `{code:0,data:<authCode>}`（app.js:78-93）；
  - `openUrl`（同步，字符串）→ 新页签/新页面（app.js:176-190）；
  - `downloadFile {url}`（同步；见 app.js:194-199，`Bridge.has('downloadFile')` 时用，否则退化 `openUrl`）；
  - `setPageHeader {title?,subtitle?,actions:[{id,text,icon,primary}]}`（异步+多次回调，回调参数是按钮 id；`open.html:43/65/79-83`，含 `share` 按钮）；
  - `chooseContacts`（异步，回 `JSON 串 [{uid,name,displayName,portrait}]`；`open.html:249-257`）；
  - `chooseGroup`（异步，回 `JSON 串 [{gid,name,portrait}]`；`open.html:259-266`）；
  - `_dsb.hasNativeMethod`（同步）。

### 5.3 实现：dsbridge 兼容 shim

在 `src/ui/workspace/bridgeClientImpl.js` 中追加（或新增 `src/ui/pan/docBridgeClient.js` 作为同一 preload 的分支）：

```js
// 让 doc-web/app.js 认为自己在野火客户端里
window._dsbridge = {
    call(method, argJson) {
        const arg = argJson ? JSON.parse(argJson) : {};
        const data = arg.data;
        const stub = arg._dscbstub;
        switch (method) {
            case '_dsb.hasNativeMethod':
                return JSON.stringify({ code: 0, data: SUPPORTED.has(data && data.name) });
            case 'openUrl':
                __wf_bridge_.call('docOpenUrl', data);           // 同步、fire-and-forget
                return JSON.stringify({ code: 0 });
            case 'downloadFile':
                __wf_bridge_.call('docDownloadFile', data);      // 同步
                return JSON.stringify({ code: 0 });
            case 'getAuthCode':
                __wf_bridge_.call('getAuthCode', data, r => window[stub](JSON.stringify(r)));
                return JSON.stringify({ code: -1 });             // 异步：同步返回不表态
            case 'setPageHeader':
                __wf_bridge_.call('docSetPageHeader', data, r => window[stub](r)); // 多次回调
                return JSON.stringify({ code: -1 });
            case 'chooseContacts':
                __wf_bridge_.call('chooseContacts', {}, r => window[stub](JSON.stringify(r)));
                return JSON.stringify({ code: -1 });
            case 'chooseGroup':
                __wf_bridge_.call('chooseGroup', {}, r => window[stub](JSON.stringify(r)));
                return JSON.stringify({ code: -1 });
            default:
                return JSON.stringify({ code: -1 });
        }
    }
};
```

要点：
- 文档 webview 的 `useragent` 也建议追加 `WF-DSBridge`（`webviewAttributes.useragent`），双保险（`app.js` 的 UA 分支）；
- `__wf_bridge_.call` 的 callback 在现有实现里响应一次即删除（`bridgeClientImpl.js:64-71`）；`setPageHeader` 需要多次回调 —— 要么新增一种"事件式"消息（宿主发 `wf-op-event`，preload 的 `register` 回调），要么在 preload 端用一个本地 `window[_dscbstub]` 包装并让宿主每次按钮点击都发事件。**推荐事件式**：宿主用 `wf-op-event` 把按钮 id 推给 webview，preload 收到后调用当前 `_dscbstub`。
- `_dsb.hasNativeMethod` 对 `setPageHeader/chooseGroup/downloadFile` 必须返回 true，否则页面会自己画标题栏、禁用选群/下载按钮（`open.html:205`、`app.js:196`）。

### 5.4 宿主侧 handler（`bridgeServerImpl.js` 扩展）

`bridgeServerImpl.init(wfc, hostPage, wsPort)` 的 `handlers`（`bridgeServerImpl.js:60-68`）增加：

| handler | 实现 |
|---|---|
| `getAuthCode` | 已有；确认 host 取 webview 当前地址 host（用于 authCode 与请求 host 一致） |
| `openUrl` | 现有实现是 `addTab`/`openExternal`；文档窗口场景应改为 `hostPage.openDocTab(url)`（同一文档窗口开新页签，见 §5.5） |
| `docDownloadFile` | `ipcRenderer.send(IPCEventType.DOWNLOAD_FILE, {remotePath, fileName, windowId})`；或直接 `shell.openExternal`/浏览器下载，取决于产品取向（Flutter 桌面端是交给默认浏览器，`js_api.dart:121-135`）。推荐复用现有下载 IPC，落盘后 `shell.openPath` |
| `docSetPageHeader` | 设置宿主文档窗口的响应式 `header` 状态（标题/副标题/按钮），返回按钮点击 id（事件式回调） |
| `chooseContacts` | 复用现有 `hostPage.chooseContacts`（`WorkspacePage.vue:70-75` 已实现，走 `$pickContact`）。返回值需是 `JSON 串 [{uid,name,displayName,portrait}]` 并包进 `{code:0,data}` |
| `chooseGroup` | 新增：弹出选群组件（可复用 `src/ui/main/pick/` 或 Forward 的选群 UI），返回 `{code:0,data: JSON 串 [{gid,name,portrait}]}` |

> `chooseContacts` 的回调协议必须匹配 `doc-web/app.js:_answerWith`：成功 `{code:0,data}`、取消 `{code:-1}`、页面已跳走 `{code:-2}`。Flutter 端同样约定（`js_api.dart:230-277`），可对照。

### 5.5 独立 Electron 文档窗口（对齐 Flutter `pc/doc_window/`）

**可行**，而且与现有集合/投票窗口完全同构。

- 新增 IPC 事件 `SHOW_DOC_WINDOW`（`src/ipcEventType.js`），主进程 `background.js` 复制 `SHOW_COLLECTION_WINDOW` 那段（background.js:1120-1142）：
  ```js
  ipcMain.on(IPCEventType.SHOW_DOC_WINDOW, async (event, args) => {
      const url = args.url;                       // 渲染层拼好 #/doc-window?url=...
      if (!docWindow) {
          docWindow = createWindow(url, 1200, 840, 720, 560, true, true, true, true);
          docWindow.on('close', () => { docWindow = null; });
          docWindow.show();
      } else {
          docWindow.loadURL(url);                 // 复用窗口：新文档作为 open 事件/新页签传入
          docWindow.show(); docWindow.focus();
      }
  });
  ```
- **两个实现约束**（读 `background.js` 得到）：① `createWindow()` **不接受 preload 参数**，新窗口 renderer 靠 `nodeIntegration:true` 直接 `require('electron')`；但窗口里的 `<webview>` 必须像 `WorkspacePage.addTab` 一样显式传 `preload: 'file://${__dirname}/preload.js'`（dev 指向 `src/ui/workspace/bridgeClientImpl.js`），否则文档页拿不到桥。② `background.js` 所有 `ipcMain.on(...)` 都注册在 `createMainWindow()` 函数体内部（约 L831-L1318），**新事件必须写在该函数内部**，否则不生效。
- 渲染层封装 `src/ui/pan/docWindow.js`：`openDoc(url, title)` → 拼 `#/doc-window?url=<enc>&name=<enc>` → `ipcRenderer.send(SHOW_DOC_WINDOW, ...)`。窗口已存在时，主进程 `loadURL` 会重建页面，会丢页签；**更好的做法**是像 Flutter `DocWindowManager.onReuseContent` 一样向已存在窗口 `webContents.send('doc-window-open', payload)`，由 `DocWindowPage.vue` 监听并 `addTab`（窗口关闭时再 `loadURL` 重建）。
- `src/ui/pan/DocWindowPage.vue`：`electron-tabs` + `<webview>`（复制 `WorkspacePage.vue` 的 `addTab`），一文档一页签；`setPageHeader` 数据驱动窗口顶部自绘标题栏/按钮；`tab-active/tab-removed` 处理（最后一个页签关闭 → `remote.getCurrentWindow().close()`，`WorkspacePage.vue:169-174`）。页签模型对齐 Flutter `doc_tabs_view_model.dart`：同一篇文档按「path+query」去重（主备 host 不同也算同一篇）、上限 8 个（超出挤掉非当前的最早页签）、关闭后激活左邻页签、标题用文件名。
- **同一渲染进程只跑一个桥 server**：`WorkspacePage.vue` 里 `init(wfc, this, Config.OPEN_PLATFORM_SERVE_PORT)`（第 182 行）是 host 侧 client 连接；文档窗口也 `init(wfc, this, 7983)`，靠 `windowId`（各自窗口的 mediaSourceId）隔离，不会串（`bridgeServerImpl.js:35-38` 过滤 `obj.windowId`）。文档窗口 page 与主窗口 page 分属不同 BrowserWindow，mediaSourceId 不同，安全。
- 主窗口内嵌 vs 独立窗口：**独立窗口优先**（对齐 Flutter、编辑器需要宽屏）；Web 端退化为整页路由 `/doc-window` 或新标签页。

### 5.6 文档页 URL 组装（`src/ui/pan/panUtils.js`）

```js
export function docOpenUrl(fileId) {
    return `${Config.getDocBaseUrl()}open?fileId=${encodeURIComponent(fileId)}`;
}
export function docViewUrl(url, name) {
    const q = [`url=${encodeURIComponent(url)}`];
    if (name) q.push(`name=${encodeURIComponent(name)}`);
    return `${Config.getDocBaseUrl()}open?${q.join('&')}`;
}
export function docLicensesUrl() { return `${Config.getDocBaseUrl()}licenses.html`; }
```

- 判定在线文档地址（聊天链接卡片）：`isDocUrl(url)` 匹配 `PAN_SERVER` 与 `PAN_BACKUP_SERVER` 派生出的 `<base>/doc/` 前缀（对齐 `pan_service.dart:66-77`，主备都认）。
- 判定可在线打开的文件名：按扩展名白名单（Word/Cell/Slide/PDF 四组，见 `pan_service.dart:78-96`）。

---

## 6. 文件消息的渲染与动作

### 6.1 现状

- 组件：`src/ui/main/conversation/message/content/FileMessageContentView.vue`。点击 `clickFile()`（第 48-78 行）在 Electron 下：本地已下载则 `shell.openPath`，否则 `downloadFile(message)` 起下载。
- 右键菜单：`src/ui/main/conversation/ConversationView.vue` 的 `<vue-context ref="menu">`（第 118-155 行），每项 `v-if="isXxx(message)"` + `@click.prevent="handler(message)"`；相关方法 `isDownloadable`、`download`（第 775-783 行）、`_forward`、`recallMessage` 等。
- 消息内容字段：`FileMessageContent`（`src/wfc/messages/fileMessageContent.js`）继承 `MediaMessageContent`，实际字段为 **`name`、`size`、`remotePath`、`localPath`**（注意不是 `remoteUrl`；在 Electron 端 `remotePath` 即远端地址，`localPath` 为本地缓存）。下载/打开用 `src/platformHelper.js:96-116 downloadFile()`。

### 6.2 新增动作（对齐 Flutter `conversation_controller.dart:724-760`、`pan_save.dart`）

在 `ConversationView.vue` 的 `vue-context` 中，紧跟"存储/下载"之前加入：

```html
<li v-if="panConfigured && isOnlineDocMessage(message)">
  <a @click.prevent="openFileOnline(message)">{{ $t('docs.onlinePreview') }}</a>
</li>
<li v-if="panConfigured && isFileMessage(message)">
  <a @click.prevent="saveMsgToPan(message, false)">{{ $t('pan.saveToMyPan') }}</a>
</li>
<li v-if="panConfigured && isFileMessage(message)">
  <a @click.prevent="saveMsgToPan(message, true)">{{ $t('pan.saveToMyPanAndOpen') }}</a>
</li>
```

方法（`ConversationView.vue` methods）：

```js
isFileMessage(message) { return message.messageContent instanceof FileMessageContent; }
isOnlineDocMessage(message) {
    const c = message.messageContent;
    return c instanceof FileMessageContent && c.remotePath && isOnlineDocName(c.name) && !!panConfigured;
}
openFileOnline(message) {
    const c = message.messageContent;
    // 文档格式走只读在线打开；非文档/未配置退回 openFileByDefault
    if (!isOnlineDocName(c.name)) return this.openFileOrSystem(message);
    docWindow.openDoc(docViewUrl(Config.urlRedirect(c.remotePath), c.name), c.name);
}
saveMsgToPan(message, openAfter) {
    saveFileMessageToMyPan(this, {
        name: message.messageContent.name,
        size: message.messageContent.size,
        storageUrl: Config.urlRedirect(message.messageContent.remotePath),
        openAfterSave: openAfter,
    });
}
```

- `src/ui/pan/panSave.js` 的 `saveFileMessageToMyPan(ctx, {name,size,storageUrl,openAfterSave})`：
  1. `PanApi.getMySpaces()` → 优先 `USER_PRIVATE`，否则第一个；
  2. 取根目录现有文件名做去重（`name`、`name (1)`…，服务端不允许同名；对齐 `pan_service.dart:228-256`）；
  3. `PanApi.createFile({spaceId, name, size, storageUrl, copy:true, mimeType, md5?})` —— `copy:true` 让服务端把 IM 媒体桶里的物理文件拷进网盘桶（`ClientFileController` + README；不拷的话权限/分享形同虚设）；
  4. Toast 成功/失败；
  5. `openAfterSave`：能在线打开 → `docWindow.openDoc(docOpenUrl(file.id))`；否则 `PanApi.getFileDownloadUrl(file.id)` 后交给系统打开。

- `FileMessageContentView.vue` 的 `clickFile()` 改为：文档格式且网盘可用 → 在线只读预览（`openFileOnline`），其余保持原逻辑（对齐 `utilities.dart:openedFileByDefault`、`conversation_controller.dart:340-380`）。
- 拖拽 `dragFile` 已导出 `{url: remotePath}`，网盘接收区可直接复用（§7.2）。

### 6.3 文件记录/收藏等其它入口

Flutter 里"按默认规则打开"被文件记录、收藏列表复用（`utilities.dart:284-300`）。vue-pc-chat 的对应页（`src/ui/fileRecord/FileRecordPage.vue`、`src/ui/main/fav/FavPage.vue`）可后续同样改成 `openFileByDefault`，本期可先只做会话内。

---

## 7. 已有的上传/下载原语

### 7.1 下载

- 渲染层：`src/platformHelper.js:96 downloadFile(message)` / `116 downloadFile2(url, name, messageUid)`；内部 `ipcRenderer.send(IPCEventType.DOWNLOAD_FILE, {remotePath, fileName, windowId, messageUid})`。
- 主进程：`background.js:966` 监听 `DOWNLOAD_FILE`，登记 `downloadFileMap`，`webContents.downloadURL(remotePath)`；`background.js:619 downloadHandler` 用 `will-download` 设置 `setSaveDialogOptions({defaultPath})`，并通过 `file-download-progress`/`file-downloaded`/`file-download-failed` 事件回报（`MessageItemView`/store 已有进度 UI）。
- 结论：网盘文件的"下载"可直接复用 `downloadFile2(<签名URL>, <name>, null)`；服务端 `/files/url` 返回 10 分钟有效的签名地址。

### 7.2 上传

- 小文件：`wfc.uploadMedia(fileName, fileOrData, mediaType, successCB, failCB, progressCB)`（`src/wfc/client/wfc.js:2529`），拿到远端 URL 后调 `PanApi.createFile`（`copy:false`，因为文件已在目标存储；对齐 `pan_service.dart:370-404`）。**`mediaType` 直接用 `MessageContentMediaType.PAN = 12`**（`src/wfc/messages/messageContentMediaType.js` 已预留该常量）。
- 大文件：`wfc.getUploadMediaUrl(fileName, mediaType, contentType, (uploadUrl, remoteUrl, backUploadUrl, serverType) => …)`（wfc.js:2563），按 `serverType` 用 XHR PUT/POST 上传（qiniu 表单 vs PUT）——**完整范例在 `src/ui/util/exampleCustomUploadFileHandler.js:42-71`**，直接移植即可；`Config` 双网时选 `backUploadUrl`。
- 上传完成后注册：`PanApi.createFile({spaceId,parentId,name,size,storageUrl, mimeType, md5})`（`md5` 可用 `md5-file` 依赖计算，`package.json` 已有）。
- 拖拽上传：`PanFolderView.vue` 监听 `drop`，从 `DataTransfer` 取 `File`/路径（Electron 下 `file.path`）。Flutter 侧不支持整目录上传（`pan_pc_folder_view.dart:150-151`），客户端同样只处理文件。

### 7.3 服务端前提（部署侧，不在本仓库改）

- `wf-pan-server` 启动，`media.*` 配好对象存储；`media.trusted_url_prefixes` 覆盖 IM 文件桶地址，否则「存到网盘 copy:true」与「按链接只读打开」会被服务端拒绝（README「保存 IM 文件消息到网盘」、`docs/view-url` 的 SSRF 校验）。
- `docs.enabled=true`、`docs.jwt_secret`、`docs.server_public_path`、`docs.server_internal_url`、`docs.callback_base_url`（README）。
- Nginx：把 `<panBase>/api/v1/` 反代到客户端口（默认 8081），`<panBase>/doc/` 指向文档页（同端口，`WebDocController`）。`doc-web/app.js:6-7` 用相对路径从 `/doc/` 推 `/api/v1/`，因此 **网盘地址与文档地址必须保持 `.../pan/api/v1` 与 `.../pan/doc/` 的相对关系**。

---

## 8. 逐文件实施计划

### 8.1 新增文件

| 文件 | 职责 | 参照 |
|---|---|---|
| `src/api/panApi.js` | 网盘 HTTP 客户端（空间/文件/分享/版本/文档） | `src/api/collectionApi.js`、`wf-pan-server/.../controller/client/*` |
| `src/api/panError.js` | `PanError` 错误类型 | `src/api/appServerError.js` |
| `src/ui/pan/panUtils.js` | 大小格式化、扩展名→图标、`isOnlineDocName`、`docOpenUrl/docViewUrl/isDocUrl` | `pan_service.dart:727-983`、`pan_widgets.dart` |
| `src/ui/pan/PanHomePage.vue` | 网盘首页：左侧空间导航 + 右侧文件夹视图（整栏） | `pan_home_screen.dart`、`pan_pc_folder_view.dart` |
| `src/ui/pan/PanFolderView.vue` | 面包屑 + 文件表格 + 右键菜单 + 拖拽上传 + 上传进度 | `pan_pc_folder_view.dart:56-190`、`pan_folder_state.dart:327-352` |
| `src/ui/pan/PanDocsPage.vue` | 在线文档首页：最近打开/共享给我、新建 docx/xlsx/pptx、打开、移除最近 | `pan_docs_screen.dart` |
| `src/ui/pan/PanDestinationPicker.vue` | 移动/复制/转存的目标空间+目录选择 | `pan_destination_picker.dart` |
| `src/ui/pan/PanShareDialog.vue` | 分享列表、增删、权限切换、发文件卡片 | `pan_share.dart` |
| `src/ui/pan/panSave.js` | `saveFileMessageToMyPan(ctx, {...})` | `pan_save.dart` |
| `src/ui/pan/docWindow.js` | `openDoc(url,title)` → `ipcRenderer.send(SHOW_DOC_WINDOW)` | Flutter `doc_window_manager.dart` |
| `src/ui/pan/DocWindowPage.vue` | 独立文档窗口：`electron-tabs` + `<webview>` + 自绘 header + 桥 server | `pc/doc_window/doc_window_app.dart`、`ui/workspace/WorkspacePage.vue` |
| `src/ui/pan/docBridgeClient.js` | dsbridge shim（可改为并入 `bridgeClientImpl.js`） | `doc-web/app.js:9-58` |
| `src/ui/pan/docBridgeServer.js` | 宿主 handler（`docSetPageHeader/docDownloadFile/chooseGroup/openUrl`） | `bridgeServerImpl.js`、`js_api.dart` |

### 8.2 修改文件

| 文件 | 改动 |
|---|---|
| `src/config.js` | 新增 `PAN_SERVER`/`PAN_BACKUP_SERVER`/`PAN_DOC_BASE`、`getPanServer()/getDocBaseUrl()/isPanConfigured()`（§3.2） |
| `src/routers.js` | 新增 `/home/pan`、`/home/docs`、`/doc-window`（§2.3） |
| `src/ui/main/HomePage.vue` | 侧栏两个门控入口 + `go2Pan/go2Docs` + `panConfigured` computed（§2.2、§4） |
| `src/ui/main/SubWindowHost.vue` | Web 兜底：注册 `/pan`、`/docs`（可选，§2.4） |
| `src/ipcEventType.js` | 新增 `SHOW_DOC_WINDOW`、`DOC_WINDOW_OPEN`（复用窗口追加页签） |
| `src/background.js` | 新增 `docWindow` 单例 + `SHOW_DOC_WINDOW` handler；复用 `createWindow`（§5.5） |
| `src/ui/workspace/bridgeClientImpl.js` | 注入 `window._dsbridge`；补 `chooseGroup` 支持（§5.3） |
| `src/ui/workspace/bridgeServerImpl.js` | 新增 `docSetPageHeader/docDownloadFile/chooseGroup` 等 handler，`openUrl` 对文档窗口分流（§5.4） |
| `src/ui/main/conversation/ConversationView.vue` | 右键菜单 3 项 + `isOnlineDocMessage/saveMsgToPan/openFileOnline`（§6.2） |
| `src/ui/main/conversation/message/content/FileMessageContentView.vue` | `clickFile()` 文档优先在线预览（§6.2） |
| `src/assets/lang/zh-CN.json` / `zh-TW.json` / `en.json` | 新增 `pan`、`docs` 分组与 `common` 缺省项 |
| `src/store.js`（可选） | `misc.panConfigured`；如需记住"上次打开的文档页签/最近 tab"再加 |
| `src/pstore.js`（若加 store slice） | 仿 `pickStore` 新增 `panStore = ref({...})` 并在 return 导出；`store.js` 的 `state` 加 `pan:null`、`init` 里挂 `panStore.value`、reset 时同步重置 |
| `src/assets/fonts/icomoon/*`（按需） | 网盘/文档图标 glyph |

### 8.3 实施顺序（建议 5 个可独立验证的里程碑）

1. **M1 基建**：`config.js` + `panApi.js` + `panError.js` + i18n 键；用一个临时入口或 console 验证 `getSpaces()`、`getRecentDocs()` 能跑通（Electron dev + 已部署 wf-pan）。
2. **M2 网盘页**：`PanHomePage.vue` + `PanFolderView.vue` + 侧栏/路由；验证空间切换、目录进出、上传、下载、重命名/删除/移动/复制。
3. **M3 文档页**：`PanDocsPage.vue`（列表/新建/最近/共享）+ `docWindow.js` + `background.js`/`ipcEventType` 文档窗口，先用系统浏览器打开文档 URL 验证服务端，再验证独立窗口。
4. **M4 桥**：dsbridge shim + 宿主 handler；验证文档页能登录（`getAuthCode`→`/doc/session`）、标题栏（`setPageHeader`）、下载、选人/选群、分享。
5. **M5 文件消息**：右键菜单 + 点击行为 + 存网盘；验证只读预览、存到网盘、存后打开、下载四类动作。

### 8.4 构建与验证命令

```bash
cd /Users/rain/Workspace/vue-pc-chat
npm run dev                 # 开发联调（需先部署 wf-pan 并在 config.js 填 PAN_SERVER）
npm run validate            # 提交前环境校验
npm run package             # 打包本平台产物（dist_electron/）

# 手动验证清单（Electron dev）
# 1) 未配置 PAN_SERVER：侧栏不出现「网盘/文档」，文件右键菜单与旧版一致
# 2) 配置 PAN_SERVER：侧栏出现网盘；空间列表/目录/上传/下载/重命名/移动/复制/删除/分享
# 3) 在线文档：最近/共享列表；新建 docx/xlsx/pptx；点开进入独立文档窗口并加载 ONLYOFFICE
# 4) 桥：文档页登录成功（无「请在客户端中打开」）；标题栏按钮（转换/历史/下载/分享）出现且可点；
#    分享面板选人/选群可用；下载触发系统保存
# 5) 文件消息：文档格式点击=只读在线预览；右键=在线预览/存到网盘/存到网盘并打开/存储
# 6) 双网：切主备地址后 getAuthCode host 与请求 base 一致（无 401/1002）

# 服务端侧自检（部署同学）
cd /Users/rain/Workspace/wf-pan/wf-pan-server && mvn clean package
java -jar target/wf-pan-server-*.jar       # 客户端口默认 8081，管理口 8080
curl -X POST http://<host>:8081/api/v1/spaces/list -H 'authCode: <code>'
# 浏览器打开 http://<host>:8081/doc/ 验证文档首页
```

### 8.5 风险与注意事项

- **桥协议错位**：文档页必须看到 `_dsbridge`（或 UA `WF-DSBridge`），否则会退回"页面自绘标题栏 + openUrl 打开文件"（白屏）。M4 是最大风险点，先做最小闭环。
- **`authCode` host 一致性**：`getAuthCode('admin',2,host)` 的 host 必须与实际请求的网盘地址 host 一致，双网切换后要重新取（现有 `collectionApi` 已踩过）。
- **独立窗口复用**：窗口已存在时不要直接 `loadURL` 重建（会丢已开页签），改用 `webContents.send(DOC_WINDOW_OPEN, ...)`，对齐 Flutter `onReuseContent`。
- **`copy:true` 与 SSRF 校验**：`media.trusted_url_prefixes` 未配时"存到网盘/按链接打开"会失败，属部署配置问题，需在联调前确认。
- **服务端形态选择**：若最终改用合并 `wf-app-server`，只需替换 `Config` 与 `PanApi._post` 的鉴权分支（§3.4），UI 层不变。
- **不改动服务端**：本方案全部为客户端改动；`doc-web/` 是服务端资源，客户端只消费其契约。

---

## 附：参考文件索引

- Flutter 网盘：`chat/lib/pan/pan_service.dart`、`pan_home_screen.dart`、`pan_pc_folder_view.dart`、`pan_folder_state.dart`、`pan_docs_screen.dart`、`pan_save.dart`、`pan_destination_picker.dart`、`pan_share.dart`、`pan_widgets.dart`
- Flutter PC：`chat/lib/pc/pc_home.dart`（侧栏/整栏 tab 常驻）、`pc/pc_shell_view_model.dart`（tab 常量）、`pc/doc_window/*`（独立文档窗口）、`pc/wf_webview_window/*`
- Flutter 桥：`chat/lib/workspace/js_api.dart`、`wf_webview_screen.dart`；消息侧 `chat/lib/utilities.dart`、`chat/lib/conversation/conversation_controller.dart`
- vue-pc-chat：`src/config.js`、`src/routers.js`、`src/ui/main/HomePage.vue`、`src/ui/main/conversation/ConversationView.vue`、`src/ui/workspace/{WorkspacePage.vue,bridgeServerImpl.js,bridgeClientImpl.js}`、`src/background.js`、`src/api/collectionApi.js`、`src/ui/util/exampleCustomUploadFileHandler.js`、`src/platformHelper.js`
- 服务端：`wf-pan-server/.../controller/client/*.java`、`.../filter/ClientAuthFilter.java`、`.../resources/doc-web/{app.js,open.html,index.html}`；形态 B：`wf-app-server/app-pan/**`
