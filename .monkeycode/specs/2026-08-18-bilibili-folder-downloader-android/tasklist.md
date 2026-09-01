# 需求实施计划

## 任务列表

- [x] 1. 搭建 Android 工程骨架与构建环境
  - [x] 1.1 创建 Gradle 工程结构：`settings.gradle.kts`、根/模块 `build.gradle.kts`、`gradle/libs.versions.toml` 版本目录
  - [x] 1.2 配置 `app` 模块：`minSdk=26`、`targetSdk=35`、`compileSdk=35`、Compose 启用、包名 `com.example.bilifolderdownloader`
  - [x] 1.3 配置依赖：Jetpack Compose BOM、Navigation Compose、OkHttp、Media3 ExoPlayer、DataStore Preferences、Security Crypto、Kotlinx Serialization/Coroutines（对应设计 2.1/9.1 及需求 21 上传依赖）
  - [x] 1.4 编写 `AndroidManifest.xml`：`INTERNET`、`ACCESS_NETWORK_STATE`、`POST_NOTIFICATIONS`、`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_MEDIA_PROCESSING` 权限与 `DownloadService` 前台服务声明（对应设计 9.2、9.3）
  - [x] 1.5 编译 Gopeed Android 二进制：`GOOS=android GOARCH=arm64 CGO_ENABLED=0` 交叉编译 gopeed，产物放入 `app/src/main/assets/gopeed/`（对应设计 9.4）
  - [x] 1.6 验证工程可编译：`./gradlew :app:assembleDebug` 通过（38MB APK，含 gopeed 资产）

- [x] 2. 实现数据模型与存储层
  - [x] 2.1 定义数据模型：`VideoInfo`（bvid/title/cid/avid）、`Folder`（mediaId/title/count）、`EngineTask`/`EngineProgress`、`TaskHistory`、`WebDavConfig`、`DownloadSettings`（对应设计 10 数据模型）
  - [x] 2.2 实现 `CookieStore`：AES-256-GCM（Keystore 别名 `bili_cookie_key`）加解密 `SESSDATA`/`bili_jct`/`mid`/`webdav_password`，密文 `base64(iv).base64(cipher)` 存 SharedPreferences，处理 `AEADBadTagException` 与密钥丢失（对应设计 9.5、需求 2、20）
  - [x] 2.3 实现 `DownloadRecordStore`：DataStore 读写已下载 `bvid` 集合、限速/清晰度/删除/仅 WiFi/重试次数/引擎类型/WebDAV 配置/自动上传开关/任务历史/首启合规标记（对应设计 9.5、需求 19/20/21）
  - [x] 2.4 单元测试：CookieStore 加密后不含明文、解密还原、异常清理；DownloadRecordStore 读写持久化与去重（app/src/test CookieStoreTest/DownloadRecordStoreTest）

- [x] 3. 实现 `BiliApiClient`（对应设计 4.1、需求 1-4、7、13）
  - [x] 3.1 实现扫码登录：`getQrCode`、`pollLogin`（2s 轮询、`86038` 过期重生成）、Cookie 提取两条策略、`/x/web-interface/nav` 验证并记录 MID（需求 1、2）
  - [x] 3.2 实现收藏夹与视频列表：`getFolders`（分页终止条件）、`getFolderVideos`（分页、错误码、返回 `(videos, error, originalCount, totalCount)`）（需求 3、4）
  - [x] 3.3 实现播放地址与删除：`getVideoUrl`（`fnval=16` DASH 解析、qn 选择、大会员受限识别）、`deleteFolderVideo`（带 `bili_jct` CSRF）（需求 4、7、13）
  - [x] 3.4 实现 buvid 指纹获取与复用、WBI 签名预留接口（`WbiSigner` 接口定义）（对应设计 4.1）
  - [x] 3.5 单元测试（MockWebServer）：登录轮询/过期、Cookie 提取、分页终止、错误码、`playurl` 无流分支、删除缺 `bili_jct` 分支（app/src/test BiliApiClientTest）

- [x] 4. 实现下载引擎层（对应设计 4.2、需求 6、15、19）
  - [x] 4.1 定义 `DownloadEngine` 接口与 `isAvailable()`，统一 `EngineTask`/`EngineProgress` 语义（需求 19）
  - [x] 4.2 实现 `OkHttpDownloadEngine`：`Range` 断点续传（206 追加/416 重下/200 从头）、令牌桶限速、进度上报、取消保留已下载文件（需求 6）
  - [x] 4.3 实现 `GopeedEngine`：assets 解压 + 二进制检测（存在/可执行/`--version`）、`ProcessBuilder` 拉起 headless 子进程（随机端口+令牌、仅回环）、健康检查、进程守护与停止（需求 19）
  - [x] 4.4 实现 `GopeedTaskClient`：REST API 封装（create/query/pause/resume/delete）、`X-Api-Token` 认证、进度/状态归一化轮询（Gopeed 已移除限速，无 setLimit；轮询代替 Webhook，对应设计 4.2.2）（需求 4、6、15、19）
  - [x] 4.5 单元测试：OkHttp 引擎续传/416/200/限速/暂停恢复；GopeedTaskClient MockWebServer 全端点（app/src/test OkHttpDownloadEngineTest/GopeedTaskClientTest）

