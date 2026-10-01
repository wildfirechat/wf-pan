# vue-chat 客户端「网盘(pan) + 在线文档(online docs)」实现方案

> 目标仓库：`/Users/rain/Workspace/vue-chat`（Vue 3 Web 客户端，移动端优先）
> 服务端参考：`/Users/rain/Workspace/wf-pan/wf-pan-server`（Java/Spring Boot，独立部署）
> 行为参考：`/Users/rain/Workspace/wf-enterprise-chat/chat/lib/pan/*`、`chat/lib/settings/me_tab.dart`
> 本文只做设计，**不修改任何代码**；所有结论均已对照源码核实，附带文件路径与行号。

---

## 0. 结论摘要与三个必须先定的事

移植目标是给 vue-chat 增加两块业务：

1. **网盘**：空间列表 → 文件夹浏览 → 上传/下载/新建文件夹/重命名/删除/移动/复制/分享。
2. **在线文档**：文档首页（最近打开 / 共享给我 / 新建 docx/xlsx/pptx）+ ONLYOFFICE 编辑器 H5（`/doc/open?fileId=...`，以及聊天文件「按链接只读打开」`/doc/open?url=&name=`）。

移植前必须确定的三个契约（本方案给出推荐，并给出兼容写法）：

| # | 问题 | 参考实现现状 | 本方案建议 |
|---|---|---|---|
| A | 网盘 HTTP API 前缀与鉴权 | Flutter 对接的是**合并服务** `wf-app-server`：`{app}/api/pan/**`，先 `POST /api/auth/login {authCode}` 换 `authToken`，之后请求带 `authToken` 头 | 目标仓库 `wf-pan-server` 是**独立服务**：`/api/v1/**` + `authCode` 头（见 `wf-pan-server/.../filter/ClientAuthFilter.java`）。vue-chat 侧封装 `panApi.js` 时把 base 前缀 + 鉴权方式做成可配置，默认按独立服务实现（与 `src/api/collectionApi.js` 同款），合并服务模式只需改前缀 + 加一次 token 交换 |
| B | 在线文档 H5 的桥 | H5（`doc-web/app.js`）**只实现了 dsbridge**（`window._dsbridge` / `prompt('_dsbridge=...')`）；vue-chat **没有任何 dsbridge 依赖**，工作台用的是 iframe + `postMessage`（`wf-op-request`） | 给 H5 增加 postMessage transport（服务端静态文件小改动，见 §5），vue-chat 复用/扩展 `src/ui/workspace/bridgeServerImpl.js`。次选：宿主给同源 iframe 注入 dsbridge shim（脆弱，不推荐） |
| C | 文档页地址 | H5 在应用服务根：`{APP_SERVER}/doc/`；独立服务对外是 `${pan.public_path}/doc/`（默认 `/pan/doc/`） | 统一由 `Config.getPanDocBase()` 计算，默认 `${Config.getPanServer()}/doc/`；打开 H5 需要**同源**部署（H5 会 `POST /doc/session` 写 `PAN_WS` Cookie，`SameSite=Strict`） |

另外，参考实现里有一个坑：`config.dart:289` 的注释说“置空即关闭网盘”，但 `PAN_SERVER_ADDRESS` 是**永远非空**的 getter，导致 Flutter 的 `me_tab` 门控永远为真。**vue-chat 必须引入显式的开关配置**（见 §4），否则“未配置时隐藏入口”的需求无法实现。

---

## 1. 技术栈、构建/运行、工程结构

### 1.1 技术栈（`vue-chat/package.json`）

- **框架**：Vue `3.5.13`，SFC + Options API 为主（少量 setup）。
- **路由**：`vue-router@4`，**hash 模式**（`src/main.js` `createWebHashHistory`）。
- **状态**：`pinia@2` 已安装，但业务用的是自研单例 store：`src/store.js`（reactive 状态 + 方法）+ `src/pstore.js`（misc 状态的持久化）。
- **i18n**：`vue-i18n@9`，语言包在 `src/assets/lang/{zh-CN,zh-TW,en}.json`，`src/main.js` 用 `createI18n` 装配，locale 存 `localStorage('lang')`。
- **HTTP**：`axios@1.6.7`（见 `src/api/*`）。
- **构建**：`@vue/cli-service@5`（webpack），`sass`；NPM 脚本见下。
- **UI 插件**：`@madogai/vue-context`（右键菜单）、`@kyvg/vue3-notification`（`$notify`）、自研 `$modal`/`$alert`/`$pickContact`/`$forwardMessage` 等全局插件。
- **IM SDK**：`src/wfc/**`（`wfc` 单例，含 `proto.min.js`，Web SDK 需授权替换）。
- **环境**：建议 Node **v18.19.0** + npm 10.2.3（与站点部署手册一致）。

### 1.2 构建 / 运行命令

```bash
cd /Users/rain/Workspace/vue-chat
npm install
npm run serve        # 开发服务器：vue-cli-service serve --open，端口 8013（vue.config.js devServer.port）
npm run serve-https  # https 开发调试
npm run build        # 产物 dist/（publicPath '/'）
npm run lint
```

- 入口：`public/index.html` → `src/main.js`；全局挂载点 `#app`。
- `vue.config.js` 关键项：`devServer.port=8013`、`allowedHosts:'all'`、`outputDir:'dist'`、`@` → `src`、`runtimeChunk('single')`。
- 需要本地联调网盘时，给 `devServer` 加代理（见 §8 修改清单）以解决跨域与 Cookie 同源问题。

### 1.3 目录结构（与本需求相关的部分）

