# wf-pan 网盘服务

基于 Java + Vue3 + MySQL 的网盘服务，支持多空间管理和权限控制。

## 项目结构

```
wf-pan/
├── DESIGN.md                    # 详细设计文档
├── README.md                    # 项目说明
├── wf-pan-server/               # 后端服务 (Java/Spring Boot)
│   ├── src/
│   │   └── main/java/com/wildfirechat/pan/
│   │       ├── config/          # 配置类
│   │       ├── controller/      # 控制器
│   │       ├── dto/             # 数据传输对象
│   │       ├── entity/          # 实体类
│   │       ├── repository/      # 数据访问层
│   │       └── service/         # 业务逻辑层
│   ├── src/main/resources/
│   │   └── static/              # 前端静态资源（npm run build 自动生成）
│   ├── pom.xml
│   └── README.md
├── wf-pan-admin/                # 管理后台 (Vue3)
│   ├── src/
│   ├── package.json
│   └── vite.config.js
```

## 快速开始

### 1. 配置数据库

编辑 `wf-pan-server/src/main/resources/application.properties`（生产环境建议使用外部配置文件覆盖）：

```properties
spring.datasource.url=jdbc:mysql://localhost:3306/wf_pan?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true
spring.datasource.username=root
spring.datasource.password=your_password
```

创建数据库：
```sql
CREATE DATABASE wf_pan CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

### 2. 配置 IM 服务

```properties
im.server.admin_url=http://localhost:18080
im.server.admin_secret=123456
```

### 3. 配置对象存储（可选）

支持多厂商对象存储：

```properties
# 媒体存储配置
# 1: 七牛云存储, 2: 阿里云对象存储, 3: 野火私有对象存储, 4: 对象存储网关
# 5: 腾讯云存储, 6: 华为云存储, 7: AWS S3, 8: 京东云存储
media.type=3
media.server_url=http://localhost:9000
media.access_key=minioadmin
media.secret_key=minioadmin
media.bucket=wf-pan
```

保存 IM 文件消息到网盘（`copy=true`）时，服务端只会从受信任的地址复制文件，
需要把 IM 文件所在 bucket 的访问地址配置为受信任前缀（逗号分隔）：

```properties
media.trusted_url_prefixes=http://localhost:9000/media/
```

客户端提交的存储地址必须位于网盘 bucket 或受信任前缀之下；服务端只删除网盘 bucket 中的对象。

**厂商配置示例：**

| 类型 | 配置示例 |
|------|----------|
| 0 - 未配置 | 不使用对象存储，文件仅保存URL |
| 1 - 七牛云 | `media.server_url=http://your-domain.qiniudn.com` |
| 2 - 阿里云 | `media.aliyun.endpoint=oss-cn-hangzhou.aliyuncs.com` |
| 3 - 野火私有 | MinIO兼容，配置`media.server_url`即可 |
| 4 - 对象存储网关 | MinIO兼容，配置`media.server_url`即可 |
| 5 - 腾讯云 | `media.tencent.region=ap-guangzhou` |
| 6 - 华为云 | `media.huawei.endpoint=obs.cn-north-4.myhuaweicloud.com` |
| 7 - AWS S3 | `media.aws.region=us-east-1` |
| 8 - 京东云 | `media.jdcloud.endpoint=s3.cn-north-1.jdcloud-oss.com` |

### 4. 构建项目

```bash
# 1. 构建前端（构建成功后自动复制到后端资源目录）
cd wf-pan-admin
npm install
npm run build

# 2. 构建并启动后端
cd ../wf-pan-server
mvn clean package
java -jar target/wf-pan-server-1.0.0.jar
```

### 5. 访问服务

- 管理后台：http://localhost:8080/
- 客户端 API：http://localhost:8081

初始管理员：
- 账号：admin
- 密码：首次启动时随机生成并打印在日志中（仅一次），也可以通过 `pan.admin.initial_password` 预先指定
- 登录后请立即修改密码；建议添加实际的 IM 用户为全局管理员（每个管理员有独立的登录密码）后删除 admin
- 旧版本升级的实例：未设置个人密码的管理员仍使用原共享密码登录，修改密码后改用个人密码

## 核心功能

### 空间管理

- **GLOBAL_PUBLIC**: 全局公共空间，所有人可读，全局管理员可写
- **USER_PUBLIC**: 用户公共空间，所有人可读，用户自己可管理
- **USER_PRIVATE**: 用户私有空间，仅自己可访问（`pan.admin.manage-private-space=true` 时管理员也可访问，默认关闭）
- **DEPT_PUBLIC/DEPT_PRIVATE**: 部门空间（预留）

