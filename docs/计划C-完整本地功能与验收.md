# 完整本地功能与验收 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 补齐本地账务、预算统计、定期任务、资产计划和数据管理，形成可核验的整款本地替代品，而非仅完成自动记账演示。

**Architecture:** 扩展 A 的统一模型和事务入口，报表读同一份账务事实，B 的识别只作为草稿来源。页面继续使用统一 M3 组件，不在每个功能中重写金额、余额或主题系统。

**Tech Stack:** Kotlin、Compose Material 3、Room、java.time、WorkManager、AndroidX Biometric、标准 AppWidget、成熟 CSV/XLSX 解析库及自动化测试。

**Spec:** [已认可规格](本地版实现规格.md)；[总计划与公共接口](实施计划.md)。依赖 A、B 阶段回归通过。

## Global Constraints

- 所有复杂类型复用 `TransactionDraft`、`PostingCalculator` 和 `LedgerRepository`，不得新增不受统一校验的“快速保存”入口。
- 收入、支出、转账、退款、报销、借贷本金及利息分别建模，不靠金额正负猜类型。
- UI 全面 M3 / Material You，任务正常、返回和错误路径都验收；无本地或云 AI 实现。
- 不连接原版服务端，汇率手动维护；所有本地差异有记录，不把服务端缺失伪装成成功。
- 每项开始前对照原版相关类、资源和可访问页面核查业务口径。以下测试定义本地版明确规则；若与原版不一致，记录差异，不静默改变已有账单含义。

## Review Focus

- C1、C2：跨月退款、部分报销及多次修改不应破坏关联或重复计收支。
- C3：不同币种不能直接相加，更新当前汇率不能篡改历史成交金额。
- C4、C5：预算排除和统计排除不同；删除自动复制预算后不能又被复制回来。
- C6：月底、闰年、夏令时、时区改变和离线补执行不能重复生成账单。
- C9、C10：损坏导入、公式型单元格、锁屏小组件和字体缩放不能造成数据泄漏或不可恢复操作。

---

### Task 1: C1 退款与报销关联账务

**Files:** 创建 `core/src/main/kotlin/app/omymoney/core/ledger/LinkedBillRules.kt`、`app/src/main/kotlin/app/omymoney/feature/linked/LinkedBillScreen.kt`；修改 `core/src/main/kotlin/app/omymoney/core/ledger/PostingCalculator.kt` 和 `data/src/main/kotlin/app/omymoney/data/RoomLedgerRepository.kt`；测试 `core/src/test/kotlin/app/omymoney/core/ledger/LinkedBillRulesTest.kt`、`data/src/androidTest/kotlin/app/omymoney/data/LinkedBillTransactionTest.kt`。

**Interfaces:** `LinkedBillRules.validate(draft, relatedBills): List<FieldError>`、`remainingReimbursement(source, settlements): Money`；关联记录仍通过 `LedgerRepository.save` 保存。

- [ ] 写断言：支出 100 退款 30 后净支出 70；跨月仍保留原关联；支出 20 退款 30 的差额 10 单独反映为退款收入；报销 100 收回 60 后待报销 40；重复确认和撤销不重复计算。
- [ ] 运行 `./gradlew :core:test --tests '*LinkedBillRulesTest'` 与 `./gradlew :data:connectedDebugAndroidTest`，记录失败。
- [ ] 实现退款和报销分录、来源关联、事务校验及详情入口；不能删掉来源后留下无法解释的孤立关系，关联删除采用明确确认和一致处理。
- [ ] 重跑测试，验证修改原账单和部分结算后的报表、账户余额与待结算金额；检查统计口径差异表。
- [ ] 提交 `feat: add linked refunds and reimbursement settlements`。

### Task 2: C2 借贷信用卡与资产分组

**Files:** 创建 `core/src/main/kotlin/app/omymoney/core/ledger/DebtRules.kt`、`app/src/main/kotlin/app/omymoney/feature/assets/AssetDetailScreen.kt`、`AssetGroupScreen.kt`（同目录）；测试 `core/src/test/kotlin/app/omymoney/core/ledger/DebtRulesTest.kt`、`app/src/androidTest/kotlin/app/omymoney/AssetFlowTest.kt`。

**Interfaces:** `DebtRules.position(accountId, bills): DebtPosition(principal, interest)`；复用账户 ID、原币金额、关联账单和统一分录模型，信用卡还款不记为普通消费。