```
src/
├── main.js                     # createApp / router / i18n / 全局插件 / eventBus
├── routers.js                  # 全部路由（/home 下是 tab 子路由）
├── config.js                   # 所有服务地址与功能开关（静态类 Config）
├── store.js                    # 全局业务状态（state.misc / contact / conversation / pick ...）
├── pstore.js                   # misc 状态持久化 & 派生开关（enableOpenWorkSpace 等）
├── platform.js / platformHelper.js  # 平台判断、downloadFile / previewMM 等
├── api/
│   ├── appServerApi.js         # 应用服务：authToken 头，_getAppServer() 主备探测
│   ├── collectionApi.js        # 接龙：wfc.getAuthCode -> authCode 头  ← 网盘 API 的模板
│   ├── pollApi.js / searchServerApi.js / organizationServerApi.js
├── wfc/
│   ├── client/wfc.js           # getAuthCode / uploadMedia / getUploadMediaUrl / ...
│   ├── messages/fileMessageContent.js
│   └── messages/messageContentMediaType.js   # File=4, PAN=12
├── ui/
│   ├── main/
│   │   ├── HomePage.vue        # 左侧导航栏（“我/入口”所在）
│   │   ├── setting/SettingPage.vue          # 设置页 tab（含账号与安全）
│   │   ├── user/UserCardView.vue            # 头像弹层
│   │   ├── conversation/
│   │   │   ├── ConversationView.vue          # 消息右键菜单（vue-context，120-161 行）
│   │   │   ├── MessageMultiSelectActionView.vue
│   │   │   └── message/content/FileMessageContentView.vue   # 文件消息气泡
│   │   └── pick/PickUserView.vue            # 现有“选人”组件
│   ├── workspace/
│   │   ├── WorkspacePage.vue    # 工作台：iframe 多标签宿主
│   │   └── bridgeServerImpl.js  # postMessage 桥（wf-op-request/response）
│   ├── fileRecord/FileRecordPage.vue        # 文件记录（消息文件汇总，非网盘）
│   └── util/helper.js           # humanSize / getFiletypeIcon / getMediaType
└── assets/lang/{zh-CN,zh-TW,en}.json
```

---

## 2. 导航与菜单注册：入口放哪、如何做门控

### 2.1 现有入口的位置

- **主导航（对应 Flutter 的“我”页/PC 侧栏）**：`src/ui/main/HomePage.vue` 的 `<nav class="menu"><ul>`（约 40–90 行）。现有条目及门控：
  - 会话 `/home`、联系人 `/home/contact`、收藏 `/home/fav`：无条件。
  - 文件记录 `/home/files`：`v-if="sharedMiscState.isElectron && sharedMiscState.isCommercialServer"`（且 `go2Files()` 实际跳 `/files`，`routers.js` 顶层路由）。
  - 工作台 `/home/h-wp`：`v-if="sharedMiscState.enableOpenWorkSpace"`（`pstore.js:225` 由 `!!Config.getOpenPlatformWorkSpaceUrl()` 派生）。
  - 会议 `/home/conference`：`v-if="supportConference"`。
  - AI `/home/ai`：`v-if="aiPortalUrl"`（`HomePage.vue:354` computed）。
  - 设置 `/home/setting`：无条件。
  - 每个条目是 `<li><div class="i-button-wrapper" @click="go2Xxx"><i class="icon-..."/></div></li>`，`go2Xxx()` 定义在 259–305 行。
- **设置页**：`src/ui/main/setting/SettingPage.vue`，左侧 tab 列表（`currentTab` 为 `general/notification/appearance/security/about`）。账号与安全 tab 内已有“修改密码 / 备份与恢复”这类行，适合再放网盘/文档入口。
- **头像弹层**：`src/ui/main/user/UserCardView.vue`。
- **子路由注册**：`src/routers.js` 中 `/home` 的 `children`（`conversation/contact/fav/h-wp/setting/conference/ai`）。

> 说明：本仓库没有独立的移动端底部 tab / “我”页面；`HomePage.vue` 的左侧导航即是 Flutter `me_tab.dart` 的等价入口。移动端优先场景下建议同时放到设置页，保证窄屏也能进入。

### 2.2 新增门控入口的做法（照抄工作台/AI 的模式）

1. `src/config.js` 增加 `static PAN_SERVER = null; static PAN_BACKUP_SERVER = null;` 与 `static getPanServer()`（见 §3、§4）。
2. `src/pstore.js` 在 misc state 里增加派生开关：
   ```js
   enablePan: !!(Config.getPanServer()),
   enableOnlineDocs: !!(Config.getPanServer()),   // 如需再校验服务端 docs.enabled，可异步刷新
   ```
   （参考 `pstore.js:225` 的 `enableOpenWorkSpace` 与 `pstore.js:250` 的同步逻辑。）
3. `src/routers.js` 在 `/home` children 增加：
   ```js
   { name: 'pan',  path: 'pan',  component: PanHomePage },
   { name: 'docs', path: 'docs', component: PanDocsPage },
   ```
   并可按需增加顶层 `{ path: '/pan/doc', component: PanDocFrame }`（只读按链接打开时用 query 传参）。
4. `HomePage.vue` 增加两个 `<li v-if="sharedMiscState.enablePan">` / `v-if="sharedMiscState.enableOnlineDocs"`，图标沿用 icomoon 字体（`icon-ion-ios-folder` / `icon-ion-code-working` / 新加一个文档图标），点击走 `go2Pan()` / `go2Docs()`（`this.$router.push('/home/pan')`）。
5. `SettingPage.vue` 在“账号与安全”卡片增加两行 `v-if="sharedMiscState.enablePan"` / `enableOnlineDocs` 的 clickable 行，跳同一路由。

> 在线文档入口按参考实现应再叠一层“有内置 WebView”判断；Web 端 iframe 始终可用，因此只需 `enablePan`（可选：服务端 `/doc/session` 返回 `docsEnabled=false` 时隐藏）。

---

## 3. HTTP 客户端与鉴权

### 3.1 现有两套鉴权模式

**A. 应用服务（authToken）** — `src/api/appServerApi.js`

- 基址：`Config.APP_SERVER` / `Config.APP_BACKUP_SERVER`，`_getAppServer()` 会按 IM 连接状态或探测 `Ok` 选主备。
- 登录成功后把 `authToken` 写入 `localStorage`，key 为 `authToken-{host}`（`_interceptLoginResponse`）。
- 请求：`axios.post(base+path, data, { headers: { authToken: getItem('authToken-'+host) } })`；响应 `{code, result}`，`code!==0` 抛 `AppServerError`。
- 适用：登录/短信/收藏/群公告等。**网盘不走这套**（除非使用合并服务模式）。

**B. 业务服务（authCode）** — `src/api/collectionApi.js`、`pollApi.js`、`searchServerApi.js`、`organizationServerApi.js`（**网盘 API 直接照这个写**）

