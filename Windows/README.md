# MOST Windows

Windows 10/11 x64 desktop client, version 0.2.0. C# / WPF / self-contained .NET 10.
Android UI is platform-specific; its VLESS transport/import/country semantics are carried into the desktop model. The same Xray family handles the network. Android and Windows share the slogan «Связь без границ»; their native builds remain separate.

`MOST.Windows/Core` owns imports, DPAPI persistence, profile routing and the native process lifecycle. Views dispatch actions through AppModel. Xray 26.10.21 provides TUN, process matching and outbound binding; official Wintun 0.14.1 is packaged with its license. Upstream ZIP hashes are pinned in `ci/windows/prepare.py`.

Build on Windows: `dotnet run --project Windows/MOST.Windows.Tests -c Release`, then `dotnet publish Windows/MOST.Windows -c Release -r win-x64 --self-contained true -o out/windows/app`. Run `python ci/windows/prepare.py` to add the verified network components. `ci/windows/setup.iss` builds the installer with Inno Setup 6.

The GitHub Actions Windows workflow runs focused parser/config/DPAPI assertions and validates generated transport configs with the real Xray binary without opening a TUN or using private subscriptions. It publishes `windows-v0.2.0` with `latest=false`; Android `/releases/latest` OTA remains independent.

Not run: Windows 10/11 user-device connection, TUN, DNS/IPv6, process exclusions, handover, stop/crash/session ending, install/uninstall, keyboard/accessibility/DPI matrix. No emulator or broad runtime test campaign was performed. A successful build does not verify VPN runtime behavior. The application and installer are unsigned. See README_RU.txt for user instructions and limits.

Desktop 0.2: compact connection dashboard, country selection with per-subscription/direct-or-bridge bounded failover (up to 12 candidates), last working profile, favorites, searchable running-executable exclusions, and in-app updates. Automatic selection accepts an exit only after its country is measured. Unknown exit countries remain available through manual selection. No cross-country or cross-subscription fallback is allowed. During recovery, the previous tunnel is replaced; this is not a kill switch.

Windows OTA reads only the fixed repository GitHub Releases API, ignores Android/dev tags and drafts, compares semantic versions and verifies the uploaded asset's immutable GitHub SHA256 digest and size before launching its installer. HTTPS redirects are restricted to GitHub release hosts. No GitHub token or signing private key is shipped. The EXE is still not Authenticode-signed: this mechanism trusts the repository over HTTPS. Checks run at startup and every 6 hours while the app/tray is running; no Windows service is installed. Updating 0.1.0 to 0.2.0 is a one-time manual installer download; subsequent releases appear in the app. Portable users can use the normal installer or manually replace the extracted portable folder.

Not run for 0.2.0: user-device auto failover, Windows notification delivery, installer handoff/update and settings retention during a real installation, updated WPF layout/keyboard/DPI/accessibility and process exclusion runtime. Focused CI checks cover candidate isolation/order, preceding-state compatibility, release selection/source/hash rules, parser/config generation and DPAPI.
