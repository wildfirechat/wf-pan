# 实施计划：android-chat 集成「网盘(pan)」+「在线文档(online docs)」

> 目标仓库：`/Users/rain/Workspace/android-chat`（原生 Android / Java 的野火 IM 客户端）。
> **权威行为规格**：`/Users/rain/Workspace/wf-pan/docs/client-pan-docs-spec.md`（客户端移植规格，本计划与它对齐；
> 若两者冲突，以 spec 为准）。
> 行为参考：Flutter 参考实现 `/Users/rain/Workspace/wf-enterprise-chat/chat/lib/pan/`、`chat/lib/settings/me_tab.dart`、
> `chat/lib/workspace/{wf_webview_screen,js_api,dsbridge_webview}.dart`、`chat/lib/utils/auth_code_api_client.dart`。
> 服务端契约：`/Users/rain/Workspace/wf-app-server/app-pan/`（合并进 app-server 的网盘模块），
> 独立版 `/Users/rain/Workspace/wf-pan/wf-pan-server/`，H5 桥脚本 `/Users/rain/Workspace/wf-pan/wf-pan-server/src/main/resources/doc-web/app.js`。
> 本文件只描述做法，**不修改任何代码**。
>
> 说明：本计划起草时发现 `uikit/src/main/java/cn/wildfire/chat/kit/Config.java` 已新增 `PAN_SERVER_ADDRESS` /
> `PAN_SERVER_BACKUP_ADDRESS` / `getPanServerAddress()` / `isPanConfigured()`（见该文件 ≈L183-195、L343-353 的 diff）。
> 但该处注释写的是「独立 wf-pan-server 客户端端口，接口在 `/api/v1` 下，鉴权用 authCode」，**与 spec 的合并服务模型
> （`PAN_SERVER_ADDRESS = appRoot + "/api/pan"`，authToken 鉴权）不一致**；本计划按 spec 实现，并建议把该注释与语义改成
> `appRoot + "/api/pan"`（或同时兼容两种部署，见 §3.3 末）。

---

## 1. 项目 / 模块布局、构建、SDK、语言

### 1.1 模块结构（`settings.gradle`）

| 模块 | 角色 | 关键路径 |
|---|---|---|
| `:chat` | 应用（`com.android.application`），`applicationId cn.wildfirechat.chat.open`，`namespace cn.wildfirechat.chat` | `chat/src/main/java/cn/wildfire/chat/app/**`，`chat/src/main/AndroidManifest.xml` |
| `:uikit` | 可复用 UI/SDK 封装（`com.android.library`），`namespace cn.wildfire.chat.kit` | `uikit/src/main/java/cn/wildfire/chat/kit/**`，`uikit/src/main/AndroidManifest.xml` |
| `:client` | IM SDK（协议栈），`ChatManager` 等 | `client/src/main/java/cn/wildfirechat/**` |
| 其他 | `:push`、`:avenginekit`、`:webrtc`、`:menu`、`:badgeview`、`:permission`、`:emojilibrary`、`:imagepicker`、`:cameraview`、`:uvccamera`、`:mars-core-release`、`:uikit-aar-dep`、`:pttclient` | `settings.gradle` |

引入方式：网盘 UI/模型放进 **`:uikit`**（`cn.wildfire.chat.kit.pan.*`），服务实现放进 **`:chat`**（`cn.wildfire.chat.app.pan.PanServiceImpl`），
与现有「接龙/投票」的分层一致（接口 + Activity 在 uikit，`*ServiceImpl` 在 chat，见 §2.4）。

### 1.2 构建命令

```bash
cd /Users/rain/Workspace/android-chat

# 编译库（快速校验 uikit 改动的编译错误）
./gradlew :uikit:assembleDebug

# 编译 App（Debug APK）
./gradlew :chat:assembleDebug
# 产物：chat/build/outputs/apk/debug/chat-debug.apk

# 发布包
./gradlew :chat:assembleRelease
# 产物：chat/build/outputs/apk/release/chat-release.apk

# 只做资源/清单合并检查（改 Manifest、strings 后）
./gradlew :chat:processDebugManifest :chat:processDebugResources

# 静态检查（lint 已配置 abortOnError false）
./gradlew :chat:lintDebug
```

- 无需 `JITPACK`（`settings.gradle` 仅在 `JITPACK=true` 时裁剪模块）。
- 命令行需 JDK 17（`compileOptions` 为 `VERSION_17`）。
- `chat` 有 `mergeSelfSignedCerts` 任务（合并 `client/src/main/assets/certs` → `res/raw/wfc_self_signed_ca`），由 `preBuild` 自动触发。
- 改 `uikit` 后若用 AAR 集成方式（见 `chat/build.gradle` 注释块），需重新出包；本计划按 **module 依赖**方式（默认）执行。

### 1.3 SDK / 语言

| 项 | 值 | 来源 |
|---|---|---|
| 语言 | **Java**（无 Kotlin 源；`kotlin_version` 在 `build.gradle` 中已注释） | `build.gradle`、各模块 `src/main/java` |
| AGP | 8.7.2 | 根 `build.gradle` |
| Gradle | wrapper（`gradle/wrapper/gradle-wrapper.properties`） | 仓库 |
| `compileSdkVersion` | 34 | `chat/build.gradle`、`uikit/build.gradle` |
| `minSdkVersion` | chat = **24**；uikit = **21** | 同上 |
| `targetSdkVersion` | 34 | 同上 |
| Java 兼容 | 17（desugaring `desugar_jdk_libs:2.1.5`） | 同上 |
| `buildFeatures.buildConfig` | true | 同上 |
| 现有网络 | OkHttp（client 4.12.0 / uikit okhttp-sse 5.1.0 / chat 3.11.0），无 Retrofit | `*/build.gradle` |

---

## 2. 导航与菜单

### 2.1 主界面 / Tab 结构

- `chat/src/main/java/cn/wildfire/chat/app/main/MainActivity.java`：`extends WfcBaseActivity implements WfcPageNavigator`。
  - 手机：`R.layout.main_activity`（`ViewPager2 @id/contentViewPager` + `BottomNavigationView @id/bottomNavigationView`，菜单 `chat/src/main/res/menu/main_bottom_navigation.xml`）。
  - 平板（双栏）：`WfcDeviceUtils.isTwoPaneLayout()` → `R.layout.main_pad_activity`。
