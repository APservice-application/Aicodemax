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

## สถานะ
- [x] Phase -1: บันทึกข้อกำหนดสูงสุด 8 ไฟล์ลง repo
- [ ] Phase 0: Architecture Contract (Maps: Architecture / Modules / Data Flow / State Machine / Interfaces)
- [ ] Phase 1: Native Android Shell (Kotlin)
- [ ] Phase 2+: ตาม DEVELOPMENT ORDER

## กติกาพื้นที่/ไฟล์ใหญ่
- ห้าม commit `build/`, `.gradle/`, APK/AAB, model weights — ดู `.gitignore`
- ไฟล์สเปก (~316KB) เก็บใน repo ได้ถาวร
