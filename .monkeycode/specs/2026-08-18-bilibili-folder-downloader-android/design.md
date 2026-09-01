# Bilibili Folder Downloader for Android

Feature Name: 2026-08-18-bilibili-folder-downloader-android
Updated: 2026-08-18

## Description

将桌面版 B 站收藏夹下载器（`1.py`，tkinter GUI）移植为 Android 原生应用。**下载引擎采用 Gopeed**：应用内嵌 Gopeed headless 服务（Go 交叉编译的 gopeed 二进制打包进 assets），由 Kotlin 子进程管理并启动，应用通过其 REST API 创建、查询、暂停、恢复、删除下载任务；多线程并发、断点续传、带宽限速全部由 Gopeed 引擎承担，不自行实现下载协议逻辑。

- B 站 API 逻辑（`BilibiliClient`，`1.py#L59-L370`）全部用 Kotlin + OkHttp 重新实现。
- UI 使用 Jetpack Compose。
- 音视频合并使用 Android 原生 `MediaMuxer` + `MediaExtractor`（纯流复制，等效 ffmpeg `-c:v copy -c:a copy`），不依赖已停止维护的 FFmpegKit。
- 前台服务保障后台持续下载；Gopeed 引擎生命周期随前台服务管理。
- 下载文件统一保存到应用专属外部目录；用户可通过 SAF 文件选择器将成品导出到任意位置。

功能范围：扫码登录、收藏夹列表、收藏夹内多选视频下载、清晰度选择（360P~4K）、Gopeed 引擎批量下载（多线程/断点续传/限速）、失败自动重试、网络状态处理（仅 WiFi 下载、断网暂停恢复）、下载后删除收藏夹视频、自动 zip 压缩、进度与日志展示（日志脱敏）、后台持续下载（前台服务合规）、应用专属目录存储 + SAF 导出、已下载视频列表与本地播放、首次启动合规声明、下载历史记录。

本设计基于需求文档 `.monkeycode/specs/2026-08-18-bilibili-folder-downloader-android/requirements.md`。

## Architecture

```mermaid
graph TD
    subgraph App["Android App（Kotlin + Jetpack Compose）"]
        UI["UI 层：Onboarding / LoginScreen / TaskConfigScreen / VideoSelectScreen / DownloadScreen / VideoLibraryScreen / SettingsScreen"]
        VM["ViewModel：LoginViewModel / DownloadViewModel / LibraryViewModel"]
        UI --> VM
        VM --> SVC["DownloadService 前台服务"]
        SVC --> DM["DownloadManager 任务调度"]
        NM["NetworkMonitor 网络监听"] --> DM
        DM --> OC["OkHttpDownloadEngine 内置下载器"]
        DM --> GT["GopeedTaskClient REST 客户端"]
        GT --> GE["GopeedEngine 内嵌 headless 服务（Go 进程）"]
        DM --> MX["Mp4Muxer MediaMuxer 合并"]
        DM --> PV["PlaybackVerifier 校验"]
        DM --> ZH["ZipHelper 压缩打包"]
        DM --> WD["WebDavClient WebDAV 上传"]
        DM --> API["BiliApiClient OkHttp 接口"]
        API --> CS["CookieStore 加密存储"]
        DM --> DR["DownloadRecordStore DataStore"]
        DM --> SM["StorageManager 文件管理"]
        MX --> FS["文件系统（应用专属目录）"]
        ZH --> FS
        SM --> FS
        OC --> FS
        GE --> FS
        WD --> DAV["WebDAV 服务器"]
        LIB["VideoLibrary 已下载管理"] --> FS
        LIB --> VP["VideoPlayer Media3 ExoPlayer"]
    end

    API --> BILI["B站 API api.bilibili.com"]
    PV --> FS
```

### 架构说明

- **Kotlin 主导 + 双下载引擎**：UI、B 站 API、任务调度、合并、存储全部为 Kotlin。下载执行支持两种引擎，由用户在设置中选择：内嵌 Gopeed headless 进程（Go 二进制，经 REST API 交互）或应用内 OkHttp 下载器（默认）。Gopeed 选项仅在二进制检测通过后可用。无 JNI、无 Python 运行时。
- **`BiliApiClient` 替代原 `BilibiliClient`**：桌面版 requests.Session 的 Cookie 会话语义由 OkHttp `CookieJar` 还原；登录、收藏夹、播放地址、删除四组接口逐一对应重写。
- **音视频合并使用 MediaMuxer**：B 站 DASH 视频流为 H.264（avc1）、音频流为 AAC（mp4a），均为标准 MP4 片段。`MediaExtractor` 读取音视频轨样本，`MediaMuxer` 按时间戳写入输出 MP4，纯流复制，无重编码。
- **WebDAV 备份上传**：下载任务完成并打包 zip 后，可选自动上传到用户配置的 WebDAV 服务器（设置中配置地址/用户名/密码，密码 AES 加密存储）。
- **UI 全部使用 Jetpack Compose**，状态通过 `StateFlow` 从管理器流式上抛。

## Components and Interfaces

### 1. `BiliApiClient`

对应桌面版 `BilibiliClient`（`1.py#L59-L370`），用 OkHttp 重新实现。内部持有 `OkHttpClient`，配置：

- 浏览器 User-Agent（与桌面版 `1.py#L63` 一致）
- `CookieJar` 内存会话 + `CookieStore` 持久化（登录 Cookie 写入 `EncryptedSharedPreferences`）
- 全局超时 10 秒（与桌面版 `timeout=10` 一致）

