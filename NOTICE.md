# NOTICE — 第三方组件与素材

Veil 主体采用 MIT 许可，见 [LICENSE](LICENSE)。本文件汇总随包分发或运行时依赖的第三方资产。

## 上游项目

- **cleanyourtv**（Gathin23）— MIT，Copyright (c) 2026 Gathin。Veil 由其派生，`LICENSE` 保留原版权行与许可全文。

## 随 APK 分发的素材

- **Outfit 字体** — SIL Open Font License 1.1，Copyright 2021 The Outfit Project Authors。许可全文见 `FONT-LICENSE-OFL.txt`。
- **aerial_fallback.jpg**（`app/src/main/assets/`）— 从一段 Apple 航拍视频抽取的单帧，仅作断流/播放失败时的兜底静图。
- **default_wallpaper.jpg**（`app/src/full/assets/`、`app/src/lite/assets/`）— 离线兜底壁纸。两 flavor 仅此图分辨率与体积不同（约 850 KB / 246 KB），是 full 与 lite 包体积差异的唯一来源。

## 运行时在线获取（不随 APK 分发）

- **Apple 航拍视频**：`app/src/main/assets/videos.json` 收录 114 条 Apple CDN 直链，运行时在线播放。素材版权归 Apple Inc. 所有，本项目既不托管也不打包视频文件。链接可能失效、受区域限制或被封禁；届时功能降级为静态壁纸与时钟。
- **必应每日壁纸 / 360 图床 / Open-Meteo 天气**：运行时在线拉取，内容版权归各自权利人。

## 代码与依赖

- **AdbShell.java**：ADB 协议帧格式部分参考 [moz1u/dadb](https://github.com/moz1u/dadb)（Apache-2.0）；ADB 线协议本身来自 AOSP。该文件其余部分随本项目 MIT 授权。
- **AndroidX** — recyclerview 1.3.2 / dynamicanimation 1.0.0 / core 1.10.1；**androidx.media3:media3-exoplayer** 1.4.1 — 均为 Apache-2.0。
- **junit 4.13.2** — EPL 1.0，仅 `src/test` 使用，不进入 APK。

## 分发说明

本项目为侧载分发：release 包使用 debug keystore 签名，不通过应用商店校验。安装前请自行核验 APK 来源与完整性。
