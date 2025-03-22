#!/bin/bash

# 检查必须是 root 用户
if [[ "$(id -u)" != 0 ]]; then
    echo "请使用 root 用户运行此脚本"
    exit 1
fi

# 获取 arch 平台架构
if [[ "$(dpkg --print-architecture)" == "arm64" ]]; then
    arch="arm64"
elif [[ "$(dpkg --print-architecture)" == "armhf" ]]; then
    arch="armv7"
else
    echo "不支持的架构: $(dpkg --print-architecture)"
    exit 1
fi

# 获取内部 IP 地址
internal_ip=$(hostname -I | awk '{print $1}')

software_dir="/opt"  # 默认软件存放目录

# 功能列表展示
function show_menu() {
    echo -e "\\n-------------- 功能列表 ------------------\\n"
    echo "本脚本下载的文件及容器映射目录都在 —— 软件存放目录: $software_dir"
    echo "== 物理机 =="
    echo "1. 修改软件存放目录"
    echo "2. 设置 ubuntu 软件源"
    echo "3. 安装 mihomo"
    echo "4. 安装 Docker"
    echo "0. 退出脚本"
    echo "== 容器-更新 =="
    echo "100. 安装 watchtower"
    echo "== 容器-面板 =="
    echo "200. 安装 dpanel:lite"
    echo "201. 安装 sun-panel:1.3.0"
    echo "202. 安装 webssh"
    echo "== 容器-网络 =="
    echo "300. 安装 lucky"
    echo "301. 安装 ddns-go"
    echo "302. 安装 nginx-proxy-manager"
    echo "303. 安装 nginx-ui"
    echo "== 容器-脚本 =="
    echo "400. 安装 qinglong"
    echo "== 容器-影音 =="
    echo "500. 安装 allinone & allinone_format"
    echo "501. 安装 allinone_format:dev"
    echo "502. 安装 xiaoya"
    echo "503. 安装 doube-itv"
    echo "== 容器-家庭自动化 =="
    echo "600. 安装 homeassistant"
    echo -e "\\n"
}

# 处理用户选择
function handle_choice() {
    local choice="$1"
    case "$choice" in
        0) exit 0 ;;
        1) modify_software_directory ;;
        2) set_software_source ;;
        3) install_mihomo ;;
        4) install_docker ;;
        100) install_watchtower ;;
        200) install_dpanel ;;
        201) install_sun_panel ;;
        202) install_webssh ;;
        300) install_lucky ;;
        301) install_ddns_go ;;
        302) install_nginx_proxy_manager ;;
        303) install_nginx_ui ;;
        400) install_qinglong ;;
        500) install_allinone_and_allinone_format ;;
        501) install_allinone_format_dev ;;
        502) install_xiaoya ;;
        503) install_doube_itv ;;
        600) install_homeassistant ;;
        *) echo "无效的选项" ;;
    esac
}

# 从 GitHub 下载文件的函数
function download_from_github() {
    local output="$1"  # 文件存放路径
    local url="$2"  # URL 下载地址

    # 国内 GitHub 加速源
    local github_proxies=(
        "https://gh-proxy.com/"
        "https://ghfast.top/"
        "https://slink.ltd/"
    )

    echo "准备下载文件: $output"
    # 随机打乱加速源
    local shuffled_proxies=($(shuf -e "${github_proxies[@]}"))

    for proxy in "${shuffled_proxies[@]}"; do
        echo "尝试从 $proxy 下载..."
        local new_url="${proxy}${url}"  # 在链接前加上加速源

        if wget -O "$output" "$new_url"; then
            echo "下载成功"
            return 0
        fi
    done

    # 尝试下载原始地址
    echo "尝试从 github.com 下载..."
    if wget -O "$output" "$url"; then
        echo "下载成功"
        return 0
    fi

    echo "所有下载尝试失败"
    exit 1
}

