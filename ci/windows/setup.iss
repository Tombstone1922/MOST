#ifndef AppVersion
#define AppVersion "0.1.0"
#endif
[Setup]
AppId={{D0B02C45-03A2-4AC0-94A2-AB19FE42C745}
AppName=MOST
AppVersion={#AppVersion}
AppPublisher=MOST
AppPublisherURL=https://github.com/Tombstone1922/MOST
DefaultDirName={autopf}\MOST
DefaultGroupName=MOST
OutputDir=..\..\out\windows
OutputBaseFilename=MOST-Windows-{#AppVersion}-Setup
SetupIconFile=..\..\Windows\MOST.Windows\Assets\most.ico
UninstallDisplayIcon={app}\MOST.exe
LicenseFile=..\..\LICENSE
Compression=lzma2
SolidCompression=yes
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
PrivilegesRequired=admin
WizardStyle=modern
AppMutex=Global\MOST.Windows
CloseApplications=yes
[Languages]
Name: "russian"; MessagesFile: "compiler:Languages\Russian.isl"
Name: "english"; MessagesFile: "compiler:Default.isl"
[Tasks]
Name: "desktopicon"; Description: "Создать ярлык на рабочем столе"; Flags: unchecked
[Files]
Source: "..\..\out\windows\app\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs
[Icons]
Name: "{autoprograms}\MOST"; Filename: "{app}\MOST.exe"
Name: "{autodesktop}\MOST"; Filename: "{app}\MOST.exe"; Tasks: desktopicon
[Run]
Filename: "{app}\MOST.exe"; Description: "Открыть MOST"; Flags: postinstall nowait skipifsilent
