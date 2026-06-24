#!/bin/bash
set -e

PKG_DIR="android/app/src/main/java/xyz/lovestyle/home"
MANIFEST="android/app/src/main/AndroidManifest.xml"

# 1. 注入 AppTracker + 自定义 MainActivity + NativeBridge
cp scripts/AppTracker.java   "$PKG_DIR/AppTracker.java"
cp scripts/MainActivity.java "$PKG_DIR/MainActivity.java"
cp scripts/NativeBridge.java "$PKG_DIR/NativeBridge.java"

# 2. 给 manifest 元素加 xmlns:tools
sed -i 's|xmlns:android="http://schemas.android.com/apk/res/android">|xmlns:android="http://schemas.android.com/apk/res/android"\n    xmlns:tools="http://schemas.android.com/tools">|' "$MANIFEST"

# 3. 在 INTERNET 权限后追加 PACKAGE_USAGE_STATS
sed -i '/android.permission.INTERNET/a\    <uses-permission android:name="android.permission.PACKAGE_USAGE_STATS" tools:ignore="ProtectedPermissions" />' "$MANIFEST"

# 4. 注入 NotificationWorker + ForegroundService
cp scripts/NotificationWorker.java  "$PKG_DIR/NotificationWorker.java"
cp scripts/ForegroundService.java   "$PKG_DIR/ForegroundService.java"

# 5. WorkManager 依赖
sed -i '/implementation.*capacitor-android/a\    implementation "androidx.work:work-runtime:2.9.0"' android/app/build.gradle

# 6. POST_NOTIFICATIONS 权限
sed -i '/PACKAGE_USAGE_STATS/a\    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />' "$MANIFEST"

# 7. FOREGROUND_SERVICE 权限
sed -i '/android.permission.POST_NOTIFICATIONS/a\    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />' "$MANIFEST"

# 8. ForegroundService 声明（加在 </application> 前）
sed -i 's|</application>|        <service android:name=".ForegroundService" android:foregroundServiceType="dataSync" />\n    </application>|' "$MANIFEST"

echo "patches applied"
