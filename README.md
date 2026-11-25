<div align="center">

<img src="docs/img/icon.svg" width="128" height="128" alt="GodotTool 应用图标" />

# GodotTool

**恢复 Godot 项目，追踪版本变化**

<p>
  <img src="https://img.shields.io/badge/Android-9.0%2B-3DDC84?style=flat-square&amp;logo=android&amp;logoColor=white" alt="Android 9.0 及以上" />
  <img src="https://img.shields.io/badge/ABI-arm64--v8a-475569?style=flat-square" alt="arm64-v8a" />
  <a href="https://github.com/tingdong105/GodotTool/releases/latest"><img src="https://img.shields.io/github/v/release/tingdong105/GodotTool?style=flat-square&amp;label=Release&amp;color=22C55E&amp;logo=github&amp;logoColor=white" alt="最新 Release" /></a>
</p>

<p>
  <a href="https://github.com/tingdong105/GodotTool/releases">📦 下载版本</a>
  &nbsp; · &nbsp;
  <a href="#-功能亮点">✨ 功能亮点</a>
  &nbsp; · &nbsp;
  <a href="#-快速上手">🚀 快速上手</a>
  &nbsp; · &nbsp;
  <a href="#-版本对比">🔎 版本对比</a>
  &nbsp; · &nbsp;
  <a href="https://github.com/tingdong105/GodotTool/issues">💬 问题反馈</a>
</p>

</div>

---

## 关于项目

**GodotTool** 是一款使用 Kotlin 与 Jetpack Compose 开发的 Android 端 Godot 项目恢复与版本对比工具。

## ✨ 功能亮点

| | 功能 | 说明 |
| :---: | :--- | :--- |
| 🧩 | 工程恢复 | 恢复项目脚本、场景、资源配置、素材、插件与项目配置，按原有路径整理结果。 |
| 🗂️ | 对比快照 | 解包时可生成快照文件，分析独立 GDScript、场景与资源中的内嵌脚本及插件脚本，供后续版本对比复用。 |
| 🌗 | 外观设置 | 支持跟随系统、浅色与深色模式，可选悬浮底栏及液态玻璃效果。 |
| 💾 | 自动保存结果 | 解包结果保存为 ZIP，对比结果另存为 `change.txt`；按日期与次数命名，保留历史产物。 |
| 🔎 | 方法和字段对比 | 导入同一项目两个版本的快照或解包 ZIP，分别查看方法、类级常量与变量的新增、修改和删除。 |
| 🧹 | 过滤和关心规则 | 按方法名、字段名或路径排除条目、突出关心的变化，支持包含、开头、后缀、完全、通配符和正则匹配。 |
| ⏳ | 任务进度与记录 | 显示整体进度、处理时长和关键日志；支持取消处理、回看结果、批量删除记录与缓存。 |

## 🚀 快速上手

