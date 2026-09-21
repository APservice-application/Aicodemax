# Aicodemax — OWN AI APPLICATION

Native Android (Kotlin) AI Operating Environment:
Chat เป็นศูนย์กลางสั่งงาน + Workspace/Tools/Runtime ที่ใช้งานจริงได้
(AI Runtime + Tool Runtime + Project Runtime + Workspace + UI)

> ไม่ใช่ Chatbot / API Client / WebView Wrapper

**พันธกิจ:** ฟรี + Open Source เพื่อให้ผู้ใช้สายฟรีทั่วโลกใช้ทำงานเขียนโค้ดได้
รายได้ในอนาคตมาจากฟีเจอร์พิเศษแยก (เช่น ไฟล์ skill) — ตัวแอปหลักฟรีเสมอ

## ลิขสิทธิ์
- โปรเจกต์นี้ใช้ **GNU GPLv3** (ดูไฟล์ `LICENSE`)
- Terminal ฝังในแอปโดยใช้ Termux ตามข้อกำหนด GPLv3 (ดู `docs/TERMINAL_PLAN.md`
  และ `docs/THIRD_PARTY.md`)

## เริ่มตรงไหน
1. อ่าน `docs/MASTER_CONTRACT.md` (สัญญาหลัก)
2. อ่านสเปกฉบับเต็มใน `docs/requirements/` (8 ไฟล์ — ข้อกำหนดสูงสุด)
3. ทำตามลำดับ Phase 0 → 30 (นิยามใน `requirements/โครงสร้าง 1.txt` ข้อ 61)

## สถานะ (sprint 2026-09-21)
- [x] Phase -1: ล็อกข้อกำหนดสูงสุด 8 ไฟล์ + AMENDMENT-001/002
- [x] Phase 0: Architecture Contract (`docs/arch/`)
- [x] Phase 1: Native Shell — แอป Kotlin/Compose 25 โมดูล + CI ประกอบ APK
- [x] Phase 2/8/10/12/21: State, TaskEngine, Registry, Memory, Checkpoint, Audit — **เทสเขียว 69/69** (JVM 26 + Android unit 43)
- [x] Phase 6/7/9/11/13/20: Orchestrator + Planner + Agent + Gateway + Verify — ใช้งานไฟล์ได้จริงผ่านแชท
- [x] Phase 3/14/15: Resource Monitor, Files, Editor — runtime จริง
- [x] Phase 4/5: Model Registry/Router + Bootstrap port (รอไฟล์โมเดล)
- [x] Phase 16/17/18/19: Terminal/Build/Git/Browser — contract + capability gate (รอ runtime)
- [x] Phase 22/23/24/25: Audit, Chat UI, Workspace gates, Settings + About/legal
- [x] Phase 26: Integration (ServiceLocator + CI)
- [ ] Phase 27–30: Stress/Offline/Low-RAM/RC — ต้องรันบนอุปกรณ์จริง

รายละเอียดสถานะราย Phase: `docs/arch/ROADMAP.md`
วิธี build: เปิดด้วย Android Studio (JDK 17) แล้วกด Run หรือดูผล CI ในแท็บ Actions
(`app-debug.apk` อยู่ใน Artifacts ของ CI)

## กติกาพื้นที่/ไฟล์ใหญ่
- ห้าม commit `build/`, `.gradle/`, APK/AAB, model weights — ดู `.gitignore`
- ไฟล์สเปก (~316KB) เก็บใน repo ได้ถาวร