```js
// src/api/collectionApi.js 的核心
let baseUrl = Config.getCollectionServer();
let host = baseUrl.replace(/^https?:\/\//, '').split('/')[0];
wfc.getAuthCode('collection', 2, host, async (authCode) => {
    let res = await axios.post(baseUrl + path, data, {
        headers: { authCode }, withCredentials: false
    });
    // res.data.code === 0 -> resolve(res.data.data|result)
});
```

- `wfc.getAuthCode`：`src/wfc/client/wfc.js:2247`（`impl.getAuthCode(appId, appType, host, cb, failCb)`）。
- appId/appType：网盘参考实现用 **`'admin'`, `2`**（`pan_service.dart:26-27`；H5 `doc-web/app.js:80` 也是 `{appId:'admin', appType:2}`）。host 只取域名/主机（Flutter `JsApi` 取 `Uri.parse(appUrl).host`，不带端口）。
- 主备双网：`src/config.js` 的 `_selectServer(main, backup)`（`wfc.connectedToMainNetwork()` 判定），网盘应同样提供 `getPanServer()`。
- 下载地址：`src/platformHelper.js` 的 `downloadFile(message)` / `downloadFile2(url, name, messageUid)`（浏览器端 a[download]，Electron 走 IPC）。

### 3.2 网盘服务端契约（两套，务必区分）

**独立服务 `wf-pan-server`（本仓库，推荐作为默认目标）**

- 客户端端口默认 **8081**，管理端口 8080（`DESIGN.md` 四、`application.properties`）。
- `filter/ClientAuthFilter.java`：
  - `/api/v1/**` 必须带 `authCode` 头，否则 401 `{code:1001}`；`authCode` 无效 401 `{code:1002}`。
  - `/doc/**`、`/dl/**`、`/internal/docs/**` 放行（由各自控制器鉴权）。文档 H5 用 `POST /doc/session {authCode}` 换 `PAN_WS` Cookie（path = `${pan.public_path}/`，默认 `/pan/`，`HttpOnly`、`SameSite=Strict`），之后 `/api/v1/**` 用 Cookie + 自定义头 `X-Pan-Web: 1`（`WEB_HEADER`）。
- 对外路径前缀：`pan.public_path=/pan`（NG 去掉 `/pan` 前缀转给 8081）。
- 端点（`controller/client/*`，全部 POST `{code,message,result}` 信封）：

  | 路径 | 说明 |
  |---|---|
  | `/api/v1/spaces/list` `/spaces/my` `/spaces/user/public` | 空间列表 / 我的空间 / 某用户公开空间 |
  | `/api/v1/spaces/files` | 空间内文件列表 `{spaceId,parentId}` |
  | `/api/v1/files/folder` | 新建文件夹 |
  | `/api/v1/files` | 新增文件记录（上传完成后登记；`copy:true` 时服务端拷贝物理对象） |
  | `/api/v1/files/delete` `/rename` `/move` `/copy` | 删除/重命名/移动/复制 |
  | `/api/v1/files/url` | 取签名下载地址 `{fileId[,versionNo]}` → `{storageUrl,...}` |
  | `/api/v1/files/check-permission` | 空间写权限 |
  | `/api/v1/shares/list` `/add` `/remove` `/with-me` | 分享管理 / 共享给我 |
  | `/api/v1/docs/create` `/editor-config` `/view-url` `/convert` `/options` `/recent` `/recent/remove` | 在线文档 |
  | `/api/v1/versions/list` `/restore` | 历史版本 |
  | `GET /doc/`、`GET /doc/open?fileId=|url=&name=`、`GET /doc/licenses.html`、`POST /doc/session` | 文档 H5（相对 `/pan` 即 `/pan/doc/...`） |
  | `GET /dl/{fileId}` | 签名直链下载（H5 用） |

**合并服务 `wf-app-server`（Flutter 参考实现的部署形态，兼容用）**

- API：`{app}/api/pan/**`；登录 `POST {app}/api/auth/login {authCode}`，响应头 `authToken`，之后请求带 `authToken` 头（`AuthCodeApiClient`，401/`code=13` 时清 token 重登并重试一次）。
- H5：`{app}/doc/`，H5 自己用 `getAuthCode` → `POST /doc/session` → 拿 `authToken` 存 `sessionStorage`，再调 `/api/pan/**`。
- 若客户实际部署的是合并服务，`panApi.js` 只需切换 `PAN_API_PREFIX` 与 `AUTH_MODE='authToken'`。

### 3.3 建议新增：`src/api/panApi.js`

- 单例类，方法命名对齐 Flutter `PanService`：`getSpaces/getVisibleSpaces/getMySpaces/getSpaceFiles/createFolder/createFile/renameFile/deleteFile/moveFile/copyFile/getFileDownloadUrl/getShares/addShare/removeShare/getSharedWithMe/getRecentDocs/removeRecentDoc/createDoc/getDocsOptions`。
- `_post(path, data)`：
  - `base = Config.getPanServer()`；`host = base.replace(/^https?:\/\//,'').split('/')[0]`；
  - **独立服务**：`wfc.getAuthCode('admin', 2, host, cb)` → header `authCode`，请求 `base + path`（`path` 以 `/api/v1` 开头）。
  - **合并服务**：先 `POST {origin}/api/auth/login {authCode}` 取 `authToken`（按 host 缓存，key `pan-authToken-{host}`），业务请求带 `authToken`；路径 `/api/pan/...`。
  - 统一解析 `{code,message,result|data}`，`code!==0` 抛 `PanError`；对 `1001/1002/1004/13` 做一次 authCode 刷新重试。
- `withCredentials`：独立服务的 `/api/v1` 用 header 鉴权，无需 Cookie；只有 H5 页面内部使用 Cookie。

---

## 4. 功能开关 / “网盘是否已配置”门控

现状（`src/config.js`）：所有服务地址都是静态字段 + `getXxx()` 方法，例如 `APP_SERVER/APP_BACKUP_SERVER`、`COLLECTION_SERVER/COLLECTION_BACKUP_SERVER`、`SEARCH_SERVER`、`OPEN_PLATFORM_WORK_SPACE_URL`（置 `null` 即关闭），并用 `_selectServer(main, backup)` 处理双网。校验在 `Config.validate()`（`config.js:395+`）。

