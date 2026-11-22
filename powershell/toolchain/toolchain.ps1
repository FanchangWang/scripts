param(
  [Parameter(Position = 0)]
  [ValidateSet('list', 'find', 'refresh', 'doctor', 'config', 'raw', 'md', 'rea-check', 'help')]
  [string]$Command = 'list',
  [Parameter(Position = 1)]
  [string]$Query = '',
  [switch]$Json,
  [string]$OutputDirectory = 'C:\Users\guyue\.agents\skills\local-toolchain',
  [string]$WrapperPath = ''
)

$ErrorActionPreference = 'Stop'
$ScriptRoot = $PSScriptRoot
$ManifestPath = Join-Path $OutputDirectory 'inventory.json'
$MarkdownPath = Join-Path $OutputDirectory 'inventory.md'
$SkillPath = Join-Path $OutputDirectory 'SKILL.md'
$OverridesPath = Join-Path $ScriptRoot 'overrides.json'
$TemplatePath = Join-Path $ScriptRoot 'skill-template.md'
$QueryTemplatePath = Join-Path $ScriptRoot 'query-template.ps1'
$QueryPath = Join-Path $OutputDirectory 'query.ps1'
$ReaCheckPath = Join-Path $ScriptRoot 'rea-env.json'
$Today = (Get-Date).ToString('yyyy-MM-dd')
$RunHint = 'pwsh.exe -NoProfile -File "' + $PSCommandPath + '"'

function Write-Utf8NoBom {
  param([string]$Path, [string]$Text)
  $dir = Split-Path -Parent $Path
  if ($dir -and -not (Test-Path -LiteralPath $dir)) {
    New-Item -ItemType Directory -Path $dir -Force | Out-Null
  }
  [System.IO.File]::WriteAllText($Path, $Text, [System.Text.UTF8Encoding]::new($false))
}

function Get-VersionText {
  param([string]$Exe, [string[]]$Argv)
  if (-not $Exe -or -not $Argv -or $Argv.Count -eq 0) { return $null }
  try {
    $raw = & $Exe @Argv 2>&1 6>&1
    $line = $raw |
      ForEach-Object { $_.ToString() } |
      Where-Object { $_ -match '\S' } |
      Select-Object -First 1
    if (-not $line) { return $null }
    return (($line -replace '\s+', ' ').Trim())
  } catch {
    return $null
  }
}

function Resolve-Exe {
  param([string]$Explicit, [string]$CommandName)
  if ($Explicit -and (Test-Path -LiteralPath $Explicit)) { return $Explicit }
  if ($CommandName) {
    $c = Get-Command -Name $CommandName -ErrorAction SilentlyContinue
    if ($c) { return $c.Source }
  }
  return $null
}

function New-ToolRecord {
  param([hashtable]$Spec)

  $exe = Resolve-Exe -Explicit $Spec.exe -CommandName $Spec.command
  $version = $Spec.static_version
  if (-not $version -and $exe -and $Spec.args) {
    $version = Get-VersionText -Exe $exe -Argv $Spec.args
  }

  $verifyParts = @()
  if ($Spec.command) { $verifyParts += $Spec.command } elseif ($exe) { $verifyParts += $exe }
  if ($Spec.args) { $verifyParts += $Spec.args }
  $verify = if ($verifyParts.Count) { $verifyParts -join ' ' } else { $null }

  [pscustomobject][ordered]@{
    id           = $Spec.id
    name         = $Spec.name
    category     = $Spec.category
    kind         = if ($Spec.kind) { $Spec.kind } else { 'executable' }
    status       = if ($exe) { 'installed' } else { 'missing' }
    version      = $version
    command      = $Spec.command
    path         = $exe
    verify       = $verify
    source       = if ($Spec.source) { $Spec.source } else { 'unknown' }
    last_verified = if ($exe) { $Today } else { $null }
    notes        = if ($Spec.notes) { $Spec.notes } else { $null }
    tags         = if ($Spec.tags) { $Spec.tags } else { @() }
  }
}

