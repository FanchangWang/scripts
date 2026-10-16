# Sublime Text 许可状态与标题 `(UNREGISTERED)` 机制分析

> 本文为**只读静态分析记录**，描述程序"如何判定并显示许可状态"。
> 文中**不包含**任何字节替换、指令改写、跳转修改或补丁步骤，也不描述如何改变许可判定结果。
> 合法消除 `UNREGISTERED` 的方式是 `Help → Enter License` 输入有效许可（见文末）。

---

## 1. 分析对象与方法

| 项目 | 值 |
|---|---|
| 目标 | `C:\Program Files\Sublime Text\sublime_text.exe` |
| SHA-256 | `b3bba60bf219501996fe5349ec659eff25daf0072c0ae81f2ceac8b0c06788f2` |
| 架构 | x64 PE |
| ImageBase | `0x140000000` |

分析方法：在**字节完全一致的副本**上用 IDA Professional 9.4 做 headless 反汇编/反编译，配合 PE 资源与字符串检索。

段布局（VA / raw）：

| 段 | VA | raw |
|---|---|---|
| `.text` | `0x1000` | `0x400` |
| `.rdata` | `0x5A7000` | `0x5A6000` |
| `.data` | `0x6F9000` | `0x6F7200` |
| `.rsrc` | `0x786000` | `0x76DC00` |

> 换算：`.text` 内 `文件偏移 = RVA − 0xC00`。本文出现的 `sub_XXXX` 中 `XXXX` 即函数入口的**虚拟地址(VA)**。

---

## 2. 现象

- 运行中进程主窗口标题为：`untitled ? - Sublime Text (UNREGISTERED)`
- `Help → About` 对话框显示 `Unregistered`
- 整个镜像里**没有明文的 `UNREGISTERED`**（ASCII 与 UTF-16LE 检索均为 0 命中）——该字样在运行时才被解出/拼接

---

## 3. 许可对象（License 对象）布局

该对象由多个函数读写、字段互相印证，布局如下（偏移相对对象基址）：

| 偏移 | 含义 | 证据 |
|---|---|---|
| `+0x00` | 许可类型 ID (int) | 解码器输出 `*a4 = v18` |
| `+0x04` | 购买时间值 (int) | 解码器输出 `*a5`；About 中 `3600*(+0x04)` → `"Purchased %Y-%m-%d"` |
| `+0x08` | 子 ID (int) | 解码器输出 `*a3 = v25` |
| `+0x0C` | **注册标志 (byte)** | `= (验证函数返回 0)` |
| `+0x0D` | **附属性标志 (byte)** | 解码器输出 `*a6 = sub_1400AA2D4(...)` |
| `+0x10` | 持有人名 `std::string`（len `+0x20`，cap `+0x28`） | About 对话框读取 |
| `+0x30` | 许可密钥文本 `std::string`（验证成功后被清空） | 注册分支中清空 |

两个不同的容器对象分别持有该许可对象指针：窗口对象 `+0x4D8`、应用/初始化对象 `+0x2B0`（`+0x268` 在另一路径中出现），本质是同一结构类型。

---

## 4. 标题后缀 `(UNREGISTERED)` 的产生路径

### 4.1 状态计算：`sub_140047830`

- 这是一个**虚方法**（没有直接调用点，只有窗口虚表中的数据引用），由窗口回调触发。
- 逻辑：取许可对象指针 → 读 `+0x0C`（注册标志）取反 → 结合 `+0x0D` 用条件移动选出一个 2bit 级"状态码"：
  - `+0x0C==1 且 +0x0D==0` → 状态 `0`
  - `+0x0C==0 且 +0x0D==0` → 状态 `2`（→ 未注册）
  - 其余组合 → 状态 `4 / 6`
- 它**本身不读文件、不验签**，只做"读标志 → 算状态"。

### 4.2 标题拼接：`sub_1401E20FB`

- 取应用名基址（全局 `qword_140740920`）；
- 若状态含"未注册"位，则从 **XOR 表**解出后缀文字：
  - 表 `aGprFapgpq` @ VA `0x14060C910`（文件 `0x60B910`），12 字节，逐字节 `XOR 0x15` → `UNREGISTERED`
- 用字面量 `" ("` 与 `")"` 包裹；
- 最终 `call SetWindowTextW`（IAT `0x1406E1278`）设置窗口标题。

> 说明：`UNREGISTERED` 之所以在镜像中检索不到，是因为它以 XOR `0x15` 的形式存放，运行时才解出。

---

## 5. `Help → About` 对话框路径

- 命令链：`sub_1400FDCA8`（命令处理）→ `sub_14003122A`（创建器）→ `sub_140030A9C`（`about_window` 构造）。
- 构造函数内部**直接**读取许可对象 `+0x0C`：
  - `== 1`：显示 `"Registered to "` + 持有人名（`+0x10` 的 `std::string`）+ 购买日期（`+0x04`）
  - `!= 1`：显示 `"Unregistered"`
