# wf-pan 部署到 wfserver（含在线文档 ONLYOFFICE）

- 服务器：`wfserver` = `root@101.42.4.222`（TencentOS 4，2C/15G，磁盘 30G）
- 对外域名：`https://pan.wildfirechat.net`
- 部署日期：2026-10-01

## 1. wf-pan 服务

| 项 | 值 |
|---|---|
| 目录 | `/root/pan` |
| jar | `/root/pan/wf-pan-server-1.0.0.jar`（本次更新，含在线文档/按链接只读打开） |
| 配置 | `/root/pan/config/application.properties`（外置，Spring Boot 自动加载 `./config/`） |
| 启动 | `/root/pan/start.sh`（nohup；工作目录必须是 `/root/pan` 才会加载外置配置） |
| 端口 | 客户端 8083，管理 8084（仅本机 nginx 使用） |
| 日志 | `/root/pan/nohup.out` |
| 备份 | `wf-pan-server-1.0.0.jar.bak-*`、`config/application.properties.bak-*` |

关键配置（本次新增/确认）：

```properties
server.port=8083
server.admin-port=8084
spring.datasource.url=jdbc:mysql://192.168.4.16:3306/pan
im.server.admin_url=http://192.168.2.5:18080
media.type=3
media.server_url=https://media.wfcoss.cn
media.bucket=pan

pan.public_path=                                   # 站点挂根路径，必须留空
media.trusted_url_prefixes=https://media.wfcoss.cn/media/   # IM 媒体桶，供「存到网盘/按链接只读打开」
pan.sign_secret=<持久随机>                          # 签名下载/文档链接；持久化避免重启后已发链接失效
docs.enabled=true
docs.jwt_secret=<与 ONLYOFFICE 相同，见 /root/onlyoffice/jwt_secret>
docs.server_public_path=/docs
docs.server_internal_url=http://127.0.0.1:8089
docs.callback_base_url=http://172.17.0.1:8083      # 容器回连宿主
docs.mobile_edit=false                             # 手机端只读：手机网页端编辑属 ONLYOFFICE 商业版功能，社区版会弹许可提示
docs.hide_chat=true
docs.mobile_pdf_preview=true                       # 手机端只读打开先转 PDF 预览（几百 KB），转不了自动退回编辑器
docs.preview_dir=/root/pan/preview                 # 预览 PDF 缓存目录（可按文件+版本复用；留空用系统临时目录）
```

## 2. ONLYOFFICE Docs（Docker）

| 项 | 值 |
|---|---|
| 容器 | `wf-docs`，镜像 `onlyoffice/documentserver:latest`（4.84GB） |
| 端口 | `127.0.0.1:8089 -> 80`（只监听本机，由 nginx 对外） |
| JWT | `JWT_ENABLED=true`，密钥存 `/root/onlyoffice/jwt_secret`（与 wf-pan `docs.jwt_secret` 一致） |
| 内网访问 | `ALLOW_PRIVATE_IP_ADDRESS=true`、`ALLOW_META_IP_ADDRESS=true`。**必须**：ONLYOFFICE 默认禁止访问内网地址，会导致取 `document.url`（`http://172.17.0.1:8083/...`）被拒 → 编辑器报「下载失败」 |
| 数据/日志 | `/root/onlyoffice/data`、`/root/onlyoffice/logs` |
| 重启策略 | `--restart=always`；宿主 docker 已 `enabled` |

## 3. nginx

站点配置：`/etc/nginx/conf.d/pan.conf`（备份 `pan.conf.bak-*`）。**wf-pan 只占几个前缀，其余给 ONLYOFFICE**（编辑器加载缓存用绝对路径 `/cache/files/...`，不带 `/docs/` 前缀）：

```nginx
location /api/ { proxy_pass http://127.0.0.1:8083; }
location /doc/ { proxy_pass http://127.0.0.1:8083; }   # 文档 H5 页面
location /dl/  { proxy_pass http://127.0.0.1:8083; }
location /internal/ { return 404; }                    # 不对外
location /docs/ { proxy_pass http://127.0.0.1:8089/; ... WebSocket 头 ... }  # 编辑器静态资源（剥离前缀）
location / { proxy_pass http://127.0.0.1:8089; ... WebSocket 头 ... }        # /cache/、/downloadfile/ 等绝对路径
```

