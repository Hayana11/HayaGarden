# AGENTS.md — HayaGarden (P1 navigation canary)

Branch `claude/app-shell-reset-p1-navigation` is a **Capacitor 8 pure WebView canary**
plus minimal Android back handling (`native/MainActivity.java` via
`scripts/apply-p1-navigation.sh`).

Do **not** restore Phase-0-forbidden native capabilities. Do **not** merge/deploy
from agent automation unless the user explicitly requests it for a later phase.

## Build

```bash
npm ci && npx cap add android && npx cap sync android
bash scripts/apply-p1-navigation.sh
cd android && ./gradlew clean assembleDebug
```
