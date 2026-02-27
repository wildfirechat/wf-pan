# 网盘客户端 UI 设计与交互流程

## 一、概述

网盘客户端集成在野火IM iOS客户端中，通过WFChatUIKit框架提供网盘功能。

## 二、UI 设计

### 2.1 网盘空间列表页 (WFCUPanViewController)

**界面布局：**
```
┌─────────────────────────────┐
│  网盘           [关闭]      │  ← 导航栏
├─────────────────────────────┤
│                             │
│  ┌───────────────────────┐  │
│  │ 📁 全局公共空间       │  │  ← 所有人可访问
│  │    系统公共空间       │  │
│  └───────────────────────┘  │
│                             │
│  ┌───────────────────────┐  │
│  │ 📁 我的公共空间       │  │  ← 所有人可读，自己可管理
│  │    你的个人公共空间   │  │
│  └───────────────────────┘  │
│                             │
│  ┌───────────────────────┐  │
│  │ 📁 我的私有空间       │  │  ← 仅自己可访问
│  │    你的个人私有空间   │  │
│  └───────────────────────┘  │
│                             │
└─────────────────────────────┘
```

**交互说明：**
- 点击任意空间进入文件列表
- 三个空间平铺展示，无分段控制器
- 空间按固定顺序排列：全局公共 → 我的公共 → 我的私有

---

### 2.2 文件列表页 (WFCUPanListViewController)

**界面布局：**
```
┌─────────────────────────────┐
│  < 返回  空间名称     [+]   │  ← 导航栏
├─────────────────────────────┤
│  📁 文件夹1                │  ← 文件夹项
│  ├─ 2024-01-15 创建       │
├─────────────────────────────┤
│  📄 文件1.pdf    2.5MB    │  ← 文件项
│  ├─ 2024-01-15 上传       │
├─────────────────────────────┤
│  📄 文件2.jpg    1.2MB    │
│  ├─ 2024-01-14 上传       │
├─────────────────────────────┤
│           ...              │
└─────────────────────────────┘
```

**交互说明：**
- 点击文件夹：进入子目录
- 点击文件：预览/下载
- 长按文件/文件夹：弹出操作菜单
- 右上角 [+] 按钮：上传文件/新建文件夹

---

### 2.3 文件操作菜单 (长按弹出)

**菜单项：**
```
┌─────────────────┐
│ 下载            │
│ 分享            │
│ 重命名          │
│ 移动到...       │
│ 复制到...       │
│ 删除            │
└─────────────────┘
```

**交互说明：**
- 根据文件类型和权限显示不同菜单项
- 公共空间文件仅管理员可操作
- 私有空间文件自己可完全控制

---

### 2.4 移动到/复制到 选择页

**界面布局：**
```
┌─────────────────────────────┐
│  < 取消  选择目标位置       │
├─────────────────────────────┤
│  全局公共空间              │  ← 可选（如权限允许）
├─────────────────────────────┤
│  我的公共空间              │  ← 可选
├─────────────────────────────┤
│  我的私有空间              │  ← 可选
├─────────────────────────────┤
│  ─────────────────────────  │
│  当前空间                  │
│  📁 根目录                 │
│  📁 文件夹1                │
│  📁 文件夹2                │
│     📁 子文件夹            │  ← 可展开
└─────────────────────────────┘
```

**限制说明：**
- 不能移动到当前所在位置
- 文件夹不能移动到自己的子目录（防止循环）
- 跨空间复制时可选择是否复制物理文件

---

### 2.5 消息中保存到网盘 (WFCUMessageListViewController)

**触发方式：**
- 长按文件消息
- 弹出菜单选择"保存到网盘"

**流程：**
```
长按文件消息
    ↓
┌─────────────────┐
│ 转发            │
│ 收藏            │
│ 保存到网盘  ←───┤
│ 删除            │
└─────────────────┘
    ↓
选择目标空间
    ↓
┌─────────────────┐
│ 全局公共空间   │
│ 我的公共空间   │
│ 我的私有空间   │
└─────────────────┘
    ↓
确认保存
    ↓
后台复制文件到Pan bucket (copy=true)
    ↓
创建文件记录
    ↓
提示"保存成功"
```

**技术说明：**
- MIME类型从文件名扩展名推断
- 调用API时设置 `copy=true`，触发OSS复制

---

## 三、API 调用

### 3.1 获取空间列表
```
GET /api/v1/spaces
Headers: authCode: {im_auth_code}
```

### 3.2 获取文件列表
```
GET /api/v1/spaces/{spaceId}/files?parentId={parentId}
Headers: authCode: {im_auth_code}
```

### 3.3 创建文件夹
```
POST /api/v1/files/folder
Body: {
  "spaceId": 1,
  "parentId": null,
  "name": "新文件夹"
}
```

### 3.4 创建文件记录（保存到网盘）
```
POST /api/v1/files
Body: {
  "spaceId": 1,
  "parentId": null,
  "name": "document.pdf",
  "size": 1024000,
  "mimeType": "application/pdf",
  "md5": "...",
  "storageUrl": "http://...",
  "copy": true  // 保存文件消息时为true
}
```

