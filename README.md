# Premium IPTV Player for Android TV

[![Platform](https://img.shields.io/badge/Platform-Android%20TV%20%2F%20Leanback-green.svg)](https://developer.android.com/tv)
[![API Level](https://img.shields.io/badge/API-21%2B-blue.svg)](https://developer.android.com/about/dashboards)
[![Build Status](https://img.shields.io/badge/Build-Passing-brightgreen.svg)]()

一款专为 Android TV / 电视盒子打造的**高性能双引擎 IPTV 播放器**。针对大屏及遥控器操作进行了深度定制与极致优化，适配主流M3U/TXT列表文件、智能 EPG、多协议代理及软硬双解码支持。

---

## 🌟 核心特性

### 1. 🚀 双播放引擎架构
*   **ExoPlayer**: 作为默认播放引擎，完美支持 HLS (m3u8)、DASH、MP4 等标准流媒体协议，硬件加速解码，超低延迟。
*   **MPV Player**: 集成自研 JNI 及 `libmpv.so`，彻底解决部分老旧电视盒子因缺少硬件解码器而导致部分频道**有图无声 (AC3/MP2 音频格式)** 的问题。

### 2. ⚡ 媒体格式全支持
*   **socks5**: 示例格式: CCTV1,sk://ip:port&http://live2.rxip.sc96655.com/live/CCTV-1H265_4000.m3u8?E=1&U=1&A=1&K=1&P=1&S=1
*   1.2.3以后版示例格式：
*   CCTV1,http://target.m3u8?q=1&socks5://host:port
*   CCTV1,http://target.m3u8?socks5://host:port
*   CCTV1,http://target.m3u8?q=1&socks5=host:port
*   CCTV1,http://target.m3u8?socks5=host:port
*   CCTV1,socks5://host:port/http://target.m3u
*   CCTV1,http://target.m3u8&http_proxy=host:port
*   CCTV1,http://target.m3u8?http_proxy=host:port
*   CCTV1,http://target.m3u8&http_proxy://host:port
*   CCTV1,http://target.m3u8?http_proxy://host:port
。
*   **webview**: 示例格式: CCTV1,webview://https://yangshipin.cn/tv/home?pid=600001859

### 3. ⚡ JS 脚本支持
*   **本地脚本**: 本地的 JS 脚本（比如 migu.js）塞进电视机根目录下的 摸鱼TV/js 文件夹里，然后在直播源里写 local://migu.js/63178053
。
*   **远程脚本**: JS 脚本（比如 migu.js）放在远程服务器、空间、网盘里，然后在直播源里写 https://你的远程地址/migu.js/63178053

### 4. ⚡ YouTube 网页直播
*   **油管webview**: 自动识别到 YouTube 网页，即启动代理播放。
*   **代理设置**: 支持单条代理URL设置，和代理池订阅。

### 5. 🎯 智能 EPG 模糊匹配
*   **高性能匹配**: 采用高度优化的模糊拼音及字符相似度算法，自动将播放列表中的台名与 EPG 节目单进行高精度匹配。
*   **强兼容性**: EPG 数据抓取支持 HTTP 重定向跟随以及 GZIP 压缩自动解压，大幅降低网络流量并提升提升解析速度。

### 6. 🎛️ 遥控器与 TV UI 极致适配
*   **防丢焦设计**: 专为遥控器十字键 (D-Pad) 优化，列表焦点移动平滑，杜绝按键无响应及焦点丢失问题。
*   **快捷选台**: 稳定支持数字键直接选台及 OK/CENTER 键呼出频道列表与设置菜单。

---
## 🚀 软件截图
<img width="1280" height="720" alt="1d1492c2-6b65-48d2-b310-a0fa54677e65" src="https://github.com/user-attachments/assets/2f8e84ec-33c7-4bf8-8083-eb7e403a5f7d" />
<img width="1280" height="720" alt="6dc63007-f906-4cf6-9dda-ec1667838ceb" src="https://github.com/user-attachments/assets/dbffac87-1a05-4b21-93ae-f179bd1fca6a" />
<img width="1280" height="720" alt="1ba4763d-c233-4589-ae70-9d19f1639922" src="https://github.com/user-attachments/assets/aa201188-8723-4408-a2a5-ed1520996abc" />
<img width="1280" height="720" alt="0d0055ee-0917-4e91-bb22-387ffe49db5e" src="https://github.com/user-attachments/assets/dc4908d5-bcb5-4c97-ac41-ed210a45e3c7" />

---
## 🏗️ 编译与运行

### 前提条件 (Prerequisites)
1.  **Android Studio** Koala (或更高版本)。
2.  **Android SDK 34**。
3.  **NDK 25+** (项目包含针对 `armeabi-v7a` 和 `arm64-v8a` 的预编译 `libmpv` 和 C++ 代码库)。

### 编译步骤
1.  克隆本项目到本地。
2.  用 Android Studio 打开项目根目录。
3.  确保 `local.properties` 正确指向您的 Android SDK 路径。
4.  **注意**：如果是直接从 GitHub 下载的开源版本，由于缺少源文件，直接编译会报错。您需要自行实现这些占位接口或使用本地备份文件覆盖恢复。

---

## 📜 开源协议

本项目采用 [MIT License](LICENSE) 开源协议。

*(注意：项目中引用的第三方二进制库如 libmpv, ForceTV 库等其版权归原作者所有)*