function Get-Specs {
  return @(
    @{ id='node'; name='Node.js'; category='runtime'; command='node'; exe='C:\Program Files\nodejs\node.exe'; args=@('--version'); source='official installer'; notes='v26.13.1. REA MCP wrapper uses this installation.'; tags=@('javascript','js','npm') },
    @{ id='npm'; name='npm'; category='package-manager'; command='npm'; exe='C:\Program Files\nodejs\npm.cmd'; args=@('--version'); source='official installer'; notes='12.2.3. Registry mirror: https://registry.npmmirror.com/ (user .npmrc).'; tags=@('javascript','registry') },
    @{ id='npx'; name='npx'; category='package-manager'; command='npx'; exe='C:\Program Files\nodejs\npx.cmd'; args=@('--version'); source='official installer'; notes='12.2.3. Use npx.cmd in Windows automation because the REA wrapper depends on .cmd launchers.'; tags=@('javascript','runner') },
    @{ id='pnpm'; name='pnpm'; category='package-manager'; command='pnpm'; exe='C:\Users\guyue\AppData\Roaming\npm\pnpm.cmd'; args=@('--version'); source='npm'; notes='12.21.0. Registry mirror: https://registry.npmmirror.com/.'; tags=@('javascript','registry') },
    @{ id='bun'; name='Bun'; category='runtime'; command='bun'; exe='C:\Users\guyue\AppData\Roaming\npm\bun.cmd'; args=@('--version'); source='npm'; notes='1.4.5. Runtime, package manager, bundler, and test runner.'; tags=@('javascript','typescript') },
    @{ id='go'; name='Go'; category='sdk'; command='go'; exe='C:\Program Files\Go\bin\go.exe'; args=@('version'); source='official installer'; notes='GOPROXY=https://goproxy.cn,direct. GOROOT=C:\Program Files\Go.'; tags=@('golang') },
    @{ id='dotnet'; name='.NET SDK'; category='sdk'; command='dotnet'; exe='C:\Program Files\dotnet\dotnet.exe'; args=@('--version'); source='official installer'; notes='SDK 11.0.100. Use this host for ILSpy/ilspycmd tooling.'; tags=@('csharp','dotnet') },
    @{ id='uv'; name='uv'; category='package-manager'; command='uv'; exe='C:\Users\guyue\AppData\Local\Microsoft\WinGet\Links\uv.exe'; args=@('--version'); source='winget'; notes='0.13.9. Python is uv-managed; no global python or pip on PATH.'; tags=@('python','uv') },
    @{ id='uvx'; name='uvx'; category='package-manager'; command='uvx'; exe='C:\Users\guyue\AppData\Local\Microsoft\WinGet\Links\uvx.exe'; args=@('--version'); source='winget'; notes='0.13.9. Use uvx <tool> for one-off Python tool CLIs.'; tags=@('python','uv') },
    @{ id='python-uv-managed'; name='Python (uv-managed CPython)'; category='runtime'; exe='C:\Users\guyue\AppData\Roaming\uv\python\cpython-3.12.15-windows-x86_64-none\python.exe'; args=@('--version'); source='uv'; notes='CPython 3.12.15. Run scripts with `uv run python file.py`; `python`/`pip` are not on PATH.'; tags=@('python','uv') },
    @{ id='rustc'; name='Rust compiler'; category='compiler'; command='rustc'; exe='C:\Users\guyue\.cargo\bin\rustc.exe'; args=@('--version'); source='rustup'; notes='rustc 1.99.1, stable-x86_64-pc-windows-msvc.'; tags=@('rust') },
    @{ id='cargo'; name='Cargo'; category='package-manager'; command='cargo'; exe='C:\Users\guyue\.cargo\bin\cargo.exe'; args=@('--version'); source='rustup'; notes='cargo 1.99.1.'; tags=@('rust') },
    @{ id='rustup'; name='rustup'; category='package-manager'; command='rustup'; exe='C:\Users\guyue\.cargo\bin\rustup.exe'; args=@('--version'); source='rustup'; notes='1.29.3; active/default toolchain stable-x86_64-pc-windows-msvc.'; tags=@('rust') },
    @{ id='php'; name='PHP'; category='runtime'; command='php'; exe=$null; args=@('--version'); source='winget'; notes='PHP 8.5 NTS installed via winget package path; verify actual patch version with the command.'; tags=@('php') },
    @{ id='composer'; name='Composer'; category='package-manager'; command='composer'; exe=$null; args=@('--version'); source='official installer'; notes='PHP dependency manager.'; tags=@('php') },
    @{ id='git'; name='Git'; category='vcs'; command='git'; exe='C:\Program Files\Git\cmd\git.exe'; args=@('--version'); source='official installer'; notes='2.56.1.windows.1. Global proxy is http://192.168.31.50:7890; core.autocrlf=true; credentials use gh.exe.'; tags=@('vcs','github','proxy') },
    @{ id='git-lfs'; name='Git LFS'; category='vcs'; command='git'; exe='C:\Program Files\Git\cmd\git.exe'; args=@('lfs','version'); source='official installer'; notes='git-lfs 3.8.1.'; tags=@('vcs','lfs') },
    @{ id='git-bash'; name='Git Bash'; category='cli'; exe='C:\Program Files\Git\bin\bash.exe'; args=@('--version'); source='official installer'; notes='GNU bash 5.3.15(2)-release. Use this explicit path; `bash` on PATH resolves to C:\WINDOWS\system32\bash.exe.'; tags=@('shell','bash') },
    @{ id='gh'; name='GitHub CLI'; category='cli'; command='gh'; exe='C:\Program Files\GitHub CLI\gh.exe'; args=@('--version'); source='winget'; notes='gh 2.105.0. Configured as Git credential helper for github.com and gist.github.com.'; tags=@('github','cli') },
    @{ id='winget'; name='winget'; category='package-manager'; command='winget'; exe='C:\Users\guyue\AppData\Local\Microsoft\WindowsApps\winget.exe'; args=@('--version'); source='built-in'; notes='v1.29.380. Preferred install source.'; tags=@('windows','installer') },
    @{ id='choco'; name='Chocolatey'; category='package-manager'; command='choco'; exe=$null; args=@('--version'); source='installer'; notes='Second install preference. Not found on PATH at refresh time.'; tags=@('windows','installer') },
    @{ id='scoop'; name='Scoop'; category='package-manager'; command='scoop'; exe=$null; args=@('--version'); source='installer'; notes='Shim directory C:\Users\guyue\scoop\shims is on PATH; verify with `scoop --version`.'; tags=@('windows','installer') },
    @{ id='pwsh'; name='PowerShell 7'; category='cli'; command='pwsh'; exe=$null; args=@('--version'); source='winget'; notes='Preferred shell in OpenCode config. This toolchain is a pwsh script.'; tags=@('shell','powershell') },
    @{ id='opencode'; name='OpenCode'; category='cli'; command='opencode'; exe=$null; args=@('--version'); source='npm'; notes='v2.3.2. REA is registered as a disabled-by-default MCP server.'; tags=@('agent','ai') },
    @{ id='code'; name='VS Code'; category='editor'; command='code'; exe='C:\Users\guyue\AppData\Local\Programs\Microsoft VS Code\bin\code.cmd'; args=@('--version'); source='official installer'; notes='1.147.0. Also configured as global Git core.editor with --wait.'; tags=@('editor') },
    @{ id='curl'; name='curl'; category='network'; command='curl'; exe='C:\Windows\System32\curl.exe'; args=@('--version'); source='built-in'; notes='Windows built-in curl 8.22.0.'; tags=@('http','download') },
    @{ id='cmake'; name='CMake'; category='build'; command='cmake'; exe='C:\Program Files\CMake\bin\cmake.exe'; args=@('--version'); source='official installer'; notes='cmake 4.4.4.'; tags=@('build') },
    @{ id='ninja'; name='Ninja'; category='build'; command='ninja'; exe='C:\Program Files\CMake\bin\ninja.exe'; args=@('--version'); source='official installer'; notes='ninja 1.12.1.'; tags=@('build') },
    @{ id='clang'; name='Clang'; category='compiler'; command='clang'; exe='C:\Program Files\LLVM\bin\clang.exe'; args=@('--version'); source='official installer'; notes='clang 21.1.8 targeting x86_64-pc-windows-msvc.'; tags=@('c','cpp','compiler') },
    @{ id='objdump'; name='GNU objdump'; category='binary-analysis'; exe='C:\Users\guyue\code-tools\binary-analysis\binutils\2.47.20260726\mingw64\bin\objdump.exe'; args=@('--version'); source='official ZIP (WinLibs)'; notes='2.47.20260726. REA setting REA_OBJDUMP_COMMAND. No standard objdump on PATH; use this absolute path.'; tags=@('rea','binutils','disassembly') },
    @{ id='wabt'; name='WABT'; category='binary-analysis'; exe='C:\Users\guyue\code-tools\binary-analysis\wabt\1.0.42\bin\wasm-objdump.exe'; args=@('--version'); source='official ZIP (GitHub accelerated)'; notes='1.0.42, pinned by REA. REA setting REA_WABT_BIN_DIRECTORY points at the parent bin directory.'; tags=@('rea','wasm') },
    @{ id='rizin'; name='Rizin'; category='binary-analysis'; exe='C:\Program Files\Rizin\bin\rizin.exe'; args=@('-v'); source='installer'; notes='0.9.1. REA setting REA_RIZIN_COMMAND. Debug-session cleanup has Windows limitations; static command use is primary.'; tags=@('rea','disassembly','debug') },
    @{ id='tshark'; name='TShark'; category='network'; exe='C:\Program Files\Wireshark\tshark.exe'; args=@('-v'); source='installer'; notes='Wireshark 4.6.10. REA setting REA_TSHARK_COMMAND. Installed without Npcap: offline pcap parsing only.'; tags=@('rea','pcap','wireshark') },
    @{ id='capinfos'; name='capinfos'; category='network'; exe='C:\Program Files\Wireshark\capinfos.exe'; args=@('-v'); source='installer'; notes='Wireshark 4.6.10. REA setting REA_CAPINFOS_COMMAND.'; tags=@('rea','pcap','wireshark') },
    @{ id='adb'; name='Android Debug Bridge'; category='binary-analysis'; exe='C:\Users\guyue\AppData\Local\Android\Sdk\platform-tools\adb.exe'; args=@('version'); source='Android SDK'; notes='REA setting REA_ADB_PATH. Only platform-tools\adb.exe is used by REA; NDK/build-tools/emulator/aapt2 are not required.'; tags=@('rea','android') },
    @{ id='android-sdk'; name='Android SDK root'; category='sdk'; exe='C:\Users\guyue\AppData\Local\Android\Sdk'; args=$null; source='Android Studio'; static_version='NDK 29/30; emulator 37.3.10.0; build-tools 19.1.0/35/36/36.1/37; platforms through android-37.2'; kind='install-dir'; notes='Full SDK at C:\Users\guyue\AppData\Local\Android\Sdk. REA only consumes platform-tools\adb.exe; the rest is for Android development.'; tags=@('android','sdk') },
    @{ id='android-studio'; name='Android Studio'; category='editor'; exe='C:\Users\guyue\AppData\Local\Programs\Android Studio\bin\studio64.exe'; args=$null; source='official installer'; static_version='AI-262.10968.63.2622.16975389'; kind='executable'; notes='Android Studio IDE. Bundles JBR 25.0.4 under ..\Android Studio\jbr.'; tags=@('android','ide') },
    @{ id='android-cli'; name='Android CLI'; category='cli'; exe='C:\Users\guyue\AppData\Local\Android\Sdk\cmdline-tools\latest\bin\android.exe'; args=@('--version'); source='Android SDK'; notes='1.1.16919734. Replaces deprecated sdkmanager; use `android sdk ...` for SDK package work.'; tags=@('android','cli','sdkmanager') },
    @{ id='sdkmanager'; name='Android SDK Manager (deprecated)'; category='cli'; exe='C:\Users\guyue\AppData\Local\Android\Sdk\cmdline-tools\latest\bin\sdkmanager.bat'; args=$null; source='Android SDK'; static_version='1.1.16919734'; kind='script'; notes='Deprecated by Google. Prefer android.exe / `android sdk ...`. Kept for compatibility.'; tags=@('android','cli','deprecated') },
    @{ id='avdmanager'; name='AVD Manager'; category='cli'; exe='C:\Users\guyue\AppData\Local\Android\Sdk\cmdline-tools\latest\bin\avdmanager.bat'; args=$null; source='Android SDK'; static_version=$null; kind='script'; notes='Create/manage Android Virtual Devices. Use together with emulator.exe.'; tags=@('android','emulator','avd') },
    @{ id='apkanalyzer'; name='apkanalyzer'; category='binary-analysis'; exe='C:\Users\guyue\AppData\Local\Android\Sdk\cmdline-tools\latest\bin\apkanalyzer.bat'; args=$null; source='Android SDK'; static_version=$null; kind='script'; notes='Inspect APK contents from the Android SDK command-line tools.'; tags=@('android','apk') },
    @{ id='android-cli-helpers'; name='Android CLI helper tools'; category='cli'; exe='C:\Users\guyue\AppData\Local\Android\Sdk\cmdline-tools\latest\bin'; args=$null; source='Android SDK'; static_version=$null; kind='install-dir'; notes='Also contains d8, r8, lint, profgen, resourceshrinker, retrace, and screenshot2.'; tags=@('android','cli') },
    @{ id='android-emulator'; name='Android Emulator'; category='cli'; exe='C:\Users\guyue\AppData\Local\Android\Sdk\emulator\emulator.exe'; args=@('-version'); source='Android SDK'; notes='Android emulator 37.3.10.0. System images installed for android-19, 31, 34, 35, 36.1.'; tags=@('android','emulator','avd') },
    @{ id='android-ndk-29'; name='Android NDK 29'; category='sdk'; exe='C:\Users\guyue\AppData\Local\Android\Sdk\ndk\29.0.14206865'; args=$null; source='Android SDK'; static_version='29.0.14206865'; kind='install-dir'; notes='NDK toolchain. PATH currently exposes NDK 30, not this one.'; tags=@('android','ndk','cpp') },
    @{ id='android-ndk-30'; name='Android NDK 30 (active on PATH)'; category='sdk'; exe='C:\Users\guyue\AppData\Local\Android\Sdk\ndk\30.0.16248370'; args=$null; source='Android SDK'; static_version='30.0.16248370'; kind='install-dir'; notes='NDK and LLVM toolchain directories are on the user PATH.'; tags=@('android','ndk','cpp') },
    @{ id='android-build-tools'; name='Android build-tools'; category='sdk'; exe='C:\Users\guyue\AppData\Local\Android\Sdk\build-tools\37.0.0'; args=$null; source='Android SDK'; static_version='19.1.0, 35.0.0, 36.0.0, 36.1.0, 37.0.0'; kind='install-dir'; notes='Contains aapt2, apksigner, dx, d8, r8, zipalign. User PATH currently exposes build-tools 37.0.0.'; tags=@('android','build-tools') },
    @{ id='android-platforms'; name='Android platform SDKs'; category='sdk'; exe='C:\Users\guyue\AppData\Local\Android\Sdk\platforms'; args=$null; source='Android SDK'; static_version='android-19, 30, 33, 34, 35, 36, 36.1, 37.0, 37.1, 37.2'; kind='install-dir'; notes='Installed android.jar platforms.'; tags=@('android','platform') },
    @{ id='android-system-images'; name='Android system images'; category='sdk'; exe='C:\Users\guyue\AppData\Local\Android\Sdk\system-images'; args=$null; source='Android SDK'; static_version='android-19, 31, 34, 35, 36.1'; kind='install-dir'; notes='AVD system images; use avdmanager and emulator.exe.'; tags=@('android','emulator','avd') },
    @{ id='android-sdk-cmake'; name='Android SDK CMake'; category='build'; exe='C:\Users\guyue\AppData\Local\Android\Sdk\cmake\4.1.2\bin\cmake.exe'; args=@('--version'); source='Android SDK'; notes='SDK-bundled CMake 4.1.2 for NDK builds.'; tags=@('android','cmake','ndk') },
    @{ id='fastboot'; name='fastboot'; category='binary-analysis'; exe='C:\Users\guyue\AppData\Local\Android\Sdk\platform-tools\fastboot.exe'; args=@('--version'); source='Android SDK'; notes='platform-tools fastboot for bootloader/firmware operations.'; tags=@('android','fastboot') },
    @{ id='apktool-3'; name='Apktool 3.0.3 (active)'; category='binary-analysis'; exe='C:\Users\guyue\code-tools\android-apk\apktool\3.0.3\apktool.bat'; args=@('--version'); source='official JAR'; notes='Active REA setting REA_APKTOOL_COMMAND.'; tags=@('rea','android','apk') },
    @{ id='apktool-2'; name='Apktool 2.9.3 (retained)'; category='binary-analysis'; exe='C:\Users\guyue\code-tools\android-apk\apktool\2.9.3\apktool.bat'; args=@('--version'); source='official JAR'; notes='Old version retained for compatibility; not the active REA command.'; tags=@('android','apk') },
    @{ id='jadx-headless-mcp'; name='JADX Headless MCP'; category='binary-analysis'; exe='C:\Users\guyue\code-tools\android-apk\jadx-headless-mcp\jadx-headless-mcp-0.7.1-all.jar'; args=$null; source='official JAR'; static_version='0.7.1'; kind='jar'; notes='REA setting REA_JADX_MCP_JAR. Java 25 is compatible.'; tags=@('rea','android','jar') },
    @{ id='ilspycmd'; name='ilspycmd'; category='binary-analysis'; exe='C:\Users\guyue\code-tools\dotnet-decompilers\ilspycmd\11.2.0.10050\.store\ilspycmd\11.2.0.10050\ilspycmd.win-x64\11.2.0.10050\tools\any\win-x64\ilspycmd.exe'; args=@('--version'); source='dotnet tool'; notes='11.2.0.10050. REA setting REA_ILSPY_CMD_PATH.'; tags=@('rea','dotnet','decompiler') },
    @{ id='kuna'; name='Kuna'; category='binary-analysis'; exe='C:\Users\guyue\code-tools\binary-analysis\kuna\1.1003\kuna-v1.1003-windows-x86_64\kuna.exe'; args=@('--version'); source='official ZIP (GitHub accelerated)'; notes='1.1003. REA setting REA_KUNA_COMMAND. REA 6.10.1 expects 1.1000 and reports unsupported_host on Windows; this warning is expected.'; tags=@('rea','kuna','java') },
    @{ id='java-jdk25'; name='Java JDK 25 (REA active)'; category='runtime'; exe='C:\Users\guyue\code-tools\runtimes\jdk-25.0.5.1\jdk-25.0.5.1+2\bin\java.exe'; args=@('-version'); source='official ZIP (Microsoft)'; notes='25.0.5.1 LTS. rea-mcp.cmd overrides process JAVA_HOME to the parent JDK 25 home; Ghidra 12.2 requires Java 25 and JADX accepts Java 17+.'; tags=@('java','jdk','rea') },
    @{ id='java-jdk25-system'; name='Java JDK 25 (system Microsoft install)'; category='runtime'; exe='C:\Program Files\Microsoft\jdk-25.0.5.102-hotspot\bin\java.exe'; args=@('-version'); source='winget/Microsoft'; notes='25.0.5.1 LTS, same build family as the REA-active copy but a different install path.'; tags=@('java','jdk') },
    @{ id='java-jdk21'; name='Java JDK 21'; category='runtime'; exe='C:\Program Files\Java\jdk-21.0.13.0.2\bin\java.exe'; args=@('-version'); source='official installer'; notes='21.0.13.0.2 LTS.'; tags=@('java','jdk') },
    @{ id='java-jdk17'; name='Java JDK 17 (process JAVA_HOME)'; category='runtime'; exe='C:\Program Files\Java\jdk-17\bin\java.exe'; args=@('-version'); source='official installer'; notes='17.0.12 LTS. This is the process JAVA_HOME; REA wrapper overrides it to JDK 25.'; tags=@('java','jdk','JAVA_HOME') },
    @{ id='java-jdk8'; name='Java JDK 8'; category='runtime'; exe='C:\Program Files\Java\jdk1.8.0_211\bin\java.exe'; args=@('-version'); source='official installer'; notes='1.8.0_211 JDK (has javac).'; tags=@('java','jdk','legacy') },
    @{ id='java-jre8-x64'; name='Java 8 JRE x64'; category='runtime'; exe='C:\Program Files\Java\jre1.8.0_513\bin\java.exe'; args=@('-version'); source='official installer'; notes='1.8.0_513 64-bit runtime only.'; tags=@('java','jre','legacy') },
    @{ id='java-jre8-x86'; name='Java 8 JRE x86'; category='runtime'; exe='C:\Program Files (x86)\Java\jre1.8.0_513\bin\java.exe'; args=@('-version'); source='official installer'; notes='1.8.0_513 32-bit runtime only.'; tags=@('java','jre','legacy') },
    @{ id='java-zulu11-jre'; name='Zulu Java 11 JRE'; category='runtime'; exe='C:\Program Files\Zulu\zulu-11-jre\bin\java.exe'; args=@('-version'); source='Azul Zulu'; notes='11.0.33.1 LTS runtime only.'; tags=@('java','jre','zulu') },
    @{ id='java-studio-jbr'; name='Android Studio bundled JBR'; category='runtime'; exe='C:\Users\guyue\AppData\Local\Programs\Android Studio\jbr\bin\java.exe'; args=@('-version'); source='Android Studio'; notes='25.0.4 JBR bundled with Android Studio. Intended for the IDE, not a general JAVA_HOME.'; tags=@('java','jbr','android') },
    @{ id='ghidra'; name='Ghidra'; category='binary-analysis'; exe='C:\Users\guyue\code-tools\binary-analysis\ghidra\12.2.0\support\analyzeHeadless.bat'; args=$null; source='official ZIP'; static_version='12.2.0'; kind='install-dir'; notes='REA setting GHIDRA_INSTALL_DIR. Requires Java 25; old 12.1.4 install retained.'; tags=@('rea','ghidra') },
    @{ id='ida-pro'; name='IDA Professional'; category='binary-analysis'; exe='C:\Program Files\IDA Professional 9.4\ida.exe'; args=$null; source='license/installer'; static_version='9.4'; kind='install-dir'; notes='REA setting IDADIR points to C:\Program Files\IDA Professional 9.4.'; tags=@('rea','ida') },
    @{ id='ida-mcp-config'; name='IDA MCP config'; category='mcp'; exe='C:\Users\guyue\.local\share\rea\ida-mcp.json'; args=$null; source='manual'; static_version=$null; kind='config'; notes='REA setting REA_IDA_MCP_CONFIG.'; tags=@('rea','ida','config') },
    @{ id='chrome'; name='Google Chrome'; category='network'; exe='C:\Program Files\Google\Chrome\Application\chrome.exe'; args=$null; source='official installer'; static_version=$null; kind='executable'; notes='REA setting REA_BROWSER_EXECUTABLE.'; tags=@('rea','browser') },
    @{ id='rea-mcp'; name='REA MCP wrapper'; category='mcp'; exe='C:\Users\guyue\.local\bin\rea-mcp.cmd'; args=$null; source='manual'; static_version='6.10.1 via npx rea-agents@latest'; kind='script'; notes='Single source of truth for REA env paths. Disabled by default in clients; connect/disconnect via OpenCode API only.'; tags=@('rea','mcp','wrapper') },
    @{ id='rea-skill'; name='REA skill'; category='mcp'; exe='C:\Users\guyue\.agents\skills\reverse-engineer-anything\SKILL.md'; args=$null; source='rea-agents'; static_version='v63 / 263 tools'; kind='skill'; notes='Installed skill. Update with `npx.cmd rea-agents@latest setup --skill-client opencode --skill` only.'; tags=@('rea','skill') },
    @{ id='reqable-mcp'; name='Reqable MCP'; category='mcp'; exe='C:\Program Files\Reqable\mcp-server.exe'; args=$null; source='official installer'; static_version=$null; kind='executable'; notes='Disabled by default. Used for live traffic capture; TShark here is offline pcap only.'; tags=@('reqable','mcp','proxy') }
  )
}