- `/doc/`（单数）→ wf-pan 的 H5 页面；`/docs/`（复数）→ ONLYOFFICE，容易混
- `/internal/` → 对外 404，只允许容器网内访问
- **坑**：若把 `location /` 指给 wf-pan，编辑器请求 `/cache/files/...` 会 404，表现为「下载失败」

## 4. 已验证（在服务器上实测）

| 检查 | 结果 |
|---|---|
| `https://pan.wildfirechat.net/doc/` | 200 |
| `https://pan.wildfirechat.net/doc/open?fileId=1` | 200 |
| `https://pan.wildfirechat.net/docs/web-apps/apps/api/documents/api.js` | 200 |
| `https://pan.wildfirechat.net/docs/doc/`（docservice） | 400（到达 ONLYOFFICE） |
| `https://pan.wildfirechat.net/api/v1/docs/options` | 401（需 authCode） |
| `https://pan.wildfirechat.net/internal/docs/file/1` | 404（对外屏蔽） |
| ONLYOFFICE `/healthcheck` | 200 |
| JWT 一致（用同一密钥签 token 调转换） | 通过（非 -8） |
| 容器 → 宿主回连 `172.17.0.1:8083` | 通（403 签名校验） |
| MySQL `pan` 库表 | `pan_file/version/space/space_admin/share/recent/global_admin/operation_log/sys_config` 齐全 |

## 5. 客户端

六个客户端的 `PAN_SERVER` 已改为 `https://pan.wildfirechat.net`：

- `vue-chat/src/config.js`、`vue-pc-chat/src/config.js`、`uni-chat-x/config.uts`
- `android-chat/uikit/.../kit/Config.java`、`hm-chat/client/.../config.ets`
- `ios-chat/wfchat/WildFireChat/WFCConfig.m`（原本就是该地址）

改完需各自重新构建/出包。

## 6. 运维注意

1. **改 JWT**：同时改 `/root/onlyoffice/jwt_secret` 与 `docs.jwt_secret`，并重建 wf-docs 容器，否则取文件/回调 403。
2. **重启 wf-pan**：`cd /root/pan && ./start.sh`（旧的先 `pkill -f wf-pan-server-1.0.0.jar`）。
3. **Docker iptables**：本机 docker 的 iptables 链曾被清空（`docker run -p` 报 "No chain/target/match"）。已非破坏性补齐链；若再次出现，可 `systemctl restart docker`（会中断 `robot-live-server`、`mediamtx`，两者 `Restart=no`，需手动 `docker start`）。
4. **磁盘**：拉取 ONLYOFFICE 后 `/` 使用率约 78%（剩 ~6.8G），注意清理。
5. **安全**：wf-pan 管理员共享密码仍是默认 `admin123`（管理端 8084 未对外），建议尽快在后台修改。
6. **「下载失败」排查**：iOS/客户端打开在线文档报“下载失败”，先看 wf-pan 是否收到 `/internal/docs/file/...`；若没有，多半是 ONLYOFFICE 拒绝了内网地址。确认 `docker exec wf-docs grep allowPrivateIPAddress /etc/onlyoffice/documentserver/local.json` 为 `true`（重建容器时带 `ALLOW_PRIVATE_IP_ADDRESS=true`）。
7. **手机端编辑需要商业版**：ONLYOFFICE 手机网页端的**编辑**能力属于**商业版/商业许可**功能，社区版（Community）在手机网页端只能查看，点编辑会弹许可提示。因此 `docs.mobile_edit=false`（默认，移动端只读、PC 端可编辑）；若客户已购 ONLYOFFICE 商业许可并希望手机端也能编辑，改 `true` 并重启 wf-pan（服务器上可用 `enable_mobile_edit.sh` / `disable_mobile_edit.sh`）。
8. **建议加 CDN**：在线文档首次打开要拉 ONLYOFFICE 的 `sdk-all.js`、字体等静态资源（十几 MB 量级），低带宽下打开很慢（详见第 7 节）。建议把 `/docs/`（静态、带版本、`immutable`）接到 CDN 回源加速，`/doc/` 保持走源站；WKWebView 对超大单文件有缓存上限，超大资源每次打开都会重下，走 CDN 收益最明显。

## 7. 打开文档慢 / 带宽分析

