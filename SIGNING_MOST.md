# MOST Android signing

MOST 0.2.0 uses a permanent RSA signing key held separately from the source code. It is not the lost 0.1.0 debug key. A backup/uninstall/install/restore migration is required from 0.1.0.

Never include the private key or its password in a source archive, APK, repository or public release. Keep a separate private backup of both files. Future updates for this package must use the same key and a higher versionCode.

The normal debug build uses the local Android debug key. To publish an update of the supplied 0.2.0 APK, the maintainer must sign the universal build with the permanent MOST key after Gradle has aligned it:

```powershell
& "$env:ANDROID_HOME\build-tools\37.0.0\apksigner.bat" sign `
  --ks "PRIVATE\most-android.p12" --ks-key-alias most `
  --ks-pass "file:PRIVATE\store-password.txt" `
  --out "MOST-0.2.0-Android.apk" `
  "V2rayNG\app\build\outputs\apk\playstore\debug\MOST_0.2.0_universal.apk"
& "$env:ANDROID_HOME\build-tools\37.0.0\apksigner.bat" verify --verbose --print-certs "MOST-0.2.0-Android.apk"
```

Replace `PRIVATE` with the private key directory. Do not print the password or paste it into a terminal command. Re-signing does not change the application's debug build type; this remains a test build.