1. 在 [Releases](https://github.com/tingdong105/GodotTool/releases) 下载已发布版本。
2. 在首页的第一步点击「选择文件」，按提示授予存储访问权限，选择一个 APK、PCK 或 Godot 资源 ZIP。
3. 第二步选择「跟随设置」，或关闭该开关，推荐范围为「仅脚本文件」。
4. 需要比较版本时，提前开启「生成对比快照」；随后进入第三步，确认处理内容并开始。
5. 完成后查看项目文件与恢复报告，或到共享存储的 `GodotTool/` 目录获取 ZIP。

### 选择适合的恢复范围

| 恢复方式 | 适用场景 |
| :--- | :--- |
| **完整** | 希望尽可能恢复项目文件，后续在 Godot 编辑器中检查和修复。 |
| **仅脚本** | 只需要阅读项目脚本，不导出插件、场景、素材和项目配置。 |
| **自定义** | 按需组合「脚本代码」「场景与资源」「素材文件」「插件内容」「数据与配置」五类内容。 |

### 生成结果

| 内容 | 说明 |
| :--- | :--- |
| `unpacked/` | 按所选范围整理的项目文件。 |
| `original/` | 开启「保留原始资源」时附带的原始副本。 |
| `report.md` | 根目录恢复报告，包含处理统计与诊断信息。 |
| `diff.gt` | 开启「生成对比快照」时附带的版本快照；|

## 🔎 版本对比

### 开始对比

1. 在「设置 → 解包」中开启「生成对比快照」，或在首页关闭「跟随设置」后为本次任务单独开启。
2. 分别解包同一项目的旧版本和新版本，获得两个包含 `diff.gt` 的 ZIP。
3. 打开底部「对比」，分别选择旧版本与新版本。可直接选择解包 ZIP，也可以选择从中取出的 `diff.gt`，无需重新导入原 APK。
4. 确认方向后点击「开始对比」；选反时可使用「交换版本」。
5. 在结果页查看变化，后续也可以从「对比记录」重新打开。

### 阅读与筛选

| 模式 | 查看内容 |
| :--- | :--- |
| **方法模式** | 具名方法的新增、修改和删除，包括方法声明与内部内容的变化。 |
| **字段模式** | 类级常量、变量的新增、修改和删除，以及无法确认的条目及原因。 |
| **关心模式** | 在当前方法或字段范围内，仅显示命中关心规则的变化。 |

### 过滤规则与关心规则

在「设置 → 对比」中分别管理两组规则：

- **过滤规则**：隐藏不需要查看的变化。
- **关心规则**：对命中的变化加粗、着色，也可以通过关心模式集中查看。

## 🛠️ 从源码构建

<details>
<summary><strong>展开查看环境准备与构建步骤</strong></summary>

### 构建环境

| 工具 | 要求 |
| :--- | :--- |
| JDK | Gradle Daemon 配置为 JDK 25，应用 JVM 编译目标为 21。 |
| Python | 3.11 或以上，用于准备原生核心与依赖。 |
| Android SDK | 安装项目要求的 API 37.1 平台。 |
| NDK / CMake | 按 `gradle/libs.versions.toml` 中固定的版本安装。 |

### 1. 获取源码

```powershell
git clone https://github.com/tingdong105/GodotTool.git
cd GodotTool
```

在 Android Studio 中打开项目并配置 Android SDK，确保命令行可使用 `python`。

### 2. 构建 APK

```powershell
.\gradlew.bat assembleDebug
```

Debug APK 输出至 `app/build/outputs/apk/debug/`。

```powershell
.\gradlew.bat assembleRelease
```

Release APK 输出至 `app/build/outputs/apk/release/`。

</details>

## 🤝 参与项目

欢迎通过 [Issues](https://github.com/tingdong105/GodotTool/issues) 反馈问题或提出建议，也欢迎提交 Pull Request。

报告恢复问题时，附上 **应用版本、Android 版本、设备型号、输入文件类型、复现步骤及恢复报告**，会更方便定位原因。

## 💙 致谢

感谢这些项目为 GodotTool 提供核心能力：

| 项目 | 用途 |
| :--- | :--- |
| [GDRE Tools](https://github.com/GDRETools/gdsdecomp) | Godot 资源解析、脚本反编译与工程恢复核心。 |
| [Godot Engine](https://github.com/godotengine/godot) | 引擎及 Android 桥接基础。 |
| [miuix](https://github.com/compose-miuix-ui/miuix) | Compose 界面组件与导航。 |
| [MMKV](https://github.com/Tencent/MMKV) | 设置与任务数据持久化。 |
| [Capstone](https://github.com/capstone-engine/capstone) | ARM64 指令分析。 |
| [Moshi](https://github.com/square/moshi) | 对比快照与记录的流式 JSON 读写。 |
| [Lottie](https://github.com/airbnb/lottie-android) | 首页步骤与处理进度动画。 |
| [Coil](https://github.com/coil-kt/coil) | 关于页开发者头像加载。 |

第三方组件遵循各自的许可证，构建准备脚本会收集相关许可文本；可在应用的「关于软件 → 开源许可」中离线阅读。

---

<div align="center">
  <sub>GodotTool</sub>
</div>
