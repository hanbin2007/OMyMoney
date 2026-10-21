# 自动记账与 OCR Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在计划 A 的真实账务闭环上实现本地自动记账，不依赖 AI；规则、去重、悬浮确认、OCR 和未来增强接口都有测试。

**Architecture:** 无障碍与图片输入先转成受限快照，经纯 JVM 规则引擎生成草稿；草稿进入持久队列，经用户确认调用 A 的 `LedgerRepository.save`。采集层不得直接修改余额。

**Tech Stack:** Kotlin、Android AccessibilityService、Compose Material 3、Room、打包的 ML Kit 中文 OCR、JUnit、AndroidX Test。

**Spec:** [已认可规格](本地版实现规格.md)；[总计划与公共接口](实施计划.md)。依赖 A9 验收通过。

## Global Constraints

- 不采集密码、不自动付款、不绕过安全窗口、不私自开启权限、不请求网络。
- 已识别、排队、用户确认、已保存、已忽略和失败分开存储；只有保存成功才计入账。
- 56 个静态连接的提取场景是分析目标，不等于真机支持率；每个场景必须列出证据等级。
- 悬浮窗口与设置继续采用 M3，48dp 点击目标、200% 字体可达、返回不丢草稿。
- 所有路径相对 OMyMoney 项目根目录。每个规则的名称和原版定位取自 `docs/qianji-static-inventory.json`，不凭空补数量。

## Review Focus

- B1、B12：未付款、退款处理中、OCR 空白或两个相冲突金额不能被猜成成功交易。
- B2 至 B8：页面文本相似、节点顺序变化与缺少时间需产生可解释结果，而非越界或造值。
- B9：同金额不同商户和无时间候选不能被永久忽略；重复提交不能重复落库。
- B10、B11：权限撤销、锁屏、后台限制与焦点变化不能泄漏账单或覆盖当前输入。
- B13：将来增强结果非法、迟到或超时，不得越过校验或覆盖用户手工输入。

## 规则接口

B1 定义 `UiSnapshot(packageName: String, windowId: Int, capturedAt: Instant, nodes: List<UiNode>)`、`UiNode(text: String?, description: String?, viewId: String?, className: String?, parentIndex: Int?)` 及 `RecognitionResult`。`PageRule` 接口有 `id: String`、`packageName: String`、`priority: Int` 三个属性，以及 `matches(snapshot: UiSnapshot): Boolean` 和 `extract(snapshot: UiSnapshot): RecognitionResult` 两个方法。

`RecognitionResult` 区分 `Matched(draft: TransactionDraft, evidence: List<FieldEvidence>, ruleId: String)`、`Unsupported(reason: String)`、`NeedsReview(partialDraft: TransactionDraft, issues: List<FieldError>)`。`FieldEvidence(field: String, nodeIndices: List<Int>, rawText: String)` 保留字段依据，仅在本机受限处理中使用。`RuleEngine.recognize(snapshot: UiSnapshot): RecognitionResult` 必须确定性执行；不让 OCR 原文或页面文案变成可执行指令。

B2 至 B8 各自产生 `XxxRules.create(): List<PageRule>`，只消费 B1 接口。正反例使用对应 `core/src/test/resources/recognition/<app>/` 中的 JSON 节点快照，包含预期字段与支付状态；每条规则至少一个正例和一个容易误判的反例。合成样本通过只记“样本通过”，不能写“真机通过”。

---

### Task 1: B1 快照模型与确定性规则引擎

**Files:** 创建 `core/src/main/kotlin/app/omymoney/core/recognition/UiSnapshot.kt`、`RuleEngine.kt`、`PageRule.kt`、`FieldEvidence.kt`（后三者同目录）；测试 `core/src/test/kotlin/app/omymoney/core/recognition/RuleEngineTest.kt`。

**Interfaces:** 产生上述模型及接口；规则执行包含文本、描述、ID、类型、组合条件和有界相邻节点提取。