# 修改软件存放目录
function modify_software_directory() {
    read -p "请输入软件存放目录 (默认为 /opt): " new_software_dir
    software_dir=${new_software_dir:-/opt}
    # 判断如果开头不是 / 则报错
    if [[ "$software_dir" != /* ]]; then
        echo "软件存放目录必须以 / 开头"
        exit 1
    fi
    # 判断如果输入以 / 结尾 则去掉 /
    if [[ "$software_dir" == */ ]]; then
        software_dir=${software_dir%/}
    fi
    # 创建软件存放目录, 判断是否创建成功
    mkdir -p "$software_dir"
    if [ $? -ne 0 ]; then
        echo "创建软件存放目录失败"
        exit 1
    fi
    echo "软件存放目录已设置为: $software_dir"
}

# 设置软件源
function set_software_source() {
    echo "当前软件源:"
    cat /etc/apt/sources.list
    echo "请选择要设置的软件源:"
    echo "1. Ubuntu 原站"
    echo "2. 阿里云容器站"
    echo "0. 返回首层菜单"
    read -p "请输入选项 (0-2): " mirror_choice

    if [[ "$mirror_choice" -eq 0 ]]; then
        return
    fi

    case $mirror_choice in
        1)
            echo "设置为 Ubuntu 原站..."
            echo "deb http://ports.ubuntu.com/ jammy main restricted universe multiverse" > /etc/apt/sources.list
            echo "deb http://ports.ubuntu.com/ jammy-security main restricted universe multiverse" >> /etc/apt/sources.list
            echo "deb http://ports.ubuntu.com/ jammy-updates main restricted universe multiverse" >> /etc/apt/sources.list
            echo "deb http://ports.ubuntu.com/ jammy-backports main restricted universe multiverse" >> /etc/apt/sources.list
            ;;
        2)
            echo "设置为阿里云容器站..."
            echo "deb https://mirrors.aliyun.com/ubuntu-ports/ jammy main restricted universe multiverse" > /etc/apt/sources.list
            echo "deb https://mirrors.aliyun.com/ubuntu-ports/ jammy-security main restricted universe multiverse" >> /etc/apt/sources.list
            echo "deb https://mirrors.aliyun.com/ubuntu-ports/ jammy-updates main restricted universe multiverse" >> /etc/apt/sources.list
            echo "deb https://mirrors.aliyun.com/ubuntu-ports/ jammy-backports main restricted universe multiverse" >> /etc/apt/sources.list
            ;;
        *)
            echo "无效的选项"
            return
            ;;
    esac

    # 执行 apt-get update
    echo "正在更新软件源..."
    apt-get update
    echo "软件源更新完成"
}

