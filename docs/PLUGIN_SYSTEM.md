# HMCL 插件系统

HMCL 插件系统允许开发者使用 Java、Kotlin 或 JavaScript 扩展启动器功能。

## 功能特性

- ✅ **多语言支持**: Java、Kotlin、JavaScript
- ✅ **完全访问权限**: 插件可修改启动器的任何操作
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
  "schemaVersion": 2,
  "id": "com.example.myplugin",
  "name": "My Plugin",
  "version": "1.0.0",
  "description": "A sample plugin",
  "author": "Your Name",
  "type": "java",
  "entrypoint": "com.example.myplugin.MyPlugin",
  "mixins": ["mixins.com.example.myplugin.json"],
  "dependencies": [],
  "minLauncherVersion": "3.0.0"
}
```

`mixins` 仅适用于 Java/Kotlin 插件。配置和 Mixin 类位于插件根目录或 `libs/*.jar` 中；启用、禁用、更新、卸载含 Mixin 的插件都需要重启，运行中不会尝试撤销已经定义的类。

若第三方 Mixin 导致启动失败，可临时添加 JVM 参数 `-Dhmcl.plugin.mixins.disabled=true` 进入恢复模式，再在插件管理页禁用或卸载问题插件。

## 使用方法

### 1. 开发插件

开发指南、三语言示例、打包工具和发布模板由独立的 HMCL Nex Plugin SDK 提供。

### 2. 安装插件

- 通过界面: 设置 → 插件管理 → 安装插件
- 手动安装: 将 `.npl` 文件放入 `.hmcl/plugins/` 目录

### 3. 管理插件

在插件管理界面中可以：
- 查看已安装插件
- 启用/禁用插件
- 卸载插件
- 查看插件详情
- 查看或安装 HMCL 托管的 Node.js 运行时

## 安全性说明

⚠️ **重要**: HMCL 插件系统允许插件完全访问启动器运行时，这意味着：

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

普通 Java/Kotlin 插件使用专用 `URLClassLoader`，卸载时关闭。只要存在已启用 Mixin 插件，首个 JVM 就会自动启动带 HMCL `premain` Agent 的第二个 JVM；Agent 把启动时发现的 JVM 插件根资源和 JAR 追加到系统类加载器搜索路径，并在任何 HMCL 应用类加载前注册 Mixin 变换器。HMCL、JavaFX 和 Mixin 插件因此都使用唯一的系统类加载器类身份，避免重复加载启动器类。

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

## 未来扩展

可能的增强：
- 版本依赖解析
- 插件签名验证
- 权限系统
- 插件热重载

## 贡献

欢迎贡献插件系统改进和示例插件！

## 许可证

插件系统遵循 HMCL 的 GPL-3.0 许可证。