- [x] 5. 实现合并、校验与打包（对应设计 4.3/4.4/4.5、需求 4、9）
  - [x] 5.1 实现 `Mp4Muxer`：MediaExtractor + MediaMuxer 流复制合并音视频（处理旋转角、无音频轨分支、合并后删临时文件）
  - [x] 5.2 实现 `PlaybackVerifier`：读取合并文件样本至 3 秒判定可播放
  - [x] 5.3 实现 `ZipHelper`：递增编号（`1.zip`、`2.zip`…）、`ZIP_STORED` 打包、CRC 校验通过后删源 MP4、失败保留
  - [x]* 5.4 单元测试：ZipHelper 编号递增、CRC 失败保留源文件（app/src/test ZipHelperTest）

- [x] 6. 实现下载调度与前台服务（对应设计 4.6/4.7/4.7.1、需求 4-6、10、11、14-16）
  - [x] 6.1 实现 `DownloadManager`：任务主流程（引擎选择与回退、分页 1.5s、名称过滤、文件名索引、已下载去重、多选过滤、逐视频下载→合并→校验→可选删除→记录、重试 2 次递增间隔、视频 2s/收藏夹 3s 间隔、进度模型、zip 打包与自动上传触发、`SharedFlow<DownloadEvent>` 上报）（需求 4/5/7/8/9/10/14/16/19/21）
  - [x] 6.2 实现 `NetworkMonitor`：`ConnectivityManager` 网络状态流（WiFi/蜂窝/断网）（需求 15）
  - [x] 6.3 实现 `DownloadService`：`mediaProcessing` 前台服务、常驻通知（进度+当前标题）、`ACTION_STOP`、停止协程安全退出、通知权限申请引导（需求 11）
  - [x] 6.4 单元测试：DownloadManager 多选过滤/去重/文件名索引（filterCandidates 纯函数，app/src/test DownloadManagerTest）；NetworkMonitor 状态机见网络层实现

- [x] 7. 实现 WebDAV 与存储管理（对应设计 4.5.1/4.8、需求 12、20、21）
  - [x] 7.1 实现 `WebDavClient`：`PROPFIND` 连接测试、递归 `MKCOL`、流式 `PUT` 上传（进度回调）、Basic 认证、`401/403` 归类、上传路径拼接（需求 20、21）
  - [x] 7.2 实现 `StorageManager`：下载目录解析、文件名安全化、SAF `ACTION_OPEN_DOCUMENT_TREE` 导出（需求 12）
  - [x] 7.3 实现 `VideoLibrary`：扫描下载目录 MP4 列表、删除、`VideoPlayer`（Media3）播放（需求 18）
  - [x] 7.4 单元测试：WebDavClient MockWebServer（PROPFIND/MKCOL/PUT/401/内嵌凭证）；文件名安全化（app/src/test WebDavClientTest/StorageManagerTest）

- [x] 8. 检查点 - 确保所有单元测试通过，如有疑问请询问用户

- [x] 9. 实现 UI 层（Jetpack Compose，对应设计 4.0/4.7.3、需求 1-3、10、13、14、17-21）
  - [x] 9.1 搭建导航骨架与 Material3 主题、`MainActivity` 入口
  - [x] 9.2 实现 `Onboarding` 合规页：免责声明与隐私说明、同意/拒绝分支（需求 17）
  - [x] 9.3 实现 `LoginScreen`：二维码展示与轮询、登录成功进入主界面、启动自动加载 Cookie（需求 1、2）
  - [x] 9.4 实现收藏夹选择页与视频多选页：MID 输入、收藏夹列表、视频列表勾选（需求 3、14）
  - [x] 9.5 实现任务配置：清晰度/限速/删除/仅 WiFi/引擎选择/自动上传 zip 开关（需求 13、19、21）
  - [x] 9.6 实现 `DownloadScreen`：进度（总体+当前视频）、日志区自动滚动、停止按钮（需求 10）
  - [x] 9.7 实现 `SettingsScreen`：引擎切换（Gopeed 二进制检测禁用）、WebDAV 地址/用户名/密码与测试连接、自动上传开关（需求 19、20、21）
  - [x] 9.8 实现 `VideoLibraryScreen`：已下载列表、播放、删除、历史记录与重新运行（需求 18）
  - [ ]* 9.9 Compose UI 测试：Onboarding 分支、设置页引擎禁用逻辑

- [x] 10. 检查点 - 确保全部测试通过，构建 `assembleDebug` 成功，如有疑问请询问用户（62 个单元测试全部通过，assembleDebug 构建成功）
- [x] 11. 调试日志增强（用户追加需求）：logcat 仅开发版明文输出，正式版完全禁止 logcat；日志明文入库 SQLite（LogStore，应用私有 debug_logs.db），每 30 分钟守护线程清理只保留最近半小时；导出默认最近 30 分钟——开发版导出明文 txt，正式版将完整 txt 整体做一次加密（CMS AuthEnvelopedData / AES-256-GCM，等价 `openssl cms -encrypt -recip 证书 -aes-256-gcm`，LogEncryptor 基于 BouncyCastle 1.85 的 CMSAuthEnvelopedDataGenerator），导出文件为 openssl SMIME 多行文本（MIME 头 + base64 每 64 字符换行），`openssl cms -decrypt -in 文件 -inkey private_key.pem` 直接可解（openssl 互操作单测通过）；导出文件 `getExternalFilesDir/logs/bili_debug_export.txt`（外部不可用回退 filesDir/logs），设置页「导出日志文件（最近 30 分钟）」按钮经 FileProvider 分享；加密证书/公钥由用户提供：`app/src/main/assets/log_encryption_cert.pem`（`openssl req -new -x509` 自签证书，首选）+ `rsa_public_key.pem`（裸公钥回退），均未放置时正式版导出返回 null；新增 LogStore/LogUtilExport/LogEncryptor 测试（Robolectric sdk 34，67 个测试全绿，APK 41MB）