### 4.1 新增配置项（`src/config.js`）

```js
// 网盘服务地址。null / 空串 = 未配置，客户端隐藏网盘与在线文档入口。
// 独立 wf-pan-server：例如 'http://192.168.1.10:8081'（对外经 NG 可为 'https://im.example.com/pan'）
static PAN_SERVER = null;
static PAN_BACKUP_SERVER = null;     // 双网备选，未配备网时保持 null

// 可选：网盘 API 前缀与鉴权模式，兼容合并服务
static PAN_API_PREFIX = '/api/v1';   // 合并服务改为 '/api/pan'
static PAN_AUTH_MODE = 'authCode';   // 'authCode' | 'authToken'

static getPanServer() {
    return Config._selectServer(Config.PAN_SERVER, Config.PAN_BACKUP_SERVER);
}
static isPanConfigured() {
    return !!Config.PAN_SERVER;      // 只认主地址，避免备选误开门控
}
```

在 `Config.validate()` 中加：配置非空时校验是否以 `http` 开头，并 `console.log` 当前网盘地址（照抄 `APP_SERVER` 的写法）。

### 4.2 派生到 store，供模板 `v-if` 使用

- `src/pstore.js`：`enablePan: Config.isPanConfigured(), enableOnlineDocs: Config.isPanConfigured()`（并加入 `reset()`/同步分支，参照 `enableOpenWorkSpace` 的 225 / 250 行写法）。
- 模板层一律用 `sharedMiscState.enablePan`（HomePage/SettingPage/消息菜单），禁止在模板里直接读 `Config`，保持与现有风格一致。
- 运行期如需按服务端能力再收紧（如 `docs.enabled=false`）：在进入文档首页时调 `panApi.getDocsOptions()` 或读取 `/doc/session` 的 `docsEnabled`，写入 `store.state.misc`，据此隐藏“在线文档”入口。
- 关键：**未配置时** `enablePan=false`，`HomePage.vue`/`SettingPage.vue` 的 `v-if` 直接不渲染；`FileMessageContentView` 与消息菜单里的网盘项也按同一开关隐藏（只保留原有“下载/存储”）。

---

## 5. WebView 可用性与 JS Bridge（在线文档页的关键）

### 5.1 现状：vue-chat 没有 WebView，也没有 dsbridge

- 全仓库 `grep -i dsbridge` **无任何结果**（`node_modules` 外）。
- Web 端“内置网页”的现有实现是 **iframe + postMessage**：
  - `src/ui/workspace/WorkspacePage.vue`：`<iframe sandbox="allow-scripts allow-same-origin allow-popups allow-forms allow-top-navigation" allow="microphone; camera; fullscreen">`，多标签、`getActiveTabWindow()` 等。
  - `src/ui/workspace/bridgeServerImpl.js`：监听 `window.message`，只处理 `data.type === 'wf-op-request'`，`{requestId, handlerName, args, appUrl}`；通过 `_response()` 回 `{type:'wf-op-response', handlerName, appUrl, requestId, windowId, args:{code,data}}`，通过 `_notify()` 发 `wf-op-event`。
  - 已实现 handler：`toast`、`openUrl`、**`getAuthCode`**、`config`、`chooseContacts`、`close`。
- 在线文档 H5（`wf-pan-server/src/main/resources/doc-web/app.js`）当前**只支持 dsbridge**：
  ```js
  Bridge.available() = !!(window._dsbridge || window._dswk || /WF-DSBridge|_dsbridge/.test(navigator.userAgent))
  Bridge.callSync(m, a) = window._dsbridge ? window._dsbridge.call(m, JSON.stringify({data:a})) : prompt('_dsbridge='+m, JSON.stringify({data:a}))
  Bridge.call(m, a)     // 异步：arg 里带 _dscbstub，客户端完成后 window[_dscbstub](res)
  Bridge.has(m)         // 同步调 _dsb.hasNativeMethod {name, type:'all'}，期望 {data:true}
  ```
  在纯浏览器里 `Bridge.available()===false`，H5 的 `login()` 会抛 “不在客户端内”，页面报“请在客户端中打开”。**因此必须给 H5 补一条 web transport，或绕过 H5。**

### 5.2 推荐方案：给 `doc-web/app.js` 增加 postMessage transport（服务端小改）

在 H5 的 `Bridge` 内新增分支：当 `window.__wf_bridge_`（由宿主注入）存在或 `window.parent !== window` 时，走 postMessage，协议与 `bridgeServerImpl.js` 完全一致：

1. 宿主在 iframe `load` 后（或 `<iframe>` 上）注入 `frame.contentWindow.__wf_bridge_ = true` 并发送 `{type:'wf-op-init', methods:[...]}`；H5 记录能力集。
2. H5 `Bridge.call(method, args)` → `window.parent.postMessage({type:'wf-op-request', handlerName:method, args, appUrl:location.href, requestId}, targetOrigin)`；宿主回 `wf-op-response`，H5 按 `requestId` resolve。
3. `Bridge.has(method)`：**postMessage 无法同步返回**，用第 1 步的能力集（宿主在 `wf-op-init` 里给出 `setPageHeader/downloadFile/chooseContacts/chooseGroup/...` 的支持列表）；H5 的 `has()` 改为查该集合（保持同步语义，`open.html` 对 `chooseGroup` 的置灰逻辑不受影响）。
4. 多次回调（`setPageHeader` 的按钮点击）：H5 `Bridge.listen` 注册 `window['__pandsl_'+method]`；宿主点击按钮后发 `wf-op-event {handlerName:'setPageHeader', args:'<id>'}`，H5 事件监听里调用该回调。
5. 异步结果统一 `{code, data}`：成功 `code:0`，取消 `-1`，宿主/页面不匹配 `-2`。

宿主侧仍用 `src/ui/workspace/bridgeServerImpl.js`（或其抽出的公共模块），只需补齐 handler。

### 5.3 备选方案（不推荐，但记录）

- **同源 dsbridge shim**：iframe 同源时，宿主动态注入 `contentWindow._dswk/_dsbridge`。问题：`app.js` 在 `<head>` 同步执行，`Bridge.available()`（含 `in-client` class）在宿主能注入前就已求值，且 `prompt` 同步返回无法实现；时序脆弱。
- **完全不走服务端 H5**：vue-chat 自研文档首页 + 用 `/docs/editor-config` 返回的配置加载 ONLYOFFICE `DocsAPI`。工作量大、重复服务端逻辑、要处理 JWT/同源，不建议。

