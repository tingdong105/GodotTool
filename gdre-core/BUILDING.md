# 核心构建与验证

主应用使用 GDRE Tools 2.7.4 官方 Android 发行包内的 arm64 原生核心，配套 Android 桥接源码固定到 Godot fork 提交 `850c331d6f9f379c2c69a77f22701918271a6fe6`。准备脚本验证发行 APK 的 SHA-256，再提取原生库与桥接源码；生成内容位于忽略的 `vendor/`。

首次构建需要 JDK 21+、Python 3.11+、Android SDK 与网络。NDK、CMake 和 Capstone 版本由版本目录固定，后续构建复用本地缓存：

```powershell
.\gradlew.bat assembleDebug assembleRelease lintDebug
```

Gradle 自动调用 `prepare_gdre.py` 与 `prepare_capstone.py`，校验并准备 GDRE 核心和 Capstone 源码。`compile_gdre_scripts.py` 下载并校验同版本官方桌面工具，将工具脚本编译为字节码；构建主机使用 Windows、Linux 或 macOS 对应工具，首次构建需要额外下载编译器，后续复用缓存。依赖版本统一由 `gradle/libs.versions.toml` 管理。当前 Compose BOM 要求 app 使用 compileSdk 37.1；miuix-nav 的内联 API 要求 app 使用 JVM 21 编译目标，最低 Android 版本仍为 API 28。GDRE 官方原生库使用 16 KB ELF 段对齐，最终 APK 仍需检查打包对齐。

Android 桥接保留上游源码与许可，通过项目自带的 `driver.gd` 调用 GDRE 的解析及导出接口。目标项目不会作为运行项目启动。每个任务使用独立核心进程；完成、取消和系统超时均回收整个核心进程，下一次任务重新初始化。原生全局状态由工作进程退出统一释放，不在失败后再次调用 Godot 卸载。

最终 APK 结构检查：

```powershell
python tools/inspect_apk.py app/build/outputs/apk/release/app-release.apk --build-tools <SDK的build-tools目录>
```

上游桌面核心在错误密钥后的清理路径出现过访问异常；Android 工作进程由服务直接回收，避免再次进入该清理路径，结果在进程退出前保存。

需在 Android 真机补做：首次启动、离屏 JNI 驱动、共享存储授权/撤销、通知权限拒绝、取消、切后台、分屏，以及连续运行两个任务。电脑上的 JVM、桌面核心与构建检查分别记录结果，不代替真机验收。

