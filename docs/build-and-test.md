# 构建与验证

OMyMoney 是独立原生应用，包名 `app.omymoney.android`，版本 `0.1.0 (1)`，最低 API 26、目标/编译 API 36。不覆盖钱迹，也不读取钱迹私有数据库。功能范围、实际验证边界分别见 `full-coverage.json` 和 `known-differences.md`。

## 可重复构建

需要 JDK 17、Android SDK platform 36、build-tools 36.0.0、platform-tools；接受 SDK 自身许可后安装。设置 `JAVA_HOME`、`ANDROID_HOME`，或使用未提交的 `local.properties` 指定 SDK 路径。Gradle Wrapper、版本目录、Room schema 与依赖 SHA-256 校验元数据均在仓库。首次构建下载依赖；已缓存依赖可加 `--offline`。不要关闭依赖校验来解决下载失败。

```sh
./gradlew --console=plain :core:test :app:assembleDebug :app:lintDebug
ANDROID_SERIAL=emulator-5560 ./gradlew --console=plain \
  :data:connectedDebugAndroidTest :app:connectedDebugAndroidTest
sh scripts/verify-offline.sh
ruby scripts/test-release-verifier.rb
```

明确指定自己创建的测试设备序列号。`connectedDebugAndroidTest` 会安装/卸载测试应用，不能针对存放真实账目的手机随意运行。实际 XML 报告分别在 `core/build/test-results/test`、`data/build/outputs/androidTest-results/connected/debug`、`app/build/outputs/androidTest-results/connected/debug`；不要仅凭 Gradle 的开始条目或 shell 退出码计算通过数。

## 专用设备检查

默认测试套件有显式跳过的设备用例。它们涉及真实权限、系统凭据、进程终止、短横屏、桌面宿主、DocumentsUI 和 TalkBack，需在专用模拟器单独执行，不能把默认跳过算作通过。

构建并安装 `app-debug.apk` 与 `app-debug-androidTest.apk` 后，集成恢复流程可这样运行：

```sh
adb -s emulator-5560 shell am instrument -w -r \
  -e class app.omymoney.FullAcceptanceTest -e fullPhase prepare \
  app.omymoney.android.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5560 shell am force-stop app.omymoney.android
adb -s emulator-5560 shell am instrument -w -r \
  -e class app.omymoney.FullAcceptanceTest -e fullPhase verify \
  app.omymoney.android.test/androidx.test.runner.AndroidJUnitRunner
```

两个阶段均须出现测试结果 `OK (1 test)`。该用例通过正式仓库接口组合各模块，随后检查 Activity 重建和跨进程业务 JSON 摘要；它不代替每个表单的 UI 或系统凭据测试。真实 TalkBack 用例为 `TalkBackAcceptanceTest`，仅在安装并启用真实 TalkBack 后传 `-e talkbackQa true`，保留其他无障碍服务，不使用假服务替代。

## 独立签名 Release

发布脚本不自动生成或选择调试密钥。需要自己的 PKCS12/JKS 文件、别名和仅本机可读的密码文件；这些不得提交、放入源码 ZIP 或发送到远端。已交付构建使用独立本地 RSA3072 证书；后续原位更新必须保持同一签名，否则先在旧版导出备份再处理迁移，不能直接卸载存有账目的旧版。

```sh
export OMYMONEY_KEYSTORE=/absolute/private/path/omymoney-release.p12
export OMYMONEY_KEYSTORE_PASSWORD_FILE=/absolute/private/path/password
export OMYMONEY_KEY_ALIAS=omymoney
sh scripts/build-release.sh
```

输出为 `outputs/OMyMoney.apk`。脚本构建非调试 release，按 16KB ZIP 边界对齐 native libraries，签名并读回证书，解码最终 APK Manifest 和实际备份规则资源，验证包名/API/调试入口/网络权限，并写入 SHA-256。ZIP 对齐不是对所有处理器或物理 16KB 内核的兼容性承诺；运行证据另行记录。

`outputs/signature.txt`、`release-manifest.xml`、`data-extraction-rules.xml` 和 `OMyMoney.apk.sha256` 为实际读回值。私钥保留在构建机器的忽略目录 `work/signing/`，不在 APK 或交付源码中。不要将阶段 A/B 的 debug APK 与最终 release APK 混淆。

## 依赖与许可

```sh
./gradlew --console=plain -I scripts/dependency-inventory.init.gradle :app:releaseDependencyInventory
ruby scripts/collect-licenses.rb
```

第二条使用 `GRADLE_USER_HOME` 下的实际 Maven 缓存，解析当前模块及父 POM 的许可证，并保留原始 JAR/AAR notice。生成的资产需要在重新打包前保留。CDDL StAX 的原始 source JAR、CDDL 正文以及已有 Material Icon 正文均随源码与 APK 提供；脚本不会擅自改写这些第三方正文。详情见 `third-party-notices.md`。

## 数据与安全边界

Room schema 10，完整 ZIP schema 9（仍可读取旧版本归档）。所有真实保存、导入及批量操作仍走公共精确分录和事务校验。已识别付款允许资金账户暂缺，此时不生成该账户的资产分录。发布包没有测试 provider 或 instrumentation 入口。系统自动备份关闭，导出 ZIP 明文且由用户主动选择存放位置。0.2 为独立诊断和 APK 更新声明网络权限，但禁止明文 HTTP；ML Kit 可能发送上游指标，系统文件提供方也可独立同步。隐私锁、诊断配对、更新设置和当前设备的支付窗口连续性缓存不在业务 ZIP 中；恢复业务 ZIP 不会关闭隐私锁。

当前开发机证据留在忽略目录 `work/build-logs` 和 `work/qa`；交付报告保留最终结果及关键截图。API 26、34、36 是不同官方系统镜像，不是三台真机，更不能证明所有厂商后台策略或支付 App 版本可用。