### 5.4 需要在 `bridgeServerImpl.js` 增加的桥方法

| 方法 | 参考实现 | vue-chat 宿主实现 |
|---|---|---|
| `getAuthCode` | `js_api.dart:185-198` | **已存在**（`bridgeServerImpl.js:155-174`）。核对：host 取 `new URL(appUrl).hostname`（不带端口）；成功回 `{code:0,data:authCode}`，失败回非 0。H5 传 `{appId:'admin',appType:2}` |
| `setPageHeader` | `js_api.dart:145-151`；H5 `open.html:43,65` | **新增**。宿主把 `{title,subtitle,actions:[{id,text,icon,primary}]}` 渲染到页面自身标题栏（或 iframe 上方工具条）；点按钮发 `wf-op-event`（`setProgressData` 语义，可多次）。`id==='share'` 可拦截为宿主内分享面板（参考 `_shareDoc`） |
| `downloadFile` | `js_api.dart:121-137`；H5 `app.js:196-199` | **新增**。`{url}` 或字符串 → `platformHelper.downloadFile2(url, name)`（浏览器 a[download]）；避免走 `openUrl`（内置 iframe 打开文件会白屏） |
| `chooseContacts` | `js_api.dart:230-255` | **已存在**（`ChooseContacts` 用 `$pickContact`）。回 `{code:0,data: JSON.stringify([{uid,name,displayName,portrait}])}`；取消 `-1` |
| `chooseGroup` | `js_api.dart:260-280` | **新增**。vue-chat 目前只有 `$pickContact`（`src/ui/common/Picker.js`），**没有选群组件**，需新增 `PickGroupView.vue`（复用 `src/ui/main/view/GroupItemView.vue` + `store.state.contact.groupList`，多选）；回 `{code:0,data: JSON.stringify([{gid,name,portrait}])}`；取消 `-1` |
| `openUrl` | `js_api.dart:99-116`；H5 `app.js:177-194` | **已存在**（`WorkspacePage.addTab` 打开新标签）。注意 H5 在同一个宿主里用 `openUrl` 打开另一个文档页（`openDoc`），`PanDocFrame` 需支持按 `url` 再开 iframe/标签 |
| `toast` / `close` / `config` | — | **已存在** |

**H5 页面地址与 Cookie 要求**：`PanDocFrame.vue` 用 `src = Config.getPanDocBase() + 'open?fileId=...'`（或 `?url=&name=`）。H5 登录会 `POST {docBase}session` 写 `PAN_WS` Cookie（`SameSite=Strict`），**必须与 vue-chat 站点同源**（推荐 NG 把 `/pan/` 反代到 wf-pan-server 8081、`/docs/` 反代 ONLYOFFICE）。跨站 iframe 下 `SameSite=Strict` Cookie 不会带上，登录会失败。若无法同源，需服务端把 Cookie 改为 `SameSite=None; Secure`（HTTPS）。

### 5.5 iframe 宿主页设计（`PanDocFrame.vue`）

- 单文档 iframe 页：接收 `fileId` / `url`+`name` query；渲染顶部标题栏（承载 `setPageHeader`）、分享面板、`loading/error` 态；`onload` 后注入 `__wf_bridge_` 并初始化桥。
- 文档首页 `PanDocsPage.vue` 内的“打开”既可在当前页打开 iframe，也可 `$router.push('/home/docs/view?fileId=')`；`openUrl`（H5 请求开新文档）统一走路由新增一个 tab/层。

---

## 6. 文件消息渲染与操作

### 6.1 现有实现

- **气泡**：`src/ui/main/conversation/message/content/FileMessageContentView.vue`
  - 点击 `clickFile()`：扩展名在 `Config.DISABLED_RECEIVE_FILE_TYPES` 内则提示；Electron 存在本地文件则 `shell.openPath`，否则 `downloadFile(message)`；Web 端直接 `downloadFile(message)`（`src/platformHelper.js` 的 `downloadFile` → `downloadFile2`）。
  - 结构：`<div class="file-message-container" @click="clickFile">` + 文件名/大小 + 下载进度。
  - 被 `MessageContentContainerView.vue`、`MessagePage.vue`、`CompositeMessagePage.vue` 复用。
- **右键/长按菜单**：`src/ui/main/conversation/ConversationView.vue` 的 `<vue-context ref="menu">`（120–161 行），条目由 `isCopyable/isDownloadable/isForwardable/isFavable/isQuotable/isMulticheckable/isRecallable/isLocalFile/...` 控制；`download(message)` 在 774 行附近。
- **多选操作条**：`src/ui/main/conversation/MessageMultiSelectActionView.vue`（转发/收藏/删除）。
- **消息内容类**：`src/wfc/messages/fileMessageContent.js`（`name`、`size`、`remotePath`）；`MediaMessageContent` 含本地/远端地址；发送见 `src/store.js:1321 sendFile()`（构造 `FileMessageContent`）。

### 6.2 需要新增的操作（对齐 Flutter `conversation_controller.dart:721-757`）

对 `FileMessageContent`（`remotePath` 非空）：

| 操作 | 显示条件 | 行为 |
|---|---|---|
| 在线预览（只读） | `enablePan && enableOnlineDocs && isOnlineDocName(name)` | 宿主 iframe 打开 `docViewUrl(remotePath, name)` = `{docBase}open?url=<enc>&name=<enc>`；不可编辑（服务端代理，不回写） |
| 存到我的网盘 | `enablePan` | `panApi.saveFileToMyPan({name,size,storageUrl,mimeType})`：取个人私有空间根目录，`copy:true`（服务端把消息文件从媒体桶拷进网盘桶），重名自动 `base(1).ext` |
| 存到网盘并打开 | `enablePan` | 上一步成功后：文档格式且可内嵌 → 打开 `docOpenUrl(fileId)`；否则取 `files/url` 签名地址交给系统下载/打开 |
| 下载 / 存储 | 始终（原有） | 保持 `download(message)` 不变 |
| 点击气泡默认行为 | — | 参考 `Utilities.openFileByDefault`：文档格式且 `enableOnlineDocs` → 在线只读预览；否则原有下载 |