### 权限控制

- 客户端使用 IM 的 authCode 认证
- 管理端使用 Session 认证
- 支持多个全局管理员
- 用户空间自动初始化

### 文件管理

- 文件夹非空不能删除
- 删除文件时，检查是否还有其他文件引用相同存储URL，无引用时才删除OSS对象
- 支持文件跨空间复制（可配置是否复制物理文件）
- 完整的配额统计

### 在线文档（ONLYOFFICE）

启用后可在客户端工作台里在线打开、编辑网盘中的 docx/xlsx/pptx，支持历史版本与旧格式转换；
文件不在网盘里时（聊天里的文件消息、外部链接）也能**按链接只读打开**。

#### 依赖：ONLYOFFICE Docs（Document Server）

在线文档需要一个额外的 **ONLYOFFICE Docs** 服务，官方以 **Docker** 方式部署。wf-pan 本身不含编辑器引擎，
只负责页面、鉴权、签名、内容代理与保存回调。不需要在线文档时把 `docs.enabled` 置为 `false`（网盘其它功能不受影响）。

```bash
docker run -d --name wf-docs --restart=always \
  -p 8089:80 \
  -e JWT_ENABLED=true \
  -e JWT_SECRET='<与 docs.jwt_secret 完全一致>' \
  -e ALLOW_PRIVATE_IP_ADDRESS=true \
  -e ALLOW_META_IP_ADDRESS=true \
  -v /data/onlyoffice/data:/var/www/onlyoffice/Data \
  -v /data/onlyoffice/logs:/var/log/onlyoffice \
  onlyoffice/documentserver
```

> `ALLOW_PRIVATE_IP_ADDRESS=true` **必须**：`docs.callback_base_url` 通常是内网地址（容器网关或服务名），
> ONLYOFFICE 默认拒绝访问内网地址，会导致打开文档时取不到内容、编辑器报「下载失败」。

wf-pan 侧配置：

```properties
docs.enabled=true
docs.jwt_secret=与 ONLYOFFICE 相同的 JWT 密钥（两边必须一致，HS256）
docs.server_public_path=/docs                 # 浏览器加载编辑器资源的同源路径（NG 反代到 ONLYOFFICE）
docs.server_internal_url=http://wf-docs       # 本服务调 ONLYOFFICE 的内网地址（转换、取保存结果）
docs.callback_base_url=http://wf-pan:8081     # ONLYOFFICE 回连本服务的内网地址（取文件、保存回调）
docs.mobile_edit=false                        # 社区版手机网页端不能编辑，默认关闭
```

#### 请求流向：谁经 wf-pan，谁直连 ONLYOFFICE

业务与文件内容都经 wf-pan 中转；只有「编辑器前端资源 + 协同编辑通道」由客户端直连 ONLYOFFICE。

| 请求 | 方向 | 说明 |
|---|---|---|
| `/api/v1/**`（空间/文件/分享/版本/文档接口） | 客户端 → wf-pan | 全部业务接口 |
| `/doc/**`、`POST /doc/session` | 客户端 → wf-pan | 文档 H5 页面与会话 Cookie |
| `/internal/docs/file/{fileId}`、`/internal/docs/raw` | **ONLYOFFICE → wf-pan** | 文档内容由 wf-pan 从对象存储读出再转发（不把原始地址交给 ONLYOFFICE） |
| `/internal/docs/callback` | **ONLYOFFICE → wf-pan** | 保存回调 |
| ONLYOFFICE `/cache/...`（内网地址） | **wf-pan → ONLYOFFICE** | 下载保存结果、格式转换 |
| `/pan/dl/{fileId}`（`/files/url` 返回的签名短链） | 客户端 → wf-pan | wf-pan 从私有桶读出转发 |
| `/docs/web-apps/apps/api/documents/api.js` 及 `/docs/**` 静态资源 | 客户端 → ONLYOFFICE | 编辑器 JS/字体/样式，浏览器直接加载 |
| 编辑器 iframe 的协同编辑 WebSocket | 客户端 → ONLYOFFICE | 实时协同不经过 wf-pan |

> 下载例外：对象存储未配置或当前类型不支持服务端读写时，`/files/url` 直接返回记录里的原始存储地址（客户端直连原存储）。

#### nginx 分流

wf-pan 只占几个前缀，其余路径都属于 ONLYOFFICE（尤其是编辑器加载缓存用的**绝对路径** `/cache/files/...`）。