- Tab Fragment 列表在 `MainActivity.initView()`（≈L638-772）构造：会话 `ConversationListFragment`(L669)、通讯录 `ContactListFragment`(L682)、工作台 `WebViewFragment`(L710，仅有 `Config.getWorkspaceUrl()` 时)、发现 `DiscoveryFragment`(L685)、我 `MeFragment`(L688)。
- 适配器：`chat/src/main/java/cn/wildfire/chat/app/main/HomeFragmentPagerAdapter.java`。
- 无独立 `MainFragment/HomeFragment` 类，Tab 即上述 5 个 Fragment。
- 平板右栏页面登记表：`uikit/src/main/java/cn/wildfire/chat/kit/pane/PaneRegistry.java`（新增 Activity 若要支持双栏，需在此登记，方法 `register(...)`，参考 `FavoriteListActivity` ≈L493、poll ≈L515、collection ≈L527）。

### 2.2 「我」页

- Fragment：`chat/src/main/java/cn/wildfire/chat/app/main/MeFragment.java`。
- 布局：`chat/src/main/res/layout/main_fragment_me.xml`（静态 `OptionItemView`，不是代码生成列表）。
- 现有项（id → 标题 → 点击方法）：`accountOptionItemView`(`@string/account_security`)、`notificationOptionItemView`、`conversationOptionItemView`(gone)、`fileRecordOptionItemView`(`@string/file`)、`favOptionItemView`(`@string/favorites`)、`settingOptionItemView`。
- 点击绑定：`MeFragment.bindEvents()`（≈L108-116）；跳转全部走 `WfcPageCompat.startPage(this, intent)`（≈L164-199）。
- 现有 gating 范例：`MeFragment.init()`（≈L151-155）用 `ChatManager.Instance().isCommercialServer()` 控制 `fileRecordOptionItemView` 的 `View.VISIBLE/GONE`。

### 2.3 增加受控的「网盘 / 文档」入口

1. **布局**：在 `chat/src/main/res/layout/main_fragment_me.xml` 的「文件/收藏」那一组里新增两项（标签用 spec §9 的「云盘」「文档」）：
   ```xml
   <cn.wildfire.chat.kit.widget.OptionItemView
       android:id="@+id/cloudDriveOptionItemView"
       style="@style/OptionItem"
       android:background="@drawable/selector_option_item"
       app:show_arrow_indicator="true"
       app:show_divider="true"
       app:start_src="@mipmap/ic_settings_file"
       app:title="@string/cloud_drive" />

   <cn.wildfire.chat.kit.widget.OptionItemView
       android:id="@+id/onlineDocsOptionItemView"
       style="@style/OptionItem"
       android:background="@drawable/selector_option_item"
       app:show_arrow_indicator="true"
       app:start_src="@mipmap/ic_star"      <!-- 或新增文档图标 -->
       app:title="@string/online_docs" />
   ```
   （图标可直接复用现有 `mipmap`，不必新增资源。）
2. **字符串**：在 `uikit/src/main/res/values/strings.xml`（及其它语言 values）新增 `cloud_drive`(云盘)、`online_docs`(文档)、`pan_save_to_my_pan`(存到网盘)、`pan_save_to_my_pan_and_open`(存到网盘并打开)、`doc_online_preview`(在线预览) 等；完整词表见 spec §9（`client-pan-docs-spec.md:742-759`）。
3. **逻辑**：`MeFragment.bindEvents()` 增加点击 → `PanHomeActivity` / `PanDocsActivity`（均用 `WfcPageCompat.startPage`）。
4. **Gating**（spec §2，`me_tab.dart:85-111`）：
   - 「云盘」：`Config.isPanConfigured()`（即 `PAN_SERVER_ADDRESS != null`）。
   - 「文档」：`Config.isPanConfigured()` **且** 平台支持内置 WebView（Android 恒为真；Flutter 对应 `isInlineWebViewSupported`）。
   ```java
   boolean panEnabled = PanServiceProvider.getInstance().isAvailable();   // = Config.isPanConfigured()
   view.findViewById(R.id.cloudDriveOptionItemView).setVisibility(panEnabled ? View.VISIBLE : View.GONE);
   // 在线文档是网盘自带的 H5 页面，网盘可用且支持内置 WebView 才有
   view.findViewById(R.id.onlineDocsOptionItemView).setVisibility(panEnabled ? View.VISIBLE : View.GONE);
   ```

### 2.4 Activity 注册方式

- 清单文件：App 级 `chat/src/main/AndroidManifest.xml`；UI 组件级 `uikit/src/main/AndroidManifest.xml`（与 app 合并）。
- 命名方式：两处都有；`chat` 清单用全限定名，`uikit` 清单常见「以点开头的相对名」，例如
  `.favorite.FavoriteListActivity`（L328-330）。接龙/投票用全限定名（如 `cn.wildfire.chat.kit.poll.activity.PollHomeActivity`，uikit 清单 ≈L355-374）。
- 新增网盘 Activity 建议写在 **uikit 清单**（与接龙/投票并列）：
  ```xml
  <!-- 网盘 -->
  <activity android:name="cn.wildfire.chat.kit.pan.PanHomeActivity" android:label="@string/cloud_drive" />
  <activity android:name="cn.wildfire.chat.kit.pan.PanFileListActivity" android:label="@string/cloud_drive" />
  <activity android:name="cn.wildfire.chat.kit.pan.PanDocsActivity" android:label="@string/online_docs" />
  <activity android:name="cn.wildfire.chat.kit.pan.PanDestinationPickerActivity" android:label="@string/pan_move_to" />
  ```
  （双栏支持：在 `PaneRegistry` 登记对应的 `PanePageFragment`，与 poll/collection 相同做法。）
- 在线文档 H5 不需要新 Activity：复用 `uikit/src/main/java/cn/wildfire/chat/kit/WfcWebViewActivity.java` / `WfcWebViewFragment.java`（见 §4）。

---

## 3. 网络与鉴权

### 3.1 现有 HTTP 栈

