# Nginx 配置说明

## 文件说明

| 文件 | 域名 | 转发到后端端口 |
|------|------|---------------|
| `pan.wildfirechat.net.conf` | pan.wildfirechat.net | 8081 (客户端端口) |
| `panadmin.wildfirechat.net.conf` | panadmin.wildfirechat.net | 8080 (管理端口) |

## 端口判断问题解答

### 工作原理

```
用户请求 → nginx → 后端 Java 服务
              ↓           ↓
         监听 80/443   getServerPort() 返回 nginx 连接的端口
              ↓           ↓
    proxy_pass 到       8080 (管理) 或 8081 (客户端)
```

### 部署架构

```
                    ┌─────────────────┐
  pan.wildfirechat.net              panadmin.wildfirechat.net
         │                                   │
         ▼                                   ▼
  ┌─────────────┐                    ┌─────────────┐
  │   Nginx     │                    │    Nginx    │
  │  :80/:443   │                    │  :80/:443   │
  └──────┬──────┘                    └──────┬──────┘
         │                                   │
         │ proxy_pass                        │ proxy_pass
         │ http://127.0.0.1:8081             │ http://127.0.0.1:8080
         ▼                                   ▼
  ┌─────────────┐                    ┌─────────────┐
  │   wf-pan    │                    │   wf-pan    │
  │   :8081     │                    │   :8080     │
  │  (客户端端口) │                    │  (管理端口)  │
  │             │                    │             │
  │  /api/v1/*  │                    │  /api/*     │
  │  客户端API  │                    │  管理API    │
  └─────────────┘                    └─────────────┘
```

### 配置使用

1. 将配置文件复制到 nginx 配置目录：
```bash
sudo cp pan.wildfirechat.net.conf /etc/nginx/conf.d/
sudo cp panadmin.wildfirechat.net.conf /etc/nginx/conf.d/
```

2. 测试配置：
```bash
sudo nginx -t
```

3. 重载 nginx：
```bash
sudo nginx -s reload
```

4. （可选）配置 SSL 证书：
   - 取消配置文件中的 SSL 相关注释
   - 修改证书路径
   - 开启 HTTP 到 HTTPS 的重定向

## 安全建议

1. **生产环境务必使用 HTTPS**
   - 管理后台已添加安全响应头
   - 客户端和管理后台都应该配置 SSL

2. **限制管理后台访问（可选）**
   可以在 nginx 层添加额外的 IP 白名单：
   ```nginx
   location / {
       allow 192.168.1.0/24;  # 只允许内网访问
       deny all;
       proxy_pass ...;
   }
   ```

3. **启用 HTTPS 后更新后端配置**
   如果使用 HTTPS，确保前端访问的是 HTTPS 地址。