function ConvertTo-CellText {
  param($Value)
  if ($null -eq $Value) { return '' }
  $text = $Value.ToString() -replace '\|', '\|'
  return (($text -replace '\s+', ' ').Trim())
}

function ConvertTo-Markdown {
  param([object]$Inventory)

  $sb = [System.Text.StringBuilder]::new()
  [void]$sb.AppendLine('# Local toolchain inventory')
  [void]$sb.AppendLine()
  [void]$sb.AppendLine("Generated: $($Inventory.generated_at)")
  [void]$sb.AppendLine("Source of truth: ``$ManifestPath``")
  [void]$sb.AppendLine()
  [void]$sb.AppendLine('## How agents should use this')
  [void]$sb.AppendLine()
  foreach ($line in $Inventory.usage) { [void]$sb.AppendLine("- $line") }
  [void]$sb.AppendLine()
  [void]$sb.AppendLine('## Conventions')
  [void]$sb.AppendLine()
  [void]$sb.AppendLine('| Key | Value |')
  [void]$sb.AppendLine('| --- | --- |')
  $inv = $Inventory.conventions
  foreach ($prop in $inv.PSObject.Properties) {
    $v = $prop.Value
    if ($v -is [array]) { $v = $v -join '; ' }
    [void]$sb.AppendLine("| $($prop.Name) | $(ConvertTo-CellText $v) |")
  }
  [void]$sb.AppendLine()
  [void]$sb.AppendLine('## Configuration facts')
  [void]$sb.AppendLine()
  [void]$sb.AppendLine('| ID | Title | Value | Usage |')
  [void]$sb.AppendLine('| --- | --- | --- | --- |')
  foreach ($fact in $Inventory.config_facts) {
    [void]$sb.AppendLine("| $($fact.id) | $(ConvertTo-CellText $fact.title) | $(ConvertTo-CellText $fact.value) | $(ConvertTo-CellText $fact.usage) |")
  }
  [void]$sb.AppendLine()
  [void]$sb.AppendLine('## Tools by category')
  [void]$sb.AppendLine()
  foreach ($group in ($Inventory.tools | Group-Object category | Sort-Object Name)) {
    [void]$sb.AppendLine("### $($group.Name)")
    [void]$sb.AppendLine()
    [void]$sb.AppendLine('| ID | Name | Version | Status | Path / command | Notes |')
    [void]$sb.AppendLine('| --- | --- | --- | --- | --- | --- |')
    foreach ($tool in ($group.Group | Sort-Object id)) {
      $target = if ($tool.path) { $tool.path } elseif ($tool.command) { $tool.command } else { '' }
      [void]$sb.AppendLine("| $($tool.id) | $(ConvertTo-CellText $tool.name) | $(ConvertTo-CellText $tool.version) | $($tool.status) | ``$(ConvertTo-CellText $target)`` | $(ConvertTo-CellText $tool.notes) |")
    }
    [void]$sb.AppendLine()
  }
  return $sb.ToString()
}

