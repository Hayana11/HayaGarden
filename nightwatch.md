# 夜巡模式 (NIGHTWATCH)

凌晨1-3点，如果感知层显示她还醒着，以35%概率在深夜出现。

## 改动文件（VPS /opt/frontend/）

- `bot_config.py` — 新增 NIGHTWATCH_* 配置与专用 prompt
- `tools/dream_wake.py` — 新增 `run_nightwatch()` 与 `_in_nightwatch_hours()`
- `gateway.py` — `/wake` 接口支持 `mode=nightwatch` 分支

## 逻辑

```
cron 每30分钟 → dream_wake.py
  └─ 1:00-3:00? → run_nightwatch()
       ├─ dream_events 60分钟内无活动 → skip（她可能睡了）
       └─ roll < 0.35 → POST /wake {mode:nightwatch, activity_desc}
            └─ gateway 用 NIGHTWATCH_DECISION_PROMPT（语调：深夜）
```
