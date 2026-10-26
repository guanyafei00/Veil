# Veil

tvOS 风格的 Android TV / 盒子桌面（launcher），干净、轻量、纯原生 View 实现。

**基于 [Gathin23/cleanyourtv](https://github.com/Gathin23/cleanyourtv)（MIT License）二次开发** —— 感谢原作者的出色底子，本项目在其基础上做了大量修复、功能增强与界面重制。

## 下载（Releases）

| 安装包 | 体积 | 说明 |
| --- | --- | --- |
| [Veil-TV-v2.0.0-full.apk](https://github.com/guanyafei00/Veil/releases/download/v2.0.0/Veil-TV-v2.0.0-full.apk) | 6.9 MB | 含 24 张预置航拍静图 + 真 4K（3840×2160）默认壁纸，航拍扩充离线可用 |
| [Veil-TV-v2.0.0-lite.apk](https://github.com/guanyafei00/Veil/releases/download/v2.0.0/Veil-TV-v2.0.0-lite.apk) | 0.6 MB | 不含预置航拍图库，功能完全一致，航拍扩充走网络（360 图库，国内直连） |

```bash
adb install -r Veil-TV-v2.0.0-lite.apk
```

两个包包名相同（`com.veil.launcher`），可互相覆盖安装。安装后在系统设置里把 Veil 设为默认桌面。

## 特性

- **tvOS 风界面**：毛玻璃 Dock 与顶栏（壁纸实时取样模糊）、焦点缩放动画、三级设置菜单
- **航拍屏保与壁纸**：Apple TV 航拍视频流播放（114 段）、航拍静图轮换、一键扩充图库、4K 分辨率档
- **必应每日壁纸**：每日一图，4K 源，库存轮换
- **扫码上传壁纸**：电视显示二维码，手机扫码打开本地网页即可传图（内置 HTTP 服务器 + 零依赖 QR 编码器，无需安装任何 App）
- **桌面锁定**：无障碍服务拦截其他桌面，防家人误改默认桌面
- **控制中心**：本机 adb 环回（127.0.0.1:5555）实现设备信息 / 重启 / 关机
- **天气时钟**：Open-Meteo 免费源，中文城市搜索
- **应用管理**：文件夹 + 4 位密码锁、图标自定义与圆角、Dock 数量调节、隐藏应用
- **屏保**：航拍/必应壁纸混池，交叉淡化 + 缩放防烧屏
- 最低 **Android 6.0**（minSdk 23），无第三方功能库

## 两个安装包（按需选）

| 包 | 体积 | 内容 |
| --- | --- | --- |
| **full** | 约 6.9 MB | 含 24 张预置航拍静图 + **真 4K（3840×2160）**默认壁纸（AI 原生渲染上采样）：航拍图库扩充**开箱即用、无需网络** |
| **lite** | 约 0.6 MB | 不含预置航拍图库，默认壁纸为原生渲染 2048×1152（省体积），其余功能完全一致；航拍扩充走网络（360 图库，国内直连） |

两个包包名相同（`com.veil.launcher`），可互相覆盖安装；首次安装哪个都行。

## 构建

```
./gradlew assembleFullRelease assembleLiteRelease
```

产物：`app/build/outputs/apk/full/release/Veil-TV-full-release.apk`、
`app/build/outputs/apk/lite/release/Veil-TV-lite-release.apk`。

需要 JDK 17 与 Android SDK（compileSdk 34）。

## 许可证

MIT（见 [LICENSE](LICENSE)）。字体遵循 [SIL Open Font License](FONT-LICENSE-OFL.txt)。
