# B 阶段验收：自动记账与本地 OCR

这是 A 基础上的 B 阶段交付，不是整款完成。C 阶段预算统计、复杂账务、周期分期、存钱计划、数据交换、隐私锁与小组件仍待实施。

## 范围与证据

- 七个应用包、56 个静态接通场景均有独立正反合成样本，共 56 个正例和 56 个反例。逐场景的静态类/位置、样本文件、案例名和未实测标记保存在 `recognition-coverage.json`；由 `ruby scripts/recognition-coverage.rb` 生成，`RuleRegistryTest` 校验数量和来源。
- 56 个场景通过的是规则样本，不是 56 个第三方 App 真机页面。当前第三方支付应用实测场景数为 0，不以静态连接或模拟数据冒充兼容率。
- API 34 `ledger34` 模拟器验证了真实无障碍授权/撤销与重连、Android Settings 节点适配、悬浮窗 IME/锁屏/返回、无网络打包中文 OCR、分享后显式开始、Activity 重建和真实进程终止后的草稿恢复。
- 截图、无障碍采集、规则、入队均不会修改余额。只有明确保存且统一账务事务成功，队列才记录 `SAVED`。

## 最终回归

`work/build-logs/b14-full-regression-first.log`：

| 门槛 | 结果 |
| --- | --- |
| JVM core | 117 通过，0 失败 |
| Android data | 54 通过，0 失败 |
| Android app | 88 总计，83 通过，5 项专用设备用例跳过 |
| Android lint | 通过 |
| 合并 Manifest 离线检查 | 无 INTERNET；关闭 legacy/cloud/device-transfer 自动备份 |

五项默认跳过覆盖实际权限、悬浮窗、附件跨进程、原始草稿跨进程与极矮横屏，分别通过独立原生脚本验收；跳过不计入以上通过数。B 阶段新增确认过程也有真实强制终止后的二次启动验证。没有以 `am instrument` 的 shell 退出码替代测试内 `OK` 结果。

## 联合工作流

`AutomationAcceptanceTest` 用受限的合成节点树驱动真实 `NodeSnapshotReader -> CaptureCoordinator -> RuleEngine -> RecognitionQueue`，然后通过实际 Compose 编辑器保存，并在 A 阶段的账单列表及数据库余额读回：

1. 没有采集许可时零读取、零候选；事件合并后只出现一条 12.30 草稿。
2. 缺少账户的保存失败，队列记录失败，但账单和余额均不变化。
3. 用户将金额改为 13.50 并选账户后保存成功；重复确认仍只有一笔，100.00 开始余额为 86.50。
4. 随后的同额近时、不同商户交易标记为相似但不丢弃；用户忽略它后仍保留处理记录。
5. 缺时间候选仍待确认，原始金额输入 `14.` 在关闭和真实进程终止后恢复；失败支付及空 OCR 不进入已保存状态。

每个验收账本最终三条识别记录：一条保存、一条忽略、一条待确认；第一条还保留先失败后成功的事件。它们与“只有一条已保存账单”是不同计数。

## 审查与修复

独立 Stage B 代码审查及 B12/B14 UX 审查覆盖源码和现有截图。六项重要问题均有失败测试后再修复：退款处理中覆盖残留成功状态；币种符号不被捕获组丢弃；相似比较读取当前草稿和当前已保存账单；交易/平台/商户编号分开；忙碌批次不能提前跳转核对；已消费导航请求不在重建时重放。

`b14-review-core-red.log`、`b14-review-data-red.log`、`b14-review-app-red.log` 和 `b14-workflow-first.log` 保留实际失败。修复后完整回归通过，不把测试类名配错造成的初始化失败算作产品问题。完整实施裁决另保存在 `decisions-B.md`。

## 图像与设备边界

`work/qa/b14` 保留 360dp 浅色及 600dp 深色、200% 字体的账单列表、队列、跨进程恢复核对页；真实原生 prepare/force-stop/verify 各阶段分别断言状态。B10/B11/B12 另有实际权限、悬浮窗及离线 OCR 图像与日志，参见 `accessibility-capture.md`、`draft-review.md`、`offline-ocr.md`。

没有原版线上交易、物理设备、API 26/36 运行、TalkBack 服务或完整硬件键盘覆盖。这些不写成“已通过”；较广的运行环境与可访问性审查属于 C10。

## 保留的小问题

- 内嵌时间编辑的格式错误和 DST 缺口共用提示。
- 内嵌分钟级时间编辑确认会将原有秒数归零；核对比较已显示秒数。
- 导入图片列表只有序号，没有缩略图/文件名，旋转反馈只有角度文字。
- OCR 存储异常暂时借用“更新设置失败”提示，错误可见但文案不准确。

## 交付性质

APK 包名为 `app.omymoney.android`，名称 OMyMoney，版本 `0.1.0 (1)`，minSdk 26、targetSdk 36。使用 Android Debug 证书，不冒充正式商店签名。不含原版后端、登录、云同步、生成式 AI 提供方、模型下载或密钥入口；中文 OCR 权重直接打包。系统授予无障碍权限仍需用户自己操作。

阶段文件：`outputs/OMyMoney-stage-B-debug.apk`。SHA-256：`71401bb36cfe025c54b4d28096f10fd43464a19e80ad79eaef9fd2a7dfe0f759`。

签名证书 SHA-256：`f466768c635812793823d388964fc4799914f21c15e7fae9954fb3c8652a8c1a`。`apksigner verify --print-certs` 与 `aapt2 dump badging` 均完成读回。此文件不是 C 阶段的最终版本。

这份验收不改变后续 C 阶段完成标准。
