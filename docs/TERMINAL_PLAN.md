> ARCHITECTURE POSITION (MASTER_ARCHITECTURE §5/§29/§71): Terminal is the **Compatibility Engine / CLI Adapter (CP-31/32/33)** — an optional fallback for CLI-only tools and deep debugging. It is NOT the AI core, NOT the main execution path, and native engines MUST work without it. This plan covers the compat layer only.

# TERMINAL PLAN — ฝัง Terminal ไว้ในแอป (AI-driven)

ตาม AMENDMENT-001 (ฝังในแอปเท่านั้น ห้ามให้ผู้ใช้ลงแอปแยก)
และ AMENDMENT-002 (อนุมัติใช้ Termux ตามข้อกำหนด GPLv3)

## เป้าหมาย
- Terminal อยู่**ข้างในแอปเรา** ไม่ต้องลงแอปอื่นเพิ่ม
- ผู้ใช้ไม่ต้องพิมพ์คำสั่งเอง — **AI สั่ง Terminal เองได้** (execute → observe → verify)
- ผู้ใช้ขั้นสูงเปิดใช้เองได้ด้วย (two-way usage ตามสเปก 1.1.txt)

## พื้นฐานลิขสิทธิ์
- termux-app: GPLv3-only → repo นี้ใช้ GPLv3 แล้ว (ดูไฟล์ `LICENSE` ที่ root)
- ส่วน emulator สืบสายจาก jackpal/Android-Terminal-Emulator (Apache 2.0)
- กฎ compliance ดูเช็กลิสต์ใน `docs/AMENDMENTS.md` (AMENDMENT-002)

## วิธีฝัง (ไม่ก๊อบปี้โค้ดมากอง)
ใช้ **git submodule ปักหมุด commit** ชี้ไปที่ `https://github.com/termux/termux-app`
แล้วสร้าง integration module ของเราเองครอบ (`:tool-terminal`):

```
Aicodemax (GPLv3)
├── app/                      # แอปหลัก (Phase 1+)
├── core:tool-gateway/        # Tool Gateway กลาง (Phase 12-13)
├── tool-terminal/            # ★ integration module ของเรา
│   ├── TermuxSessionAdapter  # ครอบ session/process ของ Termux
│   ├── AIControlAPI          # execute/observe/cancel สำหรับ Orchestrator
│   ├── PermissionHook        # ขอสิทธิ์ + audit log (ตามสเปก Security)
│   └── CapabilityGate        # โชว์เฉพาะคำสั่งที่ทำได้จริง (ตาม 100% contract)
└── vendor/termux-app/        # ★ git submodule (pinned commit, ไม่อยู่ใน workspace 128MB)
    ├── terminal-emulator/  terminal-view/  termux-shared/  app/
```

เหตุผลที่เลือก submodule:
1. ไม่เปลืองพื้นที่ workspace/repo (โคลนเฉพาะตอน build Phase 16)
2. ปักหมุดเวอร์ชันได้ อัปเดตตาม upstream ได้เป็นระบบ
3. เส้นแบ่งลิขสิทธิ์ชัด (โค้ดเขาอยู่ในโฟลเดอร์เขา + notices ครบ)
4. กฤษรีวิวง่าย (เห็นชัดว่าส่วนไหนของเรา ส่วนไหนของเขา)

## ขนาด APK (กันอ้วน)
- Bootstrap (ชุดคำสั่ง Linux) **ไม่แถมใน APK** — ดาวน์โหลดแบบ on-demand หลังติดตั้ง
  ตามข้อ 20 ON-DEMAND TOOL INSTALLATION ในสเปก (แนวนโยบายเดียวกับ Termux)
- APK วันแรกมีแค่ตัวจำลองจอ + ตัวเชื่อม → เล็ก

## ลำดับงานตาม Phase
- Phase 0: ออกแบบ interface ของ Tool Gateway ให้รองรับ terminal session/PTY/process
- Phase 1: สร้างโครง app + โมดูลเปล่า `:tool-terminal` (ยังไม่ต่อ submodule)
- Phase 12-13: Tool Registry/Gateway ตัวจริง + AIControlAPI contract
- Phase 16: ต่อ submodule + adapter + bootstrap on-demand + capability gate + ทดสอบ AI สั่งงาน
- Phase 22/25: permission/audit + About/legal notices

## สถานะตอนนี้
- [x] มติ + แผน + LICENSE + attribution ครบ
- [x] ผังเสียบละเอียด (`docs/TERMINAL_WIRING.md` — ศึกษา Termux API จริงแล้ว) + โมดูล `:tools:terminal_runtime` (executor จริง + เทสเขียว)
- [ ] เสียบ submodule + เขียน `TermuxTerminalPort` ของจริง (ทำบนเครื่อง dev/CI ตามเช็กลิสต์ใน TERMINAL_WIRING.md — ไม่ทำใน workspace 128MB นี้)