- 统一封装：`uikit/src/main/java/cn/wildfire/chat/kit/net/OKHttpHelper.java`
  - `get(url, params, Callback<T>)` L137、`post(url, param, Callback<T>)` L169、`put` L214、`upload` L237、`sse` L193。
  - 拦截器（L95-130）：自动从 `SharedPreferences("WFC_OK_HTTP_COOKIES")` 读 `authToken:<host>-<port>` 注入请求头 `authToken`，并把**响应头 `authToken`** 存回；主备地址 token 互通（`addDualNetworkAddress` L73）。
  - `clearCookies()` L270。
  - 回调/解析：`net/Callback.java`、`net/SimpleCallback.java`、`net/base/ResultWrapper.java`、`net/base/StatusResult.java`。
  - 初始化：`uikit/src/main/java/cn/wildfire/chat/kit/WfcUIKit.java:144` `OKHttpHelper.init(...)`。
- `AppService`（`:chat`）用 OKHttpHelper 的 authToken 会话，自身不传 authCode：`chat/src/main/java/cn/wildfire/chat/app/AppService.java`（`APP_SERVER_ADDRESS` L79、`appServerAddress()` L113、私有 `post/get` L227/L231）。

### 3.2 现有 authCode / authToken 做法（对照）

| 功能 | 位置 | 做法 |
|---|---|---|
| 接龙 collection | `chat/.../app/collection/CollectionServiceImpl.java` | 每次请求 `ChatManager.getAuthCode("collection",2,host)` → 头 `authCode`（L335-357），直连独立服务 |
| 投票 poll | `chat/.../app/poll/PollServiceImpl.java` | 同上，`"poll"`，L432-454 |
| 归档 archive | `chat/.../app/archive/ArchiveServiceImpl.java` | 同上，`"admin"`，L334-358 |
| 组织通讯录 org | `chat/.../app/OrganizationService.java` | `getAuthCode("admin",2,IM_SERVER_HOST)` → `POST {org}/api/user_login` body `{authCode}`；响应头下发 `authToken` 由 OKHttpHelper 缓存并自动带上（L55-90） |
| 合并应用服务 app-server | `OKHttpHelper` + `AppService` | 登录（`/api/app/login_pwd`）时下发 `authToken`，后续自动带 |
| H5 桥 | `uikit/.../kit/workspace/JsApi.java:98` | `getAuthCode` → `ChatManager.getAuthCode(appId, appType, host)` |

`ChatManager.getAuthCode`：`client/src/main/java/cn/wildfirechat/remote/ChatManager.java:9303`。

### 3.3 网盘 / 应用服务地址与 enabled 开关（按 spec §1.1、§2）

**`Config` 里已有（本计划起草时被加入）：**
```java
public static String PAN_SERVER_ADDRESS = null;           // 语义应为 appRoot + "/api/pan"
public static String PAN_SERVER_BACKUP_ADDRESS = null;
public static String getPanServerAddress() { return selectServer(PAN_SERVER_ADDRESS, PAN_SERVER_BACKUP_ADDRESS); }
public static boolean isPanConfigured()    { ... }
```
**必须把语义修正为 spec 的定义**（当前注释写的是独立 wf-pan-server 的 `/api/v1` + authCode，与合并服务不符）：
- 令 `appRoot` = 应用服务根（`AppService.APP_SERVER_ADDRESS`，即合并 `wf-app-server`），
  `PAN_SERVER_ADDRESS = appRoot + "/api/pan"`，`PAN_SERVER_BACKUP_ADDRESS = appRoot备份 + "/api/pan"`。
- 在线文档基址 `docBase = appRoot + "/doc/"`。Android 侧可从 `getPanServerAddress()` **反推** appRoot：
  剥掉结尾的 `/api/pan`；更稳妥的做法是在 `uikit` `Config` 新增 `getPanAppRoot()`（或 `APP_SERVICE_ADDRESS` 字段），
  避免字符串拼接歧义。`docBase()` / `apiBase()` 统一由它派生：
  ```java
  static String panAppRoot() {                 // R
      String base = getPanServerAddress();
      return base != null && base.endsWith("/api/pan")
             ? base.substring(0, base.length() - "/api/pan".length()) : base;
  }
  static String panApiBase() { return getPanServerAddress(); }          // R + /api/pan
  static String panDocBase() { return panAppRoot() + "/doc/"; }         // R + /doc/
  ```
- 「enabled」判定唯一入口：`Config.isPanConfigured()`；UI 入口经 `PanServiceProvider.getInstance().isAvailable()`（service 是否已注册）。
  `MyApp.onCreate()` 在 `isPanConfigured()` 为真时注册 `PanServiceImpl`，与现有 collection/poll/archive 并列
  （`chat/src/main/java/cn/wildfire/chat/app/MyApp.java` ≈L80-93），并对 `PAN_SERVER_ADDRESS`/备选调用
  `OKHttpHelper.addDualNetworkAddress(...)`（参考 L122）。

**合并后的服务端（`wf-app-server/app-pan`）实际端点**（spec §1.4，全部 `POST` + JSON + `authToken`，相对 `R/api/pan`）：

- spaces：`/spaces/list`、`/spaces/my`、`/spaces/user/public`、`/spaces/files`
- files：`/files/folder`、`/files`(POST)、`/files/delete`、`/files/rename`、`/files/move`、`/files/copy`、`/files/url`、`/files/check-permission`
- shares：`/shares/list`、`/shares/add`、`/shares/remove`、`/shares/with-me`
- docs（原生用）：`/docs/recent`、`/docs/recent/remove`、`/docs/create`、`/docs/options`
- docs/versions（**仅 H5 `open.html` 用**，原生不必实现）：`/docs/editor-config`、`/docs/view-url`、`/docs/convert`、`/versions/list`、`/versions/restore`
- 在线文档 H5：`GET /doc`、`/doc/`、`/doc/open`（`?fileId=` 或 `?url=&name=`）、`/doc/licenses.html`；`POST /doc/session`（body `{"authCode"}` → `result.authToken`，并下发响应头 `authToken`）。
- 统一登录：`POST R/api/auth/login`，body `{"authCode":"..."}`，响应头 `authToken`（`wf-app-server/app-common/.../LoginController.java`）。
- `storageUrl` 是服务端签名的短时效链接（默认 600s），**不可持久化/转发**，用时现取（spec §1.6）。