- [ ] 写断言：借入 100 增加现金及负债，归还本金 40 后剩余 60；利息 2 独立计支出；信用卡消费和还款不重复计消费；归档账户保留历史；排序重启后不变。
- [ ] 运行 `./gradlew :core:test --tests '*DebtRulesTest'` 和对应 `AssetFlowTest`，记录失败。
- [ ] 实现借入借出、本金与利息、还款收款、信用卡周期配置、资产分组排序及余额调整；账户详情明确当前余额和调整来源。
- [ ] 重跑测试，走查跨账本查看账户、修改历史账单、恢复归档账户和取消调整。
- [ ] 提交 `feat: complete debt credit and asset management`。

### Task 3: C3 多币种与资产历史

**Files:** 创建 `core/src/main/kotlin/app/omymoney/core/ledger/ExchangeRules.kt`、`data/src/main/kotlin/app/omymoney/data/AssetHistoryRepository.kt`、`app/src/main/kotlin/app/omymoney/feature/assets/CurrencyScreen.kt`、`AssetHistoryScreen.kt`；测试 `core/src/test/kotlin/app/omymoney/core/ledger/ExchangeRulesTest.kt`。

**Interfaces:** `ExchangeRules.convert(amount: Money, rate: BigDecimal, target: CurrencySpec): Money` 使用明确舍入策略；账单保存实际转出、实际转入和当时汇率，历史汇率不可被全局新值覆盖。

- [ ] 写断言：USD 10 按 7.2 换为 CNY 72，两侧各自币种正确；更新汇率后旧账仍为 72；JPY、三位小数币种按各自精度；缺汇率的汇总不伪造总额。
- [ ] 运行 `./gradlew :core:test --tests '*ExchangeRulesTest'`，记录失败。
- [ ] 实现手动汇率、跨币种转账、实际入账金额编辑、资产历史快照与趋势数据；界面明确币种和使用的汇率日期。
- [ ] 重跑测试，核对余额快照与按账单重建结果相同，舍入差额可解释且不会累计丢失。
- [ ] 提交 `feat: add explicit currency conversion and asset history`。

### Task 4: C4 月年预算与分类预算

**Files:** 创建 `core/src/main/kotlin/app/omymoney/core/budget/BudgetCalculator.kt`、`data/src/main/kotlin/app/omymoney/data/BudgetRepository.kt`、`app/src/main/kotlin/app/omymoney/feature/budget/BudgetScreen.kt`；测试 `core/src/test/kotlin/app/omymoney/core/budget/BudgetCalculatorTest.kt`、`data/src/androidTest/kotlin/app/omymoney/data/BudgetCopyTest.kt`。

**Interfaces:** `BudgetCalculator.calculate(period, bills, budgets): BudgetSummary`；`BudgetRepository.copyPreviousIfEligible(bookId, period)` 有持久化删除标记，不能反复恢复用户删除的预算。

- [ ] 写断言：1000 预算消费 120 后剩 880；不计预算的 50 不减少额度；分类预算总和不得超过未调整的总预算；跨年、月末正确；删除复制预算后再次打开不复活。
- [ ] 运行 `./gradlew :core:test --tests '*BudgetCalculatorTest'` 与对应数据层测试，记录失败。
- [ ] 实现月年总预算、分类预算、复制和删除、剩余日均及超支；预算编辑反馈持久保存状态，失败保留输入。
- [ ] 重跑测试，并用 M3 UI 创建修改删除预算、切换年月，检查首页摘要与预算页一致。
- [ ] 提交 `feat: implement consistent monthly yearly and category budgets`。

### Task 5: C5 统计日历与年度汇总

**Files:** 创建 `core/src/main/kotlin/app/omymoney/core/statistics/StatisticsCalculator.kt`、`app/src/main/kotlin/app/omymoney/feature/statistics/StatisticsScreen.kt`、`StatisticsChart.kt`、`CalendarScreen.kt`、`AnnualSummaryScreen.kt`；测试 `core/src/test/kotlin/app/omymoney/core/statistics/StatisticsCalculatorTest.kt`、`app/src/androidTest/kotlin/app/omymoney/StatisticsUiTest.kt`。

**Interfaces:** `StatisticsCalculator.summarize(bills, range, baseCurrency): StatisticsSummary`；结果包含收入、支出、转账独立合计、分类分组及日期序列，图表和可访问数据列表共享结果。

