# 自用脚本
一些个人自用的脚本

## tampermonkey 篡改猴脚本
- ~~[抖音直播网页全屏、原画](./tampermonkey/tdouyin_live_theater.user.js)~~

- ~~[抖音跳过广告、直播、购物、清屏](./tampermonkey/douyin_skip_ad.user.js)~~

- ~~[github 24小时格式显示时间](./tampermonkey/github_datatime_format.user.js)~~


## qinglong 青龙脚本
- [北京现代每日任务](./qinglong/bjxd.py)
```shell
# 使用教程

## 安装依赖
pip3 install requests

## 设置环境变量
export BJXD="token" # 北京现代 token (单个账号配置这个就行)
export BJXD1="token1" # 北京现代 token1 (多个账号按照这个格式配置)
export BJXD2="token2" # 北京现代 token2
export BJXD3="token3" # 北京现代 token3
export HUNYUAN_API_KEY="sk-xxxx" # 腾讯混元AI APIKey (可选，用于获取每日答题答案，不配置则随机答题)
export BJXD_ANSWER="A" # 预设每日答题答案，A、B、C、D 中的一个(可选，配置之后优先用这个答案)

## 本地运行方式
python3 bjxd.py

## 青龙面板单文件导入方式
ql raw https://raw.githubusercontent.com/FanchangWang/scripts/refs/heads/main/qinglong/bjxd.py
```
- [北京现代获取token](./qinglong/bjxd_get_token_gui.py)
```shell
#使用教程

## 安装依赖
pip3 install PyQt5 PyQtWebEngine

## 本地运行
python3 bjxd_get_token_gui.py
```

- [iptv.cc 签到任务](./qinglong/iptv_cc.py)
```shell
# 使用教程

## 安装依赖
pip3 install requests beautifulsoup4

## 设置环境变量
export IPTV_CC_USERNAME="xxxxx" # iptv.cc 用户名
export IPTV_CC_PASSWORD="xxxxx" # iptv.cc 密码

## 本地运行方式
python3 iptv_cc.py

## 青龙面板单文件导入方式
ql raw https://raw.githubusercontent.com/FanchangWang/scripts/refs/heads/main/qinglong/iptv_cc.py
```

- [bbs.binmt.cc MT论坛签到任务](./qinglong/binmt_cc.py)
```shell
# 使用教程

## 安装依赖
pip3 install requests beautifulsoup4

## 设置环境变量
export BINMT_CC_USERNAME="xxxxx" # bbs.binmt.cc 用户名
export BINMT_CC_PASSWORD="xxxxx" # bbs.binmt.cc 密码

## 本地运行方式
python3 binmt_cc.py

## 青龙面板单文件导入方式
ql raw https://raw.githubusercontent.com/FanchangWang/scripts/refs/heads/main/qinglong/binmt_cc.py
```
- [oshwhub 嘉立创开源硬件平台 自动任务](./qinglong/oshwhub.py)
```shell
# 使用教程

## 安装依赖
pip3 install requests

## 设置环境变量
export oshwhub1="xxxxx" # oshwhub 登录 Cookie oshwhub_session 的值
export oshwhub2="xxxxx" # oshwhub 登录 Cookie oshwhub_session 的值

## 本地运行方式
python3 oshwhub.py

## 青龙面板单文件导入方式
ql raw https://raw.githubusercontent.com/FanchangWang/scripts/refs/heads/main/qinglong/oshwhub.py
```
