# Android 5 / ARM32 DLNA 连播修复

基于官方 `v1.2.13`（`cfbd05579ad0f1520eeda0b40ec6fcbe664aaa1d`），分支 `fix/dlna-auto-next`。
最低安卓版本仍为 API 21，没有通过修改版本字符串绕过安装限制。

## 修复范围

这是 DLNA 接收端的通用修复，没有按优酷包名、视频域名或接口写特殊规则。

- 自然播完时报告 `STOPPED`，保留最终进度和时长；取消播完后的定时进度上报，避免重新变成 `PAUSED_PLAYBACK`。
- 缓冲、暂停、播完分别处理；给每次加载分配代次标识，忽略上一集迟到的状态或停止通知。
- `SetNextAVTransportURI` 保存下一条地址和元数据，当前视频不受影响；播完后消费一次队列，支持显式 `Next`，支持清空待播项。
- 修正 `GetMediaInfo` 的下一条信息，以及服务描述中的下一条动作。
- 实现 GENA 订阅、续订、取消订阅和按序发送的 `LastChange` 通知，包括自然播完的停止状态。
- SOAP 请求头大小写不敏感；按字节完整读取分段到达的 UTF-8 请求体；精确区分空的 `NextURI` 与 `NextURIMetaData`。
- 修正 RenderingControl / ConnectionManager SOAP 响应的服务命名空间。

不修改 AirPlay、Miracast 整屏镜像或手机 App 的逻辑。接收端不会自行解析视频平台的剧集接口。
自动连播仍要求发送端预先提供下一条地址，或在收到停止状态后主动发送新一集；手机后台限制、平台授权和专有协议仍可能影响结果。

## 构建

需要 JDK 17、Android SDK API 36、Build Tools 34.0.0、CMake 3.22.1 和 NDK 27.0.12077973。
已去掉仓库中写死的 Windows JDK 路径；设置 `JAVA_HOME`，并用 `local.properties` 配置 `sdk.dir`。

在 `MiracastReceiver/` 目录执行：

```sh
bash gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug -PbuildArm32 -PautonextBuild
```

`-PautonextBuild` 只为本地 debug 构建加上 `.autonext` 应用 ID 后缀和 `-autonext.1` 版本后缀。
它可以与官方版并行安装，避免用不同签名覆盖官方应用或删除原应用数据；两者接收服务使用相同端口，测试时只启动一个。
不带该参数时保留官方应用 ID。公开发布需要自行配置发布签名。

## 验证范围

回归测试覆盖播完后状态保持、延迟消息、缓冲/暂停区分、下一条缓存与清空、连续两次加载、手动停止、重新播放、分段中文 SOAP 请求、事件订阅的序号/续订/过期/取消，以及真实本机 TCP SOAP 和 NOTIFY 通信。

最终完整测试：111 项通过，0 失败、0 跳过，其中新增 DLNA 回归测试 14 项。ARM32 debug APK 构建成功，最低 API 21，v1 / v2 签名校验通过。
完整套件的一轮验证中，未修改的 `NameSetupTest.tokenProtectedFormSavesUnicodeAndRejectsInvalidRequests` 曾出现一次 `Connection reset`；未改动该组件，重新运行完整套件后通过。该原有网络测试仍可能受连接关闭时序影响。

本机协议测试模拟符合 UPnP 的控制端。随后已在 Konka-Projector-M62（API 21）真机通过两项测试：缓存下一段自动接续、发送端收到停止通知后再发送下一段。优酷手机端的真实自动下一集也已由用户实测确认通过；本次没有抓取该次优酷切集的协议记录。其他视频 App 仍需各自实测。

参考：

- [UPnP AVTransport:1](https://upnp.org/specs/av/UPnP-av-AVTransport-v1-Service.pdf)
- [官方 v1.2.13](https://github.com/weekdayjast/MiracastReceiver/releases/tag/v1.2.13)