打开一次在线文档下载的主要是 **ONLYOFFICE 编辑器引擎**（不是文档本身，文档内容 `Editor.bin` 只有 ~0.26MB）。
以 iOS 移动端一次正常打开（走 min SDK）为例，`/docs` + `/cache` 合计 ≈ **2.79MB**：

| 资源 | 传输 | 磁盘(未压缩) | 说明 |
|---|---|---|---|
| `sdkjs/common/libfont/engine/fonts.wasm` | ~1.2–1.4MB | 3.61MB | 字体引擎，最大单项 |
| `sdkjs/word/sdk-all-min.js` | ~0.63MB | 3.52MB | Word SDK（min） |
| `documenteditor/mobile/dist/js/app.js` | ~0.46MB | 0.47MB | 移动编辑器 |
| `sdkjs/word/sdk-all.js` | **28.87MB（未压缩）** | 28.87MB | **非 min 版**；一旦被加载会非常慢 |
| 其它（ChartStyles/framework7/spell.wasm/jquery…） | ~0.4MB | | |
| 文档内容 `cache/files/.../Editor.bin` | ~0.26MB | | 才是文档本身 |

> iOS 日志里出现过多次 `sdk-all.js` 的**部分下载**（147KB/294KB/671KB/1.6MB/4.7MB…），即低带宽下大文件反复尝试中断——
> 这会让「打开很久」。正常应以 `sdk-all-min.js` 为准。

**已做（0 成本）**：站点 `listen 443 ssl; http2 on;` + 外层 gzip（上游 ONLYOFFICE 已 gzip）；
静态资源本身带版本号且 `Cache-Control: public, max-age=31536000, immutable`，第二次打开理论上应命中缓存。

**建议（按性价比）**：
1. **CDN（最推荐）**：把 `/docs/`（静态、带版本、immutable）挂到腾讯云 CDN，客户端就近高速下载，源站只回源一次；
   静态流量单价低，能大幅省源站带宽。
2. **确认只加载 min SDK**：避免 `sdk-all.js`(28.87MB)，只用 `sdk-all-min.js`(3.52MB)。
3. **裁剪功能**：关掉拼写检查（`spell.wasm` + 词典）、插件市场等。
4. **客户端缓存**：iOS 仅在登出/被踢时清 WebView 数据（`AppService clearAppServiceAuthInfos`），正常使用应命中缓存；
   若发现每次全量重下，检查 WebView 是否用了非持久化存储或缓存被清。
5. **升带宽**：升级 EIP 带宽最直接，但长期成本高。

### 已实施的省流量优化（2026-10-01）

- 站点开启 **HTTP/2**（`http2 on;`）与 **gzip**（`gzip_vary on`）。
- wf-pan 编辑器配置关闭 **拼写检查**（`customization.spellcheck=false`，省 `spell.wasm`/词典）与 **插件**（`customization.plugins=false`）。
- **结论**：`sdk-all.js`(磁盘 28.87MB / gzip ~4.5MB) 是 ONLYOFFICE 编辑器**固有加载**——连官方 `preload.html`、`cache-scripts.html` 都是「同时加载 sdk-all-min.js + sdk-all.js」并置 `AscNotLoadAllScript=true`，因此**不能安全跳过**。
- 这些静态资源带版本号且 `Cache-Control: immutable, max-age=31536000`，**首次打开后会被客户端缓存**；之后同一版本再打开只应下文档内容(~0.26MB)+api.js(13KB)。若第二次仍全量重下，需排查客户端缓存。
- 进一步的量级优化只能靠 **CDN** 或 **提升源站带宽**；`sdk-all-min.js` 无法再裁剪。

### 中文字体（fonts/217）——子集化方案已放弃

在线文档的中文回退字体 `fonts/217`（文泉驿正黑 WenQuanYi Zen Hei）原始 16.79MB / gzip 9.26MB，超过 WebKit 单条缓存上限，**每次打开都重下**。

尝试过子集化（找到 ONLYOFFICE 只对字体**前 32 字节 XOR 固定密钥** `a066d620149647fa9569b850b0414948`，据此产出了同格式子集）：

| 档位 | 原始 | gzip | 结果 |
|---|---|---|---|
| full | 16.79MB | 9.26MB | 原字体 |
| gbk（约 2.1 万汉字） | 7.66MB | 4.14MB | **实测有排版问题** |
| gb2312（6763 汉字） | 2.31MB | 1.30MB | **实测缺字** |