| 方法 | 对应原实现 | 说明 |
|------|-----------|------|
| `suspend fun getQrCode(): QrCodeData` | `1.py#L68` | 请求二维码，返回 `url` 与 `qrcode_key` |
| `suspend fun pollLogin(key: String): PollResult` | `1.py#L84` | 轮询登录状态，成功时提取 Cookie 并验证 |
| `suspend fun getFolders(mid: Long): List<Folder>` | `1.py#L230` | 分页拉取用户收藏夹（`ps=20`，`list` 不足 20 条停止） |
| `suspend fun getFolderVideos(mediaId: Long, page: Int): PageResult` | `1.py#L254` | 分页拉取收藏夹视频，返回 `(videos, error, originalCount, totalCount)` |
| `suspend fun getVideoUrl(bvid: String, cid: Int, qn: Int): PlayUrlData?` | `1.py#L314` | 获取 DASH 播放地址（`fnval=16`，`qn` 由用户设置传入），解析 video/audio 流 |
| `suspend fun deleteFolderVideo(mediaId: Long, avid: Long): Boolean` | `1.py#L344` | 从收藏夹删除视频，携带 `bili_jct` CSRF |

**Cookie 提取策略**（对应 `1.py#L109-L186`）：优先从轮询响应的 Set-Cookie 获取；缺失时调用 `/x/passport-login/web/cookie/info` 获取完整 Cookie 列表；最终通过 `/x/web-interface/nav` 验证有效性并记录 MID。

**WBI 签名预留**：收藏夹接口当前不强制 WBI 签名；`BiliApiClient` 预留 `WbiSigner` 接口（从 `/x/web-interface/nav` 取 `img_key`/`sub_key`，按参数排序生成 `wts` + `w_rid`），未来接口强制签名时可启用。

**buvid 设备指纹**：首次使用时调用 `/x/frontend/finger/spi` 获取 `buvid3`/`buvid4`，作为后续请求的通用参数与 Cookie 写入，提升接口响应稳定性。

**大会员清晰度识别**：`getVideoUrl` 返回 `code=-400` 或所选 `qn` 对应的流缺失且存在更低可用流时，判定为清晰度受限；将受限原因（"需要大会员"）随错误信息返回，供 UI 提示。

### 2. 下载引擎层：`DownloadEngine` 接口与双实现

下载执行由可切换的两种引擎承担，`DownloadManager` 通过设置项（默认内置下载器）选择。两者对上层暴露同一接口：

```kotlin
interface DownloadEngine {
    val name: String                    // "Gopeed 引擎" / "内置下载器"
    val supportsLimit: Boolean          // 是否支持带宽限速（内置=是，Gopeed=否）
    suspend fun isAvailable(): Boolean  // 引擎是否可用（Gopeed：二进制检测通过）
    suspend fun download(uri: String, savePath: String, fileName: String): EngineTask
    suspend fun query(task: EngineTask): EngineProgress     // downloaded/total/status
    suspend fun pause(task: EngineTask)
    suspend fun resume(task: EngineTask)
    suspend fun cancel(task: EngineTask)                    // 保留已下载文件
    fun setLimit(bytesPerSec: Long)
}
```

**引擎可用性与选择规则**（需求 19）：

- `内置下载器`：始终可用，为默认引擎；支持带宽限速（令牌桶）。
- `Gopeed 引擎`：设置页在 `isAvailable()` 返回 false 时禁用该选项并显示"未检测到 Gopeed 二进制"；任务启动时若所选为 Gopeed 但 `isAvailable()` 为 false，回退内置下载器并提示用户。
- **限速差异**（调研确认：Gopeed v1.9.3 及 master 已移除带宽限速）：`supportsLimit=false`；设置页选择 Gopeed 引擎时禁用限速项并提示"Gopeed 引擎不支持带宽限速"（需求 5 第 6 条）。反风控的请求间隔（视频 2s/收藏夹 3s/翻页 1.5s）与 UA/Referer 由应用层统一保证，不依赖引擎限速。
- 切换仅影响后续任务，进行中的任务不受影响。

两个引擎共用相同的合并、校验、打包与上传流程，差异仅限"如何把视频/音频流文件下载到本地"。

#### 2.1 `OkHttpDownloadEngine`（内置下载器）

恢复桌面版 `_download_worker`（`1.py#L950-L1016`）的语义，纯 OkHttp 实现：

- **断点续传**：携带 `Range: bytes={size}-`；收到 `206` 追加写入，`416` 时删除残留重下，`200` 时清零从头下（对应 `1.py#L966-L979`）
- **限速**：令牌桶（token bucket）控制读取速率，`setLimit(0)` 不限速（对应 `1.py#L968-L976` 的 `_rate_limit`）
- **进度**：按已写字节数归一化为 `EngineProgress(downloaded, total)`
- **分片**：单连接下载，多线程由上层按流并行（视频流与音频流同时各一个任务）

内置引擎不依赖外部进程，作为 Gopeed 不可用时的降级选项。

#### 2.2 `GopeedEngine` 与 `GopeedTaskClient`

下载执行交给 Gopeed 引擎，Kotlin 只做进程管理与 REST 客户端封装。

##### `GopeedEngine`（进程管理）

