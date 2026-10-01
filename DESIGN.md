# 网盘服务设计方案 V4

## 一、项目结构

```
wf-pan/
├── DESIGN.md                           # 设计文档
├── wf-pan-server/                      # 后端服务 (Java/Spring Boot)
│   ├── src/
│   ├── pom.xml
│   └── README.md
├── wf-pan-admin/                       # 管理后台 (Vue3)
│   ├── src/
│   ├── package.json
│   └── README.md
```

## 二、空间与权限模型

### 2.1 空间类型

| 空间类型 | 查看权限 | 管理权限 | 自动初始化 |
|---------|---------|---------|-----------|
| **GLOBAL_PUBLIC** | 所有人 | 全局管理员 | 系统启动时 |
| **USER_PUBLIC** | 所有人 | 用户自己 | 用户首次访问时 |
| **USER_PRIVATE** | 仅自己 | 用户自己 | 用户首次访问时 |

### 2.2 权限矩阵

| 操作 | GLOBAL_PUBLIC | USER_PUBLIC | USER_PRIVATE |
|------|---------------|-------------|--------------|
| 查看 | 所有人 | 所有人 | 仅自己 |
| 上传/创建文件夹 | 全局管理员 | 用户自己 | 用户自己 |
| 删除 | 全局管理员 | 用户自己 | 用户自己 |
| 重命名/移动 | 全局管理员 | 用户自己 | 用户自己 |
| 复制到 | 全局管理员 | 用户自己 | 用户自己 |

## 三、数据库设计

### 3.1 表结构

