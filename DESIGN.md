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
| **DEPT_PUBLIC** | 部门成员（预留） | 部门管理员（预留） | 否 |
| **DEPT_PRIVATE** | 部门成员（预留） | 部门管理员（预留） | 否 |
| **USER_PUBLIC** | 所有人 | 用户自己 | 用户首次访问时 |
| **USER_PRIVATE** | 仅自己 | 用户自己 | 用户首次访问时 |

### 2.2 权限矩阵

| 操作 | GLOBAL_PUBLIC | DEPT_PUBLIC | DEPT_PRIVATE | USER_PUBLIC | USER_PRIVATE |
|------|---------------|-------------|--------------|-------------|--------------|
| 查看 | 所有人 | 部门成员(预留) | 部门成员(预留) | 所有人 | 仅自己 |
| 上传/创建文件夹 | 全局管理员 | 部门管理员(预留) | 部门管理员(预留) | 用户自己 | 用户自己 |
| 删除 | 全局管理员 | 部门管理员(预留) | 部门管理员(预留) | 用户自己 | 用户自己 |
| 重命名/移动 | 全局管理员 | 部门管理员(预留) | 部门管理员(预留) | 用户自己 | 用户自己 |

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
    space_type VARCHAR(32) NOT NULL CHECK (space_type IN ('GLOBAL_PUBLIC', 'DEPT_PUBLIC', 'DEPT_PRIVATE', 'USER_PUBLIC', 'USER_PRIVATE')),
    owner_id VARCHAR(64),
    owner_type VARCHAR(32) CHECK (owner_type IN ('USER', 'DEPT', 'SYSTEM')),
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

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/admin/api/auth/login` | 管理员登录 |
| POST | `/admin/api/auth/logout` | 退出登录 |
| GET | `/admin/api/dashboard/stats` | 系统统计 |
| GET | `/admin/api/global-admins` | 全局管理员列表 |
| POST | `/admin/api/global-admins` | 添加全局管理员 |
| DELETE | `/admin/api/global-admins/{userId}` | 移除全局管理员 |
| GET | `/admin/api/spaces` | 空间列表 |
| GET | `/admin/api/spaces/{id}/files` | 空间文件列表 |
| GET | `/admin/api/files` | 全局文件搜索 |
| DELETE | `/admin/api/files/{id}` | 删除文件 |

### 5.2 客户端端口 (8081)

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/spaces` | 获取有权限的空间列表 |
| GET | `/api/v1/spaces/{id}/files` | 获取空间内文件列表 |
| POST | `/api/v1/files/folder` | 创建文件夹 |
| POST | `/api/v1/files` | 新增文件记录 |
| DELETE | `/api/v1/files/{id}` | 删除文件/文件夹 |
| PUT | `/api/v1/files/{id}/move` | 移动 |
| PUT | `/api/v1/files/{id}/rename` | 重命名 |
| GET | `/api/v1/files/{id}/url` | 获取下载URL |

## 六、业务规则

1. **文件夹删除**: 非空文件夹不能删除，必须先删除内部文件
2. **URL验证**: 不验证客户端提交的存储URL
3. **存储同步**: 删除网盘文件记录时，同步删除对象存储文件
4. **空间初始化**: 用户首次访问时自动创建个人空间（公共+私有）
5. **部门权限**: 预留部门相关判断方法，暂不实现
6. **文件版本**: 不支持

## 七、技术栈

### 后端 (wf-pan-server)
- Java 17
- Spring Boot 3.x
- Spring Data JPA
- PostgreSQL
- Flyway
- MinIO Client (仅删除用)

### 前端 (wf-pan-admin)
- Vue 3
- Element Plus
- Pinia
- Vue Router
- Axios