- **二进制来源**：Gopeed 官方 `cmd/gopeed` 用 Go 交叉编译为 Android 可执行文件（`GOOS=android GOARCH=arm64`），随版本发布预构建，打包进 `assets/gopeed/`。
- **二进制检测**（`isAvailable()`）：assets 解压到 `filesDir/gopeed/` 后检查——文件存在、`setExecutable(true)` 成功、版本子命令（`gopeed --version`）可正常退出；任一步失败则判定不可用，返回失败原因。
- **生命周期**：`DownloadService` 启动时调用 `ProcessBuilder` 拉起 gopeed headless 子进程，命令行参数：
  - `--address 127.0.0.1 --port <随机高位端口>`：绑定本机回环端口
  - `--token <随机令牌>`：API 访问认证
  - `--headless`：无 UI 模式
- **健康检查**：子进程启动后轮询 `/api/v1/settings` 直至响应成功（超时 10s），确认服务就绪。
- **进程守护**：任务运行期间监控子进程退出码；意外退出时重启并恢复未完成任务（Gopeed 断点续传天然支持）。
- **令牌安全**：端口与令牌均为每次启动随机生成，仅绑定 127.0.0.1，不对外暴露。
- **停止**：任务结束或应用退出时通过 API 停止任务并 `destroy()` 子进程。

##### `GopeedTaskClient`（REST 封装）

通过 OkHttp 调用 Gopeed REST API（`http://127.0.0.1:<port>/api/v1/...`，`Authorization: Bearer <token>`）。

| 方法 | Gopeed 接口 | 说明 |
|------|------------|------|
| `suspend fun createTask(url, name, path): String` | `POST /api/v1/tasks` | 创建单文件下载任务，返回任务 ID |
| `suspend fun queryTask(id): TaskState` | `GET /api/v1/tasks/{id}` | 查询进度、速度、状态 |
| `suspend fun pauseTask(id)` | `PATCH /api/v1/tasks/{id}` | 暂停（网络受限时挂起） |
| `suspend fun resumeTask(id)` | `PATCH /api/v1/tasks/{id}` | 恢复 |
| `suspend fun deleteTask(id)` | `DELETE /api/v1/tasks/{id}` | 删除任务（不删已下载文件） |
| `suspend fun setLimit(bytesPerSec)` | `PATCH /api/v1/settings` | 全局带宽限速（0 = 不限） |
| `suspend fun getSettings()` | `GET /api/v1/settings` | 健康检查与配置读取 |

- **任务完成通知**：通过 Webhook 机制接收 `DOWNLOAD_DONE` / `DOWNLOAD_ERROR` 事件（Gopeed 完成/失败时 POST 到应用内 `LocalServerSocket` 或本地回环 HTTP 端点），或由 `DownloadManager` 以 1s 间隔轮询任务状态。
- 下载进度经轮询/Webhook 归一化为 `ProgressEvent(downloaded, total)` 供 UI 展示。

> **引擎差异提示**：Gopeed 引擎支持多线程分块下载、内置断点续传；内置引擎为单连接 + 应用内令牌桶限速。带宽限速仅内置引擎支持（Gopeed 当前版本无此能力，设置页对 Gopeed 禁用限速项）。两者写入的临时文件命名一致（`{safe_title}_video.mp4`/`_audio.mp4`），切换引擎不影响后续合并流程与已下载记录。

### 3. `Mp4Muxer`

MediaExtractor + MediaMuxer 合并音视频，流复制不转码。输入输出均为本地文件路径（Gopeed 引擎下载产物与应用下载目录）。

```
输入：videoPath（视频流）+ audioPath（音频流，可为空）+ outputPath（成品 mp4）
流程：
1. MediaExtractor 打开视频轨（setDataSource(path)），读取轨道格式与旋转角
2. MediaMuxer 创建输出 mp4（MediaMuxer(outputPath, MUXER_OUTPUT_MPEG_4)，setOrientationHint 写旋转角），addTrack 视频轨
3. 循环读取视频样本写入 muxer；若存在音频轨则 addTrack 后同样写入
4. muxer.stop() + release()
输出：{safe_title}.mp4
```

- 无音频流时仅写视频轨（对应 `1.py#L867-L871` 分支）
- 合并成功后才由调用方删除临时 `_video.mp4`/`_audio.mp4`（对应 `1.py#L879-L882`）

### 4. `PlaybackVerifier`

可播放性校验，替代原 ffmpeg 解码 3 秒方案（`1.py#L884-L897`）。

- `MediaExtractor` 打开输出文件，循环读取样本直至时间戳超过 3 秒
- 读取过程无异常且样本数大于 0 则判定通过
- 校验失败时该视频计为下载失败，保留文件并记录日志

### 5. `ZipHelper`

- 通过 `File.listFiles()` 扫描下载目录下全部 `.mp4`，找下一个可用编号（`1.zip`、`2.zip`…），`ZipOutputStream` 打包（`ZIP_STORED`，对应 `1.py#L1039`）
- 打包后用 `ZipInputStream` 逐条目校验 CRC，全部通过后删除源 MP4（对应 `1.py#L1046-L1057`）

### 5.1 `WebDavClient`

WebDAV 备份上传（需求 20、21）。基于 OkHttp 直接发送 WebDAV 扩展方法（WebDAV 是 HTTP 扩展协议，无需额外库），Basic 认证。

| 方法 | 说明 |
|------|------|
| `suspend fun testConnection(): Boolean` | 对配置地址发 `PROPFIND`（Depth: 0），2xx 判定连接可用 |
| `suspend fun mkdir(remoteDir: String)` | 递归 `MKCOL` 建目录（父目录不存在时逐级创建） |
| `suspend fun uploadZip(localFile: File, remotePath: String, onProgress: (Long, Long) -> Unit)` | 流式 `PUT` 上传 zip，自定义 `RequestBody` 按读入字节上报进度 |

