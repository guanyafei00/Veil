# Veil

tvOS 风格的 Android TV / 盒子桌面（launcher）。纯 Java、原生 View、包体小，主打 Apple 航拍视频壁纸 / 屏保与液态玻璃 UI。

**基于 [Gathin23/cleanyourtv](https://github.com/Gathin23/cleanyourtv)（MIT）二次开发** —— 在其基础上做了大量修复、功能增强与界面重制。许可见 [LICENSE](LICENSE)，第三方声明见 [NOTICE.md](NOTICE.md)。

当前版本：**v2.2.1 / versionCode 231**。

## 下载与安装

| 安装包 | 体积 | 说明 |
| --- | --- | --- |
| [Veil-TV-full-v2.2.1.apk](https://github.com/guanyafei00/Veil/releases/download/v2.2.1/Veil-TV-full-v2.2.1.apk) | 约 1.89 MiB | 离线兜底壁纸较高分辨率（约 850 KB） |
| [Veil-TV-lite-v2.2.1.apk](https://github.com/guanyafei00/Veil/releases/download/v2.2.1/Veil-TV-lite-v2.2.1.apk) | 约 1.30 MiB | 离线兜底壁纸较低分辨率（约 246 KB） |

两包功能完全一致，**唯一差异是 `default_wallpaper.jpg` 的体积**；包名同为 `com.veil.launcher`，可互相覆盖安装。所有壁纸与视频源均在线拉取，安装包内不预置壁纸图库。

```bash
adb install -r Veil-TV-lite-v2.2.1.apk
```

安装后在系统设置里把 Veil 设为默认桌面。设置面板里有「检查更新」（读取仓库根目录 `update.json`）。

> **TCL 电视注意**：应用内自更新在部分 TCL ROM 上会被系统安装器的校验链拦截；已在 Veil 桌面且是默认桌面的设备通常可通过，首次安装仍需 adb / U 盘 / 文件管理器手动一步。详见设置里的提示。

> **签名**：release 包使用 debug keystore 签名（侧载用途）。更换签名密钥会导致存量设备无法覆盖安装，请勿随意替换。

## 当前版本做了什么

### v2.2.1 — 黑屏全链路修复

- 换片、断流、缓冲期自动切静态图兜底，网络恢复后自动淡回视频；BUFFERING↔READY 震荡下用累计缓冲时间判定，不再被反复清零。
- 修复屏保静图被视频 SurfaceView 盖住导致的黑屏 —— 静图兜底移到 SurfaceView 之上。
- 5 秒 surface 看门狗：检测到"引擎自认在播但 surface 已死"时强制重建，下一帧上屏。
- Apple 中国 CDN（金山云节点）TLS 在电视上验不过 → 该域名强制 HTTP；错误重试用 `replaceMediaItem` 保住整条播放列表。
- 跳片 / 坏片段 / prepare 超时均有保护；`assets/aerial_fallback.jpg` 作为最后的兜底帧，纯视频模式也不会黑桌面。
- 返回桌面用 `player.stop()` + `setPlaying(false)` 挂起，避免 TCL ROM 回收后台 surface 后留下黑洞。

### v2.2.0 — 无缝播放、自定义视频源、玻璃化

- **ExoPlayer 播放列表**：全部片段洗牌后 `setMediaItems` + `REPEAT_MODE_ALL`，换片是解码级衔接，不再有约 10 秒黑帧。屏保左右键跳片走 `seekToNext/PreviousMediaItem`。
- **静态 ↔ 动态壁纸 / 屏保淡入淡出**，进屏保与回退都有过渡。
- **自定义视频源**：扫描 `/sdcard/Veil/videos` 下的本地视频、添加 http(s) 直链，播放中热更新队列不中断当前片段；支持手机扫码提交视频链接。
- **共享玻璃组件 `GlassSurface`**：设置面板与长按菜单统一材质；所有 AlertDialog 经 `Dialogs.style()` 一并玻璃化；设置浮层不再压暗桌面。

> 早期 v2.1.x 曾采用「下载再播 + LRU 600 MB / 4 条」的本地缓存方案，已在 v2.2.0 被在线播放列表取代，不再有本地视频缓存池。

## 特性

- **航拍视频壁纸与屏保**：114 段 Apple 航拍在线播放列表，无缝换片；失败自动退静态图 / 兜底帧 + 时钟，不黑屏
- **静态壁纸源**：必应每日 4K、360 图库、一键扩充、图库预览与上一张 / 下一张
- **扫码传图 / 加视频链接**：电视显示二维码，手机打开局域网页面即可上传壁纸或提交视频直链（内置 HTTP 服务，5 分钟有效，全接口 token 校验）
- **液态玻璃 UI**：毛玻璃 Dock 与顶栏、焦点缩放动画、三级设置菜单、昼夜质感
- **桌面守护**：无障碍服务 + 开机自启，防止其他桌面抢占；只拦竞争桌面，不影响普通 App
- **桌面锁定**：文件夹 / 应用 4 位密码锁
- **控制中心**：本机 adb 环回（127.0.0.1:5555）实现设备信息 / 重启 / 关机
- **天气时钟**：Open-Meteo 免费源，中文城市搜索
- **应用管理**：文件夹、图标自定义与圆角、Dock 数量、隐藏应用、图标自由换位、长按卸载
- **应用内更新**：检查 `update.json` 并调起系统安装器
- 最低 **Android 6.0**（minSdk 23，targetSdk / compileSdk 34）

## 故障排查

**桌面 / 屏保全黑**

1. 确认装的是最新版 —— v2.2.0 及更早版本缺少兜底帧、surface 看门狗与静图 z 序修复，断网时容易黑屏。
2. 视频源不稳时会自动退静态壁纸与时钟；若仍全黑，在「设置 → 壁纸」切到必应 / 航拍静图验证是否为视频链路问题。
3. 开发者可抓日志：`adb logcat -d -s Veil:V`。正常链路有 `video prepared in Xs`；`video prepare timeout` / `video error` 表示源或解码异常；反复出现 `onPlayingChanged true -> cross-fade` 却没有 `prepared` 说明引擎在空转，需要重启桌面 App。
4. 真机验证请 `adb exec-out screencap -p > screen.png` 看实际画面 —— 日志正常不等于画面正常。

**忘记锁屏 / 文件夹密码**

当前没有电视端自助重置入口，且备份 JSON 内含明文密码（妥善保管）。有 adb 时可执行 `adb shell pm clear com.veil.launcher` 重置，代价是桌面布局、壁纸、文件夹全部清空并需重新设为默认桌面。

## 构建

```bash
./gradlew assembleFullRelease assembleLiteRelease
```

产物：`app/build/outputs/apk/full/release/Veil-TV-full-release.apk`、`app/build/outputs/apk/lite/release/Veil-TV-lite-release.apk`。

需要 JDK 17 与 Android SDK（compileSdk 34）；`local.properties` 里配置 `sdk.dir`。

依赖：AndroidX recyclerview / dynamicanimation / core、media3-exoplayer 1.4.1（均为 Apache-2.0）；junit 仅测试用。其余 HTTP、QR、模糊、JSON 均为手写实现。

## 已知限制

- 仅实测过斐讯 T1（Android 7.1.2）与 TCL 85T7K / mt5879（Android 11），"老盒子兼容"仍待更多机型验证。
- 114 条 Apple 航拍直链借用 Apple CDN，可能失效、区域受限或被封禁；届时功能降级为静态壁纸。素材版权归 Apple Inc.。
- 锁屏密码以明文存于本地 `SharedPreferences`，备份 JSON 亦含明文；请勿把备份文件放到共享目录。
- 无 CI、无 androidTest；42 个 JVM 单测只覆盖 Cards 卡片校验。发版前依赖真机验证。

## 许可证

[MIT](LICENSE)（保留上游 cleanyourtv 的版权行）。Outfit 字体遵循 [SIL Open Font License](FONT-LICENSE-OFL.txt)。第三方组件与素材汇总见 [NOTICE.md](NOTICE.md)。