# 安装 mihomo
function install_mihomo() {
    local mihomo_dir="$software_dir/mihomo"
    mkdir -p "$mihomo_dir"
    cd "$mihomo_dir"

    if [[ ! -f "./mihomo-linux-${arch}" ]]; then
        echo "下载 mihomo..."
        # 获取 version
        download_from_github "version.txt" "https://github.com/MetaCubeX/mihomo/releases/latest/download/version.txt"
        local version=$(cat version.txt | tr -d ' ')
        rm -f version.txt
        # 下载 mihomo-linux-${arch}-${version}.gz 文件
        download_from_github "mihomo-linux-${arch}-${version}.gz" "https://github.com/MetaCubeX/mihomo/releases/download/${version}/mihomo-linux-${arch}-${version}.gz"
        gzip -dN "mihomo-linux-${arch}-${version}.gz"
        if [ $? -ne 0 ]; then
            echo "解压失败"
            exit 1
        fi
        chmod +x "./mihomo-linux-${arch}"
        if [ $? -ne 0 ]; then
            echo "设置执行权限失败"
            exit 1
        fi
    fi

    # 检查配置文件夹
    local config_dir="$mihomo_dir/config"
    mkdir -p "$config_dir"
    if [ $? -ne 0 ]; then
        echo "创建配置文件夹失败"
        exit 1
    fi

    # 检查 UI 文件
    if [[ ! -d "$config_dir/ui" ]]; then
        echo "下载 UI 文件..."
        mkdir -p "$config_dir/ui"
        download_from_github "ui.tgz" "https://github.com/MetaCubeX/metacubexd/releases/latest/download/compressed-dist.tgz"
        tar -xzf ui.tgz -C "$config_dir/ui"
        if [ $? -ne 0 ]; then
            echo "解压 UI 文件失败"
            exit 1
        fi
        rm -f ui.tgz
    fi

    cd "$config_dir"

    # 下载 geo 文件
    local files=("GeoIP.dat" "GeoSite.dat" "GeoIP.metadb" "GeoLite2-ASN.mmdb")
    for file in "${files[@]}"; do
        if [[ ! -f "$file" ]]; then
            echo "下载 $file..."
            case $file in
                "GeoIP.dat")
                    download_from_github "GeoIP.dat" "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/geoip.dat"
                    ;;
                "GeoSite.dat")
                    download_from_github "GeoSite.dat" "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/geosite.dat"
                    ;;
                "GeoIP.metadb")
                    download_from_github "GeoIP.metadb" "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/geoip.metadb"
                    ;;
                "GeoLite2-ASN.mmdb")
                    download_from_github "GeoLite2-ASN.mmdb" "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/GeoLite2-ASN.mmdb"
                    ;;
            esac
        fi
    done

    # 下载 config.yaml
    download_from_github "config.yaml" "https://raw.githubusercontent.com/FanchangWang/clash_config/main/config.yaml"

    # 创建并启动 mihomo 服务
    local service_file="/etc/systemd/system/mihomo.service"
    if [[ ! -f "$service_file" ]]; then
        echo "创建 mihomo 服务文件..."
        cat <<EOL > "$service_file"
[Unit]
Description=mihomo Daemon, Another Clash Kernel.
After=network.target NetworkManager.service systemd-networkd.service iwd.service

[Service]
Type=simple
LimitNPROC=500
LimitNOFILE=1000000
CapabilityBoundingSet=CAP_NET_ADMIN CAP_NET_RAW CAP_NET_BIND_SERVICE CAP_SYS_TIME CAP_SYS_PTRACE CAP_DAC_READ_SEARCH CAP_DAC_OVERRIDE
AmbientCapabilities=CAP_NET_ADMIN CAP_NET_RAW CAP_NET_BIND_SERVICE CAP_SYS_TIME CAP_SYS_PTRACE CAP_DAC_READ_SEARCH CAP_DAC_OVERRIDE
Restart=always
ExecStartPre=/usr/bin/sleep 1s
ExecStart=$mihomo_dir/mihomo-linux-${arch} -d $config_dir
ExecReload=/bin/kill -HUP \$MAINPID

[Install]
WantedBy=multi-user.target
EOL
        systemctl daemon-reload
        systemctl enable mihomo
        systemctl start mihomo
        echo "mihomo 服务已启动"
    else
        echo "mihomo 服务已存在, 重启服务..."
        systemctl restart mihomo
    fi
}

# 安装 Docker
function install_docker() {
    if ! command -v docker &> /dev/null; then
        echo "Docker 未安装，正在安装..."
        apt update
        curl -fsSL https://get.docker.com | sh
    else
        echo "Docker 已安装"
    fi
    # 检查 docker 加速是否已设置，未设置则设置
    if [[ ! -f "/etc/docker/daemon.json" ]]; then
        echo "设置 Docker 加速..."
        mkdir -p /etc/docker
        cat <<EOL > /etc/docker/daemon.json
{
  "registry-mirrors": [
    "https://docker.guyuexuan.ggff.net",
    "https://docker.1panel.top",
    "https://proxy.1panel.live"
  ]
}
EOL
        systemctl restart docker
        echo "Docker 加速设置完成"
    fi
}

