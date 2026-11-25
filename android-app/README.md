# MoonTV Android 壳应用

独立的 Kotlin + Android WebView 工程，启动后访问 **https://yhroppo.iepose.cn/**。网页功能继续由服务器提供，无需先构建仓库中的 Next.js 项目，也不包含其他 MoonTV 客户端源码。

## 环境与构建

- Android Studio，JDK 17，Android SDK Platform 35。
- Gradle Wrapper 8.10.2，Android Gradle Plugin 8.7.3，Kotlin 1.9.24。
- 支持 Android 8.0（API 26）及以上系统；手机需安装或启用可用的 Android System WebView / Chrome。

Android Studio 直接打开本目录 `android-app`，将 Gradle JDK 选择为 17，同步后选择 `app` 运行。首次同步需要联网下载 Gradle、插件和 Android SDK 组件。命令行构建前确认 `JAVA_HOME` 指向 JDK 17，然后在本目录使用 PowerShell：

```powershell
.\gradlew.bat :app:assembleDebug :app:lintDebug
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
```

调试 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`，使用 Android 调试签名，仅用于开发和验收。Lint 报告位于 `app/build/reports/lint-results-debug.html`。

默认服务器地址由 `app/build.gradle` 生成到 `BuildConfig.SERVER_URL`。如需打包其他服务器：

```powershell
.\gradlew.bat :app:assembleDebug -PmoonTvServerUrl=https://example.com/
```

地址必须是有效的 HTTPS URL，只允许默认端口或 443，不允许用户名、密码、查询参数或片段。修改地址后需重新安装构建产物。

## 发布签名

```powershell
.\gradlew.bat :app:assembleRelease
```

默认输出 `app/build/outputs/apk/release/app-release-unsigned.apk`，未配置发布签名。正式分发时，在 Android Studio 的 **Generate Signed Bundle / APK** 中创建自己的密钥并签名。密钥及密码由部署者自行保存，不能提交到仓库；后续覆盖升级必须沿用相同包名和签名，并递增 `versionCode`。

## 功能与边界

- 访问服务器上的登录、搜索、详情和网页播放器；应用内保留站点 Cookie 与 DOM Storage，登录有效期由服务端 Cookie 策略决定。
- 显示网页加载进度，连接失败或证书异常时提供重试及浏览器入口，不绕过证书错误。
- 视频全屏交由 WebView 全屏回调处理，同时检查 ArtPlayer 网页全屏状态以联动横屏；返回时优先退出全屏，再返回网页历史，无历史时退出应用。
- 外部网站交给系统浏览器；壳应用不提供原生下载、后台播放或离线视频缓存。
- 仅允许 HTTPS；HTTP 视频源、HTTP 跳转与混合内容会受限，需要资源服务器提供 HTTPS。视频格式兼容性仍取决于网页播放器、资源源站和手机 WebView。
- 手机浏览器与 APK 的 localStorage 分别保存。跨设备收藏和进度同步需要服务器配置 Redis、D1 或 Upstash 等持久化存储；安装 APK 不会自动迁移浏览器数据。
- 服务器网页更新通常无需重新发包；修改原生代码、应用图标、包名或服务器入口需要重新构建。

## 真机验收

安装后检查以下场景，网络与设备限制以实际结果为准：

1. 首次打开站点、输入账号登录，重启应用验证服务端允许的登录保留行为。
2. 搜索影片、打开详情、播放、暂停、拖动进度和切换剧集。
3. 进入和退出视频全屏、旋转手机、系统返回手势及返回键。
4. 网页后退、无历史时返回退出、后台切回和再次启动。
5. 断网加载失败、恢复网络后重试、外部链接跳转浏览器。
6. 收藏、续播；若启用服务器存储，再检查跨端同步。

本工程的编译和 Lint 无法替代具体手机、服务器账号和实际视频源的播放验收。
