; AutoRun Manager - Inno Setup 安装脚本
; 编译命令:
;   iscc setup.iss /DMyAppVersion=1.0.0 /DSourceDir="..\publish" /DArch=win-x64 /DMyAppSuffix= /DInstallerDir="..\publish\installers"

#define MyAppName "AutoRunManager"
#define MyAppPublisher "AutoRun"
#define MyAppURL ""
#define MyAppExeName "AutoRunManager.exe"
#define MyAppSchedulerExeName "AutoRunScheduler.exe"

[Setup]
AppId={{B8F7A3E1-5D2C-4A6F-9E8B-1C3D5F7A9B0E}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher={#MyAppPublisher}
DefaultDirName={autopf}\{#MyAppName}
DefaultGroupName={#MyAppName}
OutputDir={#InstallerDir}
OutputBaseFilename=AutoRun-{#MyAppVersion}-{#Arch}{#MyAppSuffix}
Compression=lzma2
SolidCompression=yes
PrivilegesRequired=admin
DisableProgramGroupPage=yes
ArchitecturesInstallIn64BitMode=x64compatible
UninstallDisplayIcon={app}\{#MyAppExeName}

[Languages]
Name: "chinesesimplified"; MessagesFile: "compiler:Languages\ChineseSimplified.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked

[Files]
Source: "{#SourceDir}\Manager-{#Arch}{#MyAppSuffix}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "{#SourceDir}\Scheduler-{#Arch}{#MyAppSuffix}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"
Name: "{autoprograms}\{#MyAppName}\卸载"; Filename: "{uninstallexe}"
Name: "{commondesktop}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; Tasks: desktopicon

[UninstallRun]
Filename: "schtasks"; Parameters: "/delete /tn ""AutoRunScheduler"" /f"; Flags: runhidden; RunOnceId: "AutoRunSchedulerDelete"

[Code]
procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
begin
  if CurUninstallStep = usPostUninstall then
    if MsgBox('是否保留配置文件和日志？'#13#13'(位于 %LOCALAPPDATA%\AutoRunManager\)', mbConfirmation, MB_YESNO) = IDNO then
      DelTree(ExpandConstant('{localappdata}\AutoRunManager'), True, True, True);
end;