实现位置：
- 气泡默认点击：改 `FileMessageContentView.vue` 的 `clickFile()`，先判断 `isOnlineDocName` + `enablePan`，走宿主事件/路由打开文档页。
- 菜单项：在 `ConversationView.vue` 的 `vue-context` 里、`isDownloadable` 项附近插入 2–3 个 `<li v-if>`，新增谓词 `isPanAvailable()` / `isOnlineDocMessage(message)`，新增方法 `saveToPan(message, openAfterSave)` / `openMessageOnline(message)`。
- 多选：可选在 `MessageMultiSelectActionView.vue` 增加“存到网盘”（仅选中项全为文件消息时可用），非必须。
- 网盘页内的文件操作（重命名/删除/移动/复制/分享/上传）参考 `pan_folder_state.dart` 的 `actionsFor` 权限规则：`space.canManage` 才有管理项；否则只给“另存副本”。

---

## 7. 已具备的文件上传 / 下载基础设施

| 能力 | 位置 | 说明 |
|---|---|---|
| 小文件中转上传 | `wfc.uploadMedia(fileName, fileOrData, mediaType, successCB, failCB, progressCB)` — `src/wfc/client/wfc.js:1949` | 媒体类型见 `src/wfc/messages/messageContentMediaType.js`（`File=4`，且**协议已预留 `PAN=12`**）。回调返回远端 URL |
| 大文件上传 | `wfc.isSupportBigFilesUpload()` / `wfc.getUploadMediaUrl(fileName, mediaType, contentType, cb, failCb)` — `wfc.js:1966/1984` | 专业版支持；拿到 `(uploadUrl, downloadUrl)` 后由应用层 PUT（七牛/表单见 Flutter `_uploadPutFile`/`_uploadQiniuFile`） |
| 发送文件消息 | `store.sendFile(conversation, file)` — `src/store.js:1321`；`FileMessageContent` 构造在 `store.js:1401` 附近 | 内部按扩展名分流 image/video/file，调用 `wfc.uploadMedia` 后 `insertMessage`/`sendSavedMessage` |
| 下载到本地 | `platformHelper.downloadFile(message)` / `downloadFile2(url,name)` — `src/platformHelper.js` | Web：a[download]；Electron：IPC `DOWNLOAD_FILE` |
| 认证下载地址 | `wfc.getAuthorizedMediaUrl(messageUid, mediaType, mediaPath, cb, failCb)` — `wfc.js:1970` | 需要鉴权的媒体 |

**网盘上传流程（Web 版，对齐 Flutter `uploadFileToSpace`）**：
1. `wfc.getUploadMediaUrl`（或小文件 `wfc.uploadMedia`，mediaType 用 `File` 或预留的 `PAN=12`）拿到 `uploadUrl/downloadUrl`；
2. `PUT`（非七牛）或表单 POST（`type===1`）上传文件；
3. `panApi.createFile({spaceId, parentId, name, size, storageUrl: downloadUrl, mimeType, copy:false})` 登记；
4. 重名由前端 `_uniqueFileName`（`base(1).ext`）或后端唯一约束处理。

**保存消息文件到网盘**：直接 `panApi.createFile({..., copy:true})`，无需重新上传（`copy=true` 时服务端从媒体桶拷进网盘桶；服务端要求源地址在 `media.trusted_url_prefixes` 白名单内，部署时必须配置）。

---

## 8. 逐文件实施清单

### 8.1 新增文件

| 文件 | 作用 / 关键内容 |
|---|---|
| `src/api/panApi.js` | 网盘 REST 客户端（§3.3）。导出单例与 `PanError`。所有方法以 `Config.getPanServer()` 为基址，`wfc.getAuthCode('admin',2,host)` 换 `authCode`（或合并模式换 `authToken`） |
| `src/ui/pan/panHelper.js` | 纯函数：`isOnlineDocName(name)`（扩展名白名单，照搬 `pan_service.dart:80-88`：Office/ODF/PDF 组）、`docOpenUrl(fileId)`、`docViewUrl(url,name)`、`isDocUrl(url)`、`panSpaceDisplayName(space)`、`formatPanSize`、`panFileIconType(name)`、`uniqueFileName(existing, name)` |
| `src/ui/pan/PanHomePage.vue` | 网盘首页：空间列表（`getVisibleSpaces()`：全局公共 + 自己的，个人空间置顶）+ 容量条；点击进入文件列表；空/错误态（“云盘服务未配置”）。桌面/宽屏可左侧空间栏 + 右侧文件区；窄屏列表推页 |
| `src/ui/pan/PanFolderView.vue` | 文件夹浏览：面包屑、文件夹优先排序、下拉刷新、上传/新建文件夹按钮（`space.canWrite` 时）；文件行点击=打开（文件夹进入 / 文档在线打开 / 其它下载），长按或“…”=操作菜单 |
| `src/ui/pan/PanFileMenu.js`（或组件） | 文件操作菜单：打开/在线打开、下载、重命名、删除（非空文件夹禁删，服务端同规则）、移动、复制、分享；按 `space.canManage` 收敛 |
| `src/ui/pan/PanUploadStrip.vue` | 上传进度条（可选，参考 `PanUploadStrip`），支持取消 |
| `src/ui/pan/PanDestinationPicker.vue` | 移动/复制目标选择：空间（`canWrite` 过滤）→ 逐级文件夹；禁止把文件夹移入自身 |
| `src/ui/pan/PanShareDialog.vue` | 分享面板：`shares/list|add|remove`，选人/选群（`$pickContact` / 新 `PickGroupView`），可改 VIEW/EDIT；“分享到聊天”发 `LinkMessageContent` 卡片（title/digest/url=`docOpenUrl`），先 `shares/add` 授权再发消息（参考 `pan_share.dart:120-178`） |
| `src/ui/pan/PanDocsPage.vue` | 在线文档首页：最近打开 / 共享给我 两个 tab；新建 docx/xlsx/pptx（`docs/create`，手机端新建受 `docs/options.mobileEdit` 约束）；从最近列表移除；打开=进入 `PanDocFrame` |
| `src/ui/pan/PanDocFrame.vue` | 单文档 iframe 宿主：加载 `${docBase}open?fileId=` 或 `?url=&name=`；顶部标题栏承载 `setPageHeader`；`onload` 注入 `__wf_bridge_`；实现/复用桥 handler；错误与“请在客户端中打开”兜底 |
| `src/ui/pan/saveFileMessageToMyPan.js` | 复用的业务函数：`saveFileMessageToMyPan({name,size,storageUrl,mimeType,openAfterSave})`，成功 toast，`openAfterSave` 时打开文档页或签名下载地址 |
| `src/ui/main/pick/PickGroupView.vue` | 选群组件（多选），供 `chooseGroup` 与分享面板使用；结果 `[{gid,name,portrait}]` |

