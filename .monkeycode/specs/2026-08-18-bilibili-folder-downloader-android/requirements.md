# Requirements Document

## Introduction

将现有的 B 站收藏夹下载器（`1.py`，基于 Python tkinter 的桌面应用）移植为 Android 原生应用。应用沿用现有桌面版的功能与行为，并在移动端场景下增强：通过 B 站扫码登录获取 Cookie（AES 加密本地存储），按用户 MID 拉取收藏夹列表，收藏夹内支持多选视频下载，可选清晰度（360P~4K），DASH 音视频分离下载后合并，支持限速、断点续传、失败自动重试、仅 WiFi 下载与断网自动暂停恢复，下载后可选择从收藏夹删除视频，全部下载完成后自动压缩为 zip；同时提供 SAF 目录选择、已下载视频本地播放、首次启动合规声明与下载历史管理。

技术路线：Kotlin 层以 OkHttp 重新实现 B 站 API 调用（登录、收藏夹、播放地址、删除），UI 使用 Jetpack Compose；**下载引擎采用 Gopeed**——应用内嵌 Gopeed headless 服务（Go 交叉编译二进制），通过其 REST API 创建与管理下载任务（多线程、断点续传、限速均由 Gopeed 承担），音视频合并使用 Android 原生 MediaMuxer，前台服务与存储管理均在 Kotlin 层实现。

## Glossary

- **B站**: 哔哩哔哩视频平台（bilibili.com）。
- **MID**: B 站用户唯一数字 ID。
- **收藏夹**: B 站用户创建的用于收藏视频的容器，有独立 `media_id` 和标题。
- **SESSDATA**: B 站登录凭证 Cookie，用于调用需认证的 Web API。
- **bili_jct**: B 站 CSRF 凭证 Cookie，写操作（如删除收藏夹视频）必需。
- **DASH**: B 站播放地址接口返回的分段流格式，视频流与音频流分离，需下载后合并。
- **断点续传**: 基于 HTTP Range 从本地已下载字节数继续下载的机制。
- **WBI 签名**: B 站对部分 Web API 要求的动态请求签名（`wts` + `w_rid`）。
- **可播放性校验**: 合并后用解码器解码输出文件前 3 秒，确认文件可正常播放。

## Requirements

### 需求 1：扫码登录

**User Story:** 作为用户，我希望用 B 站 App 扫码完成登录，以便应用获得下载所需的登录凭证。

#### Acceptance Criteria

1. WHEN 用户打开登录页面，系统 SHALL 生成二维码并显示在页面上。
2. WHEN 应用生成二维码，系统 SHALL 每 2 秒轮询一次登录状态接口。
3. WHEN 用户使用 B 站 App 扫码并确认，系统 SHALL 从轮询响应中提取并保存登录 Cookie。
4. WHEN 应用获取到 Cookie，系统 SHALL 调用用户信息接口验证 Cookie 有效性并记录 MID。
5. IF 二维码过期（轮询返回 86038），系统 SHALL 重新生成二维码。
6. WHEN 应用启动，系统 SHALL 尝试加载本地保存的 Cookie；IF Cookie 存在且有效，系统 SHALL 直接进入已登录状态。

### 需求 2：Cookie 持久化与安全存储

**User Story:** 作为用户，我希望登录状态能跨会话保留，以便无需重复扫码。

#### Acceptance Criteria

1. WHEN 登录成功，系统 SHALL 使用 AES 加密 `SESSDATA`、`bili_jct`、`mid` 后写入 SharedPreferences，存储内容不包含明文凭证。
2. WHEN 应用启动，系统 SHALL 从 SharedPreferences 读取密文并解密，再验证 Cookie 有效性。
3. IF Cookie 验证失败或解密失败，系统 SHALL 清除失效数据并提示用户重新登录。

### 需求 3：获取收藏夹列表

**User Story:** 作为用户，我希望输入目标用户的 MID 后看到其收藏夹列表，以便选择要下载的收藏夹。

#### Acceptance Criteria

1. WHEN 用户输入 MID 并点击"获取收藏夹"，系统 SHALL 调用收藏夹列表接口分页拉取全部收藏夹。
2. WHEN 拉取完成，系统 SHALL 在界面显示收藏夹标题列表，供用户选择。
3. IF 拉取失败（接口返回错误码或网络异常），系统 SHALL 显示错误信息并终止本次拉取。

