# 账务基础与 Material 3 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 交付可安装、可持久保存、账务正确的个人本地记账 App，包含完整 M3 基础交互，不以静态原型代替功能。

**Architecture:** 纯 JVM `core` 负责业务规则，Android `data` 负责 Room 与事务，`app` 负责 Compose Material 3 与系统入口。三者通过总计划定义的账单模型和 `LedgerRepository` 连接。

**Tech Stack:** Kotlin、Compose Material 3、Room、Coroutines/Flow、JUnit、Compose UI Test、AndroidX Test。依赖由 A1 选择官方稳定且兼容的固定版本。

**Spec:** [已认可规格](本地版实现规格.md)；[总计划与公共接口](实施计划.md)。

## Global Constraints

- 包名 `app.omymoney.android`，应用名“OMyMoney”，minSdk 26。
- Material Design 3 / Material You；Android 12 起动态色，浅深色及旧系统回退；48dp 点击目标，200% 字体测试，字间距 0。
- 不接本地或云 AI、不申请网络权限、不接钱迹服务器、不上传数据。
- 精确金额、事务保存、明确交易时间来源；删除和恢复可核验。
- 路径相对总计划定义的 OMyMoney 项目根目录。测试包名与被测模块一致，所有测试命令从工程根目录运行。

## Review Focus

- A2：小数精度、不同币种相加与 Long 溢出应拒绝，而非自动截断。
- A3：重复提交、旧修订号写入与中途失败不应重复改变账户余额。
- A5：换账本与旋转屏幕后必须保留当前上下文，不串账。
- A6：键盘、大字体、进程重建和返回动作不能让草稿消失或无法保存。
- A8：损坏备份及危险附件路径必须在覆盖数据之前被拒绝。

---

### Task 1: A1 可重复构建与真实首页

**Files:** 创建 `settings.gradle.kts`、`build.gradle.kts`、`gradle/libs.versions.toml`、`core/build.gradle.kts`、`data/build.gradle.kts`、`app/build.gradle.kts`、`app/src/main/AndroidManifest.xml`、`app/src/main/kotlin/app/omymoney/MainActivity.kt`、`app/src/main/kotlin/app/omymoney/ui/OMyMoneyApp.kt`、`app/src/androidTest/kotlin/app/omymoney/LaunchTest.kt`。

**Interfaces:** 产生 `@Composable fun OMyMoneyApp()`；`AppDestination` 固定 `BILLS, ASSETS, STATS, SETTINGS`。占位目的地不显示假数据或假可用按钮，后续任务替换为真实页面。

- [ ] 建立 Wrapper、三个模块和最小测试入口，写 `LaunchTest.noLoginRequired`：`onNodeWithText("账单").assertExists()`，四个主目的地均可到达；合并 Manifest 不含 INTERNET。
- [ ] 运行 `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.omymoney.LaunchTest`，记录尚未实现首页行为时的失败。
- [ ] 实现本地首页和主导航；选定兼容的稳定依赖，固定版本与校验信息；设置 `allowBackup=false`。建立独立 Git 仓库及忽略规则，不提交机器路径和密钥。
- [ ] 运行 `./gradlew :app:assembleDebug :app:lintDebug :app:connectedDebugAndroidTest`，确认构建及测试通过，实际安装可启动。
- [ ] 仅暂存本任务文件，提交 `build: bootstrap offline Material 3 Android app`。

### Task 2: A2 金额模型与基础账务

**Files:** 创建 `core/src/main/kotlin/app/omymoney/core/model/Money.kt`、`core/src/main/kotlin/app/omymoney/core/model/Transaction.kt`、`core/src/main/kotlin/app/omymoney/core/model/Catalog.kt`、`core/src/main/kotlin/app/omymoney/core/ledger/PostingCalculator.kt`；测试 `core/src/test/kotlin/app/omymoney/core/ledger/PostingCalculatorTest.kt`、`core/src/test/kotlin/app/omymoney/core/model/MoneyTest.kt`。