```nginx
# 1) wf-pan 自己的路径
location /api/ { proxy_pass http://127.0.0.1:8081; }
location /doc/ { proxy_pass http://127.0.0.1:8081; }   # 文档 H5 页面
location /dl/  { proxy_pass http://127.0.0.1:8081; }   # 签名下载
# ONLYOFFICE 回连用的接口，绝不能对外
location /internal/ { return 404; }

# 2) 编辑器静态资源带 /docs/ 前缀（剥离后转发给 ONLYOFFICE）
location /docs/ {
    proxy_pass http://wf-docs/;
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";
    proxy_set_header Host $host;
}

# 3) 其余绝对路径也属于 ONLYOFFICE
location / {
    proxy_pass http://wf-docs;
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";
    proxy_set_header Host $host;
}
```

> **重要**：ONLYOFFICE 加载转换结果用的是绝对路径 `/cache/files/...`（**不带 `/docs/` 前缀**），
> 所以 `location /` 必须给 ONLYOFFICE，wf-pan 的路径要单独列出来。否则编辑器会卡在 `/cache/files/...` 404，报「下载失败」。
> 两者路径命名易混：wf-pan 是 `/doc/`（单数），ONLYOFFICE 是 `/docs/`（复数）。

#### 注意事项

1. **双向可达**：浏览器要能访问 `/docs/`；ONLYOFFICE 容器要能访问 wf-pan 的 `/internal/docs/**`
   （用容器网络名，如 `http://wf-pan:8081`，不要用 `127.0.0.1`）。
2. **JWT 必须一致**：ONLYOFFICE 侧 `JWT_ENABLED=true` 且 `JWT_SECRET` 与 `docs.jwt_secret` 相同，否则取文件/回调全部 403。
3. **资源要求**：ONLYOFFICE 建议 ≥2 核 / ≥4GB，数据卷要持久化（字体、缓存）。
4. **手机端**：社区版手机网页端不能编辑，`docs.mobile_edit=false`（默认）时移动端只读。
5. **路径前缀**：若网盘按 `pan.public_path=/pan` 反代部署，`docs.server_public_path` 与 nginx 的 `/docs/` 需与之一致。
6. **允许内网地址**：ONLYOFFICE 必须开 `ALLOW_PRIVATE_IP_ADDRESS=true`（必要时 `ALLOW_META_IP_ADDRESS=true`）。否则它拒绝从内网取 `document.url`，表现就是打开文档报「下载失败」——服务端日志里看不到 `/internal/docs/file/...` 请求。

#### 按链接只读打开

- 接口：`POST /api/v1/docs/view-url`，body `{url, name?, platform?}`
- 页面：`/doc/open?url=<地址>&name=<文件名>`（没有 `fileId`）
- 地址必须位于网盘 bucket 或 `media.trusted_url_prefixes` 之下，否则拒绝（与「直接引用存储地址」同一校验，防 SSRF）
- 只读：不注册保存回调、不回写；页面不提供历史版本/分享/转换/下载入口，文件内容由本服务代理给 ONLYOFFICE

## API 端口

| 端口 | 用途 | 认证方式 | 访问地址 |
|------|------|---------|---------|
| 8080 | 管理端口 | Cookie/Session | http://localhost:8080/ |
| 8081 | 客户端端口 | Header authCode | http://localhost:8081/api/v1/ |

**注意**：管理后台前端部署在根路径 `/`，不是 `/admin/`

## 技术栈

**后端**
- Java 17
- Spring Boot 3.x
- Spring Data JPA
- MySQL 8.0
- Spring Security Crypto
- MinIO Client (野火私有/网关)
- 七牛云 SDK
- 阿里云 OSS SDK
- 腾讯云 COS SDK
- 华为云 OBS SDK
- AWS S3 SDK
- 京东云 OSS SDK

**前端**
- Vue 3
- Element Plus
- Pinia
- Vue Router
- Vite

## 数据初始化

应用启动时会自动：
1. 创建数据库表（`spring.jpa.hibernate.ddl-auto: update`）
2. 没有任何管理员时创建初始管理员 admin（密码见上文）
3. 创建全局公共空间

## 开发模式

```bash
# 终端1：启动后端（开发模式）
cd wf-pan-server
mvn spring-boot:run

# 终端2：启动前端开发服务器
cd wf-pan-admin
npm run dev
```

前端开发服务器：http://localhost:3000
后端 API：http://localhost:8080 / http://localhost:8081