- [ ] 写断言：相同快照输出一致；缺少金额不产生 0 元账单；未支付返回待核对或不支持；节点缺失不越界；金额带依据节点；多金额冲突不擅自选最大值。
- [ ] 运行 `./gradlew :core:test --tests '*RuleEngineTest'`，确认失败。
- [ ] 实现不可变快照、优先级匹配、字段清洗、精确金额及时间解析；含非交易文本的快照只作为数据处理。
- [ ] 重跑测试，验证边界节点、超长文本、未知包名和日期解析失败。
- [ ] 提交 `feat: add deterministic evidence-backed recognition engine`。

### Task 2: B2 微信规则

**Files:** 创建 `core/src/main/kotlin/app/omymoney/core/recognition/rules/WeChatRules.kt`、`core/src/test/kotlin/app/omymoney/core/recognition/WeChatRulesTest.kt` 及微信 JSON fixtures。

**Interfaces:** `WeChatRules.create(): List<PageRule>`，12 个已连接的 pageType；密码页不生成规则或采集字段。

- [ ] 为支付、账单、转账、红包、充值和提现逐条写断言；`"待付款"` 不入账，12.30 金额精确，缺时间保留未知，待收与已收不混同。
- [ ] 运行 `./gradlew :core:test --tests '*WeChatRulesTest'`，确认对应规则未实现而失败。
- [ ] 对照 `q9` 提取器、`z9` 匹配器和相关 Smali 独立实现每条规则，记录原版字段依据及无法确认的细节。
- [ ] 重跑测试，检查所有 12 个规则 ID 有正反例及可追溯证据；缺证据的规则标为未验收。
- [ ] 提交 `feat: implement verified WeChat transaction rules`。

### Task 3: B3 支付宝规则

**Files:** 创建 `core/src/main/kotlin/app/omymoney/core/recognition/rules/AlipayRules.kt`、`core/src/test/kotlin/app/omymoney/core/recognition/AlipayRulesTest.kt` 及支付宝 JSON fixtures。

**Interfaces:** `AlipayRules.create(): List<PageRule>`，16 个已连接 pageType；快速轮次只处理支付成功，回退轮次可匹配其他已支持页面。

- [ ] 写断言：支付成功 160ms 快速轮次与 450ms 回退轮次合并为一个候选；充值提现为转账语义；余利宝收益与本金转入转出区分；优惠前后金额不乱选。
- [ ] 运行 `./gradlew :core:test --tests '*AlipayRulesTest'`，记录失败。
- [ ] 对照 `j9`、`t9` 与 Smali 实现支付、账单、转账、充值、提现、红包、二维码收款和余利宝规则。
- [ ] 重跑测试，16 个规则均有独立正反例，验证金额格式、账户方向和时间依据。
- [ ] 提交 `feat: implement verified Alipay transaction rules`。

### Task 4: B4 拼多多规则

**Files:** 创建 `core/src/main/kotlin/app/omymoney/core/recognition/rules/PddRules.kt`、`core/src/test/kotlin/app/omymoney/core/recognition/PddRulesTest.kt` 及对应 fixtures。

**Interfaces:** `PddRules.create(): List<PageRule>`，3 个 pageType。

- [ ] 写断言：订单实付款可提取；先用后付的未来扣款不伪装为已经扣款；钱包成功账单和取消订单可区分。
- [ ] 运行 `./gradlew :core:test --tests '*PddRulesTest'`，记录失败。
- [ ] 对照 `n9` 与 `x9` 独立实现并记录原版先用后付行为与新安全策略的差异。
- [ ] 重跑测试，3 个场景证据与未确认状态完整。
- [ ] 提交 `feat: implement Pdd recognition with payment-state checks`。

### Task 5: B5 云闪付规则

**Files:** 创建 `core/src/main/kotlin/app/omymoney/core/recognition/rules/UnionPayRules.kt`、`core/src/test/kotlin/app/omymoney/core/recognition/UnionPayRulesTest.kt` 及对应 fixtures。

**Interfaces:** `UnionPayRules.create(): List<PageRule>`，5 个 pageType。

- [ ] 写断言：支付、转账与消息详情各自字段正确；首页和失败页面拒绝；银行卡尾号作为账户匹配依据而非完整卡号日志。
- [ ] 运行 `./gradlew :core:test --tests '*UnionPayRulesTest'`，记录失败。
- [ ] 对照 `p9`、`y9` 与应用过滤条件实现规则。
- [ ] 重跑测试，5 个场景均覆盖反例与时间缺失。
- [ ] 提交 `feat: implement UnionPay transaction rules`。

