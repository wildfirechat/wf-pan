# 客户端网盘/在线文档接入约定（六个客户端）

统一对接**独立 wf-pan 服务**（`wf-pan-server`）。每个客户端都有一个「网盘服务地址」配置，
**为空即关闭**：不显示网盘 / 在线文档的任何入口、菜单与文件消息动作。

## 1. 统一契约

| 项 | 值 |
|---|---|
| 客户端接口 | `{PAN_SERVER}/api/v1/**`（POST，JSON） |
| 鉴权 | 请求头 `authCode`（IM SDK `getAuthCode('admin', 2, host)`） |
| 响应信封 | `{code, message, data}`；`code==0` 成功，`data` 为业务数据 |
| 文档页 | `{PAN_SERVER}/doc/`（`open?fileId=`、`open?url=&name=`、`licenses.html`） |
| 文档页会话 | 页面内 `POST /doc/session {authCode}` → 设 `PAN_WS` Cookie，之后带 Cookie + `X-Pan-Web: 1` |
| 文档页桥 | 原生 dsbridge；浏览器/uni/iframe/弹窗走 postMessage（见 `doc-web-bridge.md`） |

> `PAN_SERVER` 是「对外可见的基础地址」，可含路径前缀（反代剥前缀部署时形如 `https://host/pan`）。
> 客户端一律 `PAN_SERVER + '/api/v1'` 与 `PAN_SERVER + '/doc/'`。uni-chat-x 把 API 根与文档根拆成两个配置，效果一致。

## 2. 各端配置与门控

| 客户端 | 配置 | 门控函数 | 入口（PC / 移动） |
|---|---|---|---|
| vue-chat | `Config.PAN_SERVER` / `PAN_BACKUP_SERVER` | `Config.isPanEnabled()` | Web：左侧图标导航栏「网盘」「在线文档」（不在设置里） |
| vue-pc-chat | `Config.PAN_SERVER` / `PAN_BACKUP_SERVER` | `Config.isPanEnabled()` | PC：左侧栏「网盘」「在线文档」；文档在**独立窗口**打开（一文档一窗口） |
| android-chat | `Config.PAN_SERVER_ADDRESS` / `_BACKUP_ADDRESS` | `Config.isPanConfigured()` | 移动：“我”页 OptionItemView |
| ios-chat | 已有 `PAN_SERVER_ADDRESS`（沿用） | `PanService.isPanConfigured`（新增/沿用） | 移动：发现页 + 资料页 |
| uni-chat-x | `Config.PAN_SERVER` / `_BACKUP` + `PAN_DOC_BASE` / `_BACKUP` | `Config.isPanEnabled()` / `isPanDocEnabled()` | 移动：“我的”页 |
| hm-chat | `Config.PAN_SERVER_ADDRESS` / `_BACKUP_ADDRESS`（`PAN_SERVER_KIND='standalone'`） | `Config.isPanEnabled()` | 移动：“我”页 MeTab |

## 3. 功能清单（各端对齐）

- 网盘：空间列表（我的空间/全局公共/共享给我）、文件列表与文件夹导航、新建文件夹、
  上传（IM SDK 上传媒体 → `POST /files` 登记）、下载（`POST /files/url` 取签名地址）、
  重命名、移动、复制、删除、分享（选人/选群）、历史版本。
- 在线文档：文档首页（最近打开、新建 docx/xlsx/pptx、开源许可）、打开编辑器/只读打开；
  移动端按 `docs.mobile_edit`（默认 false，只读）。
  > **手机端编辑需要 ONLYOFFICE 商业版**：移动端网页端（H5）的编辑能力属于 ONLYOFFICE 商业版/商业许可功能，
  > 社区版在手机网页端只能查看，点编辑会弹许可提示。因此默认 `docs.mobile_edit=false`：PC 端可编辑、移动端只读；
  > 客户购买商业许可后把该配置改为 `true` 并重启即可放开。
- 文件消息：文档格式支持「在线预览」（只读，`docViewUrl`）、「存到网盘」「存到网盘并打开」；
  其余文件保留「下载」。
- 门控：`PAN_SERVER` 为空时以上全部不渲染。

> **建议给在线文档加 CDN**：首次打开要下载 ONLYOFFICE 编辑器引擎（`fonts/217` gzip ≈ 9.26MB、`sdk-all.js` gzip ≈ 4.51MB
> 等，十几 MB 量级），源站带宽低时打开很慢，且 WKWebView 对超大单文件有缓存上限、每次都会重下。
> 建议把 `/docs/`（ONLYOFFICE 静态资源）接到 CDN 回源加速，`/doc/` 保持走源站；无 CDN 时只能升源站带宽。

## 4. 验证要点

1. 未配置 `PAN_SERVER`：各端入口/菜单/文件消息动作均不出现。
2. 配置后：入口出现；接口前缀为 `/api/v1`，信封取 `data`（不是 `result`），鉴权为 `authCode` 头。
3. 文档页：原生端走 dsbridge；网页端把 authCode 放 `#panAuthCode=`，用 postMessage 桥。
4. 构建：vue-chat / vue-pc-chat 跑 `npm run build`；android 跑 `./gradlew :chat:assembleDebug`；
   ios 跑 `xcodebuild ... -scheme WFChatUIKit`；uni / hm 按各自 IDE 构建。