### 需求 4：批量下载收藏夹视频

**User Story:** 作为用户，我希望按收藏夹批量下载其中的全部视频，以便离线观看。

#### Acceptance Criteria

1. WHEN 用户选定收藏夹并点击下载，系统 SHALL 分页拉取收藏夹内视频列表（每页 20 条）。
2. WHEN 获取到视频条目，系统 SHALL 请求 DASH 播放地址，选择用户所选清晰度对应的视频流（默认 1080P，qn=80）与对应音频流。
3. WHEN 获得音视频流地址，系统 SHALL 分别下载视频流与音频流到本地临时文件。
4. WHEN 音视频流下载完成，系统 SHALL 将两段流合并为单个 MP4 文件（流复制，不重新编码）。
5. WHEN 合并完成，系统 SHALL 执行可播放性校验；IF 校验失败，系统 SHALL 判定该视频下载失败并保留临时文件信息用于排查。
6. WHEN 视频下载成功，系统 SHALL 记录该视频 `bvid` 到已下载记录，避免重复下载。
7. WHEN 用户未指定收藏夹名称，系统 SHALL 下载该用户全部收藏夹。

### 需求 5：下载限速与反风控

**User Story:** 作为用户，我希望下载过程保持低频率请求，以避免触发 B 站风控导致接口封禁。

#### Acceptance Criteria

1. WHEN 下载视频流或音频流，系统 SHALL 按用户设定的限速值控制下载速率（默认 100 KB/s，可设置为不限速）。
2. WHEN 每个视频下载完成，系统 SHALL 等待 2 秒后再开始下一个视频。
3. WHEN 每个收藏夹处理完成，系统 SHALL 等待 3 秒后再处理下一个收藏夹。
4. WHEN 分页拉取收藏夹视频，系统 SHALL 在翻页之间等待 1.5 秒。
5. WHEN 发起 API 请求，系统 SHALL 携带与桌面版一致的浏览器 User-Agent 和 Referer 头。
6. IF 使用 Gopeed 引擎（该引擎当前版本不支持带宽限速），系统 SHALL 禁用限速设置项并提示"Gopeed 引擎不支持带宽限速"；限速仅对内置下载器生效。

### 需求 6：断点续传与中断恢复

**User Story:** 作为用户，我希望下载中断后能继续而非重新开始，以便节省流量和时间。

#### Acceptance Criteria

1. WHEN 下载中断后重新发起同一文件的下载，系统 SHALL 从本地已有部分继续下载，不重新开始。
2. WHEN 本地残留文件与服务器内容不匹配，系统 SHALL 清除残留并从零开始下载。
3. WHEN 用户点击停止，系统 SHALL 终止当前及后续下载任务，并保留已完成的文件。

### 需求 7：下载后可选择从收藏夹删除视频

**User Story:** 作为用户，我希望下载成功后可以选择自动从收藏夹删除该视频，以便整理收藏。

#### Acceptance Criteria

1. WHEN 用户开启"下载后删除"选项，系统 SHALL 在每个视频下载成功后调用收藏夹删除接口。
2. WHEN 删除接口因缺少 `bili_jct` 凭证而失败，系统 SHALL 记录失败并继续处理下一个视频。
3. WHEN 删除失败，系统 SHALL 保留该视频在收藏夹中的记录并记录日志。

### 需求 8：已下载去重

**User Story:** 作为用户，我希望重复运行下载任务时跳过已下载的视频，以便节省时间和流量。

#### Acceptance Criteria

1. WHEN 拉取到视频列表，系统 SHALL 跳过 `bvid` 已在已下载记录中的视频。
2. WHEN 拉取到视频列表，系统 SHALL 跳过保存目录中存在同名 MP4 的视频。
3. WHEN 跳过已下载视频，系统 SHALL 将跳过数量计入进度条完成数。

### 需求 9：自动压缩为 zip

**User Story:** 作为用户，我希望全部视频下载完成后自动打包为 zip，以便一次性传输或备份。

#### Acceptance Criteria

1. WHEN 全部下载任务完成且成功数大于 0，系统 SHALL 将保存目录下所有 MP4 压缩为 zip 文件。
2. WHEN 生成 zip，系统 SHALL 使用递增编号命名（`1.zip`、`2.zip`…），不覆盖已存在文件。
3. WHEN zip 完整性校验通过，系统 SHALL 删除源 MP4 文件。
4. IF zip 完整性校验失败，系统 SHALL 保留源 MP4 文件并记录错误。