### Task 6: B6 京东规则

**Files:** 创建 `core/src/main/kotlin/app/omymoney/core/recognition/rules/JdRules.kt`、`core/src/test/kotlin/app/omymoney/core/recognition/JdRulesTest.kt` 及对应 fixtures。

**Interfaces:** `JdRules.create(): List<PageRule>`，6 个 pageType。

- [ ] 写断言：钱包两个版本、Web 订单和原生订单均按实际支付字段取值；爱回收收款判作收入；商品原价不覆盖实付款。
- [ ] 运行 `./gradlew :core:test --tests '*JdRulesTest'`，记录失败。
- [ ] 对照 `l9`、`v9` 实现规则与优先级，记录不同版本布局依据。
- [ ] 重跑测试，6 个场景包含有优惠、多金额及未支付反例。
- [ ] 提交 `feat: implement JD order and wallet rules`。

### Task 7: B7 美团规则

**Files:** 创建 `core/src/main/kotlin/app/omymoney/core/recognition/rules/MeituanRules.kt`、`core/src/test/kotlin/app/omymoney/core/recognition/MeituanRulesTest.kt` 及对应 fixtures。

**Interfaces:** `MeituanRules.create(): List<PageRule>`，10 个 pageType；不把独立美团外卖 App 包名宣称为已接通。

- [ ] 写断言：订单、券、团购、电影、单车、充电宝、外卖和钱包在对应页面取实际金额；券面值不是付款金额；退款状态保留待核对。
- [ ] 运行 `./gradlew :core:test --tests '*MeituanRulesTest'`，记录失败。
- [ ] 对照 `m9`、`w9` 和 `g9.b` 实现 10 个规则，明确待使用券和已完成订单的区别。
- [ ] 重跑测试，检查规则命中顺序不会把钱包详情错认成其他订单页。
- [ ] 提交 `feat: implement Meituan transaction scenarios`。

### Task 8: B8 抖音规则与注册完整性

**Files:** 创建 `core/src/main/kotlin/app/omymoney/core/recognition/rules/DouyinRules.kt`、`core/src/main/kotlin/app/omymoney/core/recognition/RuleRegistry.kt`、`core/src/test/kotlin/app/omymoney/core/recognition/DouyinRulesTest.kt`、`RuleRegistryTest.kt`（同测试目录）及对应 fixtures。

**Interfaces:** `DouyinRules.create(): List<PageRule>`，4 个 pageType；`RuleRegistry.enabledRules(packages: Set<String>): List<PageRule>` 汇总七组规则。

- [ ] 写断言：待收和已收转账分开、支付和钱包详情正确；注册 ID 唯一；已验收规则总数不得大于有证据的规则数；淘宝和密码页不混入支持清单。
- [ ] 运行 `./gradlew :core:test --tests '*DouyinRulesTest' --tests '*RuleRegistryTest'`，记录失败。
- [ ] 对照 `k9`、`u9`、`g9.a` 实现并汇总规则；仅为通过样本验收的规则启用默认入口。
- [ ] 重跑全部 recognition 测试，逐条核对 56 个分析目标的当前证据状态，不为了达到数量开启无证据规则。
- [ ] 提交 `feat: complete transaction rule registry with coverage evidence`。

### Task 9: B9 去重账户匹配与持久草稿队列

**Files:** 创建 `core/src/main/kotlin/app/omymoney/core/recognition/Deduplicator.kt`、`AccountMatcher.kt`、`data/src/main/kotlin/app/omymoney/data/RecognitionQueue.kt`；测试 `core/src/test/kotlin/app/omymoney/core/recognition/DeduplicatorTest.kt`、`AccountMatcherTest.kt`、`data/src/androidTest/kotlin/app/omymoney/data/RecognitionQueueTest.kt`。

**Interfaces:** `Deduplicator.compare(candidate, history): DedupDecision` 返回 `EXACT_DUPLICATE, SIMILAR, NEW`；`AccountMatcher.match(draft, accounts): AccountSuggestions`；`RecognitionQueue.enqueue(result): QueueEntry` 与 `resolve(entryId, outcome)` 持久化状态。

