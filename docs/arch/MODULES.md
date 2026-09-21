# MODULE MAP — Phase 0

`[JVM]` = pure Kotlin (test ได้ไม่ต้องใช้อุปกรณ์) `[AND]` = Android library

| Module | Layer | Kind | Phase | Spec หลัก |
|---|---|---|---|---|
| :app | 1 | app | 1,23–26 | โครงสร้าง §3, ux |
| :ui:designsystem | 1 | [AND] | 23–24, 1.2 | 1.2.txt (tokens) |
| :ui:chat | 1 | [AND] | 23 | ลักษณะui, 1.1 |
| :ui:workspace | 1 | [AND] | 24 | 1.1, ux |
| :ui:settings | 1 | [AND] | 25 | 1.2 §171–177 |
| :ai:core | 2 | [AND] | 6,7 | ระบบ ai, โครงสร้าง §4 |
| :ai:tasks | 2 | [JVM] | 10 | โครงสร้าง §5 |
| :ai:agents | 2 | [AND] | 11 | โครงสร้าง §6 |
| :ai:models | 2 | [AND] | 4,5,9 | โครงสร้าง §7–10 |
| :tools:registry | 3 | [JVM] | 12 | ฝ1.3 §3–4 |
| :tools:gateway | 3 | [AND] | 13 | ฝ1.3, ระบบทั้งหมด |
| :tools:files | 3 | [AND] | 14 | โครงสร้าง §16 |
| :tools:editor | 3 | [AND] | 15 | โครงสร้าง §17 |
| :tools:terminal | 3 | [JVM→AND] | 16 | TERMINAL_PLAN, ฝ1.3 §6 |
| :tools:build | 3 | [AND] | 17 | โครงสร้าง §19 (Build/Test) |
| :tools:git | 3 | [JVM→AND] | 18 | โครงสร้าง §38 |
| :tools:browser | 3 | [AND] | 19 | ฝ1.3 §5, 1.1 §5–10 |
| :core:common | 4 | [JVM] | 2 | โครงสร้าง §44–45 |
| :core:state | 4 | [JVM] | 2 | โครงสร้าง §44–45 |
| :core:resources | 3 | [AND] | 3 | โครงสร้าง §19 (Resource) |
| :data:checkpoint | 4 | [JVM] | 21 | โครงสร้าง §22–23 |
| :data:audit | 4 | [JVM] | 22 | โครงสร้าง §27–28 |
| :data:memory | 2/4 | [JVM] | 8 | โครงสร้าง §21 |
| :data:conversations | 4 | [JVM] | 23 | ลักษณะui §34 |
| :data:settings | 4 | [AND] | 25 | 1.2 §171–177 |

Dependency ทิศทางเดียว (บน→ล่างเท่านั้น):
```
app → ui:* → ai:* → tools:* → data:* / core:*
ai:agents → ai:core (impl port) | ai:tasks → core:state, data:checkpoint
tools:* → tools:registry | tools:gateway → tools:registry, data:audit
```