**第一次尝试（只换字体文件、没动字体表）失败并回滚**：渲染出现缺字/排版异常，根因是客户端
`AllFonts.js` 里仍认为该字体覆盖全部字符，缺字时不会回退。

**第二次实现（已上线，2026-10-02）**：`deploy/onlyoffice/` 下提供完整流水线。

- `build_font_subsets.py`：还原字体（前 32 字节 XOR 混淆）→ fonttools 子集（拉丁/标点/假名/谚文/CJK 统一表意
  文字共 3.5 万码点，保留 `hhea/OS2/name/cmap` 等度量与名称表）→ 重新混淆 → gzip -9。
  TTC 只保留被使用的 face 0 输出单 face（整体子集化会让各 face 各存一份字形表，反而更大）。
- `install_font_subsets.sh`：把子集字体 + 修正后的 `AllFonts.js` 放到宿主机、由 nginx 覆盖
  `/docs/<版本>/fonts/217` 与 `/docs/<版本>/sdkjs/common/AllFonts.js`（容器重建不丢），
  并把 `WenQuanYi Zen Hei Mono/Sharp` 的 face 改成 0（文件已只剩 face 0）。
- `uninstall_font_subsets.sh`：一条命令回滚。

实测：`fonts/217`（文泉驿正黑）**9,178,672 B → 4,367,060 B（gzip，-52%）**，原始 16.79MB → 8.47MB；
字形 4.2 万 → 3.6 万，码点 4.2 万 → 3.4 万（覆盖全部 CJK 统一表意文字、假名、谚文、常用符号）。
子集只影响生僻字（CJK 扩展 B 及以后）；`134/135`（NanumGothic）、`179/182`（Noto Sans KR）主要是谚文字形，
子集化只能省 5%~10%，**没有启用**。

> 子集化后请用几篇有代表性的中文文档确认排版；有问题直接跑 `uninstall_font_subsets.sh` 回滚。

> 方案 B（只用 `sdk-all-min.js` 以跳过 28.87MB 的 `sdk-all.js`）**不可行**：`sdk-all-min.js` 只是精简子集（约为全量代码的 11%，`AscWord` 出现次数 90 vs 2803），Word 引擎主体在全量文件里，跳过会导致编辑器异常。

## 8. 许可与带宽：给客户的结论

给客户交付/报价时请说明以下两点：

1. **手机端编辑需要 ONLYOFFICE 商业版**。手机网页端（移动端 H5）的编辑功能是 ONLYOFFICE **商业版/商业许可**能力，**社区版（Community）在手机网页端只能查看**，用户点编辑会弹许可提示。因此默认 `docs.mobile_edit=false`：**PC 端可正常编辑，移动端只读**。客户若要移动端编辑，需购买 ONLYOFFICE 商业许可，然后把 `docs.mobile_edit` 设为 `true` 并重启 wf-pan。
2. **建议为在线文档加 CDN**。首次打开在线文档要下载 ONLYOFFICE 编辑器引擎，静态资源在十几 MB 量级（`fonts/217` gzip ≈ 9.26MB、`sdk-all.js` gzip ≈ 4.51MB），低带宽服务器上会「打开很久」，且 WKWebView 对超大单文件有缓存上限导致每次都重下。**建议把 `/docs/` 静态资源接到 CDN**（静态、带版本、`immutable`，回源一次即可），`/doc/` 保持走源站；没有 CDN 时只能升级源站带宽。详见第 7 节。

## 9. 一次打开要下多少 / 怎么把流量降下来

### 9.1 实测：打开一篇文档到底下了什么

以 iOS 客户端（WKWebView，缓存失效时）打开一次为例，nginx 侧统计的传输量（gzip 后）：

| 资源 | 传输 | 磁盘(未压缩) | 说明 |
|---|---|---|---|
| `sdkjs/word/sdk-all.js` | 4.71MB | 28.87MB | Word 引擎主体，最大单项 |
| `sdkjs/word/sdk-all-min.js` | 0.64MB | 3.52MB | 移动端页面同时还会加载它 |
| `sdkjs/common/libfont/engine/fonts.wasm` | 1.33MB | 3.61MB | 字体引擎 |
| `web-apps/apps/documenteditor/mobile/dist/js/app.js` | 0.47MB | | 移动编辑器 |
| `sdkjs/common/spell/spell/spell.wasm` | 0.23MB | | 拼写检查（已在编辑器配置里关掉 customization.spellcheck） |
| `fonts/*`（文本文档用到的字体，按需） | 每个 0.12–0.2MB | | 西文字体 |
| `fonts/217`（文泉驿正黑，中文回退） | **9.18MB** | 16.79MB | 文档里有中文时才会请求 |
| `cache/files/.../Editor.bin` | ~0.1–0.3MB | | 文档内容本身 |

