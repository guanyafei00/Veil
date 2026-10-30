# Veil 安装与调试工具

本目录下放了两个给**普通用户 / 开发者**用的 Windows 批处理脚本，目标是把"硬核侧载"变成"双击一下"。

> 前置条件：电脑上要有 Android `adb`（platform-tools）。
> 三种定位方式，脚本会按顺序找：① 系统 `PATH` 里的 `adb`；② 脚本同目录的 `adb.exe`；③ 脚本同目录的 `platform-tools\adb.exe`；④ 工作区的 `..\android-sdk\platform-tools\adb.exe`。
> 电视需已打开「开发者选项 → 网络 ADB 调试 / USB 调试」，并允许本机调试。

---

## 1. install-veil.bat —— 一键安装（含 TCL 验证器绕过）

**它做了什么（5 步）：**
1. `adb connect <电视IP>:5555` 连接电视
2. **临时禁用 TCL 的包校验器** `pm disable-user --user 0 com.android.packageinstaller`（绕过 `INSTALL_FAILED_VERIFICATION_FAILURE`）
3. `adb install -r <APK>` 安装 / 覆盖安装 Veil
4. **恢复** TCL 包校验器 `pm enable com.android.packageinstaller`（用完即恢复，不留安全隐患）
5. `cmd package set-home-activity com.veil.launcher/.MainActivity` 设为默认桌面

**用法：**
```
install-veil.bat                 # 用默认电视 IP 192.168.31.205，自动找同目录的 Veil-TV-*-full.apk
install-veil.bat 192.168.1.50    # 指定电视 IP
install-veil.bat 192.168.1.50 D:\apk\Veil-TV-v2.1.6-full.apk   # 指定 IP + APK 路径
```
APK 查找顺序：命令行第 2 参数 → 脚本同目录的 `Veil-TV-*-full*.apk` → `..\app\build\outputs\apk\full\release\Veil-TV-full-release.apk`。

> 默认电视 IP 是主人那台 TCL（192.168.31.205）。换成你自己的电视 IP 即可。
> 非 TCL 电视上第 2 步会自动失败（包名不同），脚本会跳过继续安装，无害。
> 装完后若想让 Veil **顶住原厂桌面的抢夺**，请到电视「设置 → 无障碍」里打开 Veil 的 launcher guard 服务。

---

## 2. keep-adb.bat —— 保活网络 ADB

**它做什么：** 每 20 秒检查一次 `adb devices`，发现电视掉线就自动 `adb connect` 重连。这样你可以持续给电视推更新，不用每次都去电视上重新打开无线调试。

**用法：**
```
keep-adb.bat                 # 守护默认 IP 192.168.31.205
keep-adb.bat 192.168.1.50    # 守护指定 IP
```
按 `Ctrl+C` 退出。

> 说明：非 root 的电视在**重启后**可能会关掉网络 ADB，此时 keep-adb 能重连的前提是电视上的无线调试又被打开——脚本没法替你自动打开，需要你在电视上点一下。它解决的是"连接莫名掉线"而非"重启后自动重开调试"。

---

## 3. 已知坑（来自真实 TCL 调试）

- TCL 的 OEM 包校验器坏了：`INSTALL_FAILED_VERIFICATION_FAILURE`，logcat 会显示它拿别的 App 签名当参照。本脚本的「禁用→安装→恢复」三连是已验证的绕过法。
- **切勿**用 `pm uninstall -k` 想"保数据重装"——在 TCL 上内部 SharedPreferences 不会保住，默认桌面偏好也会丢。直接走上面的覆盖安装（`install -r`）即可。
- MTK 视频层截屏/录屏截不到（硬件叠加层），判断视频是否在播要以电视实机肉眼为准。