### 需求 10：进度展示与日志

**User Story:** 作为用户，我希望实时看到下载进度和日志，以便了解任务状态。

#### Acceptance Criteria

1. WHEN 下载任务运行，系统 SHALL 实时显示当前视频标题、下载百分比/字节数与总体进度。
2. WHEN 下载任务运行，系统 SHALL 在日志区域追加显示关键事件（开始、完成、失败、跳过）。
3. WHEN 日志追加，系统 SHALL 自动滚动到最新日志行。
4. WHEN 发生错误，系统 SHALL 在日志中记录错误码与错误信息，并给出可读提示。
5. WHEN 写入日志，系统 SHALL 对敏感凭证（`SESSDATA`、`bili_jct`）进行脱敏，日志内容不得出现凭证明文或可还原片段。

### 需求 11：后台持续下载

**User Story:** 作为用户，我希望切换应用或锁屏后下载仍能继续，以便长时间批量下载。

#### Acceptance Criteria

1. WHEN 下载任务运行，系统 SHALL 启动前台服务并显示常驻通知。
2. WHEN 应用进入后台，系统 SHALL 保持下载任务继续执行。
3. WHEN 用户点击通知，系统 SHALL 回到下载进度页面。
4. WHEN 用户从通知或应用内点击停止，系统 SHALL 终止下载并移除前台服务。
5. WHEN 应用在前台服务可用的 Android 版本上启动下载，系统 SHALL 声明并注册对应前台服务类型（媒体处理），确保长任务不被系统限制。
6. WHEN 应用运行于 Android 13 及以上，系统 SHALL 在首次启动下载前申请通知权限。
7. WHEN 用户允许，系统 SHALL 引导用户将应用加入电池优化白名单，以保障长时间下载不被系统中断。

### 需求 12：下载目录与文件导出

**User Story:** 作为用户，我希望下载文件保存在应用内可管理的位置，并能导出到自己选择的文件夹。

#### Acceptance Criteria

1. WHEN 下载视频，系统 SHALL 将文件保存到应用专属下载目录（`/sdcard/Android/data/<包名>/files/downloads/`）。
2. WHEN 用户点击"导出到"，系统 SHALL 打开系统文件选择器（Storage Access Framework）供用户选择导出目标目录。
3. WHEN 用户选定导出目录，系统 SHALL 将所选视频或 zip 复制到该目录。
4. WHEN 下载视频，系统 SHALL 以视频标题清理非法字符后作为文件名。
5. WHEN 生成临时文件，系统 SHALL 在合并成功后清理视频流与音频流临时文件。

### 需求 13：下载清晰度选择

**User Story:** 作为用户，我希望选择视频下载清晰度，以便在画质与流量消耗之间权衡。

#### Acceptance Criteria

1. WHEN 用户打开下载设置，系统 SHALL 提供清晰度选项（如 360P、480P、720P、1080P、4K），默认 1080P。
2. WHEN 下载视频，系统 SHALL 按所选清晰度请求对应画质的视频流。
3. IF 所选清晰度需要大会员而当前账号无权限（播放地址接口拒绝），系统 SHALL 提示"该清晰度需要大会员"并允许用户降低清晰度后重试。

### 需求 14：收藏夹内多选视频下载

**User Story:** 作为用户，我希望在收藏夹内勾选部分视频下载，以便只下载需要的视频。

#### Acceptance Criteria

1. WHEN 用户选定收藏夹，系统 SHALL 展示收藏夹内视频列表（标题、时长），支持勾选。
2. WHEN 用户勾选部分视频并点击下载，系统 SHALL 仅下载被勾选的视频。
3. WHEN 用户未勾选任何视频，系统 SHALL 默认下载收藏夹内全部视频。

### 需求 15：网络状态处理

**User Story:** 作为用户，我希望控制蜂窝流量消耗，并在网络中断时自动暂停恢复，以便节约流量和避免下载失败。

#### Acceptance Criteria

1. WHEN 用户开启"仅 WiFi 下载"选项，系统 SHALL 在检测到蜂窝网络时暂停下载并提示原因。
2. WHEN 暂停后网络恢复为 WiFi，系统 SHALL 自动继续未完成的下载。
3. WHEN 网络连接断开，系统 SHALL 暂停下载；WHEN 网络恢复，系统 SHALL 自动继续下载。