合计：纯西文文档 ~7MB；**中文文档 ~16MB**。PC/Electron 端有 Chromium 的磁盘缓存 + Service Worker，
第二次打开基本是 0；**移动端 WebView 如果缓存不生效，就会每次都重下这十几 MB**。

### 9.2 已做：让 Service Worker 把大文件也缓存住（不再重复下载）

ONLYOFFICE 自带的 `document_editor_service_worker.js` 对静态资源有单文件大小上限
（`maxEntrySize = min(storage 配额 * 10%, 1GiB) / 8`）和 `isHealthy`（磁盘占用 <80%）判断，
移动端配额估算值小/磁盘偏满时，`sdk-all.js`、`fonts/217` 这类大文件就**不写缓存**，于是每次打开都重下。

`deploy/onlyoffice/` 下提供一个补丁与安装脚本：把带版本号的静态资源
（`web-apps/` `sdkjs/` `fonts/` `sdkjs-plugins/` `dictionaries/`）改成**一律缓存**（单文件上限 512MB），
带 docid 的动态文件仍走原来的 FIFO 逻辑。补丁版 Service Worker 放在宿主机、由 nginx 直接返回，
**容器重建也不会丢**；官方原文件备份在 `/root/onlyoffice/sw-backup/`。

```bash
# 在 wfserver 上（脚本会 docker cp 官方文件 → 打补丁 → 写 nginx 覆盖 → reload）
cd /root/pan-deploy/onlyoffice && ./install_doc_service_worker_cache.sh

# 验证（应输出 1）
curl -s https://pan.wildfirechat.net/docs/<版本>/document_editor_service_worker.js | grep -c wf-patch
```

客户端侧配套（不加就仍然不会缓存）：

- **Android**：WebView 必须开 DOM Storage（`setDomStorageEnabled(true)`，已加在 `WfcWebViewFragment`），
  否则编辑器的 IndexedDB/Service Worker 缓存用不了。
- **鸿蒙**：`WfcWebView` 已开 `domStorageAccess(true)`。
- **iOS**：WKWebView 默认用持久化 `WKWebsiteDataStore`（只有登出/被踢时才清），无需改动。

### 9.3 还能再降：可选的三条路

1. **字体子集化（省最多，需要人工验收）**：`fonts/217` 等中文字体是最大单项（gzip 9.18MB）。
   用 fonttools 对**同一款字体**做子集（ASCII + 标点 + GB18030 常用字，保留 `hhea/OS 2/name` 等度量表），
   再按 ONLYOFFICE 的 32 字节 XOR 规则混淆并生成 `.gz`，可降到 ~2MB。
   **关键**：必须同步修改 `sdkjs/common/AllFonts.js` 里该字体的码点覆盖区间，
   否则客户端以为字体仍然覆盖全部字符、缺字时不会回退，就会出现上一次的「缺字/排版异常」。
2. **客户端本地化编辑器资源（最彻底）**：客户端首次把 `/docs/**` 静态资源打包下载到 App 私有目录
   （或直接打进安装包），之后由本地 HTTP/自定义 scheme 提供，做到「一次下载、永久使用」，甚至离线。
   代价是每个客户端（iOS/Android/鸿蒙/PC）都要实现一份本地资源服务。
3. **移动端只读改走 PDF 预览（对"看文档"最省）**：服务端用 ONLYOFFICE 的转换能力把 docx/xlsx/pptx
   转成 PDF 并缓存，移动端只下载几百 KB 的 PDF 用现成预览器打开（编辑仍走 ONLYOFFICE）。
   只读场景可从 ~16MB 降到 0.2–1MB。

> 注意：修改 sdkjs / 重新打包编辑器属于修改 ONLYOFFICE（AGPL v3）代码，
> 对外提供修改版时需按 AGPL 要求提供对应源码与许可声明；商用前请和法务确认。