- 注意：About 判断与标题判断**读的是同一个字段**，但**读取函数不同**（About 在自己的构造函数里读，不经过 `sub_140047830`）。

---

## 6. `Help → Enter License`

- `Help` 菜单是**数据声明**，位于 `Packages/Default.sublime-package :: Main.sublime-menu`：
  ```
  { "command": "purchase_license" },
  { "command": "upgrade_license" },
  { "command": "show_license_window", "caption": "Enter License" },
  { "command": "remove_license" },
  ```
- 该菜单项**没有** `enable`/`visible` 条件绑定注册状态——无论是否注册都可见可点。
- 命令 `show_license_window` 对应类 `.?AVshow_license_window_command@@` @ `0x140700300`，命令名函数返回 `"show_license_window"`。

---

## 7. 标志是谁写的

`+0x0C` 的写入点共两处（均为"验证通过 → 置 1"），另有一处清零。

### 7.1 写入点一：启动读文件路径 —— `sub_140033272`

- 它是 **`app_loader::read_ignore_callback` 的虚方法**（vtable 符号 `??_R4read_ignore_callback@app_loader@@6B@` @ `0x1405B5390`，槽位 `0x1405B53A8`）。
- app_loader 在**读取许可文件内容**时回调它，把每一行文本送进验证函数 `sub_1400AA320`；验证返回 0 时，将许可对象 `+0x0C` 置 1。
- 它把如下"输出指针"传入验证函数（注意传入的是**已加偏移的指针**）：`+0x00`、`+0x04`、`+0x08`、`+0x10`、`+0x0D`。

### 7.2 写入点二：运行时"输入许可"提交路径 —— `sub_1400AC1DA`

- 它是 `linear_container_control` 的方法（RTTI 符号 @ `0x1405D4C78`），来自许可证窗口输入框的提交处理。
- 取输入文本 → 调用同一个验证函数 `sub_1400AA320` → `+0x0C = (返回 0)`。
- 验证失败时按返回码 `1..5` 映射到不同提示，例如：
  - `"That appears to be a Sublime Merge license…"`
  - `"That license key doesn't appear to be valid."`
  - `"That license key is no longer valid."`
  - `"That license key has been invalidated, …"`

### 7.3 `+0x0D` 的写入

- 只在验证函数 `sub_1400AA320` **内部**，通过调用方传入的输出指针 `+0x0D` 写入。
- 取值来自 `sub_1400AA2D4(许可类型, 子ID, 时间值, 493567)`——一个纯算术派生布尔（含特例 ID）。

### 7.4 清零 —— `sub_140035477`

- 该函数将许可对象 `+0x0C` 置 0（撤销注册），由定时器调度（见第 9 节）。

---

## 8. 验证器 `sub_1400AA320` 的行为

它是许可密钥的**验签器**，返回 `0` 表示有效，`1..5` 表示各类失败。行为要点：

1. 惰性构造 160 字节表：`byte_1405D4590[i] ^ 0x95` → 十六进制串，存入 `qword_14073AC68`（公钥/模数类材料）。
2. 字段拆分：`sub_1404ED1D4`（分割 + 解码）。
3. 字段标记校验：`sub_14003AF90(…, "E52D" / "EA7E" / "E3D2")`。
4. **许可类型白名单**：`sub_1400AAE65` 对一张混淆表做二分查找——表项先 `XOR 0x49349313` 再比较（`unk_1405C4170`，16365 项）。
5. 字节混合校验：`(v34[15]^0xA8 | v34[12]^0xCE | v34[18]^0xEA | v34[30]^0x56) == 0`。
6. 通过后回写 `+0x00 / +0x04 / +0x08 / +0x0D`，调用方再将 `+0x0C = 1`。

> 结论：不存在"文件存在即放行"的分支；标志只来自**密钥文本通过上述校验**的结果。

---

## 9. 许可证文件与周期性重新验签

### 9.1 文件路径

`app_loader` 构造候选路径（`sub_140103921`）：
```
"/Local/License.sublime_license"
"/License.sublime_license"
```
按 `Sublime Text` / `Sublime Text 3` / Dev Channel / Safe Mode 等枚举目录，再把文件内容交给第 7.1 节的回调逐行验证。

### 9.2 反篡改 / 自动撤销定时器

启动函数 `sub_14003236C`（日志含 `"pre session restore time"` / `"startup time"`）对许可对象挂两个定时器：

- 5s → `sub_140032A3B`
- 15s → `sub_140032BC8`

行为：

- `sub_140032A3B`：用一段**特定密钥串**（`"Name\nUnlimited User License\nEA7E-1000\n"` + 8×32 个 `0`）跑验证器；**若验证器认它（返回 0）**，则在 480000ms（8 分钟）后调用 `sub_140035477` 将 `+0x0C` 清 0。这是针对某个已知泄露/盗版密钥的"影子吊销"。
- `sub_140032BC8`：若 `+0x0C==1` 且（**重新验证已存密钥失败** 或 `+0x20==0`），则在 360000ms（6 分钟）后清 `+0x0C`。即**周期性重新验签，验不过就撤销注册**。