> 兼容独立版 `wf-pan-server`（可选）：客户端端口 8081、接口 `/api/v1/**`、H5 页面 `/pan/doc/`、鉴权头 `authCode`。
> 若确需支持，在 `PanServiceImpl` 里按 `PAN_SERVER_ADDRESS` 是否以 `/api/pan` 结尾分流（路径、鉴权头、docBase 都不同）；
> **默认按 spec 的合并服务实现**。

### 3.4 网盘 REST 鉴权（authToken，复用 OKHttpHelper）

采用与 Flutter `AuthCodeApiClient` 等价、且与 `OrganizationService` 一致的模型（spec §1.2、§8.2）：

1. `PanServiceImpl` 所有请求走 `OKHttpHelper.post(panApiBase()+path, params, callback)`；拦截器会自动带上已缓存的 `authToken`。
2. 首次/失效时先登录：`POST {panAppRoot()}/api/auth/login`，body `{"authCode": code}`，其中
   `code = ChatManager.getAuthCode("admin", 2, host)`，`host` = appRoot 的 host[:port]
   （与组织通讯录同一 appId/类型；见 Flutter `_authCodeId="admin"`, `_authCodeType=2`，spec §1.2）。
   登录成功响应头带 `authToken`，OKHttpHelper 自动落盘（按 host-port）。建议抽 `PanAuth`（或 `PanServiceImpl.login(...)`）做一次性登录 + 并发去重。
3. 失效重试：当响应 `code == 13/1001/1002/1004` 或 HTTP 401/403 时，清掉该 host 的 token → 重新 `login` → **重试一次**；与 Flutter `AuthCodeApiClient._needRelogin` 一致。
4. 统一响应约定 `{code:0,message,result}`，由 `OKHttpHelper` + `ResultWrapper` 解析；`code != 0` 作为业务错误回调。

---

## 4. WebView + JS 桥（在线文档 ONLYOFFICE）

### 4.1 现有 WebView 设施

- 用的是 **vendored DSBridge**：`uikit/src/main/java/wendu/dsbridge/DWebView.java`（`extends WebView`，内部注册 `_dsbridge`，`addJavascriptObject` L554）；`DWebView` 基类设置：JS/DOM Storage 开、`setAllowFileAccess(false)`、`MIXED_CONTENT_ALWAYS_ALLOW`、`LOAD_NO_CACHE`（L250-271）。
- 页面宿主：
  - `uikit/src/main/java/cn/wildfire/chat/kit/WfcWebViewActivity.java`（空壳）+ `WfcWebViewFragment.java`（真正实现，实现 `WfcPage`，有标题/菜单/返回/进度）。
  - `WfcWebViewFragment` 关键点：UA 追加 `WF-DSBridge`（L83-84）、`jsApi = new JsApi(this, webView, url)` + `webView.addJavascriptObject(jsApi, null)`（L86-87）、`setDownloadListener`（L88-93，`ACTION_VIEW` 交给系统）、`onPageFinished` 回写标题（L96-104）、`shouldOverrideUrlLoading` 同步 `currentUrl`（L108-113）、`onPageBackPressed` 支持网页后退（L185-192）。
  - 工作台用 `uikit/.../kit/workspace/WebViewFragment.java`（无标题/无可下载监听，不建议给文档页用）。
- 桥对象：`uikit/src/main/java/cn/wildfire/chat/kit/workspace/JsApi.java`。**现有** `@JavascriptInterface`：`openUrl`、`close`、`getAuthCode`、`config`、`toast`、`chooseContacts`。
  - 异步回调：`wendu.dsbridge.CompletionHandler`（`complete` / `setProgressData`）。
  - `onActivityResult` 负责把选人结果回给 H5（L187-213），`WfcWebViewFragment.onActivityResult` 转发给 `jsApi`（L134-139）。

### 4.2 H5 页面实际调用的桥方法（权威来源）

`wf-pan-server/src/main/resources/doc-web/app.js`（与 `open.html`）里 `D` 通过 dsbridge 调用：

| 方法 | 调用方式 | 参数 | 期望返回 |
|---|---|---|---|
| `getAuthCode` | `Bridge.call(...,20000)` 异步 | `{appId:'admin', appType:2}` | `{code:0, data:authCode}`（客户端已有） |
| `setPageHeader` | `Bridge.listen` 多次回调 | `{title, subtitle, actions:[{id,text,icon,primary}]}`；`icon` 取 `share/history/download/convert` | 点击按钮时 `setProgressData(id)`；doc URL 上的 `share` 由客户端自己处理，不回传（spec §5.2） |
| `downloadFile` | `callSync` 同步 | `{url}` | 无 |
| `chooseContacts` | `Bridge.call` 异步 | `{}` | `{code:0, data: JSON串 [{uid,name,displayName,portrait}]}`（客户端已有） |
| `chooseGroup` | `Bridge.call` 异步 | `{}` | `{code:0, data: JSON串 [{gid,name,portrait}]}` |
| `openUrl` / `toast` / `close` | 同步 | — | 客户端已有 |
| `_dsb.hasNativeMethod` | 内部 | `{name,type}` | 能力探测，`DWebView.hasNativeMethod` 已有 |

### 4.3 需要补的桥方法（改 `JsApi.java`）

1. `@JavascriptInterface public void downloadFile(Object arg)`（**同步**，因为 H5 用 `callSync`）：
   解析 `String`/`Map{url}`，`Intent.ACTION_VIEW` + `Uri.parse`，无匹配应用时吐司提示。可直接复用 `WfcWebViewFragment` 现有下载语义，或新增 `FileUtils.openUrlBySystem(context, url)`。
2. `@JavascriptInterface public void chooseGroup(Object obj, CompletionHandler handler)`（异步）：
   - 复用 `uikit/src/main/java/cn/wildfire/chat/kit/group/GroupListActivity.java` 的**多选**能力（`INTENT_FOR_RESULT`、`MODE_MULTI`、`MODE_SINGLE`，L21-28），requestCode 例如 301；
   - 结果映射为 JSON `[{gid:groupInfo.target, name:groupInfo.name, portrait:groupInfo.portrait}]`，走 `callbackJs(handler, 0, json)`；取消/异常回 `-1`（与 Flutter `chooseGroup` 约定一致）；
   - 在 `onActivityResult` 里新增分支处理 301。
