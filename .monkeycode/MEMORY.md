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
