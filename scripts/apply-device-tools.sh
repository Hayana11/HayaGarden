#!/usr/bin/env bash
set -eu
cd "$(dirname "$0")/../android/app/src/main"
cp ../../../../native/ScreenshotShortcut.java java/xyz/lovestyle/home/canary/
mkdir -p res/xml
cat >res/xml/elpis_screenshot.xml <<'XML'
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:accessibilityFlags="flagRequestAccessibilityButton" />
XML
python3 - <<'PY'
from pathlib import Path
manifest = Path("AndroidManifest.xml")
xml = manifest.read_text()
if 'android:name=".ScreenshotShortcut"' not in xml:
    xml = xml.replace("<application", """<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
    <application""", 1)
    xml = xml.replace("</application>", """    <service android:name=".ScreenshotShortcut" android:exported="true"
            android:label="Elpis 截屏" android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
            <intent-filter><action android:name="android.accessibilityservice.AccessibilityService" /></intent-filter>
            <meta-data android:name="android.accessibilityservice" android:resource="@xml/elpis_screenshot" />
        </service>
    </application>""")
    manifest.write_text(xml)
PY
