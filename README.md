#  摸鱼 TV · moyutv

![](https://img.shields.io/badge/Platform-Android%20TV%20%2F%20Box-2e7d32?style=flat-square&logo=android)
![](https://img.shields.io/badge/API-21%2B-3ddc84?style=flat-square&logo=android)
![](https://img.shields.io/badge/Language-Kotlin-7f52ff?style=flat-square&logo=kotlin)
![](https://img.shields.io/badge/License-MIT-blue?style=flat-square)


一款专为 Android TV / 电视盒子打造的**高性能双引擎 IPTV 播放器**。
支持 TXT / M3U 列表与订阅、PHP / Python / JS 本地与远程脚本、CatVod(TVBox) 爬虫协议、EPG 节目单与回看，并针对遥控器大屏操作与弱网环境做了深度优化。

> ⚠️ 本项目**不内置、不提供任何直播源与会员内容**，仅提供播放器与脚本运行能力。直播源的合法性由使用者自行负责，请勿用于非法用途。

---

## ✨ 功能特性

### 🎬 双播放引擎，按需自动切换

- **ExoPlayer（Media3，默认）**：HLS / DASH / MP4 / RTMP / RTP 等标准流媒体协议，硬件解码、超低延迟。
- **libmpv（自研 JNI）**：补齐老旧盒子缺失的解码能力（典型如 AC3/MP2 音频"有画无声"），RTSP 等协议自动交由 MPV 播放。
- 播放异常自动降级 / 切换引擎；4K 高码率频道深度适配。

### 📺 丰富的直播源格式

- **列表**：TXT（`#genre#` 分组）、M3U / M3U8；同名频道多线路自动合并，线路可切换、失败自动重试。
- **订阅**：多组 http(s) 订阅地址统一管理；本地 `摸鱼TV/list/` 目录下的 `.txt` / `.m3u` 文件**自动加载**。
- **特殊地址**：`webview://`（网页源，YouTube 网页直播自动识别并代理播放）、`rtsp://`、`rtmp://`、`rtp://` 等。
- **代理注释**：直接在地址后追加代理，支持 socks5 / http 与代理池订阅：

```text
socks5代理格式: 
CCTV1,http://target.m3u8&socks5://host:port
CCTV1,http://target.m3u8?socks5://host:port
CCTV1,http://target.m3u8&http_proxy=host:port
CCTV1,socks5://host:port/http://target.m3u8
CCTV1,sk://ip:port&http://target.m3u8

http代理格式: 
CCTV1,http://target.m3u8&http_proxy=host:port
CCTV1,http://target.m3u8?http_proxy=host:port
CCTV1,http://target.m3u8&http_proxy://host:port
CCTV1,http://target.m3u8?http_proxy://host:port 
```

### 🧩 PHP / Python / JS 脚本支持

本地脚本放入电视存储对应目录，频道行以 `local://` 引用：

| 语言 | 目录 | 频道行示例 |
|---|---|---|
| JS | `摸鱼TV/js/` | `CCTV1,local://migu.js/63178053` |
| PHP | `摸鱼TV/php/` | `海南卫视,local://hitv.php?id=hnws` |
| Python | `摸鱼TV/py/` | `CCTV1,local://ysp.py?ch=cctv1` |

- Python 脚本额外完整兼容 **CatVod / TVBox 爬虫协议**（`homeContent` / `categoryContent` / `detailContent` / `playerContent` / `searchContent` + `ext` 站点配置），同时兼容老式"打印 URL"写法：

  ```text
  湖南卫视,local://live.py?api=playerContent&args=%5B%22%22%2C%22%E6%B9%96%E5%8D%97%E5%8D%AB%E8%A7%86%22%2C%5B%5D%5D
  ```

- JS 脚本支持远程 http(s) 地址直接引用。
- PHP 脚本的 HTTPS / 加密请求由**应用内代理链路**自动完成（TLS 与证书校验在 App 侧完成），无需 root、无需折腾真机证书。

### 📅 EPG 节目单与回看

- 内置 EPG 数据源（可更换），**模糊拼音 + 字形相似度**算法自动匹配台名，重定向 / GZIP 自动处理，省流量。
- 支持历史时段回看（`playseek`，取决于直播源自身支持）。

### 📶 弱网与代理优化

- 播放前预取、自适应缓冲策略（起播 / 切台超时自动放宽、卡顿自动等待恢复）。
- socks5 直连、RTSP over socks5 隧道、本机回环中继转发。

### 🎛️ TV 大屏交互

- 防丢焦 D-Pad 适配，列表焦点平滑移动；数字键直选频道；OK 键呼出频道列表与设置菜单。
- 收藏固定置顶（★ 标记）；退出后自动恢复上次频道与分类。
- OSD 信息栏：时间、今明天气、实时码率。
- 二维码分享：直播源 / 订阅生成二维码，手机扫码即用。
- APK 在线检查更新（`update.json` 可自建更新源）。
- 内置 P2P 兼容服务（Forcetech P2P / P3P）。

---

## 📂 电视本地目录结构

```text
/sdcard/摸鱼TV/
├── php/    # PHP 脚本
├── py/     # Python 脚本
├── js/     # JS 脚本
├── list/   # 本地列表（.txt / .m3u，自动加载）
└── bin/    # 可选：自备 php 解释器二进制
```

## 📦 仓库附带示例

| 文件 | 说明 |
|---|---|
| `脚本列表示例.txt` | `local://` 频道行各种写法示例 |
| `migu.js` / `fhx.js` | JS 脚本示例 |
| `ysp.py` | Python 爬虫脚本示例 |
| `gdnz.php` | PHP 脚本示例 |
| `live.m3u` | M3U 列表示例 |
| `update.json` | 在线更新描述文件示例 |

---

## 🛠️ 编译构建

### 环境要求

- Android Studio **Koala** 或更高版本
- Android **SDK 34**、**NDK 25+**
- **Python 3.10**（Chaquopy 构建期依赖；`app/build.gradle.kts` 中 `buildPython` 需改为本机 Python 路径）
- 目标 ABI：`armeabi-v7a`（32 位；P2P 相关库暂无 arm64-v8a 版本）

### 步骤

1. 克隆仓库，用 Android Studio 打开项目根目录；
2. 确认 `local.properties` 指向本机 Android SDK；
3. 执行 `gradlew :app:assembleDebug`，或直接在 IDE 内构建。

> ⚠️ **开源版说明**：本仓库完整开放全部源代码，没有隐藏或省略核心代码，不存在源码不全的情况，拉取仓库后即可直接编译。

---
## ❓ 常见问题

**Q：部分频道有画面、没声音？**
老盒子通常缺 AC3 / MP2 等音频解码器。在设置中切换 **MPV 引擎**播放即可（此类频道也会自动走 MPV）。

**Q：脚本频道不生效？**
确认脚本位于上文目录、扩展名正确、频道行写法与示例一致；修改脚本后重启 App 生效。

**Q：PHP 脚本请求 HTTPS 报错？**
应用会自动经本机代理完成 TLS 与证书校验，通常无需处理。若仍失败，多为脚本自身的签名 / 凭证过期（可对比脚本在 TVBox 中的表现）。

**Q：EPG 匹配不到台名？**
列表台名与 EPG 数据差异过大时会失配，可在设置中更换更匹配的 EPG 数据源。

**Q：列表很大、操作卡顿？**
建议按分组拆分 TXT / M3U，或改用订阅方式导入。

---
## 🚀 软件截图
<img width="1280" height="720" alt="1d1492c2-6b65-48d2-b310-a0fa54677e65" src="https://github.com/user-attachments/assets/2f8e84ec-33c7-4bf8-8083-eb7e403a5f7d" />
<img width="1280" height="720" alt="6dc63007-f906-4cf6-9dda-ec1667838ceb" src="https://github.com/user-attachments/assets/dbffac87-1a05-4b21-93ae-f179bd1fca6a" />
<img width="1280" height="720" alt="1ba4763d-c233-4589-ae70-9d19f1639922" src="https://github.com/user-attachments/assets/aa201188-8723-4408-a2a5-ed1520996abc" />
<img width="1280" height="720" alt="0d0055ee-0917-4e91-bb22-387ffe49db5e" src="https://github.com/user-attachments/assets/dc4908d5-bcb5-4c97-ac41-ed210a45e3c7" />

---
## 📜 开源协议

本项目采用 [MIT License](LICENSE) 开源协议。

*(注意：第三方组件（libmpv / FFmpeg、AndroidX Media3 ExoPlayer、Chaquopy、NanoHTTPD、Mozilla Rhino、腾讯 X5 等）依其各自协议发布，版权归原作者所有。
)*

## 🤝 致谢

- [libmpv / FFmpeg](https://mpv.io)
- [AndroidX Media3 · ExoPlayer](https://developer.android.com/media/media3/exoplayer)
- [Chaquopy](https://chaquo.com)
- [NanoHTTPD](http://nanohttpd.org)
- [Mozilla Rhino](https://github.com/mozilla/rhino)

