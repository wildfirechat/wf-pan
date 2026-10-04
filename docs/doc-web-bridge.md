# 在线文档 H5 页面与客户端的桥协议（doc-web）

wf-pan-server 自带在线文档 H5 页面（`wf-pan-server/src/main/resources/doc-web/`，对外路径 `/doc/`）。
页面通过“桥”向宿主客户端要认证码、打开链接、下载文件等。宿主有两类，页面同时支持：

## 1. 原生客户端（Android / iOS / Flutter / 鸿蒙）

WebView 使用 **dsbridge** 协议（`window._dsbridge`，UA 里带 `WF-DSBridge`）：

- `getAuthCode({appId:'admin', appType:2})` → 回调 `{code:0, data:<authCode>}`
- `setPageHeader(header)`（宿主 → 页面，可多次回调）：`{title, subtitle, actions:[{id,text,icon,primary}]}`，点按钮回调按钮 id
- `downloadFile({url})`
- `openUrl(url)`
- `chooseContacts({})` / `chooseGroup({})`
- `toast(msg)` / `close()`
- `docReady(info)`（同步、单向，页面 → 宿主）：**文档真正可以看了**才发一次，宿主据此收起
  「正在加载…」。编辑器页在 ONLYOFFICE `onDocumentReady` 时发（首屏 5~15 秒）；
  只读 PDF 预览页在 iframe 取到 PDF / pdf.js 画出第 1 页时发
  （`info = {source:'preview', stage:'pdfjs'|'frame', pages?, reason?}`）。
  宿主不实现也没关系 —— 页面用 try/catch 兜着，只是少一个"收起加载态"的信号。
- `_dsb.hasNativeMethod({name, type:'all'})`（同步，用于能力探测）

页面拿到 authCode 后 `POST /doc/session` 换取 `PAN_WS` Cookie，之后所有接口带 Cookie + `X-Pan-Web: 1`。
因此**宿主 WebView 必须接受并保存第三方 Cookie**（同源文档页自会带上）。

## 2. 浏览器 / uni-app / 以 iframe、web-view、弹窗承载页面的宿主

没有 dsbridge 时，页面自动使用 **postMessage** 协议（宿主 = iframe 的 `window.parent`，或 `window.open` 弹窗的 `window.opener`）：

- **推荐弹窗（顶层文档）**：`window.open(url)` 打开的页面是顶层文档，`PAN_WS` Cookie 是第一方，不受浏览器第三方 Cookie 限制。宿主通过 `window.opener` 与页面用 postMessage 通信。
- **iframe**：方便布局，但跨站 iframe 里 `PAN_WS`（SameSite=Strict）属第三方 Cookie，Safari/Chrome 会拦截；需把 pan 服务与聊天站点部署在同一站点下（同源）才可靠。

消息格式：

- **认证码不走 postMessage**：宿主把 authCode 放进 URL fragment
  `…/doc/open?fileId=123#panAuthCode=<urlencoded authCode>`
  （或 `…?url=<u>&name=<n>#panAuthCode=…`）。fragment 不会发给服务端、不进日志；页面读完立即 `history.replaceState` 抹掉。
  这样任意父页面即使 iframe 本页也拿不到 authCode。
- **页面 → 宿主**：
  `宿主窗口.postMessage({__panBridge:true, type:'call'|'listen', id, method, data}, '*')`
- **宿主 → 页面**：
  - 回复：`{__panBridge:true, type:'reply', id, code, data}`（`code` 0 成功、-1 不支持）
  - 通知：`{__panBridge:true, type:'notify', method, data}`
- 网页模式下页面自己画标题栏，**不使用 `setPageHeader`**；宿主只需处理
  `getAuthCode`、`downloadFile`、`openUrl`、`chooseContacts`、`chooseGroup`、`toast`、`close`。
- 宿主收到 `type:'call'` 时，用 `(iframe.contentWindow || popupWindow).postMessage(reply, '*')` 回复。
- **Cookie**：`/doc/session` 的 Cookie 由文档页自己（对 pan 服务同源）设置。建议用 nginx 把 pan 服务与聊天站点放同一站点下（同源）。
- **顶层 web-view 且无 dsbridge（如 uni-app）**：既不能 `window.postMessage` 给宿主，也没有 opener。
  只要 URL 里带 `#panAuthCode=`，页面就能完成登录并**只读渲染**；`openUrl/downloadFile/chooseContacts/chooseGroup`
  退化为页内跳转 / 新窗口，页面自画标题栏。需要完整交互时，宿主可在 web-view `onReady` 用 `evalJS` 注入 dsbridge shim，
  或在页面里加载 `uni.webview.js` 走 uni 的 `postMessage` 通道（当前服务端页面未内置该通道）。

## 3. 客户端获取 authCode

各端用 IM SDK：`getAuthCode('admin', 2, <pan host>, ok, fail)`（appId=admin、type=2）。