# 安装 watchtower
function install_watchtower() {
    if docker ps -a | grep -q containrrr/watchtower; then
        echo "watchtower 容器已存在，跳过安装"
    else
        echo "安装 watchtower 容器..."
        docker pull containrrr/watchtower:latest
        docker run -e TZ=Asia/Shanghai -v /var/run/docker.sock:/var/run/docker.sock -d --restart=unless-stopped --name watchtower containrrr/watchtower:latest --cleanup --interval=1800
        echo "watchtower 容器安装完成"
    fi
}

# 安装 dpanel
function install_dpanel() {
    if docker ps -a | grep -q dpanel/dpanel; then
        echo "dpanel 容器已存在，跳过安装"
    else
        echo "安装 dpanel:lite 容器..."
        local dpanel_dir="$software_dir/dpanel"
        mkdir -p "$dpanel_dir"
        docker pull dpanel/dpanel:lite
        docker run -e TZ=Asia/Shanghai -v "$dpanel_dir:/dpanel" -v /var/run/docker.sock:/var/run/docker.sock -d --restart=unless-stopped -p 8807:8080 --name dpanel dpanel/dpanel:lite 
        echo "dpanel:lite 容器安装完成"
    fi
    echo "dpanel:lite 访问地址: http://${internal_ip}:8807"
}

# 安装 sun-panel
function install_sun_panel() {
    if docker ps -a | grep -q hslr/sun-panel; then
        echo "sun-panel:1.3.0 容器已存在，跳过安装"
    else
        echo "安装 sun-panel:1.3.0 容器..."
        local sun_panel_dir="$software_dir/sun-panel"
        mkdir -p "$sun_panel_dir"
        docker pull hslr/sun-panel:1.3.0
        docker run -e TZ=Asia/Shanghai -v "$sun_panel_dir/conf:/app/conf" -v "$sun_panel_dir/uploads:/app/uploads" -v "$sun_panel_dir/database:/app/database" -d --restart=unless-stopped -p 3002:3002 --name sun-panel hslr/sun-panel:1.3.0
        echo "sun-panel:1.3.0 容器安装完成"
    fi
    echo "sun-panel:1.3.0 访问地址: http://${internal_ip}:3002"
}

# 安装 webssh
function install_webssh() {
    if docker ps -a | grep -q jrohy/webssh; then
        echo "webssh 容器已存在，跳过安装"
    else
        echo "安装 webssh 容器..."
        docker pull jrohy/webssh:latest
        docker run -e TZ=Asia/Shanghai -d --restart=unless-stopped -p 5032:5032 --name webssh jrohy/webssh
        echo "webssh 容器安装完成"
    fi
    echo "webssh 访问地址: http://${internal_ip}:5032"
}

# 安装 lucky
function install_lucky() {
    if docker ps -a | grep -q gdy666/lucky; then
        echo "lucky 容器已存在，跳过安装"
    else
        echo "安装 lucky 容器..."
        local lucky_dir="$software_dir/lucky"
        mkdir -p "$lucky_dir"
        docker pull gdy666/lucky:latest
        docker run -e TZ=Asia/Shanghai -v "$lucky_dir:/goodluck" -d --restart=unless-stopped --net=host --name lucky gdy666/lucky:latest
        echo "lucky 容器安装完成"
    fi
    echo "lucky 访问地址: http://${internal_ip}:16601"
}

# 安装 ddns-go
function install_ddns_go() {
    if docker ps -a | grep -q jeessy/ddns-go; then
        echo "ddns-go 容器已存在，跳过安装"
    else
        echo "安装 ddns-go 容器..."
        local ddns_go_dir="$software_dir/ddns-go"
        mkdir -p "$ddns_go_dir"
        docker pull jeessy/ddns-go:latest
        docker run -e TZ=Asia/Shanghai -v "$ddns_go_dir:/root" -d --restart=unless-stopped --net=host --name ddns-go jeessy/ddns-go:latest
        echo "ddns-go 容器安装完成"
    fi
    echo "ddns-go 访问地址: http://${internal_ip}:9876"
}

