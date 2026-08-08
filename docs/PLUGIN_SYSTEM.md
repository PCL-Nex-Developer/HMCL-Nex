# HMCL 插件系统

HMCL 插件系统允许开发者使用 Java、Kotlin 或 JavaScript 扩展启动器功能。

## 功能特性

- ✅ **多语言支持**: Java、Kotlin、JavaScript
- ✅ **权限声明**: 安装前展示并校验插件声明的敏感能力
- ✅ **版本依赖**: 递归解析插件依赖、版本约束、冲突和循环
- ✅ **简单打包**: `.npl` 文件（ZIP 格式）
- ✅ **插件管理界面**: 安装、启用、禁用、卸载
- ✅ **跨平台支持**: Windows、macOS、Linux
- ✅ **跨架构支持**: x86_64、ARM64
- ✅ **固定 JavaScript 运行时**: 自动下载 HMCL 管理的 Node.js v24.18.0 二进制包

## 目录结构

```
org/jackhuang/hmcl/plugin/
├── Plugin.java                          # 插件接口
├── PluginContext.java                   # 插件上下文
├── PluginManifest.java                  # 插件清单
├── PluginContainer.java                 # 插件容器
├── PluginManager.java                   # 插件管理器
├── PluginUIRegistry.java                # 插件 UI 注册表
├── JavaScriptPluginPage.java            # 声明式 JavaFX 页面
└── loader/
    ├── PluginLoader.java                # 加载器接口
    ├── JavaPluginLoader.java            # Java/Kotlin 加载器
    ├── NodeJSManager.java                # 固定 Node.js 运行时管理
    └── JavaScriptPluginLoader.java      # JavaScript 加载器

org/jackhuang/hmcl/ui/main/
├── PluginManagementPage.java           # 插件管理界面
└── PluginStorePage.java                # 插件商店界面
```

## 插件生命周期

1. **启动前发现**: 读取启用状态，扫描 Java/Kotlin 插件的 `mixins`，安全解压到校验过哈希的启动缓存
2. **Mixin Agent 引导**: 首个 JVM 自动用当前 HMCL JAR 作为 `-javaagent` 启动第二个 JVM；`premain` 在 `Main` 加载前初始化 SpongePowered Mixin 0.8.7 并注册字节码变换器
3. **常规发现**: 扫描 `plugins/` 目录下的 `.npl` 文件，验证清单并按依赖拓扑排序
4. **加载**: 防 Zip Slip/解压炸弹地提取 `.npl`，实例化插件
5. **初始化**: 调用 `onLoad(context)`
6. **启用**: 调用 `onEnable()`
7. **禁用**: 调用 `onDisable()`
8. **卸载**: 调用 `onUnload()`

## 插件类型

### 1. Java 插件

```java
public class MyPlugin implements Plugin {
    @Override
    public void onLoad(PluginContext context) {
        // 插件加载
    }

    @Override
    public void onEnable() {
        // 插件启用
    }

    @Override
    public void onDisable() {
        // 插件禁用
    }

    @Override
    public void onUnload() {
        // 插件卸载
    }

    @Override
    public PluginManifest getManifest() {
        return manifest;
    }
}
```

### 2. Kotlin 插件

```kotlin
class MyPlugin : Plugin {
    override fun onLoad(context: PluginContext) {
        // 插件加载
    }

    override fun onEnable() {
        // 插件启用
    }

    override fun onDisable() {
        // 插件禁用
    }

    override fun getManifest(): PluginManifest = manifest
}
```

### 3. JavaScript 插件

```javascript
const event = process.argv[2] || process.env.HMCL_PLUGIN_EVENT;

function send(message) {
    process.stdout.write('HMCL_PLUGIN_MESSAGE:' + JSON.stringify({
        protocol: 'hmcl-ui-v1',
        ...message
    }) + '\n');
}

if (event === 'onEnable') {
    send({
        sidebar: {
            title: 'My Plugin',
            page: {
                type: 'vbox',
                children: [
                    { type: 'title', text: 'My Plugin' },
                    { type: 'button', text: 'Run', event: 'run', primary: true }
                ]
            }
        }
    });
}
```

## JavaScript 运行时

JavaScript 插件固定使用 HMCL 管理的 Node.js v24.18.0 子进程。启动器只从 Node.js 官方站下载当前系统和架构对应的二进制 ZIP 或 TAR.GZ，并解压到 `.hmcl/nodejs/current`。

启动器不会读取系统 Node.js，也不使用 GraalJS、Nashorn 或 JSR-223。Node.js 与 HMCL JVM 隔离，因此 JavaScript 不能直接实例化 JavaFX 类；插件通过 `hmcl-ui-v1` JSON 协议声明控件树，HMCL 在 JavaFX 线程创建真实控件并代理按钮事件。

### 运行时检测逻辑

