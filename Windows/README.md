# MOST Windows

Windows 10/11 x64 desktop client, version 0.1.0. C# / WPF / self-contained .NET 10.
Android UI is platform-specific; its VLESS transport/import/country semantics are carried into the desktop model. The same Xray family handles the network. No Android sources or native dependencies are changed.

`MOST.Windows/Core` owns imports, DPAPI persistence, profile routing and the native process lifecycle. Views dispatch actions through AppModel. Xray 26.10.21 provides TUN, process matching and outbound binding; official Wintun 0.14.1 is packaged with its license. Upstream ZIP hashes are pinned in `ci/windows/prepare.py`.

Build on Windows: `dotnet run --project Windows/MOST.Windows.Tests -c Release`, then `dotnet publish Windows/MOST.Windows -c Release -r win-x64 --self-contained true -o out/windows/app`. Run `python ci/windows/prepare.py` to add the verified network components. `ci/windows/setup.iss` builds the installer with Inno Setup 6.

The GitHub Actions Windows workflow runs focused parser/config/DPAPI assertions and validates generated transport configs with the real Xray binary without opening a TUN or using private subscriptions. It publishes `windows-v0.1.0` with `latest=false`; Android `/releases/latest` OTA remains independent.

Not run: Windows 10/11 user-device connection, TUN, DNS/IPv6, process exclusions, handover, stop/crash/session ending, install/uninstall, keyboard/accessibility/DPI matrix. No emulator or broad runtime test campaign was performed. A successful build does not verify VPN runtime behavior. The application and installer are unsigned. See README_RU.txt for user instructions and limits.
