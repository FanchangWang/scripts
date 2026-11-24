#!/usr/bin/env bash
# User-space tool bootstrap for the Linux Mint laptop.
# Network: proxy 192.168.31.50:7890 + npm mirror; system packages come from apt.
# No pipefail: `yes | sdkmanager` legitimately SIGPIPEs yes when sdkmanager exits.
set -u

HOME_DIR="${HOME:-/home/guyue}"
BIN="$HOME_DIR/.local/bin"
TOOLS="$HOME_DIR/code-tools"
REV="$TOOLS/reverse"
mkdir -p "$HOME_DIR/code" "$HOME_DIR/code-ref" "$TOOLS" "$REV" "$BIN"

# Proxy is ON-DEMAND only: never export globally (that would route LAN/direct traffic
# through it). Individual download helpers below set it for their own process.
PROXY_URL="http://192.168.31.50:7890"
export PATH="$BIN:$TOOLS/nodejs/bin:$TOOLS/sysroot/bin:$PATH"

have() { command -v "$1" >/dev/null 2>&1; }
step() { printf '\n===== %s =====\n' "$1"; }
fail() { printf 'FAILED: %s (continuing)\n' "$1"; }
curl_retry() {
  env http_proxy="$PROXY_URL" https_proxy="$PROXY_URL" \
      HTTP_PROXY="$PROXY_URL" HTTPS_PROXY="$PROXY_URL" \
      curl --retry 3 --retry-delay 2 -fsSL "$@"
}
curl_direct() {
  env -u http_proxy -u https_proxy -u HTTP_PROXY -u HTTPS_PROXY \
      curl --retry 3 --retry-delay 2 -fsSL "$@"
}
latest_tag() {
  curl_retry "https://api.github.com/repos/$1/releases/latest" 2>/dev/null \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["tag_name"].lstrip("v"))' 2>/dev/null || echo ""
}

step "directories"
printf 'code=%s\ncode-ref=%s\ncode-tools=%s\n' \
  "$HOME_DIR/code" "$HOME_DIR/code-ref" "$TOOLS"

step "system packages via apt (git / jq / JDK17)"
if have git && have jq && have java; then
  echo "git/jq/java already present from apt"
else
  echo "MISSING_SYSTEM_PACKAGES: run 'sudo apt install -y git jq openjdk-17-jdk' then rerun this script"
fi
if have java; then
  JAVA_BIN="$(readlink -f "$(command -v java)")"
  export JAVA_HOME="$(dirname "$(dirname "$JAVA_BIN")")"
  echo "JDK17_HOME=$JAVA_HOME (from apt)"
fi

step "JDK 25 (Adoptium tarball via proxy)"
if [ ! -x "$TOOLS/jdk-25/bin/java" ]; then
  curl_retry -o /tmp/jdk25.tar.gz \
    'https://api.adoptium.net/v3/binary/latest/25/ga/linux/x64/jdk/hotspot/normal/eclipse' \
    && mkdir -p "$TOOLS/jdk-25" \
    && tar -xzf /tmp/jdk25.tar.gz -C "$TOOLS/jdk-25" --strip-components=1 \
    || fail "JDK 25 download"
fi
export JDK25_HOME="$TOOLS/jdk-25"
if [ -x "$JDK25_HOME/bin/java" ]; then
  "$JDK25_HOME/bin/java" -version 2>&1 | head -1
  cat > "$BIN/java25" <<EOF
#!/usr/bin/env bash
exec "$JDK25_HOME/bin/java" "\$@"
EOF
  chmod +x "$BIN/java25"
fi
cat > "$BIN/java17" <<'EOF'
#!/usr/bin/env bash
exec java "$@"
EOF
chmod +x "$BIN/java17"
export PATH="$TOOLS/jdk-25/bin:$PATH"

step "uv + Python 3.12"
if ! have uv; then
  curl_retry https://astral.sh/uv/install.sh | env UV_INSTALL_DIR="$BIN" sh || fail "uv install"
fi
export PATH="$BIN:$PATH"
if have uv; then
  env http_proxy="$PROXY_URL" https_proxy="$PROXY_URL" \
      HTTP_PROXY="$PROXY_URL" HTTPS_PROXY="$PROXY_URL" \
      uv python install 3.12 || fail "uv python install"
  uv run --no-project python -V || true
else
  fail "uv unavailable"
fi