- **配置来源**：地址、用户名从 `DownloadRecordStore` 读取，密码从加密存储读取（`CookieStore` 同机制），组装 `Authorization: Basic base64(user:pass)`；支持地址中内嵌 `https://user:pass@host/path` 的兼容解析（地址中内嵌密码时优先，仍以加密存储为准）。
- **上传路径**：`{webdav_base}/{app_dir}/{yyyyMMdd}_{folderName}/{n}.zip`（应用目录名固定为 `bili_folder_downloader`，避免与用户其他文件混淆）。
- **失败处理**：非 2xx 或网络异常视为失败；`401/403` 归类为"配置错误"并在 UI 提示检查账号；其余为"可重试"（记录日志，保留本地 zip，下载页提供手动重试）。
- **超时**：上传大文件使用长超时（连接 15s、读写 5min），进度回调驱动 UI 显示。

### 6. `DownloadManager`

对应原 `_download_worker`（`1.py#L950`）与 `_process_folder`（`1.py#L1064`）的调度逻辑，在协程中运行：

```
流程：
1. 校验 Cookie 与下载目录可访问性；确认所选引擎可用——Gopeed 二进制检测失败时回退内置下载器并提示（需求 19）；Gopeed 可用时启动子进程并健康检查
2. 拉取全部收藏夹，按名称过滤目标收藏夹（名称匹配 `1.py#L961-L976`）
3. 对每个收藏夹：
   a. 分页拉取视频（每页 20，翻页间隔 1.5s）
   b. 建立文件名索引：任务开始时 listFiles() 一次，后续同目录判断复用索引（避免逐文件扫描）
   c. 过滤已下载（bvid 记录 + 文件名索引双重判断，`1.py#L1092-L1106`）
   d. 按用户勾选过滤：勾选子集时仅保留被勾选 bvid（需求 14）
   e. 逐视频：
      - 取播放地址（按所选清晰度）
      - 通过所选引擎（`DownloadEngine`）为视频流、音频流各创建下载任务（保存到临时文件）
      - 轮询/事件等待两任务完成（Gopeed 负责多线程与断点续传；内置引擎负责单连接续传）
      - Mp4Muxer 合并 → PlaybackVerifier 校验 → 可选 deleteFolderVideo → 记录 bvid
   f. 失败自动重试（默认 2 次，重试间隔递增：5s、15s）
   g. 视频间间隔 2s，收藏夹间间隔 3s
4. 全部完成：ZipHelper 打包 → 若开启"自动上传 zip"则 WebDavClient 上传（上传失败保留本地 zip，可手动重试）
```

**引擎切换**（需求 19）：设置变更写入 `DownloadRecordStore`（键 `engine_type`）；当前任务执行中切换不影响进行中的任务，从下一任务起生效；Gopeed 引擎设为"内置"时停止子进程，反向切换时重新启动。设置页加载时调用 `isAvailable()`，Gopeed 不可用时禁用其选项并展示原因。

**网络暂停恢复**（需求 15）：`DownloadManager` 订阅 `NetworkMonitor` 的网络状态流：

- `仅 WiFi 模式 + 蜂窝网络` → 暂停全部任务（Gopeed：`pauseTask`；内置引擎：协程挂起暂停写入），状态显示"等待网络"
- `仅 WiFi 模式 + 恢复 WiFi` / 断网恢复 → 恢复任务（Gopeed：`resumeTask`；内置引擎：从暂停点续传），自动继续
- 暂停/恢复不终止任务、不丢失进度

**临时文件策略**（需求 16）：视频流下载中断时保留 `_video.mp4`/`_audio.mp4`，引擎重建任务后基于已有文件断点续传；合并失败时保留临时文件供排查，重试时先删除同名残留再重新创建下载任务。

- 进度模型：`progressTotal`（收藏夹 `media_count`，第一页返回后确定）+ `progressDone`（完成/跳过计数）
- 通过 `SharedFlow<DownloadEvent>` 上报事件（日志行、进度、完成），`DownloadViewModel` 收集后驱动 UI

### 7. `DownloadService`

前台服务，持有 `DownloadManager`。

- 启动时 `startForeground(NOTIFICATION_ID, 通知)`，通知实时显示进度与当前视频标题
- 提供 `ACTION_STOP` 通知动作停止任务
- 停止时置 `stopFlag`，协程安全退出，保留已完成文件
- **前台服务类型合规**（需求 11）：`AndroidManifest` 声明 `android:foregroundServiceType="mediaProcessing"`（Android 14+ 强制），不受 `dataSync` 6 小时时长限制
- **通知权限**（需求 11）：Android 13+ 首次启动下载前动态申请 `POST_NOTIFICATIONS`；用户拒绝时降级为前台服务不显示通知的受限运行并提示
- **电池优化**（需求 11）：任务开始时若应用未在白名单且用户同意，通过 `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 引导加入白名单

### 7.1 `NetworkMonitor`

基于 `ConnectivityManager.registerDefaultNetworkCallback` 的网络状态监听。

- 输出 `StateFlow<NetworkState>`：`Connected(wifi)` / `Connected(cellular)` / `Disconnected`
- 供 `DownloadManager` 订阅实现暂停/恢复；供 UI 显示当前网络状态
- 需要 `ACCESS_NETWORK_STATE` 权限

### 7.2 `VideoLibrary` 与 `VideoPlayer`

