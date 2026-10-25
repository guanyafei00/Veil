# Veil

tvOS 风格的 Android TV / 盒子桌面（launcher），干净、轻量、纯原生 View 实现。

**基于 [Gathin23/cleanyourtv](https://github.com/Gathin23/cleanyourtv)（MIT License）二次开发** —— 感谢原作者的出色底子，本项目在其基础上做了大量修复、功能增强与界面重制。

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
- 最低 **Android 6.0**（minSdk 23），APK 约 300KB，无第三方功能库

## 构建

```
./gradlew assembleRelease
```

需要 JDK 17 与 Android SDK（compileSdk 34）。

## 许可证

MIT（见 [LICENSE](LICENSE)）。字体遵循 [SIL Open Font License](FONT-LICENSE-OFL.txt)。