3. `@JavascriptInterface public void setPageHeader(Object obj, CompletionHandler handler)`（异步、多次回调）：
   - 解析 header 存入 `JsApi`，调用宿主回调把标题写进标题栏（`WfcPageCompat.setPageTitle`），并记录 actions；
   - 用户点按钮时 `handler.setProgressData(id)`；id == `"share"` 时不回传而是交给客户端打开网盘分享页（`PanShareActivity`，从 URL 的 `fileId` 取），对应 Flutter `JsApi._shareDoc`；
   - `JsApi` 需新增一个宿主回调接口（例如 `OnPageHeaderListener`），由 `WfcWebViewFragment` 实现，用于渲染副标题/操作按钮；无该能力时 H5 会自己画页面头（`pageHeader` 返回 false）。
4. **修正 `preCheck()`**：当前 `JsApi.preCheck()`（L249-254）要求 `ready == true`（仅 `config()` 会置位）且 host 相同；而在线文档页只调 `getAuthCode`，**不调 `config()`**，会导致 `chooseContacts/chooseGroup` 直接被挡（`-2`）。
   参考 Flutter `_preCheck()`（仅比较 `appUrl == currentUrl`），建议改为「只做 host/URL 一致性校验」，或在 `getAuthCode` 成功后也置 `ready = true`。

### 4.4 打开文档页

- 网盘内点文件：`PanService.docOpenUrl(fileId) = docBase() + "open?fileId=" + fileId` → `WfcWebViewActivity.loadUrl(fragment, name, url)`。
- 聊天文件消息「只读打开」：`PanService.docViewUrl(storageUrl, name) = docBase() + "open?url=<enc>&name=<enc>"`（服务端代理、不回写）。
- 开源许可：`docBase() + "licenses.html"`。
- 文档链接卡片：`PanService.isDocUrl(url)`（`docBase()` 前缀判断）为真时强制走**内置** `WfcWebViewActivity`（`LinkMessageContentViewHolder` 已经是走内置，确认即可）。
- 在线文档格式表：移植 Flutter `PanService.isOnlineDocName`（spec §6.1，大小写不敏感扩展名）到 Java：
  - word：`doc, docx, docm, dot, dotx, dotm, odt, ott, rtf, txt, wps, wpt, fodt, mht, mhtml, htm, html, epub, fb2`
  - cell：`xls, xlsx, xlsm, xlt, xltx, xltm, xlsb, ods, ots, csv, et, ett, fods`
  - slide：`ppt, pptx, pptm, pot, potx, potm, pps, ppsx, ppsm, odp, otp, dps, dpt, fodp`
  - pdf：`pdf, djvu, xps, oxps`
  - 仅「文件（非文件夹）」且有上述扩展名才算在线文档；服务端区分 EDITABLE（OOXML，docx/docm/xlsx/xlsm/pptx/pptm，可原地编辑）与 CONVERTIBLE（旧格式，需转换后编辑）。
- 旧 `/pan/doc/` 链接：服务端已从 `<root>/pan/doc/` 迁到 `<root>/doc/`，Flutter 的兼容重写先加后revert（spec §6.3）。Android 侧**需显式决定**：为兼容旧卡片，可在 `isDocUrl`/打开前实现 `upgradeLegacyDocUrl`（主/备 root 都处理，把 `<root>/pan/doc/` 重写为 `<root>/doc/`）；不做则旧卡片打不开。

---

## 5. 文件消息渲染 / 长按菜单

### 5.1 现有渲染链路

- 适配器：`uikit/src/main/java/cn/wildfire/chat/kit/conversation/ConversationMessageAdapter.java`
  - `getItemViewType`（L715-727）编码 `direction << 24 | contentType`；`onCreateViewHolder`（L426-487）经 `MessageViewHolderManager` 反射建 holder。
- 注册表：`uikit/.../conversation/message/viewholder/MessageViewHolderManager.java`（`init()` L27-48、`registerMessageViewHolder` L54-75）。
- 文件消息 holder：`uikit/.../conversation/message/viewholder/FileMessageContentViewHolder.java`（`@MessageContentType(FileMessageContent.class)` + `@EnableContextMenu`）。
  - 点击 `onClick`（L66-76）→ `FileUtils.openFile(context, message.message)`（先系统 app，失败回退 `ONLINE_FILE_PREVIEW_URL`）。
  - 现有菜单：`@MessageContextMenuItem(tag = TAG_SAVE_FILE, priority = 14)` `saveFile`（L78-101）。
- 长按菜单机制：`ConversationMessageAdapter.processContentLongClick`（L644-662）+ `popupMenuForMessageViewHolder`（L576-641），扫描 holder 类层级上的 `@MessageContextMenuItem`，用 `contextMenuTitle/Icon/Filter` 出标题/图标/过滤，点击反射调用 `method.invoke(viewHolder, itemView, message)`。
- 标签常量：`uikit/.../message/viewholder/MessageContextMenuItemTags.java`（现无网盘相关 tag）。

### 5.2 新增的长按动作

1. 在 `MessageContextMenuItemTags.java` 增加 tag：
   `TAG_OPEN_ONLINE_DOC = "openOnlineDoc"`、`TAG_SAVE_TO_PAN = "saveToPan"`、`TAG_SAVE_TO_PAN_AND_OPEN = "saveToPanAndOpen"`、`TAG_DOWNLOAD = "downloadFile"`。
2. 在 `FileMessageContentViewHolder.java` 增加 `@MessageContextMenuItem` 方法（签名 `(View itemView, UiMessage message)`）。菜单集合按 spec §6.2：
   - `remoteUrl` 非空时才有这些项（`FileMessageContent` 的 `remoteUrl` 来自 `MediaMessageContent`）：
     - **在线预览**（`docOnlinePreview`）：仅当 `PanServiceProvider.isAvailable() && PanService.isOnlineDocName(name)`。
       动作：读只读链接 `PanService.docViewUrl(remoteUrl, name)` → `WfcWebViewActivity.loadUrl(fragment, name, url)`（拿到 `remoteUrl` 前若未下载过，直接从 `remoteUrl` 生成即可，服务端代理取内容）。
     - **存到网盘**（`panSaveToMyPan`）与 **存到网盘并打开**（`panSaveToMyPanAndOpen`）：仅当 `PanServiceProvider.isAvailable()`。
       动作：`PanSaveHelper.saveFileMessageToMyPan(..., openAfterSave = false/true)`（见 §5.3），`copy=true`。
     - **下载**（`download`）：始终提供；沿用现有 `saveFile`/`FileUtils` 或显式下载到 `Config.FILE_SAVE_DIR`。
   - 网盘未配置或 app-service 地址缺失时，「在线预览」回退为系统打开（spec §6.2 `_openFileMessageOnline`）。
