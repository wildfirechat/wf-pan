# wf-pan-server 网盘服务端

基于 Java/Spring Boot + MySQL 实现的网盘服务。

## 功能特性

- ✅ 双端口设计（8080管理端口 + 8081客户端端口）
- ✅ 多空间支持（全局空间/部门空间/个人空间）
- ✅ 权限管理（全局管理员/部门管理员/个人）
- ✅ 文件夹管理（非空不能删除）
- ✅ 存储同步删除
- ✅ 用户空间自动初始化
- ✅ 基于 authCode 的 IM 认证
- ✅ 内嵌管理后台（Vue3）

## 技术栈

- Java 17
- Spring Boot 3.x
- Spring Data JPA
- MySQL 8.0
- Spring Security Crypto
- MinIO Client

## 快速开始

### 1. 创建数据库

```sql
CREATE DATABASE wf_pan CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

### 2. 配置

编辑 `src/main/resources/application.properties`（生产环境建议使用外部配置文件覆盖）：

```properties
spring.datasource.url=jdbc:mysql://localhost:3306/wf_pan?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true
spring.datasource.username=root
spring.datasource.password=your_password

im.server.admin_url=http://localhost:18080
im.server.admin_secret=123456
```

### 3. 运行

```bash
mvn spring-boot:run
```

服务启动后：
- 管理后台：http://localhost:8080/
- 客户端 API：http://localhost:8081

### 4. 初始管理员

- 账号：admin
- 密码：首次启动时随机生成并打印在日志中（仅一次），也可以通过 `pan.admin.initial_password` 预先指定
- 登录后请立即修改密码；建议添加实际的 IM 用户为全局管理员（每个管理员有独立的登录密码）后删除 admin
- 旧版本升级的实例：未设置个人密码的管理员仍使用原共享密码登录，修改密码后改用个人密码

## 完整构建（包含前端）

```bash
# 1. 构建前端（需要 npm/node）
cd ../wf-pan-admin
npm install
npm run build

# 2. 构建后端
cd ../wf-pan-server
mvn clean package

# 3. 运行
java -jar target/wf-pan-server-1.0.0.jar
```

访问 http://localhost:8080/ 打开管理后台。

## API 文档

### 管理端口 (8080)

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/auth/login | 登录 |
| POST | /api/auth/logout | 退出 |
| GET | /api/dashboard/stats | 统计信息 |
| GET | /api/global-admins | 全局管理员列表 |
| POST | /api/global-admins | 添加管理员 |
| DELETE | /api/global-admins/{userId} | 移除管理员 |
| GET | /api/spaces | 空间列表 |
| GET | /api/spaces/{id}/files | 空间文件 |
| GET | /api/files | 全局搜索 |

### 客户端端口 (8081)

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/v1/spaces | 空间列表 |
| GET | /api/v1/spaces/my | 我的空间 |
| GET | /api/v1/spaces/{id}/files | 文件列表 |
| POST | /api/v1/files/folder | 创建文件夹 |
| POST | /api/v1/files | 新增文件记录 |
| DELETE | /api/v1/files/{id} | 删除 |
| PUT | /api/v1/files/{id}/rename | 重命名 |
| PUT | /api/v1/files/{id}/move | 移动 |
| GET | /api/v1/files/{id}/url | 获取下载URL |

所有客户端请求需要在 Header 中携带 `authCode`。

## 数据库表结构

- `sys_config`: 系统配置
- `pan_global_admin`: 全局管理员
- `pan_space`: 空间
- `pan_space_admin`: 空间管理员
- `pan_file`: 文件/文件夹
- `pan_dept_member`: 部门成员（预留）
- `pan_operation_log`: 操作日志

## 数据初始化

应用启动时会自动创建表结构和初始化数据：
1. 没有任何管理员时创建初始管理员 admin（随机密码打印在日志中，或使用 `pan.admin.initial_password`）
2. 创建全局公共空间
3. 为旧数据回填文件的对象存储 key