step "Node.js 26 (tarball via proxy) + npm/pnpm/npx"
NODE_MAJOR=0
have node && NODE_MAJOR="$(node -v | cut -d. -f1 | tr -d 'v')"
if ! have node || [ "$NODE_MAJOR" -lt 26 ]; then
  # REA needs Node 26; prefer the newest v26 line, fall back to latest LTS.
  NODE_VER="$(curl_retry https://nodejs.org/dist/index.json \
    | python3 -c 'import json,sys; d=json.load(sys.stdin); print(next((x["version"] for x in d if x["version"].startswith("v26.")), next((x["version"] for x in d if x.get("lts")), "")))' 2>/dev/null)"
  NODE_VER="${NODE_VER:-v26.13.1}"
  rm -rf "$TOOLS/nodejs"
  curl_retry -o /tmp/node.tar.xz "https://nodejs.org/dist/${NODE_VER}/node-${NODE_VER}-linux-x64.tar.xz" \
    && mkdir -p "$TOOLS/nodejs" \
    && tar -xJf /tmp/node.tar.xz -C "$TOOLS/nodejs" --strip-components=1 \
    || fail "node tarball"
fi
export PATH="$TOOLS/nodejs/bin:$PATH"
if have npm; then
  npm config set prefix "$HOME_DIR/.local" || true
  npm config set registry https://registry.npmmirror.com/ || true
  npm install -g "pnpm@latest" || fail "pnpm install"
  echo "node $(node -v) / npm $(npm -v) / pnpm $(pnpm -v 2>/dev/null)"
else
  fail "npm unavailable"
fi

step "Android SDK (proxy)"
ANDROID_HOME="$TOOLS/android-sdk"
mkdir -p "$ANDROID_HOME/cmdline-tools/latest"
if [ ! -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]; then
  curl_retry -o /tmp/cmdline.zip \
    'https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip' \
    && unzip -q -o /tmp/cmdline.zip -d /tmp/cmdtl \
    && cp -a /tmp/cmdtl/cmdline-tools/. "$ANDROID_HOME/cmdline-tools/latest/" \
    || fail "cmdline-tools download"
fi
if [ -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]; then
  SDKMGR="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
  # dl.google.com is reachable directly here; the HTTP proxy corrupts large zips
  # (bad_record_mac), so sdkmanager deliberately runs without proxy args.
  SDK_ENV="env -u http_proxy -u https_proxy -u HTTP_PROXY -u HTTPS_PROXY"
  yes | $SDK_ENV "$SDKMGR" --sdk_root="$ANDROID_HOME" \
    "platform-tools" "platforms;android-19" "platforms;android-37.0" \
    "build-tools;19.1.0" "build-tools;35.0.0" "build-tools;37.0.0" \
    "ndk;29.0.14206865" "ndk;30.0.16248370" >/dev/null 2>&1 || fail "sdkmanager packages"
  yes | $SDK_ENV "$SDKMGR" --sdk_root="$ANDROID_HOME" --licenses >/dev/null 2>&1 || true
else
  fail "sdkmanager unavailable"
fi
export ANDROID_HOME
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/30.0.16248370"
export ANDROID_NDK_ROOT="$ANDROID_NDK_HOME"
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
adb version | head -1 || true
echo "ANDROID_NDK_HOME=$ANDROID_NDK_HOME"

step "GitHub CLI (release via proxy)"
if ! have gh; then
  GH_VER="${GH_VERSION:-$(latest_tag cli/cli)}"
  GH_VER="${GH_VER:-2.105.0}"
  curl_retry -o /tmp/gh.tgz \
    "https://github.com/cli/cli/releases/download/v${GH_VER}/gh_${GH_VER}_linux_amd64.tar.gz" \
    && tar -xzf /tmp/gh.tgz -C /tmp \
    && cp "/tmp/gh_${GH_VER}_linux_amd64/bin/gh" "$BIN/gh" \
    && chmod +x "$BIN/gh" \
    || fail "gh download (set GH_VERSION to a valid release)"
fi
gh --version 2>/dev/null | head -1 || true

step "OpenCode V2 (@opencode/cli, npm mirror)"
if have opencode; then
  OC_MAJOR="$(opencode --version 2>/dev/null | grep -oE '[0-9]+\.[0-9]+' | head -1 | cut -d. -f1)"
  OC_MAJOR="${OC_MAJOR:-1}"
  if [ "$OC_MAJOR" -lt 2 ]; then
    npm uninstall -g opencode-ai >/dev/null 2>&1 || true
    hash -r
  fi
