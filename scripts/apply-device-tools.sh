#!/usr/bin/env bash
set -eu
cd "$(dirname "$0")/../android/app/src/main"
cp ../../../../native/ScreenshotShortcut.java java/xyz/lovestyle/home/canary/
cp ../../../../native/MotionRecognition.java java/xyz/lovestyle/home/canary/
mkdir -p res/xml
cat >res/xml/elpis_screenshot.xml <<'XML'
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:accessibilityFlags="flagRequestAccessibilityButton" />
XML
python3 - <<'PY'
from pathlib import Path
gradle = Path("../../build.gradle")
dependency = 'implementation "com.google.android.gms:play-services-location:21.3.0"'
source = gradle.read_text()
if dependency not in source:
    gradle.write_text(source.replace("dependencies {", "dependencies {\n    " + dependency, 1))
manifest = Path("AndroidManifest.xml")
xml = manifest.read_text()
if 'android:name=".MotionRecognition$Receiver"' not in xml:
    xml = xml.replace("<application", """<uses-permission android:name="android.permission.ACTIVITY_RECOGNITION" />
    <uses-permission android:name="com.google.android.gms.permission.ACTIVITY_RECOGNITION" android:maxSdkVersion="28" />
    <application""", 1).replace("</application>", """    <receiver android:name=".MotionRecognition$Receiver" android:exported="false" />
    </application>""")
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
