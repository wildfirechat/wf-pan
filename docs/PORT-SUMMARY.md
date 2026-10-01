# 客户端网盘/在线文档同步 — 完成情况汇总

日期：2026-10-01
来源：`../wf-enterprise-chat`（分支 `pan-docs`，Flutter）
目标端：`vue-chat`、`vue-pc-chat`、`android-chat`、`ios-chat`、`uni-chat-x`、`hm-chat`
服务端：独立 `wf-pan-server`（`{PAN_SERVER}/api/v1` + `authCode` 头 + 信封 `data`；文档页 `{PAN_SERVER}/doc/`）

## 结论

六个客户端均已加入网盘与在线文档：入口按平台区分（PC 侧栏/独立窗口，移动端“我”页），
并以「是否配置网盘地址」为总开关 —— 未配置时不渲染任何入口与文件消息动作。

## 各端状态

| 客户端 | 入口（PC/移动） | 门控 | 构建验证 |
|---|---|---|---|
| vue-chat | 移动：设置页「网盘/在线文档」 | `Config.isPanEnabled()`（默认 `''`） | `npm run build` ✅（已独立复验） |
| vue-pc-chat | PC：侧栏「网盘/在线文档」 | `Config.isPanEnabled()`（默认 `''`） | web + electron 构建 ✅（已独立复验） |
| android-chat | 移动：“我”页两项 | `Config.isPanConfigured()`（默认 `null`） | `:uikit/:chat compileDebug` ✅（已独立复验） |
| ios-chat | 移动：发现页 + “我”页 + 资料页 | `WFCUConfigManager isPanConfigured` | WFChatUIKit / WildFireChat `xcodebuild` ✅（已独立复验 WFChatUIKit） |
| uni-chat-x | 移动：“我的”页两项 | `Config.isPanEnabled()/isPanDocEnabled()`（默认 `''`） | 无本地构建（HBuilderX）；UTS 转译 + uvue CSS 静态检查 ✅ |
| hm-chat | 移动：“我”页两项 | `Config.isPanEnabled()`（默认 `null`） | `hvigorw assembleHap` ✅（已独立复验） |

## 主要功能

- 网盘：空间列表/配额、文件夹导航、新建文件夹、上传（IM SDK 上传后登记）、下载（签名地址）、
  重命名、移动、复制、删除、分享（选人/选群）、历史版本（部分端）。
- 在线文档：文档首页（最近打开、新建 docx/xlsx/pptx、开源许可）、打开编辑器/只读打开；
  移动端按 `docs.mobile_edit`（默认只读）。
- 文件消息：文档格式「在线预览」（只读）、「存到网盘」「存到网盘并打开」，其余保留「下载」。

## 文档页桥（wf-pan-server 侧改动）

`wf-pan-server/src/main/resources/doc-web/app.js` 新增网页桥，三套承载方式并存：

1. 原生客户端 dsbridge（Android/iOS/鸿蒙）—— 原有行为不变；
2. iframe / 弹窗宿主 postMessage（vue-chat / vue-pc-chat 网页回退）—— authCode 走 `#panAuthCode=` fragment；
3. 顶层 web-view 无桥（uni）—— 只要带 `#panAuthCode=` 即可登录并只读渲染，其余桥方法安全降级。

已用 stubbed DOM 冒烟验证：fragment 登录、postMessage 投递、纯浏览器无桥时的行为均符合预期。

## 已知限制（不影响主流程）

- android：`setPageHeader` 仅设标题（未画原生按钮）；未接入平板双栏 `PaneRegistry`。
- vue-pc-chat：文档为应用内多页签 webview 覆盖层，不是独立 OS 窗口。
- ios-chat：未补网盘分享/历史版本的客户端方法（服务端接口已具备）；为兼容 `complete:NO` 微调了 vendored dsbridge。
- hm-chat：未实现大文件预签名直传（走 IM 媒体上传）。
- uni-chat-x：宿主 postMessage 桥在顶层 web-view 下不会触发，按只读降级；如需完整交互可在 `onReady` 注入 dsbridge shim。
- ios-chat 默认 `PAN_SERVER_ADDRESS` 仍是一个演示地址；置为 `nil` 即完全隐藏入口。

## 关键约定

- 统一契约、各端配置键与门控函数、验证要点见 `docs/client-port-config.md`；
- 文档页桥协议见 `docs/doc-web-bridge.md`；
- 各端落地计划见 `docs/plan-*.md`。