# 安装 nginx-proxy-manager
function install_nginx_proxy_manager() {
    if docker ps -a | grep -q jc21/nginx-proxy-manager; then
        echo "nginx-proxy-manager 容器已存在，跳过安装"
    else
        echo "安装 nginx-proxy-manager 容器..."
        local nginx_proxy_manager_dir="$software_dir/nginx-proxy-manager"
        mkdir -p "$nginx_proxy_manager_dir"
        docker pull jc21/nginx-proxy-manager:latest
        docker run -e TZ=Asia/Shanghai -v "$nginx_proxy_manager_dir/data:/data" -v "$nginx_proxy_manager_dir/letsencrypt:/etc/letsencrypt" -d --restart=unless-stopped -p 2082:80 -p 81:81-p 2083:443 --name nginx-proxy-manager jc21/nginx-proxy-manager:latest
        echo "nginx-proxy-manager 容器安装完成"
    fi
    echo "nginx-proxy-manager 访问地址: http://${internal_ip}:81"
}

# 安装 nginx-ui
function install_nginx_ui() {
    if docker ps -a | grep -q uozi/nginx-ui; then
        echo "nginx-ui 容器已存在，跳过安装"
    else
        echo "安装 nginx-ui 容器..."
        local nginx_ui_dir="$software_dir/nginx-ui"
        mkdir -p "$nginx_ui_dir"
        docker pull uozi/nginx-ui:latest
        docker run -e TZ=Asia/Shanghai -v "$nginx_ui_dir/nginx:/etc/nginx" -v "$nginx_ui_dir/nginx-ui:/etc/nginx-ui" -v "$nginx_ui_dir/www:/var/www" -p 2082:80 -p 2083:443 -d --restart=unless-stopped --name=nginx-ui uozi/nginx-ui:latest
        echo "nginx-ui 容器安装完成"
    fi
    echo "nginx-ui 访问地址: http://${internal_ip}:2082"
}

# 安装 qinglong
function install_qinglong() {
    if docker ps -a | grep -q whyour/qinglong; then
        echo "qinglong 容器已存在，跳过安装"
    else
        echo "安装 qinglong 容器..."
        echo "请选择安装镜像版本 latest 或 develop"
        read -p "请输入选项 (1: latest[默认] 或 2: develop): " qinglong_version
        if [[ "$qinglong_version" == "2" ]]; then
            qinglong_version="develop"
        else
            qinglong_version="latest"
        fi
        local qinglong_dir="$software_dir/qinglong"
        mkdir -p "$qinglong_dir"
        docker pull whyour/qinglong:$qinglong_version
        docker run -e TZ=Asia/Shanghai -v "$qinglong_dir:/ql/data" -d --restart=unless-stopped -p 5700:5700 --name qinglong whyour/qinglong:$qinglong_version
        echo "qinglong:$qinglong_version 容器安装完成"
    fi
    echo "qinglong 访问地址: http://${internal_ip}:5700"
}

# 安装 allinone & allinone_format
function install_allinone_and_allinone_format() {
    if docker ps -a | grep -q youshandefeiyang/allinone; then
        echo "allinone 容器已存在，跳过安装"
    else
        echo "安装 allinone 容器..."
        # 定义 aesKey 和 userid 和 token
        local aesKey="423iupx13z65gh46bmxyusyk9rvs63v9"
        local userid="834158134"
        local token="df286cf77e20498ed11a2ffa9512352d2e1678125ca1cebdcbc510473472dbb9f69826c884f8291811093c2f28f67793774a722c41d1589b2016846fe40eb400348e7d0342c5"
        docker pull youshandefeiyang/allinone:latest
        docker run -e TZ=Asia/Shanghai -d --restart=unless-stopped -p 35455:35455 --name allinone youshandefeiyang/allinone:latest -aesKey=$aesKey -userid=$userid -token=$token
        echo "allinone 容器安装完成"
    fi
    if docker ps -a | grep -q yuexuangu/allinone_format:latest; then
        echo "allinone_format:latest 容器已存在，跳过安装"
    else
        echo "安装 allinone_format:latest 容器..."
        local allinone_format_dir="$software_dir/allinone_format"
        mkdir -p "$allinone_format_dir"
        docker pull yuexuangu/allinone_format:latest
        docker run -e TZ=Asia/Shanghai -v $allinone_format_dir:/app/config -d --restart=unless-stopped -p 35456:35456 --name allinone_format yuexuangu/allinone_format:latest
        echo "allinone_format:latest 容器安装完成"
    fi
    echo "allinone 访问地址: http://${internal_ip}:35455/tv.m3u"
    echo "allinone_format:latest 访问地址: http://${internal_ip}:35456"
}