function Refresh-Inventory {
  if (-not (Test-Path -LiteralPath $OverridesPath)) {
    throw "Missing overrides file: $OverridesPath"
  }
  $overrides = Get-Content -LiteralPath $OverridesPath -Raw -Encoding UTF8 | ConvertFrom-Json
  $tools = @(Get-Specs | ForEach-Object { New-ToolRecord $_ })

  $categories = [ordered]@{}
  foreach ($t in $tools) {
    if (-not $categories.Contains($t.category)) { $categories[$t.category] = 0 }
    $categories[$t.category]++
  }

  $inventory = [ordered]@{
    schema_version = 1
    generated_at   = (Get-Date).ToString('o')
    host           = [ordered]@{
      os             = 'windows'
      shell          = 'pwsh'
      tool_root      = $overrides.conventions.tool_root
      scope          = $overrides.scope
      last_verified  = $Today
    }
    usage = @(
      'Read inventory.json in this skill directory before claiming a tool is missing or starting a full filesystem scan.',
      'Use the generated local-toolchain skill as the agent-facing entry point. If an agent does not support skills, give it the SKILL.md path directly.',
      'AI queries use query.ps1 in this skill directory only; the source directory is not needed.',
      'Query with: pwsh.exe -NoProfile -File "' + $QueryPath + '" find <query>',
      'Show configuration with: pwsh.exe -NoProfile -File "' + $QueryPath + '" config',
      'Check core tools with: pwsh.exe -NoProfile -File "' + $QueryPath + '" doctor',
      'This manifest is generated; do not hand-edit it. Regeneration is a maintainer task.'
    )
    conventions = $overrides.conventions
    config_facts = $overrides.config_facts
    tools = @($tools)
    summary = [ordered]@{
      total      = $tools.Count
      installed  = @($tools | Where-Object status -eq 'installed').Count
      missing    = @($tools | Where-Object status -eq 'missing').Count
      categories = $categories
    }
  }

  if (-not (Test-Path -LiteralPath $TemplatePath)) {
    throw "Missing skill template: $TemplatePath"
  }
  if (-not (Test-Path -LiteralPath $QueryTemplatePath)) {
    throw "Missing query template: $QueryTemplatePath"
  }

  $json = ($inventory | ConvertTo-Json -Depth 14) + "`n"
  Write-Utf8NoBom -Path $ManifestPath -Text $json
  Write-Utf8NoBom -Path $MarkdownPath -Text (ConvertTo-Markdown $inventory)
  Write-Utf8NoBom -Path $QueryPath -Text (Get-Content -LiteralPath $QueryTemplatePath -Raw -Encoding UTF8)

  $skillText = (Get-Content -LiteralPath $TemplatePath -Raw -Encoding UTF8).
    Replace('{{MANIFEST_PATH}}', $ManifestPath).
    Replace('{{MARKDOWN_PATH}}', $MarkdownPath).
    Replace('{{QUERY_PATH}}', $QueryPath).
    Replace('{{SOURCE_DIR}}', $ScriptRoot).
    Replace('{{RUN_HINT}}', $RunHint)
  Write-Utf8NoBom -Path $SkillPath -Text $skillText

  Write-Host "Refreshed: $ManifestPath"
  Write-Host "Refreshed: $MarkdownPath"
  Write-Host "Refreshed: $QueryPath"
  Write-Host "Refreshed: $SkillPath"
  Write-Host ("Tools: {0} installed, {1} missing, {2} total" -f $inventory.summary.installed, $inventory.summary.missing, $inventory.summary.total)
}