- Windows x64/ARM64: 下载官方二进制 ZIP
- macOS x64/ARM64: 下载官方二进制 TAR.GZ
- Linux x64/ARM64/ARMv7: 下载官方二进制 TAR.GZ

## 插件清单示例

```json
{
  "schemaVersion": 4,
  "id": "com.example.myplugin",
  "name": "My Plugin",
  "version": "1.0.0",
  "description": "A sample plugin",
  "author": "Your Name",
  "type": "java",
  "entrypoint": "com.example.myplugin.MyPlugin",
  "mixins": ["mixins.com.example.myplugin.json"],
  "dependencies": [
    {
      "id": "com.example.base",
      "version": ">=1.2.0 <2.0.0"
    }
  ],
  "permissions": ["filesystem", "launcher-ui", "mixin"],
  "requiredPermissions": ["launcher-ui", "mixin"],
  "launcherVersion": ">=26.8-beta.3-fix <27.0"
}
```

`mixins` 仅适用于 Java/Kotlin 插件。schema v4 包必须把独立高风险权限 `mixin` 同时列入 `permissions` 和 `requiredPermissions`。配置和 Mixin 类位于插件根目录或 `libs/*.jar` 中；启用、禁用、更新、卸载含 Mixin 的插件都需要重启，运行中不会尝试撤销已经定义的类。

HMCL Nex 只安装和执行 schema v4 插件。schema v4 要求显式填写 `permissions`、`requiredPermissions` 和 `launcherVersion`；必要权限必须是全部声明权限的子集，默认开启并锁定，其余权限为可选权限，用户可以拒绝。`launcherVersion` 使用与插件依赖相同的版本约束语法，开发者必须明确声明兼容的 HMCL Nex 版本范围。schema v1-v3 包仅保留识别、状态展示、更新和卸载能力，不会进入依赖解析、安装或生命周期执行。

实际可用权限始终是“开发者声明 ∩ 用户授权”。首次安装时必要权限固定开启、可选权限默认关闭；每次更新（包括同版本但 SHA-256 改变的重打包）都必须重新显示完整授权窗口。旧版本仍声明的可选授权作为预选，新增可选权限默认关闭，从可选升级为必要的权限会明确标记。取消窗口不会发布包，也不会改写原授权。

授权记录绑定插件 ID、版本和 `.npl` SHA-256。同一安装计划中，每个需要安装或更新的依赖插件都有独立授权分组。未声明权限不会出现在开关中，持久化层拒绝缺少必要权限或包含未声明权限的授权写入。Mixin 启动引导会在加载任何插件字节码前核验整个依赖闭包的清单、必要权限、启动器版本约束和精确包身份；任一依赖被阻断时，上游 Mixin 也不会注册。

普通 Java/Kotlin 插件由专用类加载器执行，`PluginManager` 的授权、安装、更新、卸载和生命周期管理入口会拒绝插件代码直接调用，防止插件通过公开管理对象自授权或绕过更新确认。该保护仍不是 JVM 或操作系统沙箱：插件可以直接调用 JDK/启动器内部 API，Mixin 与主程序共享类加载器，反射或本机代码也无法由当前模型完全隔离，因此用户仍只能安装可信来源的插件。

插件依赖项兼容旧的字符串 ID，也可使用 `{ "id": "...", "version": "..." }`。插件依赖和 `launcherVersion` 都支持 `*`、精确版本、`<`、`<=`、`>`、`>=`，多个条件以空格或逗号连接。常规加载与 Mixin 启动都会验证依赖版本和 HMCL Nex 版本；缺失依赖、冲突约束、循环依赖或启动器版本不兼容时不会加载入口类。

若第三方 Mixin 导致启动失败，可临时添加 JVM 参数 `-Dhmcl.plugin.mixins.disabled=true` 进入恢复模式，再在插件管理页禁用或卸载问题插件。

## 使用方法

### 1. 开发插件

开发指南、三语言示例、打包工具和发布模板由独立的 HMCL Nex Plugin SDK 提供。

### 2. 安装插件

- 通过界面: 设置 → 插件管理 → 安装插件
- 手动放置: 将 `.npl` 文件放入 `.hmcl/plugins/` 目录；未经过授权窗口的 schema v4 包会因缺少必要授权而保持阻断，需在插件管理页确认必要权限并选择可选权限。需要更新时建议使用管理界面或插件商店，以便在发布新包前完成重新授权。

### 3. 管理插件

在插件管理界面中可以：
- 查看已安装插件
- 启用/禁用插件
- 卸载插件
- 查看插件详情
- 查看开发者声明的必要权限和可选权限；必要权限固定启用，可选权限可逐项允许或拒绝
- 随时修改当前包的可选授权；不接受必要权限时可禁用或卸载插件
- 查看或安装 HMCL 托管的 Node.js 运行时

插件商店还支持：