已下载视频管理（需求 18）。

- `VideoLibrary`：扫描下载目录（`File.listFiles()`）过滤 MP4，输出列表（标题、大小、修改时间），支持删除文件
- `VideoPlayer`：基于 Media3 ExoPlayer 的本地播放器，`MediaItem.fromUri(uri)` 直接播放
- 下载历史：`DownloadRecordStore` 追加任务历史（时间、收藏夹名称、总数、成功数、失败数、zip 路径），`VideoLibraryScreen` 展示并提供重新运行入口

### 7.3 `Onboarding`

首次启动合规引导（需求 17）。

- 首启展示免责声明（仅供个人学习、禁止传播）与隐私说明（Cookie 加密本地存储、不上传第三方）
- 用户点击同意后写入 `DataStore`（键 `onboarding_agreed`）并进入主界面；拒绝则 `finishAffinity()` 退出

### 8. `StorageManager`

管理下载文件与导出，统一封装文件操作。

- **下载目录**：固定为应用专属外部存储下载目录（`context.getExternalFilesDir(null)/downloads/`）。Gopeed 引擎以本地路径写入该目录（无需存储权限），应用始终可访问，卸载即清理。
- **导出**：已下载页与任务完成提示提供"导出到"入口，通过 `ACTION_OPEN_DOCUMENT_TREE` 让用户选择目标目录，选择成功后 `contentResolver.openOutputStream(uri)` 将所选 MP4/zip 流式复制到目标位置；`takePersistableUriPermission` 可选持久化。
- **统一文件操作接口**：

| 操作 | 实现 |
|------|------|
| 打开写入流（追加） | `contentResolver.openOutputStream(uri, "wa")` |
| 打开文件描述符 | `contentResolver.openFileDescriptor(uri, "rw")`（供 MediaMuxer / MediaExtractor） |
| 文件大小 | `File.length()`（下载目录）或 `DocumentFile.length()`（SAF 导出目标） |
| 列出目录文件 | `File.listFiles()`（下载目录） |
| 删除文件 | `File.delete()` / `DocumentFile.delete()` |

- 任务开始前校验下载目录可写（`File.canWrite()`）；不可写时提示用户检查存储空间。

### 9. 存储层

| 存储 | 技术 | 内容 |
|------|------|------|
| `CookieStore` | `SharedPreferences` + AES-GCM 加密（Android Keystore 管理密钥） | `SESSDATA`、`bili_jct`、`mid`、WebDAV 密码，密文存储（对应 `1.py` 的 `bili_cookies.json`） |
| `DownloadRecordStore` | `DataStore (Preferences)` | 已下载 `bvid` 集合、限速设置、删除选项、清晰度设置、仅 WiFi 开关、重试次数、引擎类型（需求 19）、WebDAV 地址/用户名/自动上传开关（需求 20、21）、任务历史、首启合规标记（对应 `1.py` 的 `download_progress.json`） |

### `CookieStore` 加密设计

采用 **AES-256-GCM** 认证加密，密钥由 **Android Keystore** 生成与保管，明文凭证不出现在磁盘上。

```mermaid
graph LR
    LOGIN["登录成功获得 Cookie"] --> ENC["Cipher AES/GCM/NoPadding 加密"]
    KS["Android Keystore（AES-256 密钥，别名 bili_cookie_key）"] --> ENC
    ENC --> PREFS["SharedPreferences 存储 base64(iv).base64(密文)"]
    PREFS --> DEC["Cipher 解密"]
    KS --> DEC
    DEC --> APP["应用内使用 Cookie"]
```

**密钥管理**：

- 首次使用时通过 `KeyGenerator` 在 Keystore 中生成 AES-256 密钥，别名固定为 `bili_cookie_key`，`KeyGenParameterSpec` 设置 `PURPOSE_ENCRYPT | PURPOSE_DECRYPT`。
- 密钥不可导出、不落盘（由 Keystore 硬件/系统级保护），应用重装或设备重置后密钥失效。
- 每次调用通过 `KeyStore.getKey` 获取同一密钥实例，无需自行保存密钥材料。

**加解密流程**：

| 步骤 | 操作 |
|------|------|
| 加密 | 生成 12 字节随机 IV → `Cipher.getInstance("AES/GCM/NoPadding")` 初始化加密模式 → 输出 `ivBase64 + "." + cipherBase64` 写入 SharedPreferences |
| 解密 | 读取密文字符串，按 `.` 切分 IV 与密文 → 解密恢复明文 |
| 存储键 | `cookie_sessdata`、`cookie_bili_jct`、`cookie_mid`、`webdav_password` 四个键分别加密存储 |

**异常处理**：

- `AEADBadTagException`（数据被篡改或密钥失效）：清除全部 Cookie 与 WebDAV 密码数据，提示用户重新登录 / 重新配置 WebDAV。
- Keystore 密钥丢失（应用数据被清除）：静默重新生成密钥，清除旧数据并提示重新登录。

## Data Models

### `VideoInfo`

```kotlin
data class VideoInfo(
    val bvid: String,
    val title: String,
    val cid: Int,
    val avid: Long = 0
)
```

对应桌面版 `VideoInfo` dataclass（`1.py#L51-L56`）。

### 下载状态机

```mermaid
graph LR
    IDLE["Idle"] --> RUN["Running"]
    RUN --> STOPPED["Stopped（用户中断）"]
    RUN --> DONE["Completed"]
    RUN --> FAILED["Failed（致命错误）"]
    DONE --> IDLE
    FAILED --> IDLE
```