源码构建的上游入口为 [GDRE v2.7.4](https://github.com/GDRETools/gdsdecomp/tree/v2.7.4)；其配套 fork、SCons 参数、Rust 与 NDK 版本按该版本官方工作流固定。应用复用官方 GDRE 二进制，Capstone 的 ARM64 指令分析桥接通过 NDK/CMake 编译。

Android 启动项目位于 APK 根资源 `assets/project.godot`，脚本从 `res://gdre/driver.gdc` 加载，并在挂载目标项目前加载 `res://gdre/webp_recovery.gdc`。源码保留在 `src/main/assets` 供编辑，APK 仅打包 `build/generated/gdreAssets` 中的生成资源和 `vendor/assets` 许可，不直接打包源码目录。字节码使用与内核兼容的 `ebc36a7` 修订（格式 101）；`.gdc` 避免直接发布明文，但不提供加密保护。Java 启动参数使用上游识别的 `--disable_godot_splash`。Android 的原生循环调用 DisplayServerAndroid，因此通过 1 × 1 EGL Pbuffer 配合 GLES 3 与关闭渲染循环运行，保留 Android 显示后端，避免 headless 后端的类型冲突。EGL 与引擎在同一线程创建和回收。

NM 专用处理集中在主应用 `feature/CNM`。适配器优先用 Capstone 分析 ARM64 指令引用，识别不到时扫描 ELF 只读段中的置换表与异或材料，用目录的完整 MD5 验证候选密钥；同时根据目录块结构和资源哈希匹配包布局，不绑定整个引擎的 SHA 或绝对文件地址。密钥只在内存中使用，结束即清零。当前支持 NM sparsepck v4 的该类密钥构造，未匹配的构造返回诊断码 `NM_KEY_NOT_FOUND`。

文件输入使用应用内 miuix 浏览器。Android 11+ 首次选择文件时申请“所有文件访问”，Android 9/10 使用存储运行时权限。处理结果自动保存到共享存储 `GodotTool/yyMMdd_次数.zip`；例如 `260101_1.zip`。同日 ZIP 原子预留递增编号，历史目录与归档均不覆盖。NM 原始资源先保存并写入阶段结果，再启动脚本恢复；原生恢复中断时保留已解密资源与部分完成记录。清理任务缓存保留公共结果。`engine.log` 与 `bootstrap.log` 用于记录原生启动及 Java 启动异常。

离屏驱动在主线程创建不挂载窗口的 `OffscreenRenderView`，在引擎线程创建 EGL 并注册插件列表中的全部插件。该接口覆盖原生 Android 后端访问的光标/输入方法，避免缺失 renderView 的空指针路径；对应 JNI 方法在 Release 保留。

`engine-startup.log` 在进入每个启动原生调用前写入，记录最初三帧及脚本阶段；启动 60 秒仍无事件时返回 `ENGINE_START_TIMEOUT`。主进程在连接断开后，将 `RECOVERY_INTERRUPTED` 阶段记录转为 `NATIVE_RECOVERY_INTERRUPTED`，补写系统进程退出信息（Android 11+）、指定引擎 PID 的有界 logcat 和最后执行阶段。Android 9/10 使用启动日志定位。源码恢复未完成时在首页和记录页明确显示状态，文件页默认展示可读产物，并提供恢复报告入口；点选原始字节码时优先跳转已有 `.gd`。

核心准备脚本 schema 7 将离线许可生成到 `assets/txt/licenses.txt`，复用旧缓存时迁移原许可文件；关于页的三份补充声明也位于 `assets/txt/`。对固定来源的 GodotIO 应用两项可复现补丁：无 Activity 时通过 DisplayManager 查询默认显示设备，避免 Application Context 的 getDisplay 抛异常；离屏视图没有 RootWindowInsets 时返回空缺口列表。补丁匹配预期源码片段后应用，源码变化会中止准备流程，保留上游许可。

恢复内容由 RecoverySelection 选择：项目脚本、场景和资源配置、素材与数据、addons 插件、项目入口配置。默认完整恢复，原始副本默认关闭。导入时可选择“仅项目脚本”，该预设不导出插件、场景、素材和项目配置，用于静态阅读。所选 import/remap 的编译依赖与版本识别数据在工作区内保留，避免把隐藏缓存当作用户内容盲目全选。

CNM 在解密资源前应用选择，按已选 import/remap 追踪真实编译文件，必要时保留一个脚本版本探针。普通 APK/PCK 在 GDRE 提取前按同一职责筛选。importer 的 dest_files 用于关联编译缓存与源码路径，避免只改后缀或移动文件破坏 res 引用。

新版公共输出为 `yyMMdd_次数.zip`，归档内包含 `project/` 与 `reports/`，勾选保留原始副本时额外包含 `raw/`。处理中资源仅写应用私有工作区，原生恢复前发布阶段 ZIP，恢复结束原子替换为完整 ZIP。日志采用固定长度快照，追加写入不触发资源大小超限；取消或保存失败保留上一份完整归档。ProjectAssembler 合并恢复文件和原始素材，成功转换后移除对应的 gdc/scn/res、冗余 remap 与普通引擎缓存；转换失败但仍被选中资源引用的缓存会保留。目录和文件名保留原包真实路径及大小写。先提交完整 project 目录，再回收中间产物，取消时清理未提交的工程目录。历史 raw/recovered 结果继续可浏览。

识别 APK 包名 `com.shallwaystudio.nodemotion` 自动进入 CNM，索引格式异常返回 NM 专属诊断；继续保留资源结构探测。界面使用 miuix 0.9.4 的官方 OverlayDialog 与 OverlayDropdownPreference（内部调用 OverlayListPopup），宿主释放时清理弹层状态。

导航使用与 UI 同版本的 `miuix-nav` 独立模块，官方 NavController 管理返回栈，NavDisplay 提供转场和预测返回。页面键的枚举名称由 SavedStateHandle 持久化，不调用要求序列化插件的 rememberNavBackStack；一级标签共享稳定 contentKey，文件目录和预览优先处理页面内返回。准备脚本从版本目录同步 miuix/MMKV 许可版本。
