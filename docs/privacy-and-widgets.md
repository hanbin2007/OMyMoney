# 本机隐私与桌面入口

## 隐私锁

- 启用、停用及解锁均由 Android 系统验证。Android 10 及以上使用系统 BiometricPrompt，允许强生物识别或设备凭据；Android 8/9 使用系统设备凭据页面。未设置系统锁屏凭据时不能启用。
- 取消、错误、销毁中的页面、过期回调或在后台返回的验证结果均不解锁。进程重新启动、最后一个应用页面进入后台、息屏都会锁定；配置变化和应用内部页面切换不作为离开应用。
- 主页面、快捷记账、桌面组件入口、图片分享导入和组件配置均经过同一隐私门。未消费的入口在锁定和重建中保留，验证后消费一次。锁定时移除私密页面的可访问性节点，恢复后保留导航和草稿。
- 开启隐私锁时强制 `FLAG_SECURE`；单独的截图保护也可开启。它约束系统截图、任务预览和不安全显示，不保证被修改的系统或所有厂商设备均提供相同保护。
- **这不是数据库加密。** 系统设备凭据不由本应用保存。应用数据库仍在 Android 私有目录，导出的备份和文件也不因此加密。
- 隐私模式下禁止自动记账浮层，包括暂停到停止的过渡窗口；已授权的无障碍识别仍可收集为待审核记录，不自动入账。通知不展示交易金额。

## 标准桌面组件

- 标准 Android AppWidget 提供账本选择、本月收入/支出及记账入口。汇总复用统计核心，读取已提交且未删除账单，遵守排除统计、退款和手动汇率规则；缺失汇率明确显示未知，不显示虚假的零。
- 使用账本时区按自然月归组。提交、删除、账本和汇率变化触发刷新；系统更新周期为 30 分钟，另处理日期/时间/时区变化和设备解锁。系统可推迟后台刷新，不声称实时精确到秒。
- **隐私锁启用时，即使应用当前已解锁，组件也始终隐藏金额与账本名**，避免桌面宿主缓存敏感画面。锁屏宿主和设备锁定时也执行遮蔽；API 36 元数据增加 `not_keyguard`，但宿主仍有实现差异。
- 小组件只保存组件 ID 与本地账本 ID。归档或不存在的账本不能静默切换成别的账本。原厂商 MIUI/Honor 等专有组件协议未实现，不能用标准 AppWidget 的成功推定厂商兼容。
- 桌面长按应用提供记账快捷方式，设置页可请求固定当前账本快捷方式与组件。是否接受固定请求由用户与当前桌面决定。
- 设置页固定组件时预选页面显示的账本。系统不会替此 API 打开配置页，故只有收到桌面成功回调、确认组件属于本应用且账本仍可用后，才保存映射。回调仅用一次；未配置组件的两个入口均打开账本选择页，不默认进入其他账本。

## 保存范围

外观、截图保护、隐私锁与组件映射存于本机私有设置，不含密码，不进入业务 ZIP 备份。替换或合并账务备份不会关闭现有设备的隐私锁；在新设备恢复后，需在该设备另行开启。云备份和设备迁移继续按 Manifest 与数据提取规则关闭。

## 系统依据

- [系统 BiometricPrompt](https://developer.android.com/reference/android/hardware/biometrics/BiometricPrompt.Builder)
- [设备凭据](https://developer.android.com/reference/android/app/KeyguardManager)
- [组件宿主类别](https://developer.android.com/reference/android/appwidget/AppWidgetProviderInfo)
- [组件更新](https://developer.android.com/develop/ui/views/appwidgets/advanced)
- [固定组件回调与配置约束](https://developer.android.com/reference/android/appwidget/AppWidgetManager#requestPinAppWidget(android.content.ComponentName,android.os.Bundle,android.app.PendingIntent))
