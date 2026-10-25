# MOST 0.4.0 validation

Build status is recorded in BUILD_INFO_MOST.json after assembly.

Not run:
- :app:testPlaystoreDebugUnitTest — no extensive test campaign, as requested by user.
- Emulator/physical-device VPN connect, exit-IP observation, two failed probes, backup switch, user-stop/reload race, network handover and all run modes — user requested no emulator runs.
- Android API 24/26/28/33+ installer, unknown-source permission and real OTA upgrade — no device test campaign.
- TalkBack, IME traversal and complete layout/theme matrix — no emulator.
- Lint — not run in this focused build.

Only a successful assembly and signing check are claimed. No runtime or published-OTA validation is claimed.
Native dependencies and dependency versions were preserved. No real subscription was fetched during development.