### 8.2 修改文件

| 文件 | 改动 |
|---|---|
| `src/config.js` | 新增 `PAN_SERVER/PAN_BACKUP_SERVER/PAN_API_PREFIX/PAN_AUTH_MODE`、`getPanServer()/isPanConfigured()/getPanDocBase()`；`validate()` 增加网盘地址校验与日志 |
| `src/pstore.js` | `enablePan` / `enableOnlineDocs`（由 `Config` 派生），纳入持久化与同步分支（参照 `enableOpenWorkSpace`） |
| `src/routers.js` | `/home` children 增 `pan`、`docs`、`docs/view`（文档 iframe 页）；import 新页面 |
| `src/ui/main/HomePage.vue` | 导航栏新增 `网盘`/`在线文档` 两个 `<li v-if="sharedMiscState.enablePan/enableOnlineDocs">`；新增 `go2Pan()`/`go2Docs()`；如把 iframe 页做成右侧内容，确保 `keep-alive`/`drag-area` 逻辑不冲突 |
| `src/ui/main/setting/SettingPage.vue` | “账号与安全”卡片新增两行门控入口（跳同一路由） |
| `src/ui/workspace/bridgeServerImpl.js` | 新增 handler：`setPageHeader`、`downloadFile`、`chooseGroup`；抽出可复用的 `registerHandlers/getAuthCode`；补 `_notify` 的多次回调用法；`getAuthCode` host 处理与返回结构按 §5.4 对齐 |
| `src/ui/main/conversation/message/content/FileMessageContentView.vue` | `clickFile()` 增加“文档格式且网盘开启 → 在线只读预览”；其余保持下载 |
| `src/ui/main/conversation/ConversationView.vue` | `vue-context` 增加 `<li>`：在线预览 / 存到我的网盘 / 存到网盘并打开；新增谓词与方法；引用 `enablePan`、`panApi` |
| `src/ui/main/conversation/MessageMultiSelectActionView.vue` | （可选）“存到网盘”批量操作 |
| `src/platformHelper.js` | 可抽出 `downloadUrl(url, name)`（现 `downloadFile2` 已够用，按需）；文档地址判断可复用 `panHelper` |
| `src/assets/lang/zh-CN.json`、`zh-TW.json`、`en.json` | 新增 `pan` 命名空间（云盘、在线文档、上传、新建文件夹、重命名、移动、复制、分享、存到我的网盘、存到网盘并打开、在线预览、云盘服务未配置、最近打开、共享给我、新建文档…） |
| `vue.config.js` | 本地联调代理：`devServer.proxy` 把 `/pan`、`/doc`、`/docs` 转发到 wf-pan-server / ONLYOFFICE，保证同源与 Cookie；生产用 NG 反代 |
| `README.md` / `docs/` | 写明 `PAN_SERVER` 配置、需服务端 `docs.enabled=true`、`media.trusted_url_prefixes` 等前置条件 |

### 8.3 服务端配套（不在本仓库 `vue-chat` 内，但为保证功能必须）

- `wf-pan-server/src/main/resources/doc-web/app.js`：新增 postMessage transport + 能力握手（§5.2），否则 Web 端文档页不可用。
- 生产 NG：`/pan/` → `wf-pan-server:8081`，`/docs/` → ONLYOFFICE；`pan.public_path`、`docs.server_public_path`、`docs.callback_base_url` 配置正确；`docs.enabled=true`。
- Cookie 同源：`PAN_WS` 为 `SameSite=Strict`，要求 vue-chat 与 `/pan/` 同站点（同域或同端口经反代）。

---

## 9. 构建与验证

### 9.1 构建

```bash
cd /Users/rain/Workspace/vue-chat
npm install
npm run build          # 产物 dist/
# 本地开发联调
npm run serve          # http://localhost:8013
```

### 9.2 验证步骤

1. **未配置门控**：`Config.PAN_SERVER=null` 时 `npm run serve`，确认导航栏/设置页**没有**网盘、在线文档入口；文件消息菜单只有原生“存储/打开”，点击仍能下载。
2. **配置网盘**：填入 `PAN_SERVER`（独立服务如 `http://host:8081`，或同源 `/pan`）→ 入口出现；打开网盘首页能看到空间列表；造数据验证文件夹进入/返回、容量显示。
3. **上传/下载**：上传一个小文件（走 `wfc.uploadMedia` → `panApi.createFile`）与大文件（`getUploadMediaUrl` + PUT，专业版）→ 列表出现、下载可用；新建文件夹/重命名/删除/移动/复制/另存副本逐项验证权限收敛。
4. **保存消息文件到网盘**：文件消息右键 → “存到我的网盘” → 个人私有空间根目录出现同名文件（服务端已 `copy`）；“存到网盘并打开”对 docx/pdf 打开在线文档页，对 zip 走签名下载。
5. **在线文档页**：`docs.enabled=true`、`/pan/doc/` 可访问；从文档首页/网盘/文件消息进入 iframe，页面**不再提示“请在客户端中打开”**：
   - 登录成功（`/doc/session` 200，浏览器有 `PAN_WS` Cookie）；
   - `setPageHeader` 标题/只读说明/按钮正确渲染；
   - `chooseContacts`/`chooseGroup` 能弹选择器并把结果写回页面；
   - `downloadFile` 触发浏览器下载；
   - 分享面板增删成员/改权限生效，“分享到聊天”发出链接卡片，对方点开可只读/编辑（按授权）。
