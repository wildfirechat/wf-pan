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
docs.mobile_edit=false
docs.hide_chat=true
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