- 单个视频失败不中断整体任务（`Failed` 仅用于 Cookie 失效、存储不可写等致命场景）
- `DownloadEvent` 流：`LogEvent(text)`、`ProgressEvent(done, total, currentTitle)`、`CompletedEvent(zipPath)`、`FailedEvent(reason)`

### 文件布局

下载文件统一保存到应用专属外部存储目录（Gopeed 引擎以本地路径写入）；用户可通过"导出到"将成品复制到任意 SAF 目录：

```
下载目录：/storage/emulated/0/Android/data/<package>/files/downloads/
├── {safe_title}.mp4          # 合并后的成品
├── {safe_title}_video.mp4    # 临时视频流（Gopeed 下载，合并后删除）
├── {safe_title}_audio.mp4    # 临时音频流（Gopeed 下载，合并后删除）
├── 1.zip / 2.zip …           # 打包产物（源 mp4 删除后）
```

文件名安全化规则沿用桌面版：`title.replace(Regex("[\\\\/*?:\"<>|]"), "_")`。

## Correctness Properties

1. **下载幂等性**：同一 `bvid` 或同名 MP4 已存在时跳过，重复运行任务不重复下载（需求 8）。
2. **断点续传正确性**：Gopeed 引擎基于本地已下载文件自动续传；内置引擎按 `Range` 续传（`416` 重下、`200` 从头下）；`DownloadManager` 重建任务时复用同名临时文件（需求 6）。
3. **合并原子性**：仅当 `Mp4Muxer` 成功且 `PlaybackVerifier` 通过后才写入已下载记录；临时流文件仅在合并成功后才删除（需求 4、12）。
4. **zip 数据安全**：仅当 zip 条目 CRC 校验全部通过后才删除源 MP4，校验失败保留源文件（需求 9）。
5. **进度一致**：跳过已下载视频计入 `progressDone`；`progressTotal` 为收藏夹 `media_count`，进度条单调不减（需求 8、10）。
6. **风控合规**：请求频率受限速与固定间隔约束；API 请求携带桌面版同款浏览器 UA/Referer（需求 5）。
7. **重试幂等性**：同一视频的重试不会重复写入已下载记录；重试前清理同名临时残留（需求 16）。
8. **网络恢复正确性**：网络受限时暂停/恢复任务（Gopeed 引擎调用 `pauseTask`/`resumeTask`；内置引擎挂起协程），不终止任务、不丢失已下载进度（需求 15）。
9. **日志保密性**：日志模块统一过滤 `SESSDATA`/`bili_jct`/Gopeed 令牌/WebDAV 密码等敏感键值，任何日志输出不包含敏感明文（需求 10）。
10. **Gopeed 引擎可用性**：任务运行期间引擎意外退出时自动重启并恢复任务，任务状态与文件不丢失。
11. **上传不丢数据**：WebDAV 上传失败时本地 zip 保留且已下载记录不受影响，手动重试后不重复上传同一任务产生冲突（需求 21）。
12. **引擎一致切换**：切换引擎不影响已下载记录与临时文件命名约定，任务从下一轮开始使用新引擎；Gopeed 不可用时不产生切换入口，任务自动回退内置引擎（需求 19）。

## Error Handling

| 场景 | 检测 | 处理 |
|------|------|------|
| 二维码过期 | 轮询返回 `86038` | 自动重新生成二维码（需求 1） |
| Cookie 失效 | `/x/web-interface/nav` 返回非 0 | 停止任务、清除 Cookie、提示重新登录（需求 2） |
| Cookie 解密失败 | `AEADBadTagException` 或 Keystore 密钥丢失 | 清除 Cookie 数据并提示重新登录（需求 2） |
| 风控触发 | 接口返回 `-352`/`-403` | 显示"请求过于频繁"提示，建议调低速率后重试（需求 5） |
| 单个视频无流/获取失败 | `playurl` 返回错误或无可用流 | 该视频计为失败，记录日志，继续下一个（需求 4） |
| Gopeed 二进制缺失 | assets 解压失败或 `--version` 无法执行 | 设置页禁用 Gopeed 选项并提示；任务启动时回退内置下载器（需求 19） |
| Gopeed 引擎启动失败 | 子进程启动异常或健康检查超时 | 提示下载引擎不可用，终止任务（需求 4） |
| Gopeed 引擎意外退出 | 子进程退出码非 0 | 自动重启引擎并恢复未完成任务，记录日志 |
| 下载任务失败 | Webhook `DOWNLOAD_ERROR` 事件 | 该视频计为失败，按重试策略重试（需求 16） |
| 合并失败 | `MediaMuxer` 异常 | 视频计为失败，保留临时文件用于排查（需求 4） |
| 存储空间不足 | 写文件抛 `IOException` | 终止任务，提示清理空间 |
| 下载目录不可写 | 启动任务前 `File.canWrite()` 为 false | 提示检查存储空间，终止任务（需求 12） |
| 清晰度受限 | 播放地址接口拒绝或目标 `qn` 流缺失 | 提示"该清晰度需要大会员"，引导降低清晰度后重试（需求 13） |
| 蜂窝网络限制 | 仅 WiFi 模式 + 网络状态为蜂窝 | 暂停全部任务并提示"等待 WiFi"，恢复 WiFi 自动继续（需求 15） |
| 网络断开 | `NetworkMonitor` 上报 Disconnected | 暂停任务，恢复后自动继续（需求 15） |
| 单个视频多次失败 | 重试耗尽（默认 2 次） | 记录失败原因，继续下一个视频（需求 16） |
| 删除收藏夹视频失败 | 删除接口返回非 0 | 记录日志，继续下一个视频（需求 7） |
| 网络异常 | 请求超时/连接失败 | 该视频计为失败，记录原因，继续下一个（需求 10） |
| WebDAV 连接失败 | `testConnection`/`MKCOL`/`PUT` 网络异常 | 显示失败原因，保留本地 zip，任务结果不受影响（需求 21） |
| WebDAV 认证失败 | `401`/`403` | 提示检查地址/用户名/密码，标记"配置错误"，不再自动重试（需求 20、21） |
| WebDAV 上传中断 | 上传中连接断开 | 记录日志，保留本地 zip，允许手动重试（需求 21） |