6. **按链接只读**：聊天里 docx 文件消息点击 → `/doc/open?url=&name=` 只读打开，页面无编辑/转换/历史版本入口，仅有下载。
7. **构建回归**：`npm run build` 无报错；`npm run lint`（若启用）无新增错误；确认 i18n 三种语言无缺 key。

### 9.3 关键验证命令

```bash
# 独立网盘服务健康与鉴权
curl -i -X POST http://<pan-host>:8081/api/v1/spaces/list -H 'Content-Type: application/json' -d '{}'   # 无 authCode -> 401 {code:1001}
curl -i http://<pan-host>:8081/pan/doc/                                                              # 文档 H5 资源
# 站点
cd /Users/rain/Workspace/vue-chat && npm run build && ls -la dist/
```

---

## 附录 A：网盘/文档接口对照表

| 能力 | 独立 `wf-pan-server` | 合并 `wf-app-server`（Flutter 参考） |
|---|---|---|
| 鉴权 | 头 `authCode`（`/api/v1/**`）；H5 用 `PAN_WS` Cookie + `X-Pan-Web` | `POST /api/auth/login {authCode}` → `authToken` 头 |
| 空间列表 | `POST /api/v1/spaces/list` | `POST {app}/api/pan/spaces/list` |
| 我的空间 | `POST /api/v1/spaces/my` | `.../spaces/my` |
| 文件列表 | `POST /api/v1/spaces/files {spaceId,parentId}` | `.../spaces/files` |
| 新建文件夹 | `POST /api/v1/files/folder` | `.../files/folder` |
| 新增文件记录 | `POST /api/v1/files {spaceId,name,size,storageUrl,copy,parentId?,mimeType?,md5?}` | `.../files` |
| 删除/重命名/移动/复制 | `/api/v1/files/delete|rename|move|copy` | 同左（前缀 `/api/pan`） |
| 下载地址 | `POST /api/v1/files/url {fileId[,versionNo]}` → `result.storageUrl` | 同左 |
| 分享 | `/api/v1/shares/{list,add,remove,with-me}` | 同左 |
| 文档 | `/api/v1/docs/{create,editor-config,view-url,convert,options,recent,recent/remove}` | 同左 |
| 版本 | `/api/v1/versions/{list,restore}` | 同左 |
| 文档 H5 | `GET {panPath}/doc/open?fileId=|url=&name=`、`POST /doc/session` | `GET {app}/doc/open`、`POST /doc/session` |

## 附录 B：数据模型（需在 JS 侧解析）

- **SpaceVO**：`id`（=spaceId）、`spaceType`（`GLOBAL_PUBLIC|USER_PUBLIC|USER_PRIVATE`）、`ownerId`、`name`、`totalQuota`、`usedQuota`、`fileCount`、`folderCount`、`autoInit`、`createdAt`、`canManage`；`canWrite = spaceType!=='USER_PUBLIC' || ownerId===当前用户`。
- **FileVO**：主键字段是 **`id`**（不是 `fileId`），另有 `spaceId`、`parentId`、`name`、`type`（`FILE|FOLDER` 或 1/0）、`size`、`mimeType`、`md5`、`storageUrl`、`childCount`、`creatorId`、`creatorName`、`versionNo`、`createdAt`、`updatedAt`。**解析时 `id`/`fileId` 两键都取**。
- **ShareVO**：`id`、`targetType`（`USER|GROUP`）、`targetId`、`targetName`、`targetPortrait?`、`permission`（`VIEW|EDIT`）、`createdByName`、`createdAt`。
- **SharedFileVO**：`{file: FileVO, permission, sources:[{targetType,targetName,createdByName}], sharedAt}`。
- **RecentDocVO**：`{file: FileVO, permission, openedAt}`。
- 响应信封：`{code, message, result}`，`code===0` 成功。

## 附录 C：i18n 新增键（建议）

在 `src/assets/lang/*.json` 新增顶层 `"pan"`，至少包含：
`cloud_drive`、`online_docs`、`not_configured`、`upload`、`new_folder`、`rename`、`move`、`copy`、`copy_to`、`move_to`、`delete`、`share`、`shares`、`share_to_chat`、`save_to_pan`、`save_to_pan_and_open`、`online_preview`、`download`、`recent`、`shared_with_me`、`new_word`、`new_cell`、`new_slide`、`empty_folder`、`no_spaces`、`permission_view`、`permission_edit`、`load_failed`、`retry`。
同时给 `common` 补 `open_online`、`save_to_pan` 等（若复用现有 `common.save/open/download` 则按需）。

## 附录 D：风险与待决策

1. **服务端形态未定**：独立 `wf-pan-server` vs 合并 `wf-app-server`。方案通过 `PAN_API_PREFIX`/`PAN_AUTH_MODE` 兼容，但落地前需确认，否则 `panApi.js` 的路径与鉴权两套都要测。
2. **H5 必须支持 postMessage**：这是 Web 端在线文档能否工作的硬前提；若不允许改 `doc-web/app.js`，只能退回同源 dsbridge shim（脆弱）或自研 ONLYOFFICE 页面（工作量大）。
3. **同源/Cookie**：`PAN_WS` 为 `SameSite=Strict`，跨站 iframe 会失败；需 NG 同源反代。
4. **上传媒体桶与网盘桶**：`copy:true` 依赖服务端 `media.trusted_url_prefixes` 白名单，否则保存消息文件会失败。
5. **大文件上传**：Web 端需实现 `getUploadMediaUrl` 后的 PUT/七牛表单分支；大文件仅专业版支持。
6. **只读 vs 可编辑**：手机端 ONLYOFFICE 社区版只读（`docs.mobile_edit=false`）；Web 端按服务端 `platform` 判定传递（`platform=pc` 还是 `mobile` 由 `Config`/UA 决定，与 H5 `isMobile()` 保持一致）。
7. **`_preCheck` 语义**：参考实现里 SPA 地址变化会让 `chooseContacts/chooseGroup` 静默返回 `-2`；vue-chat 的 `PanDocFrame` 应保证 `appUrl` 与当前页 URL 一致。
8. **Electron 端**：`HomePage`/`WorkspacePage` 同时服务 Electron；新增 iframe 页需确认 `sandbox`/`allow`（摄像头、全屏）与窗口行为，且不要破坏 `drag-area`。