### 3.5 移动文件
```
POST /api/v1/files/{id}/move
Body: {
  "targetSpaceId": 2,
  "targetParentId": 10
}
```

### 3.6 复制文件
```
POST /api/v1/files/{id}/copy
Body: {
  "targetSpaceId": 2,
  "targetParentId": 10,
  "copy": false  // 同空间false，跨空间true
}
```

### 3.7 删除文件
```
POST /api/v1/files/{id}/delete
```

### 3.8 重命名文件
```
POST /api/v1/files/{id}/rename
Body: {
  "newName": "新名称.pdf"
}
```

### 3.9 获取下载URL
```
POST /api/v1/files/url
Body: {
  "fileId": 123
}
Response: {
  "url": "https://...",
  "expires": 3600
}
```

---

## 四、数据模型

### 4.1 空间类型 (WFCUPanSpaceType)
```objc
typedef NS_ENUM(NSInteger, WFCUPanSpaceType) {
    WFCUPanSpaceTypeGlobalPublic = 1,  // 全局公共空间
    WFCUPanSpaceTypeUserPublic = 2,    // 用户公共空间
    WFCUPanSpaceTypeUserPrivate = 3,   // 用户私有空间
};
```

### 4.2 空间对象
```objc
@interface WFCUPanSpace : NSObject
@property (nonatomic, assign) NSInteger spaceId;
@property (nonatomic, assign) WFCUPanSpaceType type;
@property (nonatomic, copy) NSString *name;
@property (nonatomic, assign) NSInteger totalQuota;
@property (nonatomic, assign) NSInteger usedQuota;
@end
```

### 4.3 文件对象
```objc
@interface WFCUPanFile : NSObject
@property (nonatomic, assign) NSInteger fileId;
@property (nonatomic, assign) NSInteger spaceId;
@property (nonatomic, assign) NSInteger parentId;
@property (nonatomic, copy) NSString *name;
@property (nonatomic, assign) BOOL isFolder;
@property (nonatomic, assign) long long size;
@property (nonatomic, copy) NSString *mimeType;
@property (nonatomic, copy) NSString *storageUrl;
@property (nonatomic, copy) NSString *creatorId;
@property (nonatomic, copy) NSString *creatorName;
@property (nonatomic, copy) NSDate *createTime;
@end
```

---

## 五、权限控制

### 5.1 客户端权限判断
| 操作 | 全局公共空间 | 我的公共空间 | 我的私有空间 |
|------|-------------|-------------|-------------|
| 查看 | ✅ 所有人 | ✅ 所有人 | ✅ 仅自己 |
| 上传 | ❌ 普通用户 | ✅ 自己 | ✅ 自己 |
| 删除 | ❌ 普通用户 | ✅ 自己 | ✅ 自己 |
| 重命名 | ❌ 普通用户 | ✅ 自己 | ✅ 自己 |
| 移动到 | ❌ 普通用户 | ✅ 自己 | ✅ 自己 |
| 复制到 | ✅ 有权限 | ✅ 自己 | ✅ 自己 |

**注：** 全局公共空间的管理权限由后端判断，客户端仅作展示。

---

## 六、国际化 (i18n)

### 6.1 键值定义
```
// 网盘相关
"Pan" = "网盘";
"SaveToPan" = "保存到网盘";
"GlobalPublicSpace" = "全局公共空间";
"MyPublicSpace" = "我的公共空间";
"MyPrivateSpace" = "我的私有空间";

// 文件操作
"Download" = "下载";
"Share" = "分享";
"Rename" = "重命名";
"MoveTo" = "移动到";
"CopyTo" = "复制到";
"Delete" = "删除";
"CreateFolder" = "新建文件夹";
"UploadFile" = "上传文件";

// 提示信息
"SaveSuccess" = "保存成功";
"DeleteConfirm" = "确定要删除吗？";
"FolderNotEmpty" = "文件夹非空，无法删除";
"QuotaExceeded" = "空间容量不足";
```

### 6.2 支持语言
- 简体中文 (zh-Hans)
- 繁体中文 (zh-Hant)
- 英文 (en)

---

## 七、错误处理

### 7.1 网络错误
- 显示重试按钮
- 提示用户检查网络连接

### 7.2 权限错误 (403)
- 提示"无权限执行此操作"
- 返回上一级页面

### 7.3 配额不足
- 提示"空间容量不足"
- 建议清理空间或联系管理员

### 7.4 文件已存在
- 提示"该位置已存在同名文件"
- 提供"覆盖"或"重命名"选项

---

## 八、性能优化

### 8.1 列表加载
- 分页加载，每页20条
- 下拉刷新
- 上拉加载更多

### 8.2 图片预览
- 缩略图缓存
- 原图懒加载

### 8.3 文件下载
- 断点续传
- 后台下载
- 下载进度显示