## Test Strategy

### 单元测试（JUnit + MockWebServer + Robolectric）

- `BiliApiClient`：二维码生成、轮询状态解析（含 `86038` 过期）、Cookie 提取两条策略、收藏夹分页终止条件、视频列表空页/错误码解析、`playurl` 无视频流/无音频流分支、删除接口缺 `bili_jct` 分支、buvid 获取与复用
- `GopeedTaskClient`：MockWebServer 模拟 REST API——创建任务、查询进度、暂停/恢复/删除、限速设置、Webhook `DOWNLOAD_DONE`/`DOWNLOAD_ERROR` 事件解析
- `OkHttpDownloadEngine`：断点续传（先下载一半再续传）、`200` 重置、`416` 重下、令牌桶限速生效、取消保留已下载文件
- `DownloadEngine` 引擎切换：设置切换后任务使用新引擎；引擎接口行为一致性（两个实现返回同一 `EngineProgress` 语义）；Gopeed 二进制缺失时 `isAvailable()` 返回 false、设置页禁用选项、任务启动回退内置引擎（需求 19）
- `WebDavClient`：MockWebServer 模拟 `PROPFIND` 连接测试、`MKCOL` 逐级建目录、`PUT` 上传进度回调、`401/403` 归类、Basic 认证头组装
- `ZipHelper`：打包命名递增、CRC 校验失败保留源文件
- `DownloadRecordStore`：读写、去重、设置项与任务历史持久化、引擎类型与 WebDAV 配置读写、自动上传开关持久化
- `StorageManager`：下载目录校验、文件名安全化、SAF 导出 URI 解析（Robolectric 可覆盖纯逻辑部分）
- `DownloadManager`：多选过滤（仅保留勾选 bvid）、失败重试次数与间隔、重试幂等性（不重复记录）、目录索引复用、任务状态轮询归一化、上传失败不阻塞任务完成
- 日志模块：断言日志输出不含 `SESSDATA`/`bili_jct`/Gopeed 令牌/WebDAV 密码明文（脱敏测试）
- 网络策略：Mock 网络状态流，验证蜂窝暂停/恢复逻辑（纯状态机部分）

### Gopeed 引擎测试（Instrumented，真机/模拟器）

- 启动 headless 子进程 → 健康检查通过、REST API 可访问
- 创建真实 HTTP 下载任务（本地 MockWebServer 文件）→ 任务完成、文件落盘正确
- 模拟引擎进程被杀 → 验证自动重启与任务恢复
- 限速设置生效（对比限速前后下载耗时）
- 二进制检测：删除/损坏 `filesDir/gopeed/` 下二进制 → `isAvailable()` 返回 false、设置页 Gopeed 选项禁用、任务启动回退内置下载器（需求 19）

### WebDAV 上传测试（Instrumented，本地 WebDAV 服务或模拟器回环）

- 对本地 WebDAV 服务器（如内置 Python `wsgidav` 或 MockWebServer 的 PUT/MKCOL 支持）上传 zip → 远程文件字节数与本地一致
- 建目录失败（父目录不存在）→ 逐级 `MKCOL` 重试成功
- 断网中断上传 → 本地 zip 保留、任务记录成功、可手动重试

### 导出测试（Instrumented，真机/模拟器 SAF）

- 通过 `ACTION_OPEN_DOCUMENT_TREE` 选择导出目录 → 验证 MP4/zip 复制到目标位置
- 导出目标不可写 → 验证错误提示

### 合并模块测试（Instrumented，Robolectric 不覆盖 MediaMuxer）

- `Mp4Muxer`：用预生成的小体积 H.264+AAC 测试片段，验证合并输出可被 `MediaExtractor` 完整读取、时长正确
- `PlaybackVerifier`：正常文件通过、损坏文件失败

### 网络与播放测试（Instrumented）

- 模拟 WiFi/蜂窝切换与断网（`ConnectivityManager` 测试 API）→ 验证任务暂停与自动恢复
- `VideoPlayer`：本地 MP4 播放、播放器生命周期释放

### 集成测试（手动，真机）

1. 扫码登录 → 重新启动应用验证 Cookie 自动加载
2. 下载 1 个收藏夹（含音频流）→ 校验合并文件可播放
3. 中断下载 → 再次启动验证断点续传
4. 开启"下载后删除" → 验证收藏夹视频被移除
5. 后台/锁屏下载 → 验证前台服务持续运行
6. 下载完成 → 验证自动 zip 打包
7. 导出：通过文件选择器选择公共目录（如 `/Download`）→ 验证成品（MP4/zip）复制到该目录
8. 切换清晰度为 720P → 验证按所选画质下载；无大会员账号选 4K → 验证大会员提示
9. 收藏夹内勾选部分视频 → 验证仅下载勾选项
10. 仅 WiFi 模式下切到蜂窝 → 验证暂停并提示；切回 WiFi → 验证自动继续
11. 断网（飞行模式）→ 验证暂停；恢复网络 → 验证继续
12. 首启 → 验证免责声明/隐私说明；同意后进入主界面
13. 已下载页 → 验证列表、本地播放、删除、历史记录与重新运行

