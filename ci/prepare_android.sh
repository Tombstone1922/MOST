#!/usr/bin/env bash
set -euo pipefail
sdkmanager 'ndk;30.0.16248370'
export NDK_HOME="$ANDROID_HOME/ndk/30.0.16248370"
bash compile-hevtun.sh
mkdir -p V2rayNG/app/libs
cp -r libs/. V2rayNG/app/libs/
curl -fL --retry 3 https://github.com/2dust/AndroidLibXrayLite/releases/download/v26.10.17/libv2ray.aar -o V2rayNG/app/libs/libv2ray.aar
echo 'fd6cb61fed873e465c3064327dffbcb22dc3fd78260d162ec9e868db2582495f  V2rayNG/app/libs/libv2ray.aar' | sha256sum -c -
