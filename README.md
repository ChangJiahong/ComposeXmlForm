# ComposeXmlForm

用 XML 描述界面、由 Compose 渲染的表单引擎，Kotlin Multiplatform（Android / iOS / Desktop）。

## 模块划分

| 模块 | 角色 | 说明 |
| --- | --- | --- |
| [`composexmlform`](./composexmlform) | **库** | 引擎本体，包名 `cn.changjiahong.composexmlform`，门面对象 `ComposeXmlForm`，可被任意 Compose 工程依赖 |
| [`shared`](./shared) | 示例 | 跨平台示例 UI（`App.kt`：一份 XML + 数据跑三端），包名 `cn.changjiahong.sample` |
| [`androidApp`](./androidApp) | 示例 | Android 宿主（applicationId `cn.changjiahong.sample`） |
| [`desktopApp`](./desktopApp) | 示例 | 桌面宿主 + 调试台（左 XML 编辑、右实时渲染、底部数据快照） |
| [`iosApp`](./iosApp) | 示例 | iOS 宿主（Xcode 工程，`import Shared`） |

依赖方向单向：`androidApp / desktopApp → shared → composexmlform`。库内不含任何示例代码。

## 引入库

```kotlin
// settings.gradle.kts
include(":composexmlform")

// 依赖方
dependencies {
    implementation(project(":composexmlform"))
}
```

最小用法：

```kotlin
import cn.changjiahong.composexmlform.api.DynamicLayout
import cn.changjiahong.composexmlform.api.rememberBindableData

val data = rememberBindableData(mapOf("username" to ""))
DynamicLayout(xml = FORM_XML, data = data)
```

自定义组件注册走 `ComposeXmlForm.createRegistry { ... }`。

完整 API 与语义契约见文档：`WorkBuddy/ComposeXmlForm/API_USAGE.md`、`IMPLEMENTATION.md`、`DESIGN.md`。

## 运行示例

- Android：`./gradlew :androidApp:assembleDebug`
- Desktop：
  - 热重载：`./gradlew :desktopApp:hotRun --auto`
  - 普通运行：`./gradlew :desktopApp:run`
- iOS：
  - 用 Xcode 打开 `iosApp/` 运行（可选择模拟器或真机）
  - 在 Android Studio 里一键跑到真机：选运行配置 **iosApp 真机**（配置见 `.idea/runConfigurations/iosApp_iPhone.xml`，实际执行 `scripts/run-ios-device.sh`），等价于命令行：
    ```bash
    ./scripts/run-ios-device.sh              # 构建 + 安装 + 启动到第一台已连接 iPhone
    CONSOLE=1 ./scripts/run-ios-device.sh    # 同时把设备控制台输出接到终端
    DEVICE_UDID=<udid> ./scripts/run-ios-device.sh   # 多台设备时指定
    ```
  - 注意：Android Studio 自带的 iOS 真机运行需要 KMP 插件（`+` → **Kotlin Mobile** → 名称 `iOS App`）；若设备下拉框为空，优先用上面的脚本配置。

## 环境要求（iOS 侧）

Android Studio 的 iOS 真机/模拟器能力（设备枚举、断点、控制台）不是自己实现的，而是通过 KMP 插件里的 `JBDeviceService` 直接调用**本机 Xcode 的 CoreDevice / DVT 框架**。所以 Xcode 版本必须与插件、与设备 iOS 版本对得上：

| 项目技术栈 | 官方对应 Xcode | 宿主要求 |
| --- | --- | --- |
| Kotlin 2.4.x（本工程） | Xcode 26.x / 27.x | macOS 15.6+（Xcode 26.3）/ 26.4+（Xcode 27） |
| Kotlin 2.3.x | Xcode 26.0 | macOS 15.6+ |
| Kotlin 2.2.20 | Xcode 16.4 | macOS 14.5+ |
| Kotlin 2.1.20 | Xcode 16.0 | macOS 14.5+ |

排查口径：

- `otool -L "$HOME/Library/Application Support/Google/AndroidStudio<版本>/plugins/kmm-plugin/bin/JBDevice.framework/Versions/A/Resources/JBDeviceService"` 里记录的 `CoreDevice` 版本，就是插件编译时用的 Xcode 版本；若明显高于本机 `/Library/Developer/PrivateFrameworks/CoreDevice.framework` 的版本，IDE 侧设备枚举就会崩（`JBDeviceService` 段错误 → 运行配置里看不到真机）。
- 设备 iOS 版本高于 Xcode 支持的 SDK 时，Xcode 图形界面无法运行/调试该设备，但 `xcrun devicectl install/launch`（脚本用的就是它）仍然可以安装启动，因为它不依赖 Xcode 的 DeviceSupport。

## 测试

```bash
./gradlew :composexmlform:jvmTest     # 引擎用例（126 个）
```

## 技术栈

Kotlin 2.4 / Compose Multiplatform 1.12 / Gradle 9.5 / AGP 9.1
