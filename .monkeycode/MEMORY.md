# User Instruction Memory

This file records user instructions, preferences, and teachings for reference in future interactions.

## Format

### User Instruction Entry
User instruction entries should follow this format:

[User Instruction Summary]
- Date: [YYYY-MM-DD]
- Context: [Mentioned scenario or time]
- Instructions:
  - [Content of user teaching or instruction, described line by line]

### Project Knowledge Entry
Entries discovered by the Agent during task execution should follow this format:

[Project Knowledge Summary]
- Date: [YYYY-MM-DD]
- Context: Discovered by Agent while performing [specific task description]
- Category: [Operations & Deployment|Build Methods|Testing Methods|Troubleshooting & Debugging|Workflow & Collaboration|Environment Configuration]
- Instructions:
  - [Specific knowledge points, described line by line]

## Deduplication Strategy
- Before adding a new entry, check for similar or identical instructions.
- If a duplicate is found, skip the new entry or merge it with the existing one.
- When merging, update the context or date information.
- This helps avoid redundant entries and keeps the memory file tidy.

## Entries

[Project Knowledge Summary]
- Date: 2026-09-01
- Context: Discovered by Agent while making Android 端日志导出与 `openssl cms` 解密互操作（BouncyCastle CMS 加密）
- Category: Troubleshooting & Debugging
- Instructions:
  - `openssl cms -encrypt` 的默认输出与 `openssl cms -decrypt` 默认读取的 SMIME 是「MIME 头 + base64 每 64 字符换行」格式；`-----BEGIN CMS-----` 纯 base64 是 PEM 格式（需 `-inform PEM`），不是 SMIME。
  - BouncyCastle 做 GCM 加密必须用 `CMSAuthEnvelopedDataGenerator`（RFC 5083 AuthEnvelopedData，contentType=id-smime-ct-authEnvelopedData），并把 `JceCMSContentEncryptorBuilder(AES256_GCM).build()` 强转为 `OutputAEADEncryptor`；用普通 `CMSEnvelopedDataGenerator` 塞 GCM 会生成 openssl 无法正确解密的非标准结构（解出乱码）。
  - BouncyCastle 的 CMS 输出默认是 BER（不定长编码），openssl SMIME 读取器要求 DER，需用 `ASN1Primitive.fromByteArray(ber)` + `ASN1OutputStream.create(bos, ASN1Encoding.DER)` 强制转 DER（注意 `ASN1Primitive.getEncoded()` 在 BC 1.85 下可能仍返回 BER，必须用显式 ASN1OutputStream）。
  - `openssl cms -encrypt -recip` 需要 X509 证书（裸公钥不认）；`openssl cms -decrypt` 只需 `-inkey` 私钥，`-recip` 可省略。
  - BouncyCastle 1.85 起三个 jar（bcprov/bcpkix/bcutil）都含 `META-INF/LICENSE.md`/`NOTICE.md`，Android packaging 需排除 `/META-INF/LICENSE.md` 与 `/META-INF/NOTICE.md`，否则 `mergeDebugJavaResource` 冲突。
- Note: BouncyCastle 是 Android 上唯一支持 openssl CMS(S/MIME) 互操作的库；Conscrypt 仅 TLS、Google Tink 无 CMS 支持，不要为 CMS 场景换库。

[Project Knowledge Summary]
- Date: 2026-09-11
- Context: Discovered by Agent while 将 Gopeed 下载引擎从"子进程 exec 外部二进制"改造成 Android 进程内 gomobile 绑定库（修复 Android 10+ exec 被禁导致引擎不可用）
- Category: Build Methods
- Instructions:
  - 环境：NDK 位于 `/opt/ndk/android-ndk-r27c`，Android SDK 位于 `/opt/android-sdk`（`local.properties` 的 `sdk.dir`）。
  - 重建引擎库命令（须在 gopeed 源码目录执行；先用 `go get -tool golang.org/x/mobile/cmd/gobind` 给目标模块加 tool 依赖，否则新版 gomobile 报 `missing golang.org/x/mobile dependency`）：
    `ANDROID_HOME=/opt/android-sdk ANDROID_NDK_HOME=/opt/ndk/android-ndk-r27c gomobile bind -tags nosqlite -ldflags="-w -s -checklinkname=0" -o app/libs/libgopeed.aar -target=android/arm64 -androidapi 26 -javapkg=com.gopeed github.com/GopeedLab/gopeed/bind/mobile`
  - 该命令产出的 aar 提供 `com.gopeed.libgopeed.Libgopeed`（静态方法 `touch`/`start`/`stop`/`invokeAsync` 等），对应 `lib/arm64-v8a/libgojni.so`（ELF64 AArch64 DYN）。
  - 引擎调用约定：`Libgopeed.start(cfgJson)` 只初始化进程内运行时（storage=bolt + storageDir 持久化），NativeMode 下 HTTP 端口默认为 0 且不监听；请求走 `Libgopeed.invokeAsync(method,path,query,body,requestId,callback)` → gopeed `rest.Dispatch`，path 用完整 `/api/v1/...`，无需令牌。`invokeAsync` 的回调式 API 由 `NativeGopeedTransport` 包装为挂起函数。
  - R8 必须 `-keep class com.gopeed.** { *; }`（JNI 反射注册）；`app/build.gradle.kts` 的 `defaultConfig.ndk.abiFilters += "arm64-v8a"`。
  - so 未压缩 45MB、压缩后约 15MB，`packaging.jniLibs.useLegacyPackaging = true` 让 APK 从 55MB 降到 25MB。
  - 旧的 `assets/gopeed/gopeed-arm64`（约 43MB）已废弃，文件已移至 `/tmp/gopeed-assets-backup-20260911/`。

[User Instruction Summary]
- Date: 2026-09-18
- Context: 用户要求在主界面加"上传调试日志 db"按钮，实现初稿把 WebDAV 地址/账号写进了 Kotlin 常量
- Instructions:
  - 凭据（地址、用户名、密码等）一律不写进源码，改用外置文件：放到 `app/src/main/assets/` 下的配置文件并加入 `.gitignore`，源码只保留文件名与键名，运行时读取。
  - 提供该外置文件时同样不要写进版本控制，避免凭据入库。

[User Instruction Summary]
- Date: 2026-09-19
- Context: 用户在多轮迭代后提出构建范围约束
- Instructions:
  - 除非用户明确要求，否则只编译 release 变体（`assembleRelease`），不再默认同时编 debug。
  - 用户显式要求 debug 时才执行 debug 构建。
  - 发版流程仍按需上传 APK，但构建范围以上述规则为准。