### 需求 16：失败重试与临时文件管理

**User Story:** 作为用户，我希望偶发网络抖动导致的失败能自动恢复，以便减少手动干预。

#### Acceptance Criteria

1. WHEN 单个视频下载或合并失败，系统 SHALL 自动重试该视频（默认 2 次），重试间隔递增。
2. WHEN 重试仍失败，系统 SHALL 记录失败原因并继续下一个视频。
3. WHEN 视频流下载中断，系统 SHALL 保留已下载的临时文件，供下次任务断点续传。
4. WHEN 视频合并失败，系统 SHALL 保留视频流与音频流临时文件用于排查，并记录日志。

### 需求 17：合规与隐私

**User Story:** 作为用户，我希望应用说明其使用边界与数据处理方式，以便放心使用。

#### Acceptance Criteria

1. WHEN 用户首次启动应用，系统 SHALL 展示免责声明，说明应用仅用于个人学习用途、下载内容不得传播。
2. WHEN 用户首次启动应用，系统 SHALL 展示隐私说明，说明登录凭证加密存储于本地且不向第三方上传。
3. WHEN 用户点击同意，系统 SHALL 进入主界面；IF 用户拒绝，系统 SHALL 退出应用。

### 需求 18：已下载视频管理

**User Story:** 作为用户，我希望查看已下载的视频列表并支持本地播放、删除，以便管理离线内容。

#### Acceptance Criteria

1. WHEN 应用打开"已下载"页面，系统 SHALL 展示保存目录中的视频列表（标题、大小）。
2. WHEN 用户点击某个视频，系统 SHALL 通过本地播放器播放该视频。
3. WHEN 用户在列表中选择删除，系统 SHALL 删除对应文件并更新列表。
4. WHEN 应用记录下载历史，系统 SHALL 展示历史任务（时间、收藏夹、成功/失败数），支持重新运行。

### 需求 19：下载引擎选择

**User Story:** 作为用户，我希望在设置中选择下载引擎，以便在 Gopeed 引擎与内置下载器之间切换。

#### Acceptance Criteria

1. WHEN 用户打开设置，系统 SHALL 提供下载引擎选项（Gopeed 引擎 / 内置下载器），默认内置下载器。
2. IF 应用未检测到可用的 Gopeed 引擎二进制，系统 SHALL 禁用"Gopeed 引擎"选项并提示原因，用户无法切换到该引擎。
3. WHEN 用户切换引擎，系统 SHALL 从下一个下载任务开始使用所选引擎。
4. WHEN 使用 Gopeed 引擎，系统 SHALL 启动内嵌引擎进程并通过其 API 管理下载任务。
5. WHEN 使用内置下载器，系统 SHALL 使用应用内实现的限速与断点续传逻辑下载。
6. IF 任务启动时选择 Gopeed 引擎但二进制不可用，系统 SHALL 回退到内置下载器并提示用户。

### 需求 20：WebDAV 配置

**User Story:** 作为用户，我希望在设置中配置 WebDAV 服务器信息，以便上传下载的压缩包。

#### Acceptance Criteria

1. WHEN 用户打开设置，系统 SHALL 提供 WebDAV 服务器地址、用户名、密码输入项。
2. WHEN 用户保存配置，系统 SHALL 对密码使用 AES 加密后存储（与 Cookie 相同机制），存储内容不包含明文密码。
3. WHEN 用户点击"测试连接"，系统 SHALL 向配置的 WebDAV 地址发起连接测试并反馈结果。
4. IF 地址、用户名或密码为空，系统 SHALL 禁止启用自动上传并提示补全配置。

### 需求 21：下载完成自动上传 zip（可选）

**User Story:** 作为用户，我希望下载任务完成后自动将 zip 上传到配置的 WebDAV 服务器，以便备份到其他设备。

#### Acceptance Criteria

1. WHEN 用户开启"自动上传 zip"选项，系统 SHALL 在任务全部完成且 zip 打包成功后上传 zip 到 WebDAV。
2. WHEN 上传开始，系统 SHALL 实时显示上传进度。
3. WHEN 上传成功，系统 SHALL 记录成功日志并提示用户。
4. IF 上传失败（网络或服务器错误），系统 SHALL 记录失败原因并保留本地 zip，允许用户手动重试上传。
5. WHILE 上传进行中，系统 SHALL 保留本地 zip 文件，不因上传而删除。