**Interfaces:** 使用总计划公共类型；产生 `Money.parse(text: String, currency: CurrencySpec): Money`、`PostingCalculator.calculate(draft: TransactionDraft): List<Posting>`。目录模型定义 `Book`、`Category(parentId: String?)`、`AssetAccount(currency: CurrencySpec)`。未完成的复杂类型明确返回不支持，不走默认支出分支。

- [ ] 写断言：`parse("0.10") + parse("0.20") == parse("0.30")`；CNY 的 `"1.001"`、币种混加、溢出均失败；100 元账户支出 12.30 后为 87.70；转账 20 元两侧合计变化为 0。
- [ ] 运行 `./gradlew :core:test --tests '*MoneyTest' --tests '*PostingCalculatorTest'`，确认失败来自待实现行为。
- [ ] 实现精确解析、非负草稿金额校验、收入支出和同币种转账分录；模型预留复杂类型和关系，UI 暂不启用它们。
- [ ] 重跑上述命令，确认所有断言通过；增加不同精度币种和负分录的回归用例。
- [ ] 提交 `feat: add exact money and ledger posting rules`。

### Task 3: A3 数据库与事务保存

**Files:** 创建 `core/src/main/kotlin/app/omymoney/core/ledger/LedgerRepository.kt`、`data/src/main/kotlin/app/omymoney/data/LedgerDatabase.kt`、`data/src/main/kotlin/app/omymoney/data/LedgerDao.kt`、`data/src/main/kotlin/app/omymoney/data/RoomLedgerRepository.kt`、`data/src/main/kotlin/app/omymoney/data/LedgerEntities.kt`；测试 `data/src/androidTest/kotlin/app/omymoney/data/LedgerTransactionTest.kt`。

**Interfaces:** 实现总计划的 `save/observeBills/delete/undo`；账单修订号和幂等提交 ID 在数据库有唯一约束，保存账单与分录在同一事务内完成。

- [ ] 写断言：相同提交 ID 保存两次仅一笔；旧修订号返回 `Conflict`；插入分录中途异常后 `billCount == 0` 且余额不变；修改金额先撤旧分录；删除后撤销恢复一次且不重复。
- [ ] 运行 `./gradlew :data:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.omymoney.data.LedgerTransactionTest`，记录失败。
- [ ] 实现 Room 实体、DAO、事务、索引与 repository；启用 schema 导出，关闭破坏性迁移回退。删除账户时有历史关联则归档，不静默删账。
- [ ] 重跑数据库测试并新增文件数据库重开断言，确认账单、余额及修订号持久化。
- [ ] 提交 `feat: persist ledger with atomic idempotent writes`。

### Task 4: A4 Material 3 设计系统与可访问导航

**Files:** 创建 `app/src/main/kotlin/app/omymoney/ui/theme/LedgerTheme.kt`、`app/src/main/kotlin/app/omymoney/ui/theme/DesignTokens.kt`、`app/src/main/kotlin/app/omymoney/ui/navigation/LedgerNavigation.kt`、`app/src/main/res/values/strings.xml`；修改 `ui/OMyMoneyApp.kt`；测试 `app/src/androidTest/kotlin/app/omymoney/MaterialThemeTest.kt`。

**Interfaces:** `LedgerTheme(themeMode: ThemeMode, dynamicColor: Boolean, content: @Composable () -> Unit)`；`ThemeMode.SYSTEM, LIGHT, DARK`。`LedgerNavigation(selected: AppDestination, onSelect: (AppDestination) -> Unit)`。

- [ ] 写断言：四个目的地语义可点击且选中状态唯一；图标按钮有名称；点击目标不少于 48dp；浅深色、动态色关闭、旧系统回退均可显示正文；字体样式字间距为 0。
- [ ] 运行 `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.omymoney.MaterialThemeTest`，确认缺失状态测试失败。
- [ ] 用官方 M3 组件与语义颜色实现主题、导航栏和大窗口导航轨；布局适配 Insets，不嵌套装饰卡片。状态反馈遵守原生组件行为。
- [ ] 重跑测试，在 360dp 和 600dp 宽度、浅深色及 200% 字体下实际截图检查；不以截图生成成功替代人工看图。
- [ ] 提交 `feat: implement coherent Material You design system`。

