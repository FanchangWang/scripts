# daily-calendar-puzzle

日历拼版：10 块拼板覆盖 8×7 棋盘，按日期露出月 / 日 / 星期三格。同源一套求解核心，提供**求解**与**自己游玩**两种网页视图，另附命令行求解工具。

## 环境要求

- Node.js（原生 ESM，无第三方运行时依赖）。

## 运行

### 1. 起本地服务（推荐，开发调试）

```bash
npm start
# 求解视图 http://localhost:5173/
# 游玩视图 http://localhost:5173/play
```

### 2. 编译静态单文件（可双击打开）

```bash
npm run build
# 产物在 dist/index.html、dist/play.html，file:// 直接打开即可
```

### 3. 命令行求解

```bash
node cli/solve.js                       # 今天，求第 1 解
node cli/solve.js --date 2026-12-31
node cli/solve.js --limit 10
node cli/solve.js --all                 # 枚举全部，统计总数
node cli/solve.js --print               # 打印首解棋盘布局
```

### 4. 测试

```bash
npm test                # 抽样多日期校验合法性、覆盖 47 格
FULL=1 npm test         # 含全量基线：2026-11-13 = 2543 解（约 30s）
```

## 网页视图说明

- **求解视图**：选日期后先求第 1 种方案；Worker 后台逐解枚举，过程中数量实时增长并可翻页；点“求解全部解法”给出总数；左侧托盘缩略图与棋盘上各拼板朝向一致。
- **游玩视图**：从左侧托盘把拼板拖到棋盘，拖拽以被抓取的格子为锚点（预览占位与落点一致）；选中后可左 / 右旋 90°、左右 / 上下翻转、取回；规则实时校验；“提示下一块”会高亮推荐落点并把托盘对应块转到推荐朝向；“完整答案”铺一种参考方案，可撤销恢复原摆法；全部铺满判胜。

## 棋盘规则

- 棋盘 8 行 × 7 列；可放置格 47，当天露出 3 格（月份、日期、星期），永久禁放 6 格。
- 禁放格：右上“坚 / 持”、左下“就 / 是 / 胜 / 利”，不可放置拼板。
- 10 块拼板：3 块四格板（1 / 4 / 10）+ 7 块五格板（2 / 3 / 5 / 6 / 7 / 8 / 9），旋转、翻转均允许。
- 拼板不可越界、不可压禁放 / 露出格、不可重叠，47 格全部覆盖即成立。

## 求解口径

- 回溯 + 最受限空格启发式；先填可选落法最少的空格。
- 解的合法性由 `verify` 校验：每块格数正确、不越界、不落禁放 / 露出格、不重叠、覆盖完整。
- 基线：2026-11-13（周五）共 2543 种唯一铺法。

## 目录结构

```
daily-calendar-puzzle/
├── core/                  # 同源核心：CLI 与网页共用这一份逻辑
│   ├── board.js          # 棋盘常量：标签、禁放格、拼板形状、配色、名称
│   ├── geometry.js        # 归一化 / 旋转 / 翻转 / 朝向枚举
│   ├── date.js            # 日期 → 露出格映射
│   ├── solver.js         # 回溯求解器（限量 / 全量）+ 合法性校验
│   └── index.js
├── cli/solve.js           # 命令行求解（后台批量计算 / 校验用）
├── server/server.js       # 本地静态服务，托管两个网页视图
├── web/                   # 网页前端（求解 + 游玩）
│   ├── index.html         # 求解视图
│   ├── play.html          # 游玩视图
│   ├── style.css
│   ├── solver-app.js
│   ├── play-app.js
│   ├── core.bundle.js     # 由 core/ 打包生成的浏览器主线程副本（勿手改）
│   └── worker.js          # 由 core/ 打包生成的 Worker 枚举脚本（勿手改）
├── scripts/
│   ├── build-bundle.js    # core/ → web/core.bundle.js + web/worker.js
│   └── build-static.js    # 编译成可 file:// 打开的单文件 HTML 到 dist/
├── test/solver.test.js     # 合法性 / 覆盖格数 / 解数基线测试
└── dist/                  # 静态打包产物（build 生成）
```