> 这也是"注册状态不是一次写入就永久成立"的原因：程序会周期性复验并可能自动撤销。

---

## 10. 在线校验

- `sub_1400AB6CD`：命令名 `"license_check"`，请求主机 `license.sublimehq.com`，User-Agent `"sublime-license-check/3.0"`，把许可对象序列化后上报（`sub_1404ED824`）。

---

## 11. 小结（数据流）

```
启动
 ├─ app_loader 读 /Local/License.sublime_license  (sub_140103921 构造路径)
 │     └─ read_ignore_callback 虚方法  (sub_140033272)
 │            └─ 验签器 sub_1400AA320
 │                  ├─ 通过 → 写 +0x00/+0x04/+0x08/+0x0D
 │                  └─ 调用方写 +0x0C = 1
 ├─ 启动函数 sub_14003236C 挂定时器
 │     ├─ sub_140032A3B (5s, 8min 后清 +0x0C)
 │     └─ sub_140032BC8 (15s, 复核失败则 6min 后清 +0x0C)
 │            └─ sub_140035477 清 +0x0C
 └─ 运行时输入许可 (Enter License)
       └─ linear_container_control 方法 sub_1400AC1DA → sub_1400AA320 → 写 +0x0C

显示
 ├─ 标题: 虚方法 sub_140047830 读 +0x0C/+0x0D → 状态码
 │        └─ sub_1401E20FB 解 XOR 表得 "UNREGISTERED" + " (" ")" → SetWindowTextW
 └─ About: sub_1400FDCA8 → sub_14003122A → sub_140030A9C 读 +0x0C
            ├─ ==1: "Registered to <名>" + "Purchased %Y-%m-%d"
            └─ !=1: "Unregistered"
```

---

## 12. 相关地址速查

| 符号 | VA | 作用 |
|---|---|---|
| `sub_140047830` | `0x140047830` | 标题状态码计算（虚方法） |
| `sub_1401E20FB` | `0x1401E20FB` | 标题字符串拼接 + `SetWindowTextW` |
| `aGprFapgpq` | `0x14060C910` | `UNREGISTERED` 的 XOR 表（key `0x15`） |
| `sub_140030A9C` | `0x140030A9C` | `about_window` 构造 |
| `sub_14003122A` | `0x14003122A` | About 创建器 |
| `sub_1400FDCA8` | `0x1400FDCA8` | About 命令处理 |
| `sub_140033272` | `0x140033272` | 写 `+0x0C`（启动读文件回调） |
| `sub_1400AC1DA` | `0x1400AC1DA` | 写 `+0x0C`（输入许可提交） |
| `sub_140035477` | `0x140035477` | 清 `+0x0C`（撤销） |
| `sub_1400AA320` | `0x1400AA320` | 许可验签器 |
| `sub_1404ED1D4` | `0x1404ED1D4` | 密钥字段拆分 |
| `sub_1400AAE65` | `0x1400AAE65` | 类型 ID 白名单（混淆表二分） |
| `sub_1400AA2D4` | `0x1400AA2D4` | `+0x0D` 取值计算 |
| `sub_140103921` | `0x140103921` | 许可文件路径构造（app_loader） |
| `sub_14003236C` | `0x14003236C` | 启动初始化 + 挂定时器 |
| `sub_140032A3B` | `0x140032A3B` | 定时回调（8min 撤销） |
| `sub_140032BC8` | `0x140032BC8` | 定时回调（6min 复核撤销） |
| `sub_1400AB6CD` | `0x1400AB6CD` | 在线校验 `license_check` |

---

## 13. 合法消除 `UNREGISTERED`

`Help → Enter License`（命令 `show_license_window`）输入有效许可 → 由 `sub_1400AC1DA` 走验证器 `sub_1400AA320` → 通过后置 `+0x0C = 1`，标题与 About 随即显示注册状态。

## 历史版本 asm 修改
```asm
4215 版本 改的 验签器 sub_1400AA320
; 0fb6510c83f201 原特征码
.text:0000000140047886  0f b6 51 0c         movzx   edx, byte ptr [rcx+0Ch]
.text:000000014004788A  83 f2 01            xor     edx, 1

; c6410c01b20090 改特征码
c6 41 0C 01    mov byte ptr [rcx+0Ch], 1 ; 写 1 到 [rcx+5]
b2 00          mov dl, 0               ; dl=0
90             nop

4200 版本
; 0fb6510583f201 原特征码
0f b6 51 05        movzx edx, byte ptr [rcx+5]   ; ① 读取 [rcx+5] 这个字节，零扩展到 edx
83 f2 01           xor   edx, 1                  ; ② edx = edx ^ 1

; c6410501b20090 改特征码
c6 41 05 01    mov byte ptr [rcx+5], 1 ; 写 1 到 [rcx+5]
b2 00          mov dl, 0               ; dl=0
90             nop

```