## Build & Release Configuration

### SDK 级别

- `minSdk = 26`（Android 8.0）：覆盖 MediaMuxer（API 18+）、MediaExtractor（API 16+）、SAF（API 21+）全部能力需求
- `targetSdk = 35`（Android 15）：满足 2026 年应用商店目标 API 要求
- `compileSdk = 35`，Kotlin 2.x + Jetpack Compose（BOM 最新稳定版）

### 权限清单（`AndroidManifest.xml`）

| 权限 | 用途 | 备注 |
|------|------|------|
| `INTERNET` | B站 API 与 CDN 下载 | 正常 |
| `ACCESS_NETWORK_STATE` | `NetworkMonitor` 监听网络状态 | 正常 |
| `POST_NOTIFICATIONS` | Android 13+ 前台服务通知 | 运行时动态申请 |
| `FOREGROUND_SERVICE` | 前台服务 | 正常 |
| `FOREGROUND_SERVICE_MEDIA_PROCESSING` | 声明媒体处理前台服务类型（Android 14+） | 正常 |

不需要 `WRITE_EXTERNAL_STORAGE` / `MANAGE_EXTERNAL_STORAGE`（SAF 方案）。

### 前台服务声明

```xml
<service
    android:name=".service.DownloadService"
    android:foregroundServiceType="mediaProcessing"
    android:exported="false" />
```

`mediaProcessing` 类型无 `dataSync` 的 6 小时时长限制，适合长时下载。

### Gopeed 引擎构建

- **二进制编译**：Go 工具链交叉编译 Gopeed 官方 `cmd/gopeed`（`GOOS=android GOARCH=arm64 CGO_ENABLED=0`），产物打包进 `app/src/main/assets/gopeed/`；需在 Android Gradle 构建流程中同步构建或使用预发布二进制。
- **ABI 支持**：arm64-v8a 为主目标；如支持 x86_64 模拟器需同时提供对应二进制并做 ABI 分发。
- **体积影响**：Go 静态二进制约 20~30 MB，APK 体积显著增加；考虑使用 `android:extractNativeLibs` 相关优化或后续按需下载引擎（第一版内置）。
- **执行权限**：assets 解压到应用私有目录（`filesDir/gopeed/`）后 `setExecutable(true)` 再启动子进程。

### 发布前清单

1. 配置 R8/ProGuard 混淆（Compose、OkHttp、Media3 官方规则）
2. 生成发布签名 keystore 并配置 `signingConfigs`
3. 商店上架材料：应用图标、截图、隐私政策链接（引用应用内隐私说明）
4. 版本号管理（`versionCode`/`versionName`）

## References

[^1]: (File) - [桌面版源码 `1.py`](/workspace/1.py)，移植基准与行为对照
[^2]: (Website) - [bilibili-API-collect 接口文档](https://github.com/SocialSisterYi/bilibili-API-collect)，收藏夹与登录接口定义
[^3]: (Website) - [Android MediaMuxer API](https://developer.android.com/reference/android/media/MediaMuxer)，音视频流复制合并
[^4]: (Website) - [OkHttp 官方文档](https://square.github.io/okhttp/)，HTTP 客户端与 CookieJar
[^5]: (Website) - [Android Foreground Service 指南](https://developer.android.com/develop/background-work/services/foreground-services)，后台下载保活
[^6]: (Website) - [Android Keystore 系统](https://developer.android.com/privacy-and-security/keystore)，AES 密钥安全管理
[^7]: (Website) - [AES-GCM 认证加密参考（Android Cipher 文档）](https://developer.android.com/reference/javax/crypto/Cipher)，加密实现依据
[^8]: (Website) - [Storage Access Framework 概览](https://developer.android.com/training/data-storage/shared/documents-files)，文件导出目标目录选择
[^9]: (Website) - [DocumentFile 文档](https://developer.android.com/reference/androidx/documentfile/provider/DocumentFile)，SAF 文件操作 API
[^10]: (Website) - [Media3 ExoPlayer 文档](https://developer.android.com/media/media3/exoplayer)，本地视频播放
[^11]: (Website) - [ConnectivityManager 网络监听](https://developer.android.com/reference/android/net/ConnectivityManager)，网络状态监测与暂停恢复
[^12]: (Website) - [Gopeed 官方文档：API 对接](https://gopeed.com/zh/docs/dev-api)，REST API 与令牌认证
[^13]: (Website) - [Gopeed 官方文档：Webhook](https://gopeed.com/zh/docs/webhook)，下载完成/失败事件推送
[^14]: (Website) - [Gopeed 官方文档：介绍](https://gopeed.com/zh/docs/)，下载引擎能力（多线程/断点续传/限速）
[^15]: (Website) - [WebDAV 协议规范（RFC 4918）](https://datatracker.ietf.org/doc/html/rfc4918)，MKCOL/PROPFIND/PUT 等 WebDAV 方法定义
[^16]: (Website) - [OkHttp 自定义请求方法](https://square.github.io/okhttp/4.x/okhttp/okhttp3/-request/)，发送 PUT/MKCOL 等 WebDAV 扩展方法