fi
if ! have opencode || [ "$OC_MAJOR" -lt 2 ]; then
  npm install -g "@opencode/cli@latest" || fail "opencode V2 install"
  hash -r
fi
opencode --version 2>/dev/null || true

step "REA MCP wrapper"
cat > "$BIN/rea-mcp" <<'EOF'
#!/usr/bin/env bash
exec npx -y rea-agents@latest mcp "$@"
EOF
chmod +x "$BIN/rea-mcp"
echo "wrapper: $BIN/rea-mcp"

step "reverse engineering tools"
download_asset() {
  # $1 = URL, $2 = output; try direct first, then through proxy.
  local url="$1" out="$2"
  curl -fsSL --retry 2 -o "$out" "$url" \
    || curl_retry -o "$out" "$url"
}
if [ ! -f "$REV/apktool.jar" ]; then
  APKTOOL_VER="${APKTOOL_VERSION:-$(latest_tag iBotPeaches/Apktool)}"
  APKTOOL_VER="${APKTOOL_VER:-3.0.3}"
  download_asset \
    "https://github.com/iBotPeaches/Apktool/releases/download/v${APKTOOL_VER}/apktool_${APKTOOL_VER}.jar" \
    "$REV/apktool.jar" || fail "apktool download"
fi
if [ -f "$REV/apktool.jar" ]; then
  cat > "$BIN/apktool" <<EOF
#!/usr/bin/env bash
exec java -jar "$REV/apktool.jar" "\$@"
EOF
  chmod +x "$BIN/apktool"
fi

if [ ! -x "$REV/bin/jadx" ]; then
  JADX_VER="${JADX_VERSION:-$(latest_tag Skylot/jadx)}"
  JADX_VER="${JADX_VER:-1.5.7}"
  download_asset \
    "https://github.com/Skylot/jadx/releases/download/v${JADX_VER}/jadx-${JADX_VER}.zip" \
    /tmp/jadx.zip \
    && unzip -q -o /tmp/jadx.zip -d "$REV" \
    || fail "jadx download (set JADX_VERSION)"
fi
if [ -x "$REV/bin/jadx" ]; then
  cat > "$BIN/jadx" <<EOF
#!/usr/bin/env bash
exec "$REV/bin/jadx" "\$@"
EOF
  chmod +x "$BIN/jadx"
fi

if have uv; then
  # PyPI works directly on this LAN; the HTTP proxy breaks TLS for pythonhosted.
  export UV_LOCK_TIMEOUT=900
  env -u http_proxy -u https_proxy -u HTTP_PROXY -u HTTPS_PROXY \
    uv tool install androguard || fail "androguard install"
  env -u http_proxy -u https_proxy -u HTTP_PROXY -u HTTPS_PROXY \
    uv tool install binwalk || fail "binwalk install"
fi

step "persist env (single source of truth)"
ENV_FILE="$HOME_DIR/.custo-toolchain-env.sh"
cat > "$ENV_FILE" <<EOF
# custo-linux-toolchain-env: sourced by .profile (login) and .bashrc (interactive)
# Proxy is intentionally NOT exported here; download helpers use it on demand.
export JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")"
export JDK25_HOME="$HOME_DIR/code-tools/jdk-25"
export ANDROID_HOME="$HOME_DIR/code-tools/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/30.0.16248370"
export ANDROID_NDK_ROOT="$ANDROID_NDK_HOME"
export PATH="$HOME_DIR/.local/bin:$HOME_DIR/code-tools/nodejs/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$JDK25_HOME/bin:$JAVA_HOME/bin:\$PATH"
EOF
SOURCE_LINE='[ -f "$HOME/.custo-toolchain-env.sh" ] && . "$HOME/.custo-toolchain-env.sh"'
for f in "$HOME_DIR/.profile" "$HOME_DIR/.bashrc"; do
  grep -q 'custo-toolchain-env.sh' "$f" 2>/dev/null || printf '\n%s\n' "$SOURCE_LINE" >> "$f"
done
echo "env file: $ENV_FILE (sourced by .profile and .bashrc)"

step "done"
echo "Next: bash ~/code-tools/toolchain/toolchain.sh refresh"
