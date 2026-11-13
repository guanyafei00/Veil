# Veil

tvOS 风格的 Android TV / 盒子桌面（launcher）。纯 Java、原生 View、包体小，主打 Apple 航拍视频壁纸 / 屏保与液态玻璃 UI。

**基于 [Gathin23/cleanyourtv](https://github.com/Gathin23/cleanyourtv)（MIT）二次开发** —— 在其基础上做了大量修复、功能增强与界面重制。许可见 [LICENSE](LICENSE)，第三方声明见 [NOTICE.md](NOTICE.md)。

当前版本：**v2.2.4 / versionCode 234**。

## 下载与安装

| 安装包 | 体积 | 说明 |
| --- | --- | --- |
| [Veil-TV-full-v2.2.4.apk](https://github.com/guanyafei00/Veil/releases/download/v2.2.4/Veil-TV-full-v2.2.4.apk) | 约 1.89 MiB | 离线兜底壁纸较高分辨率（约 850 KB） |
| [Veil-TV-lite-v2.2.4.apk](https://github.com/guanyafei00/Veil/releases/download/v2.2.4/Veil-TV-lite-v2.2.4.apk) | 约 1.30 MiB | 离线兜底壁纸较低分辨率（约 246 KB） |

两包功能完全一致，**唯一差异是 `default_wallpaper.jpg` 的体积**；包名同为 `com.veil.launcher`，可互相覆盖安装。所有壁纸与视频源均在线拉取，安装包内不预置壁纸图库。

```bash
adb install -r Veil-TV-lite-v2.2.4.apk
```

> **签名**：所有发布包使用同一套签名，可相互覆盖安装。更换签名密钥会导致存量设备无法覆盖安装，请勿随意替换。

### 首次上手三步

1. 安装 APK 后，在系统设置里把 Veil 设为默认桌面。部分 ROM 会弹「主屏幕应用」选择器，选「始终」。
2. 按需在系统「无障碍 / 辅助功能」里开启 Veil 的桌面守护；不开也能正常使用，只是无法阻止其他桌面抢占。
3. 需要本机控制（设备信息 / 重启 / 关机）时，再按设置内提示完成 adb 授权。

设置面板里的「检查更新」会读取仓库根目录 `update.json`。

> **TCL 电视注意**：应用内自更新在部分 TCL ROM 上会被系统安装器的校验链拦截；已在 Veil 桌面且是默认桌面的设备通常可通过，首次安装仍需 adb / U 盘 / 文件管理器手动一步。

## 近期更新

### v2.2.4 — 胶囊圆角与液态玻璃可读性

- 右上角两个胶囊改为框架级裁切的真圆角（`ViewOutlineProvider` + `clipToOutline`），跨 ROM 不再出现方角。
- 保持半透明液态玻璃材质：烟灰主体 + 顶部高光 + 亮描边 + 底部暗折射边；可读性改由白字柔和深阴影承担，无图标（纯壁纸）桌面上不再出现深色块。
- 设置面板、对话框与选项列表文字统一提亮，聚焦态对比度同步修正。
- 「检查更新」失败的通用提示改为简体中文。

### v2.2.3 — 自更新修复 + 设置面板整理

- 更新清单从单源改为四源回退（cdn / fastly / gcore / raw），任一镜像可达即可检查新版本。
- 设置面板补齐 17 个简体中文词条、条目重新分组；退出二三级菜单后再进入自动回到一级列表。

### v2.2.2 / v2.2.1 — 黑屏根治

- 播放位置冻结守卫（桌面与屏保共用）+ 断流 / 缓冲期静态图兜底 + surface 看门狗，全面消除黑屏。
- 局域网接口鉴权与上传流式落盘加固；新增 MIT LICENSE 与 NOTICE.md。

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

当前没有电视端自助重置入口，备份文件包含桌面配置与凭据，请妥善保管。有 adb 时可执行 `adb shell pm clear com.veil.launcher` 重置，代价是桌面布局、壁纸、文件夹全部清空并需重新设为默认桌面。

## 已实测机型

| 设备 | 系统 | 状态 |
| --- | --- | --- |
| 斐讯 T1（q201） | Android 7.1.2 | 主要验证机，全功能实拍验证 |
| TCL 85T7K（mt5879） | Android 11 | 已实测；首次安装需手动一步，部分 ROM 应用内自更新受系统校验链拦截 |

其它机型尚未验证，欢迎在 Issues 反馈实机表现。

## 构建

```bash
./gradlew assembleFullRelease assembleLiteRelease
```

产物：`app/build/outputs/apk/full/release/Veil-TV-full-release.apk`、`app/build/outputs/apk/lite/release/Veil-TV-lite-release.apk`。

需要 JDK 17 与 Android SDK（compileSdk 34）；`local.properties` 里配置 `sdk.dir`。

依赖：AndroidX recyclerview / dynamicanimation / core、media3-exoplayer 1.4.1（均为 Apache-2.0）；junit 仅测试用。其余 HTTP、QR、模糊、JSON 均为手写实现。

## 已知限制

- 114 条 Apple 航拍直链借用 Apple CDN，可能失效、区域受限或被封禁；届时功能降级为静态壁纸。素材版权归 Apple Inc.。
- 桌面锁凭据仅保存在本机；备份文件请妥善保管，勿放共享目录。
- 自动化测试覆盖有限（42 个 JVM 单测），发版前依赖真机验证。
- 老盒子兼容性依赖社区反馈，目前实测机型见上表。

## 许可证

[MIT](LICENSE)（保留上游 cleanyourtv 的版权行）。Outfit 字体遵循 [SIL Open Font License](FONT-LICENSE-OFL.txt)。第三方组件与素材汇总见 [NOTICE.md](NOTICE.md)。
