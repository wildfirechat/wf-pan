# wf-pan-admin 网盘管理后台

基于 Vue3 + Element Plus 的网盘管理后台。

## 功能模块

- 登录/登出
- 仪表盘统计
- 全局管理员管理
- 空间管理
- 文件管理

## 技术栈

- Vue 3
- Element Plus
- Pinia
- Vue Router
- Axios
- Vite

## 开发模式

```bash
# 安装依赖
npm install

# 启动开发服务器
npm run dev
```

开发服务器启动后访问 http://localhost:3000

- 前端页面：http://localhost:3000
- API 请求会自动代理到 http://localhost:8080

## 生产构建

```bash
# 构建生产环境（构建成功后自动复制到后端资源目录）
npm run build
```

执行 `npm run build` 后，前端资源会自动复制到 `../wf-pan-server/src/main/resources/static/` 目录下。

这样后端打包时会自动包含前端页面，访问 `http://localhost:8080/` 即可打开管理后台。

## 访问地址

| 环境 | 地址 |
|------|------|
| 开发环境 | http://localhost:3000 |
| 生产环境（嵌入后端） | http://localhost:8080 |

初始账号：admin。密码由后端配置 `pan.admin.initial_password` 指定；未配置时后端首次启动随机生成并打印在日志中（仅一次）。登录后请立即修改密码。

## 配置说明

### 部署路径

管理后台部署在后端的根路径下：
- 页面：`/index.html`
- 资源：`/assets/...`
- API：`/api/...`

如需修改部署路径，需要调整以下配置：
1. `vite.config.js` 中的 `base` 配置
2. `src/router/index.js` 中的 `createWebHistory()`
3. 后端 `AdminAuthFilter.java` 中的拦截路径