### Task 5: A5 账本分类账户与首次初始化

**Files:** 创建 `app/src/main/kotlin/app/omymoney/feature/catalog/CatalogViewModel.kt`、`app/src/main/kotlin/app/omymoney/feature/catalog/CatalogScreens.kt`、`data/src/main/kotlin/app/omymoney/data/CatalogRepository.kt`；测试 `data/src/androidTest/kotlin/app/omymoney/data/CatalogTest.kt`、`app/src/androidTest/kotlin/app/omymoney/CatalogFlowTest.kt`。

**Interfaces:** `CatalogRepository.ensureLocalDefaults(): String` 返回默认账本 ID；提供 `observeBooks()`、`observeAccounts()`、`observeCategories(bookId, kind)` 的 Flow 以及显式增改归档方法。UI 通过 ViewModel 读写，不绕过 repository。

- [ ] 写断言：初始化两次仍只有一个默认账本；不同账本分类隔离；切账本后列表和新草稿使用同一当前账本；被引用分类和账户不能被无提示物理删除。
- [ ] 运行 `./gradlew :data:connectedDebugAndroidTest :app:connectedDebugAndroidTest`，确认 `CatalogTest` 和 `CatalogFlowTest` 因待实现行为失败。
- [ ] 实现账本选择、两级分类、账户与基本排序；首次无登录可记账，不在首次启动索要无障碍或相册权限。
- [ ] 在真实 UI 新建两个账本和账户、切换、重启并回读；测试通过且数据不串账。
- [ ] 提交 `feat: add local books categories and accounts`。

### Task 6: A6 记账表单与草稿恢复

**Files:** 创建 `app/src/main/kotlin/app/omymoney/feature/bills/BillEditorViewModel.kt`、`app/src/main/kotlin/app/omymoney/feature/bills/BillEditorScreen.kt`、`data/src/main/kotlin/app/omymoney/data/DraftRepository.kt`；测试 `app/src/androidTest/kotlin/app/omymoney/BillEditorFlowTest.kt`。

**Interfaces:** `BillEditorViewModel.onAction(action: EditorAction)`、`submit()`、`discard()`；`EditorAction` 在同文件定义为金额文本、类型、分类 ID、账户 ID、时间、备注、标签和附件各自有类型的变更事件。`EditorState` 包含草稿、字段错误、保存状态、dirty 标志和修订号。`DraftRepository` 持久保存未提交输入，最终仍调用 `LedgerRepository.save`。

- [ ] 写断言：输入 12.30 并选择分类后只保存一笔；连续点击不重复；旋转与重建保留金额备注；200% 字体和输入法显示时保存仍可达；返回选择器不丢主表单。
- [ ] 运行 `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.omymoney.BillEditorFlowTest`，记录失败。
- [ ] 实现收支转账分段控件、金额输入、分类和账户选择、时间与备注；处理键盘 Insets、焦点、逐字段校验和可恢复返回。未知识别时间要由用户确认，不默认为事实。
- [ ] 重跑测试，人工走查新增、取消、重开、错误修正；检查保存后原列表位置及 Snackbar 反馈。
- [ ] 提交 `feat: add recoverable accessible bill editor`。

### Task 7: A7 账单列表搜索编辑与附件

**Files:** 创建 `app/src/main/kotlin/app/omymoney/feature/bills/BillListScreen.kt`、`app/src/main/kotlin/app/omymoney/feature/bills/BillListViewModel.kt`、`app/src/main/kotlin/app/omymoney/feature/bills/BillDetailScreen.kt`、`data/src/main/kotlin/app/omymoney/data/AttachmentStore.kt`；测试 `app/src/androidTest/kotlin/app/omymoney/BillLifecycleTest.kt`。