function Get-Inventory {
  if (-not (Test-Path -LiteralPath $ManifestPath)) { Refresh-Inventory }
  return (Get-Content -LiteralPath $ManifestPath -Raw -Encoding UTF8 | ConvertFrom-Json)
}

function Show-ToolTable {
  param([array]$Tools)
  if (-not $Tools -or $Tools.Count -eq 0) {
    Write-Host 'No matching tools.'
    return
  }
  $Tools | Sort-Object category, id | Select-Object id, name, category, version, status, path | Format-Table -AutoSize -Wrap
}

function Add-ReaCheckResult {
  param(
    [string]$Level,
    [string]$Check,
    [string]$Message,
    [System.Collections.Generic.List[object]]$Results
  )
  $Results.Add([pscustomobject][ordered]@{ level = $Level; check = $Check; message = $Message })
}

function Test-PathAtLeastEqual {
  param([string]$Base, [string]$Target)
  if (-not $Base -or -not $Target) { return $false }
  $b = $Base.TrimEnd([char[]]@('\', '/')).Replace('/', '\').ToLowerInvariant()
  $t = $Target.TrimEnd([char[]]@('\', '/')).Replace('/', '\').ToLowerInvariant()
  return ($t -eq $b -or $t.StartsWith($b + '\'))
}

function Test-PathExactlyEqual {
  param([string]$Left, [string]$Right)
  if (-not $Left -or -not $Right) { return $false }
  $l = $Left.TrimEnd([char[]]@('\', '/')).Replace('/', '\').ToLowerInvariant()
  $r = $Right.TrimEnd([char[]]@('\', '/')).Replace('/', '\').ToLowerInvariant()
  return ($l -eq $r)
}

function Find-ToolByPath {
  param([array]$Tools, [string]$Value)
  foreach ($t in $Tools) {
    if ($t.path -and (Test-PathAtLeastEqual $Value $t.path)) { return $t }
  }
  return $null
}

function Complete-ReaCheck {
  param(
    [System.Collections.Generic.List[object]]$Results,
    [switch]$Json
  )
  if ($Json) {
    $Results | ConvertTo-Json -Depth 6
  } else {
    foreach ($r in $Results) {
      Write-Host ("{0,-5} {1,-22} {2}" -f $r.level, $r.check, $r.message)
    }
    Write-Host ''
    $errors = @($Results | Where-Object level -eq 'ERROR').Count
    $warnings = @($Results | Where-Object level -eq 'WARN').Count
    $oks = @($Results | Where-Object level -eq 'OK').Count
    Write-Host ("REA wrapper check: {0} error(s), {1} warning(s), {2} OK" -f $errors, $warnings, $oks)
  }
  $problems = @($Results | Where-Object { $_.level -in @('ERROR', 'WARN') }).Count
  exit $(if ($problems -gt 0) { 1 } else { 0 })
}

function Invoke-ReaCheck {
  param(
    [switch]$Json,
    [string]$WrapperPath = ''
  )

  if (-not (Test-Path -LiteralPath $ReaCheckPath)) {
    throw "Missing REA check config: $ReaCheckPath"
  }
  $cfg = Get-Content -LiteralPath $ReaCheckPath -Raw -Encoding UTF8 | ConvertFrom-Json
  $wrapper = if ($WrapperPath) { $WrapperPath } else { $cfg.wrapper_path }
  $results = [System.Collections.Generic.List[object]]::new()

  $inventory = Get-Inventory
  $toolMap = @{}
  foreach ($t in $inventory.tools) { $toolMap[$t.id] = $t }

  if (-not (Test-Path -LiteralPath $wrapper)) {
    Add-ReaCheckResult 'ERROR' 'wrapper' "Wrapper not found: $wrapper" $results
    Complete-ReaCheck -Results $results -Json:$Json
  }

  $bytes = [System.IO.File]::ReadAllBytes($wrapper)
  if ($bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF) {
    Add-ReaCheckResult 'ERROR' 'encoding' 'Wrapper has a UTF-8 BOM; expected CP936/GBK with no BOM' $results
  } else {
    Add-ReaCheckResult 'OK' 'encoding' 'No BOM (CP936/GBK expected)' $results
  }

  try {
    $encoding = [System.Text.Encoding]::GetEncoding(936)
  } catch {
    [void][System.Text.Encoding]::RegisterProvider([System.Text.CodePagesEncodingProvider]::Instance)
    $encoding = [System.Text.Encoding]::GetEncoding(936)
  }
  $text = $encoding.GetString($bytes)
  $lines = $text -split "`r?`n"

  $bareLf = [regex]::Matches($text, "(?<!`r)`n").Count
  if ($bareLf -gt 0) {
    Add-ReaCheckResult 'WARN' 'line-endings' "Found $bareLf bare LF line ending(s); expected CRLF" $results
  } else {
    Add-ReaCheckResult 'OK' 'line-endings' 'CRLF' $results
  }

  $launchOk = @($lines | Where-Object { $_ -notmatch '^\s*REM' -and $_.Contains($cfg.expected_launch) }).Count -gt 0
  if ($launchOk) {
    Add-ReaCheckResult 'OK' 'launch' 'npx.cmd -y rea-agents@latest mcp' $results
  } else {
    Add-ReaCheckResult 'ERROR' 'launch' "Expected launch command not found: $($cfg.expected_launch)" $results
  }

  $active = @{}
  $commented = @{}
  foreach ($line in $lines) {
    if ($line -match '^\s*REM\s+set\s+"([A-Za-z_][A-Za-z0-9_]*)=(.*?)"') {
      $commented[$Matches[1]] = $Matches[2]
    } elseif ($line -match '^\s*set\s+"([A-Za-z_][A-Za-z0-9_]*)=(.*?)"') {
      $active[$Matches[1]] = $Matches[2]
    }
  }

  foreach ($m in $cfg.env_map) {
    $name = $m.env
    $tool = $toolMap[$m.tool]
    if (-not $tool) {
      Add-ReaCheckResult 'ERROR' $name "Mapping references unknown tool id '$($m.tool)'" $results
      continue
    }

    $activeValue = if ($active.ContainsKey($name)) { $active[$name] } else { $null }
    $commentValue = if ($commented.ContainsKey($name)) { $commented[$name] } else { $null }

    if ($null -ne $activeValue) {
      if ($tool.status -ne 'installed') {
        Add-ReaCheckResult 'ERROR' $name "$($m.tool) is not installed in the inventory" $results
        continue
      }
      if (-not (Test-Path -LiteralPath $activeValue)) {
        Add-ReaCheckResult 'ERROR' $name "Path does not exist: $activeValue" $results
        continue
      }
      $matched = if ($m.kind -eq 'ancestor') {
        Test-PathAtLeastEqual $activeValue $tool.path
      } else {
        Test-PathExactlyEqual $activeValue $tool.path
      }
      if ($matched) {
        $versionText = if ($tool.version) { $tool.version } else { $tool.path }
        Add-ReaCheckResult 'OK' $name "$($m.tool) -> $versionText" $results
      } else {
        $candidate = Find-ToolByPath $inventory.tools $activeValue
        $detail = if ($candidate -and $candidate.id -ne $m.tool) {
          "；该路径更接近 $($candidate.id) ($($candidate.version))"
        } else { '' }
        Add-ReaCheckResult 'ERROR' $name "Points to '$activeValue' but $($m.tool) is at '$($tool.path)'$detail" $results
      }
    } elseif ($null -ne $commentValue) {
      if ($tool.status -eq 'installed') {
        Add-ReaCheckResult 'WARN' $name "$($m.tool) is installed but still a REM placeholder ($($tool.path))" $results
      } else {
        Add-ReaCheckResult 'OK' $name "REM placeholder is correct; $($m.tool) is not installed" $results
      }
    } else {
      Add-ReaCheckResult 'ERROR' $name 'Missing: not an active set and not a REM set' $results
    }
  }

  Complete-ReaCheck -Results $results -Json:$Json
}

switch ($Command) {
  'refresh' {
    Refresh-Inventory
  }
  'list' {
    $inv = Get-Inventory
    if ($Json) { $inv.tools | ConvertTo-Json -Depth 8; break }
    Write-Host ("Inventory: {0} | generated {1}" -f $ManifestPath, $inv.generated_at)
    Show-ToolTable $inv.tools
  }
  'find' {
    if (-not $Query) { Write-Host "Usage: $RunHint find <query>"; break }
    $inv = Get-Inventory
    $q = $Query.ToLowerInvariant()
    $matches = @($inv.tools | Where-Object {
      $haystack = (@($_.id, $_.name, $_.category, $_.command, $_.path, $_.notes) + @($_.tags)) -join ' '
      $haystack.ToLowerInvariant().Contains($q)
    })
    if ($Json) { $matches | ConvertTo-Json -Depth 8; break }
    Write-Host ("Matches for '{0}': {1}" -f $Query, $matches.Count)
    Show-ToolTable $matches
  }
  'config' {
    $inv = Get-Inventory
    if ($Json) { $inv.config_facts | ConvertTo-Json -Depth 8; break }
    $inv.config_facts | Select-Object id, title, value, usage | Format-Table -AutoSize -Wrap
  }
  'doctor' {
    $inv = Get-Inventory
    $required = @('node','npm','npx','go','dotnet','uv','uvx','git','gh','pwsh','opencode','rea-mcp','rea-skill','java-jdk25','objdump','wabt','rizin','tshark','capinfos','apktool-3','ilspycmd')
    $map = @{}
    foreach ($t in $inv.tools) { $map[$t.id] = $t }
    $problems = 0
    foreach ($id in $required) {
      if (-not $map.ContainsKey($id)) {
        Write-Host "MISSING  $id (not defined)"
        $problems++
      } elseif ($map[$id].status -ne 'installed') {
        Write-Host "MISSING  $id"
        $problems++
      } else {
        Write-Host "OK       $id  $($map[$id].version)"
      }
    }
    Write-Host ''
    Write-Host "Doctor: $problems problem(s). Missing entries may be optional on this host."
    exit $(if ($problems -gt 0) { 1 } else { 0 })
  }
  'raw' {
    $inv = Get-Inventory
    $inv | ConvertTo-Json -Depth 14
  }
  'md' {
    if (-not (Test-Path -LiteralPath $MarkdownPath)) { Refresh-Inventory | Out-Null }
    Get-Content -LiteralPath $MarkdownPath -Raw -Encoding UTF8
  }
  'rea-check' {
    Invoke-ReaCheck -Json:$Json -WrapperPath $WrapperPath
  }
  'help' {
    @"
Usage:
  $RunHint list             List all tools
  $RunHint find <query>     Filter by id/name/category/path/tag/notes
  $RunHint config           Show important machine-specific configuration
  $RunHint doctor           Check core tools
  $RunHint rea-check        Read-only validation of rea-mcp.cmd against the inventory
  $RunHint refresh          Regenerate inventory.json / inventory.md / query.ps1 / SKILL.md
  $RunHint raw              Output full JSON manifest
  $RunHint md               Output Markdown mirror
  $RunHint help             Show this help

Add -Json to list/find/config/rea-check for JSON output.
Add -OutputDirectory <path> to write generated files elsewhere; default is C:\Users\guyue\.agents\skills\local-toolchain.
Add -WrapperPath <path> to rea-check a copy instead of the live rea-mcp.cmd.
"@ | Write-Host
  }
}
