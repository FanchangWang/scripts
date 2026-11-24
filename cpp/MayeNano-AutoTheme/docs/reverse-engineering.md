# 逆向笔记（Maye Nano 7.0.1.261107）

## 目标结构

- `Maye Nano.exe`（1.7 MB）：UPX 加壳的 Arae 运行时。REA `binary_overview` 显示段 `UPX0` / `UPX1`，Ghidra 只识别到 61 个字符串——加壳状态下静态分析看不到应用逻辑。
- `app.arae`（580 KB）：资源容器。开头是 `OTTO`（MaterialIcons OTF 字体），后面是图片 / 语言 JSON，末尾有资源索引清单。
- `theme\*.json`：颜色表，值是 ARGB 整数。
- `cfg.json`：`theme` 字段指定当前主题文件名；留空 / `""` 表示默认主题。
- 插件 API 的 `GetCfgItem` / `SetCfgItem` 实际读写插件目录下的私有 `cfg.ini`，**不是** `cfg.json`；所以插件直接解析 `cfg.json` 取真实主题值。

## 插件 API opcode（运行时分发表 dump 结果）

| opcode | 功能 |
|---|---|
| 50 | GetVersion |
| 100 | GetSelfId |
| 110 | GetSelfDir |
| 210 | SetCfgItem |
| 211 | GetCfgItem |
| 99999 | 内部保留 |

没有主题切换 / 重载 API，因此必须调用内部函数。

## 定位内部主题函数

1. **解包 PE**：启动应用后，从进程内存读出模块镜像，按节表重建为可分析的 PE（约 4.4 MB），恢复出可读字符串。`.\theme\` 字符串只有一处引用（主题列表枚举），不是应用函数。
2. **IAT hook**：在插件里扫描主模块镜像找到 `CreateFileW` 的 IAT 槽并替换，记录文件名 + `RtlCaptureStackBackTrace`。
3. **触发真实切换**：在设置界面手动切换一次主题，抓到 `.\theme\Black-1.json` 被打开时的 16 层调用栈。
4. **反编译**：Ghidra 需要 8 GB heap 才能完成这个 4.4 MB 镜像的自动分析（`GHIDRA_HEADLESS_MAXMEM=8192m`）。调用栈第 8 帧落在 `FUN_7ff7f15d19d0`（dump 镜像基址 `0x7ff7f1450000`）。
5. **换算 RVA**：`0x7ff7f15d19d0 - 0x7ff7f1450000 = 0x1819D0`（第一次算错成 `0x15D19D0`，调用直接崩溃；用运行时字节核对后才确认）。

### 函数签名

```c
// RVA 0x1819D0，基于 7.0.1.261107
void ApplyTheme(const char* utf8ThemeFile);  // NULL = 默认主题
```

反编译显示它做的事：

1. 把全局颜色表（`DAT_7ff7f18313b0` 起）重置为默认值；
2. 若参数非空，用 `fmt` 拼出 `.\theme\{name}`；
3. `std::ifstream` 打开并解析 JSON；
4. 把解析出的颜色写回全局颜色表（`FUN_7ff7f15ce360`）。

这正是设置界面切换主题时调用的同一个函数，因此进程内调用即可立即生效，无需重启。

## 线程问题

ImGui 有线程亲和性，直接从监控线程调用会崩溃。插件在 `_MN_Notify(Load)`（主线程回调）中创建 message-only 窗口，监控线程把主题名 `PostMessage` 过去，在窗口过程里调用内部函数。

## 版本风险

RVA `0x1819D0` 绑定 7.0.1.261107。软件更新后若函数地址 / 签名变化，最坏情况会崩溃。配置里 `UseInternalCall=0` 可退回“写配置 + 窗口隐藏后重启”的旧方案。