**Interfaces:** 列表消费 `observeBills(BillQuery)`；详情携带账单 ID 和修订号进入 A6；`AttachmentStore.import(uri: Uri): AttachmentRef` 仅复制用户选择的文件到私有目录。

- [ ] 写断言：备注搜索与日期筛选正确；空结果有清除筛选入口；编辑不重复入账；删除可撤销且余额还原；长商户名和大金额不遮挡；添加图片失败不损坏草稿。
- [ ] 运行 `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.omymoney.BillLifecycleTest`，确认失败。
- [ ] 实现分组列表、详情、筛选、编辑和撤销删除；保留滚动位置；用户主动附图通过系统选择器，不批量扫描相册。批量操作在 C9 补充。
- [ ] 重跑测试，关闭进程后重开并核对账单与附件仍可读，失效附件显示可恢复状态。
- [ ] 提交 `feat: add searchable bill lifecycle and attachments`。

### Task 8: A8 本地完整备份与原子恢复

**Files:** 创建 `data/src/main/kotlin/app/omymoney/data/backup/BackupArchive.kt`、`data/src/main/kotlin/app/omymoney/data/backup/RestoreValidator.kt`、`app/src/main/kotlin/app/omymoney/feature/settings/BackupScreen.kt`；测试 `data/src/androidTest/kotlin/app/omymoney/data/BackupRoundTripTest.kt`。

**Interfaces:** `BackupArchive.exportTo(output: OutputStream): BackupSummary`；`inspect(input: InputStream): RestorePreview` 生成受控临时副本和 `previewId`；`restore(previewId: String, mode: RestoreMode): RestoreResult` 只接受仍有效且摘要相同的预览。模式定义为 `MERGE` 与 `REPLACE`，冲突必须在预览中明确处理。归档为有 schemaVersion 和清单校验的 ZIP+JSON，附件只接受规范化内部相对路径。

- [ ] 写断言：导出再恢复后账单、关系、分录和附件摘要相等；损坏 JSON、未知 schema、重复 ID、`../` 路径、过量解压数据被拒绝且原库未变；用户取消不删除数据。
- [ ] 运行 `./gradlew :data:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.omymoney.data.BackupRoundTripTest`，记录失败。
- [ ] 实现流式导出、受限解包、预览与隔离验证、事务恢复；通过 SAF 由用户选择目标，不自动云备份。替换现有账本前明确确认并保留可恢复快照。
- [ ] 重跑测试，实际经系统文件选择器导出并回读；日志与备份不包含无障碍原始密码或完整采集日志。
- [ ] 提交 `feat: add validated local backup and restore`。

### Task 9: A9 首阶段发布与 UX 审查

**Files:** 创建 `docs/feature-coverage.json`、`docs/acceptance-A.md`、`app/src/androidTest/kotlin/app/omymoney/StageAAcceptanceTest.kt`、`scripts/verify-offline.sh`；修复前述任务文件中本阶段发现的问题。

**Interfaces:** 覆盖清单每项有 `scope, implementation, tests, deviceEvidence, knownDifferences`，禁止用一个布尔值把实现和真机验证合并。

- [ ] 写回归测试串联新建账本、记收入支出转账、搜索编辑、删除撤销、重启、备份恢复，核对最终余额；离线验证脚本检查合并 Manifest 的 INTERNET 和备份设置。
- [ ] 运行 `./gradlew :core:test :data:connectedDebugAndroidTest :app:connectedDebugAndroidTest :app:lintDebug`；记录真实缺陷，不把环境失败当成功。
- [ ] 修复并请独立 UX/UI 审查检查表单、导航、可访问性和状态反馈，按严重性处理发现；未指定机型时明确只有模拟器证据。
- [ ] 重跑全部测试，构建安装 `./gradlew :app:assembleDebug`；查看截图并保存设备/API/构建版本与结果。
- [ ] 提交 `test: validate offline ledger foundation and Material UX`，交付首阶段 APK，明确尚未包含 B、C 完整功能。
