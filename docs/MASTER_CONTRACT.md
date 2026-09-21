# MASTER CONTRACT — Aicodemax (OWN AI APPLICATION)

> ไฟล์นี้คือสัญญาหลักของ repo นี้
> ข้อกำหนดสูงสุดทั้งหมดอยู่ใน `docs/requirements/` (8 ไฟล์, ~20,059 บรรทัด)
> AI/นักพัฒนาทุกคนที่ทำงานกับ repo นี้ **ต้องอ่านไฟล์ใน `requirements/` ก่อนเริ่มงาน**
> และต้องทำงานต่อจาก repo ได้โดยไม่พึ่งประวัติการสนทนา

## แหล่งอ้างอิงสูงสุด (เรียงตามลำดับการอ้างอิง)
1. `requirements/ระบบทั้งหมด.txt` — MASTER ARCHITECTURE (Canonical Source of Truth)
2. `requirements/โครงสร้าง 1.txt` — MASTER BLUEPRINT V1.0 + Phase 0–30
3. `requirements/ระบบ ai.txt` — MAXCODE AI Intelligence System
4. `requirements/ux 1.txt` — Architecture + UI/UX Blueprint
5. `requirements/ลักษณะui 1.txt` — ChatGPT-like UI/UX Spec
6. `requirements/1.1.txt` — UI/UX + User-Visible Experience + Tool Interface Spec
7. `requirements/1.2.txt` — UI/UX Visual + Interaction Contract (Design System)
8. `requirements/ฝ1. 3.txt` — Tool 100% Capability Contract

ทะเบียนฉบับเต็ม (พร้อมกฎสูงสุด 14 ข้อ + ลำดับ Phase): ดู `SPEC_INDEX.md` ใน workspace ของ Agent
(และจะถูก mirror ไว้ที่นี่เมื่อมีการอัปเดต)

## กฎเหล็กย่อ (ฉบับเต็มอยู่ในไฟล์ requirements)
- สร้างใหม่จากศูนย์ ห้ามปะติดปะต่อสถาปัตยกรรมเดิม
- ไม่ใช่ Chatbot/API Client/WebView/Termux wrapper → คือ AI Runtime + Tool Runtime + Project Runtime + Workspace + UI
- Own AI First, Local-first; โมเดลภายนอกเป็นแค่ Adapter
- ทุกความสามารถต้องมี Full UI ห้ามของหลอก
- ทุก Tool ต้องมีครบ 7 ชั้นถึง Verification/Recovery
- Native Android (Kotlin) ห้ามพึ่ง Termux
- มี Bootstrap AI ตั้งแต่วันติดตั้ง
- งานอันตรายต้องขอ permission + audit log
- ทำตาม Phase 0→30 ห้ามข้ามถ้า interface ยังไม่เสถียร
- ของใหม่ต้องผ่าน 10 ขั้น (เช็กซ้ำซ้อน/layer/dependency/compat + task/test/checkpoint + docs)

## คำชี้ขาดเพิ่มเติม
- ดู `docs/AMENDMENTS.md` (ต่อท้ายเท่านั้น ห้ามแก้ของเก่า) — ปัจจุบันมี AMENDMENT-001:
  "ห้ามพึ่ง Termux" = ห้ามบังคับผู้ใช้ติดตั้งแอปแยก แต่ฝัง Terminal ไว้ในแอปได้

## กติกาทำงาน (Agent เขียน → กฤษรีวิว)
- Agent ทำงานบน feature branch (`phase-X/...` หรือ `feature/...`) แล้ว push
- กฤษรีวิวก่อน merge เข้า `main`
- ห้าม commit ไฟล์ใหญ่ (build/, .gradle/, APK, model weights) — ดู `.gitignore`