- 收藏插件并仅查看收藏
- 选择、下载和安装某个明确版本
- 查看每个版本的兼容性、权限、依赖和更新日志
- 查看插件仓库 README 文本、格式和链接；外部图片、媒体与嵌入资源不会自动加载
- 在写入插件目录前递归下载并校验完整依赖安装计划

### 插件来源

插件商店的官方来源可以禁用，但不能编辑 URL 或删除；自定义来源可以添加、编辑、排序、启用或移除。新增来源和修改自定义来源 URL 时，HMCL Nex 会先在不写入配置的情况下加载并预览该来源；只有预览成功且用户确认后才保存。仅修改本地别名无需重新访问网络。

商店会汇总所有启用来源。相同插件 ID 的候选项按来源列表中的可见优先级选择，优先级最高的项用于详情、依赖解析和下载；详情页会显示选中来源及其他候选来源。调整来源顺序会同时改变这些关联的选择，而不是只改变列表显示。

单个来源或其部分仓库清单加载失败时，其他成功来源的插件仍可使用，商店会显示降级状态和不含来源 URL 的提示。只有所有已启用来源均失败时，商店才没有可浏览的插件。安装确认会显示每个远程包的来源，并在更高优先级来源不可用时提示用户核对选择的来源。移除来源不会卸载已安装插件，但该插件之后的更新可能不再可用。

## 安全性说明

⚠️ **重要**: 权限系统强制约束 HMCL 官方插件 SDK 的受保护接口，但当前同进程插件模型并不能限制所有 Java/JDK/启动器内部调用。这意味着：

1. 插件可以执行任意代码
2. 插件可以修改任何启动器功能
3. 插件可以访问和修改配置文件
4. 插件可以访问网络

**因此**:
- 只安装来自可信来源的插件
- 插件安全性是用户和插件作者之间的责任
- HMCL 不对插件行为负责

## 示例插件

Java、Kotlin 和 JavaScript Hello World 示例位于独立的 [HMCL Nex Plugin SDK](https://github.com/PCL-Nex-Developer/HMCL-Nex-Plugin-SDK) 中。Java/Kotlin 示例可直接调用 JavaFX 和启动器 API；JavaScript 示例使用声明式 JavaFX 控件树和异步事件协议。

## 国际化

插件管理界面支持中文国际化：

```properties
plugin.manage=插件管理
plugin.install=安装插件
plugin.enabled=已启用
plugin.disabled=已禁用
plugin.js_engine_available=JavaScript 引擎可用
plugin.js_engine_unavailable=JavaScript 引擎不可用
# ... 更多翻译
```

## 技术实现

### 类加载

普通 Java/Kotlin 插件使用专用 `URLClassLoader`，卸载时关闭。只有存在已启用、必要权限完整且通过精确 artifact 校验的 Mixin 插件时，首个 JVM 才会自动启动带 HMCL `premain` Agent 的第二个 JVM。Agent 在追加任何包到系统搜索路径前检查 Mixin 所有者及其完整依赖闭包的必要权限、版本约束和包身份。Mixin 启动阶段需要的第三方库必须由插件自行打包（例如 shade 到插件 JAR），配置资源也必须唯一地存在于声明它的包内。

Agent 启动期间加入系统搜索路径的 JVM 插件类无法在运行中真正移除，因此涉及 Mixin 的启用、禁用、更新和卸载统一采用等待重启语义。

### JavaScript 引擎

JavaScript 插件通过固定 Node.js 子进程运行：
- 每个生命周期和 UI 事件都由插件专用的后台执行器顺序调用
- 插件元数据、数据目录和 UI 输入值通过环境变量传递
- 标准输出中带 `HMCL_PLUGIN_MESSAGE:` 前缀的消息按 `hmcl-ui-v1` 解析
- 单次调用超时为 30 秒，不阻塞 JavaFX UI 线程

### 插件隔离

每个插件有：
- 独立的包解压目录 (`.hmcl/plugin-data/<plugin-id>/`)
- 独立的持久化数据目录 (`.hmcl/plugin-storage/<plugin-id>/`)
- 启动 Mixin 缓存 (`.hmcl/plugin-cache/<plugin-id>/`)
- 独立的类加载器（Java/Kotlin）
- 独立的 Node.js 子进程调用序列（JavaScript）
- 普通插件调用管理器状态变更入口时的类加载器与生命周期调用栈检查

这些边界用于减少误用和阻止普通插件直接绕过用户授权，不等同于进程级隔离。Mixin、反射、本机代码以及插件直接使用 JDK 系统 API仍需按可信代码处理。

## 未来扩展

可能的增强：
- 插件签名验证
- 可强制执行的进程级沙箱
- 插件热重载

## 贡献

欢迎贡献插件系统改进和示例插件！

## 许可证

插件系统遵循 HMCL 的 GPL-3.0 许可证。