# 安装 allinone_format:dev
function install_allinone_format_dev() {
    if docker ps -a | grep -q yuexuangu/allinone_format:dev; then
        echo "allinone_format:dev 容器已存在，跳过安装"
    else
        echo "安装 allinone_format:dev 容器..."
        local allinone_format_dir="$software_dir/allinone_format_dev"
        mkdir -p "$allinone_format_dir"
        docker pull yuexuangu/allinone_format:dev
        docker run -e TZ=Asia/Shanghai -v $allinone_format_dir:/app/config -d --restart=unless-stopped -p 35457:35456 --name allinone_format_dev yuexuangu/allinone_format:dev
        echo "allinone_format:dev 容器安装完成"
    fi
    echo "allinone_format:dev 访问地址: http://${internal_ip}:35457"
}

# 安装 xiaoya
function install_xiaoya() {
    if docker ps -a | grep -q xiaoyaliu/alist; then
        echo "xiaoya 容器已存在，跳过安装"
    else
        echo "安装 xiaoya 容器..."
        local xiaoya_dir="$software_dir/xiaoya"
        mkdir -p "$xiaoya_dir"
        docker pull xiaoyaliu/alist:latest
        docker run -e TZ=Asia/Shanghai -v "$xiaoya_dir/data:/data" -v "$xiaoya_dir/www:/www/data" -v "$xiaoya_dir/alist:/opt/alist/data" -d --restart=unless-stopped -p 5678:80 --name=xiaoya xiaoyaliu/alist:latest
        echo "xiaoya 容器安装完成"
    fi
    echo "xiaoya 访问地址: http://${internal_ip}:5678"
}

# 安装 doube-itv
function install_doube_itv() {
    if docker ps -a | grep -q doubebly/doube-itv; then
        echo "doube-itv 容器已存在，跳过安装"
    else
        echo "安装 doube-itv 容器..."
        docker pull doubebly/doube-itv:latest
        docker run -e TZ=Asia/Shanghai -d --restart=unless-stopped -p 5000:5000 --name doube-itv doubebly/doube-itv:latest
        echo "doube-itv 容器安装完成"
    fi
    echo "doube-itv 访问地址: http://${internal_ip}:5000/help"
}

# 安装 homeassistant
function install_homeassistant() {
    if docker ps -a | grep -q homeassistant/home-assistant; then
        echo "homeassistant/home-assistant 容器已存在，跳过安装"
    else
        echo "安装 homeassistant/home-assistant 容器..."
        local homeassistant_dir="$software_dir/homeassistant"
        mkdir -p "$homeassistant_dir"
        docker pull homeassistant/home-assistant:latest
        docker run -e TZ=Asia/Shanghai --privileged -d --restart=unless-stopped -v "$homeassistant_dir:/config" --name homeassistant --network=host homeassistant/home-assistant:latest
        echo "homeassistant/home-assistant 容器安装完成"
    fi
    echo "homeassistant/home-assistant 访问地址: http://${internal_ip}:8123"
}

while true; do
    show_menu
    read -p "请选择要执行的功能 [0 退出脚本]: " choice
    handle_choice "$choice"
    echo "按任意键继续..."
    read -s -n 1
done