3. 覆写 `contextMenuTitle(...)`/`contextMenuIcon(...)`/`contextMenuItemFilter(...)` 提供文案、图标与可见性；适配器无需改。中文文案：在线预览 / 存到网盘 / 存到网盘并打开 / 下载（spec §9）。
4. 点击文件消息的行为（可选增强）：在 `onClick` 中，若网盘可用且 `PanService.isOnlineDocName(name)` 则优先打开只读在线文档，否则保持现状。
5. 网盘分享进聊天发的是**链接卡片**而非文件消息（签名下载地址 600s 有效，spec §1.6/§7.5）；卡片走 `LinkMessageContent`，点击时按 §4.4 的 `isDocUrl` 规则进内置 WebView。

### 5.3 保存到网盘（移植 `pan_save.dart`）

- 新增 `uikit/.../kit/pan/PanSaveHelper.java`：
  - `getMySpaces()` → 取 `USER_PRIVATE`（无则第一个）→ `getSpaceFiles(spaceId)` 做重名处理（`name(1).ext`）→ `createFile(spaceId, name, size, storageUrl=remoteUrl, mimeType, md5?, copy=true)`。
  - 与 Flutter `PanService.saveFileToMyPan` 完全一致；`copy=true` 让服务端把 IM 媒体桶对象拷进网盘桶。
  - 成功后吐司「保存成功」；`openAfterSave=true` 时按格式走在线文档或签名下载地址。

---

## 6. 上传 / 下载基础能力

### 6.1 上传（无 OSS SDK 类，走 IM 预签名 URL）

- 小文件/统一入口：`ChatManager.Instance().uploadMediaFile(String path, int mediaType, UploadMediaCallback cb)`（`client/.../remote/ChatManager.java:6677`），
  `mediaType` 用 `MessageContentMediaType.FILE(4)`（`client/.../message/MessageContentMediaType.java`；另有预留 `PAN(12)` 暂未使用）。
  成功回调 `onSuccess(String remoteUrl)`，另有 `onProgress(long,long)`。
- 大文件（>100MB 或服务端强制）：`ChatManager.isSupportBigFilesUpload()`（L10394）+ `getUploadUrl(fileName, mediaType, contentType, GetUploadUrlCallback)`（L10414），
  回调 `onSuccess(uploadUrl, remoteUrl, backupUploadUrl, serverType)`；`serverType==1` 走七牛表单，否则 HTTP PUT。
  - 备选网络下优先 `backupUploadUrl`（对照 Flutter `_selectUploadUrl` / `DualNetwork`）。
  - 参考实现：`client/.../client/ClientService.java` `uploadBigFile` L5442、`uploadFile`(PUT) L5466、`uploadQiniu` L5521。
- 网盘上传流程（移植 Flutter `PanService.uploadFile`）：先按重名算出 `remoteName` → IM 上传拿 `remoteUrl` → `POST /api/pan/files`（`copy=false`）建记录 → 回 `PanFile`。
  进度：`UploadMediaCallback.onProgress` 映射为 0..1 回调；取消令牌可移植 `PanUploadCancelToken`。

### 6.2 下载

- `uikit/src/main/java/cn/wildfire/chat/kit/utils/DownloadManager.java`
  - `download(String url, String saveDir, OnDownloadListener)` L44 / 带 `name` 重载 L48（OkHttp GET，含密聊解密）。
  - `mediaMessageContentFile(Message)` L178、`fileRecordFile(FileRecord)` L172、`getNameFromUrl` L241、`SimpleOnDownloadListener` L279。
- 网盘下载：`POST /api/pan/files/url` 取签名 `storageUrl` → 用 `DownloadManager.download(...)` 存到 `Config.FILE_SAVE_DIR` → `FileUtils.openFile`/`getViewIntent` 打开。
- 在线文档页的「下载」由 H5 调桥 `downloadFile`（§4.3），不经过 `DownloadManager`。

### 6.3 打开/预览

- `uikit/.../kit/utils/FileUtils.java`：`getViewIntent` L577-635（FileProvider）、`openFile(Context, Message)` L972-1006、`onlinePreview` L1008-1020（用 `Config.ONLINE_FILE_PREVIEW_URL`）。
- 网盘文件的「非在线文档格式」预览：取签名下载地址后复用 `FileUtils.getViewIntent` / `openFile`（对照 Flutter `pan_save.dart` 尾部逻辑）。

---

## 7. 逐文件实施清单

### 7.1 新增文件

