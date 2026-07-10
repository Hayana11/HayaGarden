#!/bin/bash
set -e

PKG_DIR="android/app/src/main/java/xyz/lovestyle/home"
MANIFEST="android/app/src/main/AndroidManifest.xml"

# 1. 注入 AppTracker + 自定义 MainActivity + NativeBridge + BootReceiver + 截屏组件
cp scripts/AppTracker.java   "$PKG_DIR/AppTracker.java"
cp scripts/MainActivity.java "$PKG_DIR/MainActivity.java"
cp scripts/NativeBridge.java "$PKG_DIR/NativeBridge.java"
cp scripts/BootReceiver.java "$PKG_DIR/BootReceiver.java"
cp scripts/NotificationWorker.java              "$PKG_DIR/NotificationWorker.java"
cp scripts/ForegroundService.java               "$PKG_DIR/ForegroundService.java"
cp scripts/ScreenCaptureService.java            "$PKG_DIR/ScreenCaptureService.java"
cp scripts/ScreenCapturePermissionActivity.java "$PKG_DIR/ScreenCapturePermissionActivity.java"
cp scripts/PocketClient.java              "$PKG_DIR/PocketClient.java"
cp scripts/PocketManager.java             "$PKG_DIR/PocketManager.java"
cp scripts/PocketWebViewPolicy.java       "$PKG_DIR/PocketWebViewPolicy.java"
cp scripts/PocketBrowserActivity.java     "$PKG_DIR/PocketBrowserActivity.java"

# 2. 给 manifest 根元素加 xmlns:tools
sed -i 's|xmlns:android="http://schemas.android.com/apk/res/android">|xmlns:android="http://schemas.android.com/apk/res/android"\n    xmlns:tools="http://schemas.android.com/tools">|' "$MANIFEST"

# 3. 权限：整块追加在 INTERNET 之后（tools:ignore 让 lint 放过受保护权限）
#    注意用精确锚点，避免多次 sed -a 之间互相匹配导致重复插入。
sed -i '/android.permission.INTERNET/a\
    <uses-permission android:name="android.permission.PACKAGE_USAGE_STATS" tools:ignore="ProtectedPermissions" />\
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />\
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />\
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />\
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" />\
    <uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />\
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />\
    <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />\
    <uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" />\
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />' "$MANIFEST"

# 4. WorkManager + OkHttp（pocket-browser WebSocket）
sed -i '/implementation.*capacitor-android/a\    implementation "androidx.work:work-runtime:2.9.0"\n    implementation "com.squareup.okhttp3:okhttp:4.12.0"' android/app/build.gradle

# 5. 组件声明：服务 / 截屏授权活动 / Pocket 登录浏览器 / 开机自启广播，一次性插在 </application> 前
sed -i 's|</application>|\
        <service android:name=".ForegroundService" android:foregroundServiceType="dataSync" />\
        <service android:name=".ScreenCaptureService" android:exported="false" android:foregroundServiceType="mediaProjection" />\
        <activity android:name=".ScreenCapturePermissionActivity" android:exported="false" android:theme="@android:style/Theme.Translucent.NoTitleBar" android:excludeFromRecents="true" />\
        <activity android:name=".PocketBrowserActivity" android:exported="false" />\
        <receiver android:name=".BootReceiver" android:enabled="true" android:exported="true">\
            <intent-filter>\
                <action android:name="android.intent.action.BOOT_COMPLETED" />\
                <action android:name="android.intent.action.QUICKBOOT_POWERON" />\
            </intent-filter>\
        </receiver>\
    </application>|' "$MANIFEST"

echo "patches applied"