- [ ] 写断言：同订单重复事件只排一次；同金额不同商户仍可确认；无时间的一小时范围只产生可解释相似判断；取消不标已保存；连续十个候选在重启后仍可处理；多账户歧义不静默选错。
- [ ] 运行 `./gradlew :core:test --tests '*DeduplicatorTest' --tests '*AccountMatcherTest'` 和 `./gradlew :data:connectedDebugAndroidTest`，确认失败。
- [ ] 实现事件去重、60 秒相似查询、一小时无时间缓存及保存幂等；账户按名称、尾号、相似度和场景匹配，记录差异，不把不可用的原版用户映射框架当作现成功能。
- [ ] 重跑测试，核对队列上限、过期和状态转换，超限显式报告而不悄悄丢单。
- [ ] 提交 `feat: add loss-aware deduplication and durable draft queue`。

### Task 10: B10 无障碍采集与权限状态

**Files:** 创建 `app/src/main/kotlin/app/omymoney/platform/accessibility/LedgerAccessibilityService.kt`、`NodeSnapshotReader.kt`、`app/src/main/res/xml/ledger_accessibility_service.xml`、`app/src/main/kotlin/app/omymoney/feature/automation/AutomationSettingsScreen.kt`；测试 `app/src/androidTest/kotlin/app/omymoney/AccessibilityCaptureTest.kt`。

**Interfaces:** `NodeSnapshotReader.read(root, allowedPackage): UiSnapshot?`；服务只向 `RecognitionQueue` 输出，不调用保存。权限状态在 `onResume` 和绑定状态变化时重新读取。

- [ ] 写断言：未授权或用户暂停时采集数量为 0；未知包名拒绝；password 节点及子树排除；重复窗口事件合并；服务重连不会将旧节点当新交易；手工记账不受权限拒绝影响。
- [ ] 运行 `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.omymoney.AccessibilityCaptureTest`，记录失败。
- [ ] 实现受限节点读取、单工作队列、页面延迟、取消与过期；服务真实声明用途，不使用仿 Google 的类名，也不自我授权。
- [ ] 在专用模拟器测试授权、暂停、撤销、重连和未付款样本；真机厂商保活仍标未验证。
- [ ] 提交 `feat: integrate consent-based accessibility capture`。

### Task 11: B11 Material 3 悬浮确认与通知

**Files:** 创建 `app/src/main/kotlin/app/omymoney/platform/overlay/DraftOverlayHost.kt`、`app/src/main/kotlin/app/omymoney/feature/automation/DraftReviewContent.kt`、`app/src/main/kotlin/app/omymoney/platform/notification/RecognitionNotification.kt`；测试 `app/src/androidTest/kotlin/app/omymoney/OverlayReviewTest.kt`。

**Interfaces:** 无障碍路径优先由服务托管 `TYPE_ACCESSIBILITY_OVERLAY`，`DraftOverlayHost.show(entryId)` 与 `dismiss(reason)` 绑定正确生命周期；分享截图使用正常 Activity。内部通信不导出可被任意 App 注入账单的广播入口。

- [ ] 写断言：新草稿不抢当前编辑焦点；未保存不改变余额；保存成功只处理一次；锁屏隐藏敏感内容；返回保留待办；相似账单能比较差异；窗口拒绝时待办仍可在主 App 打开。
- [ ] 运行 `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.omymoney.OverlayReviewTest`，记录失败。
- [ ] 用与 A6 共用的 M3 表单实现浮层；通知仅在权限允许时显示真实状态及待处理数量；不通过后台强启 Activity 绕过限制。
- [ ] 测试输入法、返回、大字体、通知拒绝、锁屏和连续事件；验证 ComposeView 生命周期正确释放，不残留透明阻挡层。
- [ ] 提交 `feat: add non-disruptive Material draft review overlay`。

### Task 12: B12 离线 OCR 与截图规则