**uikit — 服务接口 / Provider / 模型（`uikit/src/main/java/cn/wildfire/chat/kit/pan/`）**
| 文件 | 内容 |
|---|---|
| `PanService.java` | 接口，方法对齐 Flutter `pan_service.dart`：`getSpaces/getVisibleSpaces/getMySpaces/getUserPublicSpace/getSpaceFiles/createFolder/createFile/deleteFile/renameFile/moveFile/copyFile/getFileDownloadUrl/getShares/setShare/removeShare/grantForConversation/getRecentDocs/getSharedWithMe/removeRecentDoc/createDoc/isMobileDocEditEnabled/checkSpaceWritePermission/createFileFromMessage/uploadFile` + 回调接口；静态工具 `docOpenUrl/docViewUrl/docLicensesUrl/isDocUrl/isOnlineDocName/formatPanSize` |
| `PanServiceProvider.java` | 单例 holder（`setService/isAvailable/getService`），照抄 `uikit/.../kit/collection/CollectionServiceProvider.java` |
| `model/PanSpace.java` | 字段/`fromJson` 对齐 Flutter `PanSpace`（`spaceType` 支持字符串/数字，`canWrite`、`canManage`） |
| `model/PanFile.java` | 对齐 Flutter `PanFile`（`type` 支持 `FILE/FOLDER` 与 0/1，`sizeText`、`iconType`、扩展名） |
| `model/PanDocEntry.java` | 最近打开 / 共享给我的一行（`file`、`canEdit`、`time`、`sources`） |
| `model/PanShare.java` / `model/PanShareSource.java` | 分享对象 |
| `model/PanException.java`、`model/PanUploadCancelToken.java` | 异常与取消令牌（可并入 service） |
| `PanHomeActivity.java` (+ `PanHomePageFragment.java`) + 布局 | 空间列表（全局公共 / 我的公共 / 我的私有；参考 `pan_home_screen.dart`） |
| `PanFileListActivity.java` (+ `PanFileListPageFragment.java`/`PanFolderViewModel.java`) + 布局 | 目录浏览、上传、新建文件夹、文件长按菜单、分页/刷新（参考 `pan_file_list_screen.dart`、`pan_folder_state.dart`） |
| `PanDestinationPickerActivity.java` + 布局 | 移动/复制选择目标空间与目录，禁止移动到自身/子目录（参考 `pan_destination_picker.dart`） |
| `PanDocsActivity.java` (+ `PanDocsPageFragment.java`) + 布局 | 在线文档首页：最近打开 / 共享给我，两个 tab，新建 docx/xlsx/pptx（参考 `pan_docs_screen.dart`） |
| `PanShareActivity.java`/Dialog 或 `PanShareView` | 分享管理：查看/新增/删除分享、发到会话前 `grantForConversation`（参考 `pan_share.dart`） |
| `PanSaveHelper.java` | 文件消息保存到「我的网盘」+ 保存后打开（参考 `pan_save.dart`） |
| `PanAdapter`/`PanViewHolder` 等 | 空间/文件/文档列表 Adapter（明暗模式与图标可参考 `pan_widgets.dart`） |

**chat — 服务实现（`chat/src/main/java/cn/wildfire/chat/app/pan/`）**
| 文件 | 内容 |
|---|---|
| `PanServiceImpl.java` | 实现 `PanService`：`panApiBase()/panDocBase()` 由 `Config.getPanServerAddress()` 派生；`login()` 用 `ChatManager.getAuthCode("admin",2,host)` → `POST {appRoot}/api/auth/login {authCode}`；所有请求走 `OKHttpHelper`；失效 `code==13/1001/1002/1004/401/403` 重登重试一次；上传走 `ChatManager.uploadMediaFile/getUploadUrl` |

### 7.2 修改文件

| 文件 | 改动 |
|---|---|
| `uikit/src/main/java/cn/wildfire/chat/kit/Config.java` | **字段已存在**（`PAN_SERVER_ADDRESS` / `PAN_SERVER_BACKUP_ADDRESS` / `getPanServerAddress()` / `isPanConfigured()`）；本计划把其**语义对齐 spec**：`PAN_SERVER_ADDRESS = appRoot + "/api/pan"`，并新增 `panAppRoot()`/`panDocBase()` 派生；修正当前「`/api/v1` + authCode」的注释 |
| `chat/src/main/java/cn/wildfire/chat/app/MyApp.java` | `Config.isPanConfigured()` 为真时 `PanServiceProvider.getInstance().setService(PanServiceImpl.getInstance())`；`OKHttpHelper.addDualNetworkAddress(Config.PAN_SERVER_ADDRESS, Config.PAN_SERVER_BACKUP_ADDRESS)`（≈L80-93、L122） |
| `chat/src/main/java/cn/wildfire/chat/app/main/MeFragment.java` | `bindViews/bindEvents` 绑定两个新 item；`init()` 按 `PanServiceProvider.isAvailable()` 控制可见性；新增 `cloudDrive()`/`onlineDocs()` 跳转 |
| `chat/src/main/res/layout/main_fragment_me.xml` | 新增 `cloudDriveOptionItemView`(云盘) / `onlineDocsOptionItemView`(文档) |
| `uikit/src/main/res/values/strings.xml` + 各语言 values | 新增 `cloud_drive`、`online_docs`、`pan_save_to_my_pan`、`pan_save_to_my_pan_and_open`、`doc_online_preview`、`pan_move_to`、`pan_download` 等（词表见 spec §9） |
| `uikit/src/main/AndroidManifest.xml` | 注册 4 个网盘 Activity（与接龙/投票并列，≈L355-374） |
| `uikit/src/main/java/cn/wildfire/chat/kit/pane/PaneRegistry.java` | （双栏）登记网盘 Activity → `PanePageFragment`，参考 FavoriteListActivity L493 / poll L515 |
| `uikit/src/main/java/cn/wildfire/chat/kit/workspace/JsApi.java` | 新增 `downloadFile`(同步)、`chooseGroup`(异步)、`setPageHeader`(异步多次回调)；`onActivityResult` 增加选群分支；修正 `preCheck()` 不再要求 `config()` 成功；新增宿主回调接口给 `setPageHeader`/分享 |
| `uikit/src/main/java/cn/wildfire/chat/kit/WfcWebViewFragment.java` | 实现 `setPageHeader` 宿主回调（标题/副标题/操作按钮，点击 `share` 打开 `PanShareActivity`）；如需在文档页隐藏转发等菜单可在此细化 |
| `uikit/src/main/java/cn/wildfire/chat/kit/conversation/message/viewholder/MessageContextMenuItemTags.java` | 新增 `TAG_OPEN_ONLINE_DOC` / `TAG_SAVE_TO_PAN` / `TAG_SAVE_TO_PAN_AND_OPEN` / `TAG_DOWNLOAD` |
| `uikit/.../conversation/message/viewholder/FileMessageContentViewHolder.java` | 新增「在线预览」「存到网盘」「存到网盘并打开」「下载」菜单方法与 `contextMenuTitle/Icon/Filter`；`onClick` 优先在线文档 |
| `uikit/src/main/java/cn/wildfire/chat/kit/utils/FileUtils.java` | （可选）新增 `openUrlBySystem(context, url)` 供桥 `downloadFile` 使用；网盘非在线格式预览复用 `getViewIntent` |
| `uikit/.../conversation/message/viewholder/LinkMessageContentViewHolder.java` | 确认在线文档链接卡片走内置 `WfcWebViewActivity`（现已是），必要时加 `PanService.isDocUrl` 分支 |

