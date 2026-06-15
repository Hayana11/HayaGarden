#!/bin/bash
set -e

PKG_DIR="android/app/src/main/java/xyz/lovestyle/home"
MANIFEST="android/app/src/main/AndroidManifest.xml"

# 1. 注入 AppTracker + 自定义 MainActivity
cp scripts/AppTracker.java   "$PKG_DIR/AppTracker.java"
cp scripts/MainActivity.java "$PKG_DIR/MainActivity.java"

# 2. 给 manifest 元素加 xmlns:tools
sed -i 's|xmlns:android="http://schemas.android.com/apk/res/android">|xmlns:android="http://schemas.android.com/apk/res/android"\n    xmlns:tools="http://schemas.android.com/tools">|' "$MANIFEST"

# 3. 在 INTERNET 权限后追加 PACKAGE_USAGE_STATS
sed -i '/android.permission.INTERNET/a\    <uses-permission android:name="android.permission.PACKAGE_USAGE_STATS" tools:ignore="ProtectedPermissions" />' "$MANIFEST"

echo "patches applied"
