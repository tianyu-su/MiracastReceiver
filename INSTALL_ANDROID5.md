# Android 5 / ARM32：原版与连播修复版

这两个版本均供 API 21 的 32 位 ARM 设备使用。本次实测设备为 Konka-Projector-M62。
设备界面虽然显示 Android 11.0，其系统接口实际为 API 21；安装兼容性按 API 级别判断。

| 版本 | 源码 | APK 下载 | 用途 |
| --- | --- | --- | --- |
| 原版 v1.2.13 | [原始源码分支](https://github.com/tianyu-su/MiracastReceiver/tree/original/v1.2.13) / [上游版本标签](https://github.com/tianyu-su/MiracastReceiver/tree/v1.2.13) | [下载原版 ARM32 APK](apks/original-v1.2.13/miracast-receiver-v1.2.13-armeabi-v7a.apk?raw=true) | 保留官方安装包，便于回退 |
| 修复版 v1.2.13-autonext.1 | [修复源码分支](https://github.com/tianyu-su/MiracastReceiver/tree/fix/dlna-auto-next) / [修复版本标签](https://github.com/tianyu-su/MiracastReceiver/tree/v1.2.13-autonext.1) | [下载修复版 ARM32 APK](apks/autonext-v1.2.13/miracast-receiver-v1.2.13-autonext.1-armeabi-v7a.apk?raw=true) | 通用 DLNA 连播修复测试版，优酷实测通过 |

两个 APK 都作为 Git 文件保存在 `apks/`，克隆仓库即可保留安装包，无需依赖外部下载站。
SHA256 校验值见 [apks/SHA256SUMS.txt](apks/SHA256SUMS.txt)。原版 APK 已与上游官方发布的 SHA256 核对。

## 安装与共存

- 原版包名：`com.weekd.miracastreceiver`。
- 修复版包名：`com.weekd.miracastreceiver.autonext`，显示名称为“Miracast Receiver 连播测试版”。
- 修复版使用本地 debug 签名和独立包名，可以与官方版并存，不覆盖原版数据。
- 两者使用相同接收端口。投屏时只运行其中一个接收服务，避免端口冲突。

通过 ADB 安装修复版：

```sh
adb connect <投影仪IP>:5555
adb install -r apks/autonext-v1.2.13/miracast-receiver-v1.2.13-autonext.1-armeabi-v7a.apk
adb shell am force-stop com.weekd.miracastreceiver
adb shell am start -n com.weekd.miracastreceiver.autonext/com.weekd.miracastreceiver.ui.MainActivity
```

切回原版时先停止测试版接收服务，然后打开原版。两版均已安装时，无需卸载或清除数据。

## 修复与验证

修复了播完后被错误更新为暂停、下一条地址未正确缓存、以及缺少 DLNA 状态订阅通知的问题。
修复面向标准 DLNA 流程，没有依赖优酷包名或视频域名。其他 App 的连续播放能力仍取决于发送端实现。

- 完整单元测试：111 项通过，其中新增 DLNA 测试 14 项。
- 真机：提前缓存下一段后自动接续，通过。
- 真机：发送端收到 `STOPPED` 通知后再投送下一段，通过。
- 优酷实际投屏：用户确认无需手动选下一集，即可自动进入下一集。

详情见 [验证记录](verification/autonext-validation.json)、[修复说明](DLNA连播修复说明.md)。
修复说明与具体源码位于 `fix/dlna-auto-next` 分支；仓库 `main` 保留 fork 原有的上游代码，并追加本页及 APK 备份。

## 从源码构建

```sh
git switch fix/dlna-auto-next
cd MiracastReceiver
bash gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug -PbuildArm32 -PautonextBuild
```

需要 JDK 17、Android SDK API 36、Build Tools 34.0.0、CMake 3.22.1、NDK 27.0.12077973。
`-PautonextBuild` 生成上述独立包名测试版，最低 API 仍为 21。

上游项目：[weekdayjast/MiracastReceiver](https://github.com/weekdayjast/MiracastReceiver)。保留原项目及其第三方组件的许可证和来源声明。