- [ ] 写断言：列表、分类图、趋势和日历相同筛选范围总额一致；不计统计与不计预算标记互不混淆；跨日时区边界正确；空数据无除零；缺汇率不合并原币。
- [ ] 运行 `./gradlew :core:test --tests '*StatisticsCalculatorTest'` 及 `StatisticsUiTest`，记录失败。
- [ ] 实现收支趋势、分类分布、可点选日期、年度汇总和统一筛选；图表明确刻度与单位，提供 TalkBack 可读的数据表，不仅用颜色区别曲线。
- [ ] 重跑测试，检查大数字、单条数据、负退款差额、大字体和横竖屏，统计页不可覆盖导航和筛选控件。
- [ ] 提交 `feat: add accessible statistics calendar and annual summaries`。

### Task 6: C6 周期记账与分期

**Files:** 创建 `core/src/main/kotlin/app/omymoney/core/schedule/ScheduleCalculator.kt`、`data/src/main/kotlin/app/omymoney/data/ScheduledBillRepository.kt`、`app/src/main/kotlin/app/omymoney/platform/schedule/ScheduledBillWorker.kt`、`app/src/main/kotlin/app/omymoney/feature/schedule/ScheduleScreen.kt`；测试 `core/src/test/kotlin/app/omymoney/core/schedule/ScheduleCalculatorTest.kt`、`data/src/androidTest/kotlin/app/omymoney/data/ScheduleIdempotencyTest.kt`。

**Interfaces:** `ScheduleCalculator.dueOccurrences(rule, through: Instant): List<Occurrence>`；每次执行唯一键为计划 ID 与计划发生时间。使用 `java.time` 和 WorkManager，打开 App 时也补偿检查，不承诺后台精确到秒。

- [ ] 写断言：每月 31 日在二月采用当月最后一天，三月仍回到 31 日；100 元三期为 33.33、33.33、33.34；闰年、夏令时缺口和重叠只产生一次；重启补执行不重复。
- [ ] 运行 `./gradlew :core:test --tests '*ScheduleCalculatorTest'` 与 `ScheduleIdempotencyTest`，记录失败。
- [ ] 实现周期配置、预览、暂停、编辑和执行记录，保存发生时间而非补执行时刻；自动落账需在创建计划时明确选择，默认生成待确认草稿。
- [ ] 重跑测试，模拟设备离线多日、系统限制任务和时区变更，确认补执行可预览且不会重复影响余额。
- [ ] 提交 `feat: add idempotent recurring and installment bookkeeping`。

### Task 7: C7 存钱计划与入金记录

**Files:** 创建 `core/src/main/kotlin/app/omymoney/core/savings/SavingsCalculator.kt`、`data/src/main/kotlin/app/omymoney/data/SavingsRepository.kt`、`app/src/main/kotlin/app/omymoney/feature/savings/SavingsScreens.kt`；测试 `core/src/test/kotlin/app/omymoney/core/savings/SavingsCalculatorTest.kt`。

**Interfaces:** `SavingsCalculator.progress(plan, deposits): SavingsProgress`；入金可以关联现有转账或仅作为计划记录，两种模式明确区分，不能重复创建支出。

- [ ] 写断言：固定、递增、倒序和自定义计划总额正确；已关联转账再次标记入金不改余额；取消或修改入金后进度一致；目标达成不溢出显示。
- [ ] 运行 `./gradlew :core:test --tests '*SavingsCalculatorTest'`，记录失败。
- [ ] 对照原版存钱计划规则实现计划创建、排序、详情、入金与撤销，使用同一货币精度和清晰进度反馈。
- [ ] 重跑测试，走查计划中断、提前完成、多币种限制与长计划列表。
- [ ] 提交 `feat: add savings plans with consistent linked deposits`。

### Task 8: C8 设置隐私锁与标准小组件

**Files:** 创建 `app/src/main/kotlin/app/omymoney/feature/settings/SettingsScreen.kt`、`app/src/main/kotlin/app/omymoney/platform/security/PrivacyLock.kt`、`app/src/main/kotlin/app/omymoney/platform/widget/LedgerWidgetProvider.kt`、`app/src/main/res/xml/ledger_widget_info.xml`；测试 `app/src/androidTest/kotlin/app/omymoney/PrivacyAndWidgetTest.kt`。