**Files:** 创建 `app/src/main/kotlin/app/omymoney/platform/ocr/LocalTextRecognizer.kt`、`app/src/main/kotlin/app/omymoney/feature/automation/ScreenshotImportActivity.kt`、`core/src/main/kotlin/app/omymoney/core/recognition/ScreenshotRules.kt`；测试 `core/src/test/kotlin/app/omymoney/core/recognition/ScreenshotRulesTest.kt`、`app/src/androidTest/kotlin/app/omymoney/ScreenshotImportTest.kt`。

**Interfaces:** `LocalTextRecognizer.recognize(uri: Uri): OcrDocument` 为挂起函数，返回 `OcrDocument(width: Int, height: Int, lines: List<OcrLine>)`，其中 `OcrLine(text: String, left: Int, top: Int, right: Int, bottom: Int)` 定义在核心快照模型文件；`ScreenshotRules.recognize(document: OcrDocument): RecognitionResult`。未知模板生成可编辑部分草稿，不调用远程服务。

- [ ] 写断言：离线环境可读打包中文模型；空图片、URI 失效、旋转图、超大图和多金额均有明确结果；未知格式不生成猜测金额；返回取消释放图片资源。
- [ ] 运行 `./gradlew :core:test --tests '*ScreenshotRulesTest'` 和对应 Android OCR 测试，记录失败。
- [ ] 集成打包版 ML Kit 中文 OCR，受限图片解码、SAF/分享 URI 与模板解析；不引入 Play Services 动态下载作为首次识别必需条件。
- [ ] 重跑测试并实际断网识别合成图片，检查合并 Manifest 无网络权限、批量输入不阻塞主线程。
- [ ] 提交 `feat: add offline screenshot recognition without AI`。

### Task 13: B13 AI 增强接口的空实现

**Files:** 创建 `core/src/main/kotlin/app/omymoney/core/recognition/RecognitionEnhancer.kt`、`NoOpRecognitionEnhancer.kt`、`EnhancementValidator.kt`；测试 `core/src/test/kotlin/app/omymoney/core/recognition/RecognitionEnhancerTest.kt`。

**Interfaces:** `suspend fun enhance(request: EnhancementRequest): EnhancementResult`；request 含原文字段依据、已有草稿、允许的候选 ID、版本与超时；result 区分 `Unavailable, Suggestions, Failed`。默认 `Unavailable`，UI 不显示无效 AI 开关。

- [ ] 写断言：NoOp 不改任何草稿字段；不存在的分类、原文不支持的金额、迟到版本和已手改字段建议均被拒绝；取消传播，超时回到规则草稿。
- [ ] 运行 `./gradlew :core:test --tests '*RecognitionEnhancerTest'`，记录失败。
- [ ] 实现契约、空提供方和统一校验；只用测试 fake 模拟结果，不接模型、不下载权重、不添加密钥字段。
- [ ] 重跑测试，验证禁用增强后 B1 至 B12 流程完整通过且不依赖网络。
- [ ] 提交 `feat: reserve validated AI enhancement contract with no-op provider`。

### Task 14: B14 自动记账验收与覆盖报告

**Files:** 创建 `app/src/androidTest/kotlin/app/omymoney/AutomationAcceptanceTest.kt`、`docs/recognition-coverage.json`、`docs/acceptance-B.md`；按结果修复以上任务文件。

**Interfaces:** 每个 pageType 单独记录静态来源、fixture、设备版本与结论，计数区分识别、入队、保存、忽略和失败。

- [ ] 写端到端断言：捕获 -> 规则 -> 去重 -> 排队 -> 编辑 -> 保存 -> A 的账单列表和余额；另测拒绝权限、杀进程、OCR 失败、相似交易与错误状态。
- [ ] 运行 `./gradlew :core:test :data:connectedDebugAndroidTest :app:connectedDebugAndroidTest :app:lintDebug`，记录真实失败。
- [ ] 修复并进行独立 UX 审查，尤其检查权限引导、非阻断队列、重复确认和键盘焦点；无真机证据的场景保持未实测标记。
- [ ] 重跑全套测试、安装检查及离线 Manifest 检查，确认不连接原后端且没有 AI 运行依赖。
- [ ] 提交 `test: validate offline automatic bookkeeping pipeline`，交付 B 阶段 APK 和逐场景覆盖表。
