# bjxd

北京现代会员系统接口的 REST Client 调试样例，覆盖用户、文章、答题、签到、任务、成绩等接口。

## 环境要求

- 支持 REST Client 的 IDE（VS Code REST Client 插件 / JetBrains HTTP Client）。
- 在同目录 `.env` 中配置 `BASE_URL` 与 `BJXD` 用户 token（模板见 `.env.example`）。

## 快速开始

1. 复制 `.env.example` 为 `.env`，填入 `BASE_URL` 与 `BJXD` token。
2. 打开任一 `.http` 文件，点击发送按钮调用；公共请求头为 `token: {{$dotenv BJXD}}`、`device: mp`。

## 接口清单

| 文件 | 方法 | 路径 | 说明 |
|---|---|---|---|
| `getUserInfo.http` | GET | `/v1/app/account/users/info` | 用户信息 |
| `getArticleList.http` | GET | `/v1/app/white/article/list2` | 文章列表 |
| `getArticleDetail.http` | GET | `/v1/app/white/article/list2?page_no=1&page_size=1` | 文章（样例取 1 条；文件名「详情」与路径对应关系待核对） |
| `getQuestionInfo.http` | GET | `/v1/app/special/daily/ask_info?date=YYYYMMDD` | 每日答题题目信息 |
| `getScoreDetails.http` | GET | `/v1/app/user/my_score` | 积分 / 成绩 |
| `getSignList.http` | GET | `/v1/app/user/reward_list` | 签到列表 |
| `getTaskList.http` | GET | `/v1/app/user/task/list` | 任务列表 |
| `submitArticleScore.http` | POST | `/v1/app/score` | 提交文章打分 |
| `submitQuestionAnswer.http` | POST | `/v1/app/special/daily/ask_answer` | 提交答题答案 |
| `submitSign.http` | POST | `/v1/app/user/reward_report` | 提交签到 |

## 功能 / 行为说明

- 基址由 `.env` 的 `BASE_URL` 提供（默认 `https://bm2-api.bluemembers.com.cn`）。
- 日期参数 `{{$datetime 'YYYYMMDD'}}` 由 REST Client 自动取当天。
- 每个文件下方注释附了历史响应样例；token 失效时接口会返回未授权，需重新获取 `BJXD`。
