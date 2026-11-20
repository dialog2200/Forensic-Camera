# 区块链取证摄像头（Forensic-Camera）

Android 取证相机：**无服务端**架构，录制即加密、哈希上链、离线可核验。

- Gitee 镜像：https://gitee.com/lanyuancloud/blockchain-forensic-camera
- 链上核验：https://zxscan.qq.com

## 核心特性

- **无服务端闭环**：采集、加密、哈希、上链全部在设备本地完成，直连至信链网关（`https://open.zxchain.qq.com`），不存在任何自建上传接口或服务端。
- **加密取证（fmt=E1，默认关闭）**：Camera2 + MediaCodec 双路编码，媒体分片 AES-256-GCM 加密落盘（自写 fMP4 封装 + mbedTLS 最小子集），录制全程明文不落沙盒。
- **三层密钥体系**：每条录制一次一密 DEK（仅存内存，用完清零）→ Android Keystore 包裹（StrongBox 优先，降级 TEE，密钥不可导出）→ 可选 SM2 公钥托管（`dek.escrow.json`，私钥持有方可在 PC 离线解封还原）。
- **段链抗篡改**：分片间以 SHA-256 链式绑定，段链校验**不需要密钥**——可严格区分"密钥不可得"与"密文被篡改"两种结论。
- **链上锚定**：证据锚定哈希（文件哈希+设备+时间+时长+APK哈希）经 HMAC-SM3 / SM2 签名上链至至信链，公开可核验；APK 版本经链上登记簿绑定 versionCode、APK 哈希与证书指纹。
- **硬件证明**：证据签名使用 Android 硬件 Keystore，支持 Key Attestation（锚定哈希作为 challenge 嵌入）。
- **向后兼容**：旧证据（fmt=F0）走原有明文路径，不受影响。

## 目录结构

```
app/src/main/java/com/forensic/
  capture/          UI（Compose）与录制状态机
  camera/           相机与双路编码
  enc/              加密证据格式、密钥库、原生桥（NativeEnc）
  crypto/           国密（SM2/SM3，BouncyCastle）
  zxchain/          至信链网关客户端（SM2 签名鉴权）
  anchor/           锚定与核验
  data/             证据台账、设置（敏感项 Keystore 包裹存储）
app/src/main/cpp/
  writer/mp4/crypto JNI 原生层：fMP4 封装、GCM 分片加解密、段链
  third_party/mbedtls  仅编译所需源文件子集（Apache-2.0，见其 LICENSE）
```

## 构建

要求：JDK 17、Android SDK（compileSdk 34）、NDK 26.1.10909125、CMake 3.22.1。

```bash
# local.properties 写入 sdk.dir=<你的 Android SDK 路径>
./gradlew :app:assembleRelease
```

> 注意：Windows 下 CMake 对非 ASCII 路径存在崩溃问题，建议在纯英文路径构建。

## 版本与链上登记

| 版本 | versionCode | 链上登记 evId |
|---|---|---|
| v3.4.5 | 11 | `ee4ac50ae23711f1a305eeb2661d065b` |
| v3.4.3 | 9 | `c9b41b4fe19a11f1b5eebe689d0b305b` |

公开核验：`https://zxscan.qq.com/cert/ev?evId=<evId>`

## 许可

- 本项目采用 **GPL-3.0**（见 LICENSE）。
- 内置 mbedTLS 子集按其 **Apache-2.0** 许可保留于 `app/src/main/cpp/third_party/mbedtls/LICENSE`。
- BouncyCastle（SM2/SM3）按其 MIT 类许可由 Gradle 依赖引入。

## 安全声明

密钥保护根位于设备 StrongBox/TEE 硬件内，任何软件层（含本仓库源码与编译产物）均不含可导出的主密钥。换机或清除应用数据后，未经 SM2 托管的历史证据不可解密——这是设计代价，已在架构文档中如实声明。