```sql
-- 系统配置表
CREATE TABLE sys_config (
    id BIGSERIAL PRIMARY KEY,
    config_key VARCHAR(64) UNIQUE NOT NULL,
    config_value TEXT,
    description VARCHAR(255),
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 全局管理员表（支持多个）
CREATE TABLE pan_global_admin (
    id BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL UNIQUE,
    username VARCHAR(128),
    created_by VARCHAR(64),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 空间表
CREATE TABLE pan_space (
    id BIGSERIAL PRIMARY KEY,
    space_type VARCHAR(32) NOT NULL CHECK (space_type IN ('GLOBAL_PUBLIC', 'USER_PUBLIC', 'USER_PRIVATE')),
    owner_id VARCHAR(64),
    owner_type VARCHAR(32) CHECK (owner_type IN ('USER', 'SYSTEM')),
    name VARCHAR(128) NOT NULL,
    total_quota BIGINT DEFAULT 10737418240,
    used_quota BIGINT DEFAULT 0,
    file_count INT DEFAULT 0,
    folder_count INT DEFAULT 0,
    auto_init BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(space_type, owner_id)
);

-- 空间管理员表
CREATE TABLE pan_space_admin (
    id BIGSERIAL PRIMARY KEY,
    space_id BIGINT NOT NULL REFERENCES pan_space(id),
    user_id VARCHAR(64) NOT NULL,
    admin_type VARCHAR(32) DEFAULT 'ADMIN' CHECK (admin_type IN ('ADMIN', 'SUPER')),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(space_id, user_id)
);

-- 文件/文件夹表
CREATE TABLE pan_file (
    id BIGSERIAL PRIMARY KEY,
    space_id BIGINT NOT NULL REFERENCES pan_space(id),
    parent_id BIGINT REFERENCES pan_file(id),
    name VARCHAR(255) NOT NULL,
    type VARCHAR(20) NOT NULL CHECK (type IN ('FILE', 'FOLDER')),
    size BIGINT DEFAULT 0,
    mime_type VARCHAR(128),
    md5 VARCHAR(32),
    storage_url TEXT,
    child_count INT DEFAULT 0,
    creator_id VARCHAR(64) NOT NULL,
    creator_name VARCHAR(128),
    is_deleted BOOLEAN DEFAULT FALSE,
    deleted_at TIMESTAMP,
    deleted_by VARCHAR(64),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(space_id, parent_id, name, is_deleted)
);

-- 索引
CREATE INDEX idx_storage_url_deleted ON pan_file(storage_url, is_deleted);  -- 用于检查storage_url引用计数
CREATE INDEX idx_space_parent ON pan_file(space_id, parent_id, is_deleted); -- 用于列表查询
CREATE INDEX idx_parent_id ON pan_file(parent_id, is_deleted);              -- 用于子文件统计

-- 部门成员表（预留）
CREATE TABLE pan_dept_member (
    id BIGSERIAL PRIMARY KEY,
    dept_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(dept_id, user_id)
);

-- 操作日志表
CREATE TABLE pan_operation_log (
    id BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    operation VARCHAR(64) NOT NULL,
    target_type VARCHAR(32),
    target_id BIGINT,
    space_id BIGINT,
    details JSONB,
    ip_address VARCHAR(64),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

## 四、双端口设计

| 端口 | 用途 | 认证方式 |
|------|------|---------|
| 8080 | 管理端口 | Session/Cookie |
| 8081 | 客户端端口 | Header authCode |

## 五、核心API

### 5.1 管理端口 (8080)

**注意**：所有API路径前缀为 `/api/*`，不是 `/admin/api/*`

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/auth/login` | 管理员登录 |
| POST | `/api/auth/logout` | 退出登录 |
| GET | `/api/dashboard/stats` | 系统统计 |
| GET | `/api/global-admins` | 全局管理员列表 |
| POST | `/api/global-admins` | 添加全局管理员 |
| DELETE | `/api/global-admins/{userId}` | 移除全局管理员 |
| GET | `/api/spaces` | 空间列表 |
| GET | `/api/spaces/{id}/files` | 空间文件列表 |
| GET | `/api/files` | 全局文件搜索 |
| DELETE | `/api/files/{id}` | 删除文件 |

### 5.2 客户端端口 (8081)

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/spaces` | 获取有权限的空间列表 |
| GET | `/api/v1/spaces/{id}/files` | 获取空间内文件列表 |
| POST | `/api/v1/files/folder` | 创建文件夹 |
| POST | `/api/v1/files` | 新增文件记录 |
| DELETE | `/api/v1/files/{id}` | 删除文件/文件夹 |
| POST | `/api/v1/files/{id}/copy` | 复制文件/文件夹 |
| POST | `/api/v1/files/{id}/move` | 移动文件/文件夹 |
| POST | `/api/v1/files/{id}/rename` | 重命名文件/文件夹 |
| POST | `/api/v1/files/url` | 获取下载URL |

### 5.3 在线文档（ONLYOFFICE）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/docs/create` | 用空白模板新建 docx/xlsx/pptx |
| POST | `/api/v1/docs/editor-config` | 打开编辑器（返回签名的编辑器配置） |
| POST | `/api/v1/docs/view-url` | 按链接**只读**打开在线文档（文件不在网盘里），`{url, name?, platform?}`；地址须在 `media.trusted_url_prefixes` 下，内容由服务端代理给 ONLYOFFICE，不回写 |
| POST | `/api/v1/docs/convert` | 旧格式转为 OOXML 另存 |
| POST | `/api/v1/docs/recent` `/recent/remove` | 最近打开 |
| GET | `/doc/open` | 在线文档 H5 页面（`?fileId=` 网盘文件，或 `?url=&name=` 按链接只读） |
| GET/POST | `/internal/docs/**` | 只给 ONLYOFFICE 在容器网络内调用（JWT + 地址签名），NG 不转发 |

## 六、业务规则

1. **文件夹删除**: 非空文件夹不能删除，必须先删除内部文件
2. **URL验证**: 不验证客户端提交的存储URL
3. **存储同步**: 删除网盘文件记录时，检查是否还有其他文件引用相同的 storage_url，如无引用则删除对象存储文件
4. **空间初始化**: 用户首次访问时自动创建个人空间（公共+私有）
5. **部门权限**: 预留部门相关判断方法，暂不实现
6. **文件版本**: 不支持
7. **文件去重**: 相同 storage_url 的文件可存在于多个目录/空间，共享物理存储

## 七、技术栈

### 后端 (wf-pan-server)
- Java 17
- Spring Boot 3.x
- Spring Data JPA
- MySQL 8.0
- MinIO Client (删除/复制用)
- 七牛云 SDK
- 阿里云 OSS SDK
- 腾讯云 COS SDK
- 华为云 OBS SDK
- AWS S3 SDK
- 京东云 OSS SDK

## 八、多OSS对象存储支持

### 8.1 支持的厂商

| 类型值 | 厂商 | SDK | 复制功能 | 删除功能 | 状态 |
|--------|------|-----|----------|----------|------|
| 0 | 未配置 | - | ✓ | ✓ | 完整支持 |
| 1 | 七牛云 | qiniu-java-sdk | ✓ | ✓ | 完整支持 |
| 2 | 阿里云OSS | aliyun-sdk-oss | ✓ | ✓ | 完整支持 |
| 3 | 野火私有 | minio | ✓ | ✓ | 完整支持 |
| 4 | 对象存储网关 | minio | ✓ | ✓ | 完整支持 |
| 5 | 腾讯云COS | cos_api | ✓ | ✓ | 完整支持 |
| 6 | 华为云OBS | esdk-obs-java | ✓ | ✓ | 完整支持 |
| 7 | AWS S3 | aws-s3-sdk | ✓ | ✓ | 完整支持 |
| 8 | 京东云 | aws-s3-sdk | ✓ | ✓ | 完整支持 |

**注意**：对象存储网关(type=4)暂未实现，留作后续扩展

### 8.2 文件复制机制

**copy 参数逻辑：**
- `copy=false`（默认）：仅创建文件记录，不复制物理文件
- `copy=true`：检查文件是否在目标bucket中，不存在则执行复制

**使用场景：**
1. **客户端直接上传**：`copy=false`，文件已上传到正确bucket
2. **从文件消息保存**：`copy=true`，文件可能在IM bucket，需复制到Pan bucket

**跨空间复制：**
- 同空间内复制：`copy=false`，共享物理文件
- 跨空间复制：`copy=true`，确保目标空间独立拥有文件

### 前端 (wf-pan-admin)
- Vue 3
- Element Plus
- Pinia
- Vue Router
- Axios