### 7.3 参考映射（Flutter → Android）

| Flutter | Android |
|---|---|
| `chat/lib/pan/pan_service.dart` | `uikit/.../kit/pan/PanService.java` + `chat/.../app/pan/PanServiceImpl.java` |
| `pan_home_screen.dart` | `PanHomeActivity/PanHomePageFragment` |
| `pan_file_list_screen.dart` / `pan_folder_state.dart` | `PanFileListActivity/PanFileListPageFragment/PanFolderViewModel` |
| `pan_destination_picker.dart` | `PanDestinationPickerActivity` |
| `pan_share.dart` | `PanShareActivity`（或 Dialog） |
| `pan_save.dart` | `PanSaveHelper.java` |
| `pan_docs_screen.dart` | `PanDocsActivity/PanDocsPageFragment` |
| `pan_widgets.dart` | `PanAdapter`/`PanViewHolder`/尺寸格式化 |
| `settings/me_tab.dart`（网盘/文档入口） | `MeFragment` + `main_fragment_me.xml` |
| `workspace/wf_webview_screen.dart` + `js_api.dart` | `WfcWebViewFragment` + `workspace/JsApi.java` |
| `utils/auth_code_api_client.dart` | `PanServiceImpl.login()` + `OKHttpHelper` 的 `authToken` 拦截器 |

---

## 8. 构建 / 验证清单

```bash
cd /Users/rain/Workspace/android-chat

# 1) 仅编译库，快速发现 uikit（Config / PanService / JsApi / ViewHolder）编译错误
./gradlew :uikit:assembleDebug

# 2) 资源与清单合并（改 Manifest / strings / layout 后）
./gradlew :chat:processDebugManifest :chat:processDebugResources

# 3) 完整 Debug APK
./gradlew :chat:assembleDebug
#    产物 chat/build/outputs/apk/debug/chat-debug.apk

# 4) Release APK（签名按 chat/build.gradle signingConfigs.wfc / release 配置）
./gradlew :chat:assembleRelease

# 5) 静态检查（可选）
./gradlew :chat:lintDebug
```

功能验证（装到真机/模拟器后）：
1. `Config.PAN_SERVER_ADDRESS` 为 `null` → 「我」页不出现「云盘/文档」；配成 `appRoot + "/api/pan"` → 出现（gating 生效）。
2. 进「云盘」：能列空间（全局公共/我的公共/我的个人），进目录、上传、新建文件夹、重命名/移动/复制/删除/转存、下载。
3. 聊天里的文件消息长按：非在线格式只有「存到网盘/存到网盘并打开/下载」；在线格式（docx/xlsx/pptx/pdf…）额外有「在线预览」，点开进入 ONLYOFFICE 页并能编辑/只读。
4. 「存到网盘」后文件出现在「我的个人空间」，且为物理拷贝（`copy=true`）。
5. 「文档」首页：最近打开 / 共享给我；新建 docx/xlsx/pptx 能打开编辑器；分享（选人/选群，`chooseContacts`/`chooseGroup`）可用；`setPageHeader` 的标题/按钮正确显示。
6. 双网：切换主备后 `authToken` 复用、地址自动切换（`Config.selectServer` + `OKHttpHelper.addDualNetworkAddress`）。
7. 平板双栏：网盘页/文档页在右栏打开，标题/返回/菜单正常（`WfcPage`/`PaneRegistry`）。

日志观察点：`JsApi` 的 `getAuthCode` / `setPageHeader` / `chooseContacts` / `chooseGroup` 打印；`OKHttpHelper` 的 `response=` 日志；`PanServiceImpl.login` 的 authCode→authToken 流程。

---

## 9. 风险与注意事项

1. **`JsApi.preCheck()` 的 `ready` 依赖**：在线文档页只调 `getAuthCode`、不调 `config()`，若不修 `preCheck`，`chooseContacts/chooseGroup` 会被静默拦截（§4.3-4）。
2. **H5 文件上传未实现**：`DWebView` 未实现 `onShowFileChooser`；若在线文档页需要「上传本地文件替换」，需另开 WebChromeClient 文件选择（本计划范围外，先记录）。
3. **DSBridge JS 未随 App 打包**：`dsbridge.js` 由 H5 页面自带；App 只认 UA `WF-DSBridge` 与 `window._dsbridge`。文档页需部署在 `docBase()` 下。
4. **`DWebView` 全局 `setAllowFileAccess(false)` 且 mixed content = ALWAYS_ALLOW**：HTTPS 部署下正常工作；纯 HTTP 内网也允许（沿用现状）。
5. **鉴权模型二选一**：本计划按**合并 app-server + authToken**实现，以对齐 Flutter；若目标环境是独立 `wf-pan-server`（8081，`/api/v1/**` + 头 `authCode`），需在 `PanServiceImpl` 增加分支（地址/路径/头）。
6. **选群多选**：`GroupListActivity` 已含 `MODE_MULTI`，但需确认结果回参（`GroupInfo` 列表）满足 `{gid,name,portrait}`；必要时新增轻量 `PickGroupActivity`（参考 `pan_destination_picker` 的树选择）。
7. **消息类型 `MessageContentMediaType.PAN(12)` 目前无引用**：本计划的网盘文件不走自定义消息类型，而是复用 IM 媒体上传 + `/api/pan/files` 记录；若后续要「网盘文件卡片消息」，再引入新 `MessageContent` + `@ContentTag(>1000)` + `MessageViewHolderManager.registerMessageViewHolder`（参考 `MyApp.java` L73-74）。
8. **不要改协议栈**：`Config.IM_SERVER_HOST` 的注释已说明直接改无效；网盘地址一律走 `Config.PAN_SERVER_ADDRESS`（= `appRoot + "/api/pan"`）。
9. **`Config.java` 已有改动**：本会话进行中 `PAN_SERVER_ADDRESS` 等字段已被加入仓库（未提交）。实现前先确认其注释/语义与 spec 一致（`appRoot + "/api/pan"`、authToken），不要按旧注释实现成 `/api/v1` + authCode。
