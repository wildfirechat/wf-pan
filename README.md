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

编辑 `wf-pan-server/src/main/resources/application.yml`：

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/wf_pan?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true
    username: root
    password: your_password
```

创建数据库：
```sql
CREATE DATABASE wf_pan CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

### 2. 配置 IM 服务

```yaml
im:
  admin-url: http://localhost:18080
  admin-secret: 123456
```

### 3. 配置对象存储（可选）

```yaml
storage:
  provider: minio  # none | minio | s3
  endpoint: http://localhost:9000
  access-key: minioadmin
  secret-key: minioadmin
```

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

- 管理后台：http://localhost:8080/admin/
- 客户端 API：http://localhost:8081

默认管理员：
- 账号：admin
- 密码：admin123

## 核心功能

### 空间管理

- **GLOBAL_PUBLIC**: 全局公共空间，所有人可读，全局管理员可写
- **USER_PUBLIC**: 用户公共空间，所有人可读，用户自己可管理
- **USER_PRIVATE**: 用户私有空间，仅自己可访问
- **DEPT_PUBLIC/DEPT_PRIVATE**: 部门空间（预留）

### 权限控制

- 客户端使用 IM 的 authCode 认证
- 管理端使用 Session 认证
- 支持多个全局管理员
- 用户空间自动初始化

### 文件管理

- 文件夹非空不能删除
- 删除文件时同步删除对象存储
- 完整的配额统计

## API 端口

| 端口 | 用途 | 认证方式 | 访问地址 |
|------|------|---------|---------|
| 8080 | 管理端口 | Cookie/Session | http://localhost:8080/admin/ |
| 8081 | 客户端端口 | Header authCode | http://localhost:8081/api/v1/ |

## 技术栈

**后端**
- Java 17
- Spring Boot 3.x
- Spring Data JPA
- MySQL 8.0
- Spring Security Crypto
- MinIO Client

**前端**
- Vue 3
- Element Plus
- Pinia
- Vue Router
- Vite

## 数据初始化

应用启动时会自动：
1. 创建数据库表（`spring.jpa.hibernate.ddl-auto: update`）
2. 初始化系统配置（管理员账号密码）
3. 创建默认全局管理员（admin/admin123）
4. 创建全局公共空间

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