**Interfaces:** `PrivacyLock.state: StateFlow<LockState>`、`requestUnlock(activity)`；小组件只读取已提交汇总，更新与账务事件一致，不读取未确认草稿。

- [ ] 写断言：锁定后账单、浮层和小组件不泄漏金额；认证取消不解锁；没有生物识别时可使用系统设备凭据或保持禁用状态；主题重启保持；组件点击进入正确账本。
- [ ] 运行 `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.omymoney.PrivacyAndWidgetTest`，记录失败。
- [ ] 集成系统 BiometricPrompt、截图隐私选项、后台锁定、主题与小组件；不自制密码加密方案，不把隐私锁宣称为数据库加密。厂商专有扩展另列差异。
- [ ] 重跑测试，检查桌面大小调整、锁屏、解锁失败、数据变化刷新，以及快捷记账入口不绕过锁定。
- [ ] 提交 `feat: add privacy-aware settings shortcuts and widgets`。

### Task 9: C9 导入导出与批量操作

**Files:** 创建 `data/src/main/kotlin/app/omymoney/data/exchange/BillImportService.kt`、`BillExportService.kt`、`ImportMapping.kt`、`app/src/main/kotlin/app/omymoney/feature/exchange/ImportPreviewScreen.kt`、`app/src/main/kotlin/app/omymoney/feature/bills/BatchEditScreen.kt`；测试 `data/src/androidTest/kotlin/app/omymoney/data/ImportExportTest.kt`、`app/src/androidTest/kotlin/app/omymoney/BatchActionTest.kt`。

**Interfaces:** `BillImportService.preview(input, format): ImportPreview`、`commit(previewId, choices): ImportResult`；`BillExportService.export(query, format, output)`。使用成熟的 CSV 解析器与经过 Android 构建和实测的 XLSX 库，不用逗号 split 或自写 XML 拼接解析器。

- [ ] 写断言：引号逗号换行、UTF-8 BOM、空字段和小数无损解析；重复文件导入不重复扣余额；未知币种、无效日期、超大行和损坏 XLSX 逐条报告；文本公式单元格安全导出；取消批量修改不改变任何账单。
- [ ] 运行 `./gradlew :data:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.omymoney.data.ImportExportTest` 及 `BatchActionTest`，记录失败。
- [ ] 从原版导入器清单逐种建立格式映射与 golden fixture；先预览再提交，批量修改删除走统一事务入口；不宣称没有样本的格式已兼容。原始文本在本地备份中保持可恢复。
- [ ] 重跑测试，实际导出后回读比对条数、金额、类别和账户，检查错误行不会导致整批半写入；为未覆盖格式写清差异。
- [ ] 提交 `feat: add validated data exchange and reversible batch actions`。

### Task 10: C10 整体验收可安装交付与剩余差异

**Files:** 创建 `app/src/androidTest/kotlin/app/omymoney/FullAcceptanceTest.kt`、`docs/full-coverage.json`、`docs/known-differences.md`、`docs/build-and-test.md`；最终报告发布到项目目录下的 `outputs/验收报告.md`，APK 发布为 `outputs/OMyMoney.apk`。

**Interfaces:** 所有先前模块通过总计划接口集成，不在验收时另起绕过校验的演示路径。

- [ ] 写完整工作流断言：手工与自动草稿、账户余额、退款报销、多币种、预算统计、周期分期、存钱计划、数据交换、锁定及恢复一致；Release Manifest 无调试注入入口和 INTERNET 权限。
- [ ] 运行 `./gradlew :core:test :data:connectedDebugAndroidTest :app:connectedDebugAndroidTest :app:lintDebug :app:assembleDebug`，保留实际结果和失败明细。
- [ ] 修复后进行独立代码、UX 和可访问性审查；核查低端兼容 API 26、已有 API 34 和目标 API 36 环境，缺少的运行镜像经正常权限安装；不得把模拟器当目标真机。
- [ ] 重跑回归，实际安装 APK、查看各主题截图、测试 TalkBack、字体放大、旋转、返回和键盘；对 Manifest 权限、包名、版本、签名和 SHA256 做 readback。
- [ ] 提交 `test: verify complete offline bookkeeping release`，交付源码、APK、覆盖表及差异。仅当范围全部实现且所需验收完成，才称整款完成；任何未实现项明确列出，不用“现代手机应该都能用”替代证据。
