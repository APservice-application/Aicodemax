# AMENDMENTS — บันทึกคำชี้ขาด/ไอเดียเพิ่มเติม (ต่อท้ายเท่านั้น ห้ามแก้ของเก่า)

## AMENDMENT-001 (2026-09-21): ความหมายของ "ห้ามพึ่ง Termux" + Terminal ฝังในแอป

- **ที่มา:** ไอเดียเจ้าของโปรเจกต์ — Terminal เป็นฟีเจอร์ที่ยากที่สุด ให้เอา Terminal
  สำเร็จรูปมาใช้เป็นเครื่องมือ 1 ตัวในแอป (อ้างอิง https://github.com/termux/termux-app)
- **คำชี้ขาด (เจ้าของโปรเจกต์):**
  "ห้ามพึ่ง Termux" = **ห้ามบังคับผู้ใช้ติดตั้งแอป Termux แยก**
  แต่**อนุญาตให้ฝังเทคโนโลยี Terminal ไว้ภายในแอปของเราเองเท่านั้น**
- **เหตุผล:** ลดขั้นตอนเชื่อมต่อ ลดคำสั่งที่ผู้ใช้ต้องพิมพ์เอง
  และทำให้ **AI ของเราสั่งงาน Terminal ได้ด้วยตัวเอง** (AI-driven terminal)
- **กรอบลิขสิทธิ์ (ห้ามละเมิด):**
  termux-app ทั้งก้อนเป็น GPLv3 → ห้าม embed โค้ดส่วน GPL ทั้งดุ้น
  (จะบังคับให้แอปเราต้อง open-source ทั้งหมด) จนกว่าเจ้าของจะอนุมัติเรื่องนี้โดยเฉพาะ
  ให้ใช้ได้เฉพาะส่วน permissive (Apache-2.0 lineage: terminal-emulator / terminal-view)
  + สร้าง Execution/Runtime Layer เอง โดยใช้ termux-app เป็น reference architecture
- **ผลต่อสเปกเดิม:** ตีความข้อ 47 ของ `requirements/โครงสร้าง 1.txt`,
  `APPLICATION ≠ TERMUX WRAPPER` ใน `requirements/ux 1.txt`,
  และ "Application ต้องไม่พึ่ง Termux" ใน `requirements/ฝ1. 3.txt`
  ตามคำชี้ขาดนี้ (ไม่ลบทิ้ง แค่ตีความให้ชัด: ห้ามแยกแอป แต่ฝังในได้)
- **สถานะ:** รอ implement ใน Phase 16 (Terminal) — ออกแบบ Tool Gateway ให้รองรับตั้งแต่วันนี้

## AMENDMENT-002 (2026-09-21): แอปฟรี + Open Source + อนุมัติใช้ Termux ตามข้อกำหนดเขา

- **มติเจ้าของโปรเจกต์:** แอปตั้งใจให้**ใช้ฟรี + เปิด source code**
  (ลิขสิทธิ์ GPLv3 ได้เลย) — เพื่อให้ผู้ใช้สายฟรีทั่วโลกใช้ทำงานเขียนโค้ดได้
- **โมเดลธุรกิจ:** หารายได้จาก**ฟีเจอร์พิเศษแยกในอนาคต** (เช่น ขายไฟล์ skill)
  — ตัวแอปหลักต้องฟรีเสมอ (GPL คุมตัวโค้ดแอป ไม่ได้ห้ามขายคอนเทนต์/บริการเสริมแยก)
- **อนุมัติ:** ให้ติดตั้ง/ฝัง Termux ได้ตามข้อกำหนดลิขสิทธิ์ของเขา (GPLv3-only)
  → ยกเลิกกรอบ "ห้าม wholesale GPL" ใน AMENDMENT-001
  เปลี่ยนเป็น "ใช้ได้ แต่ต้องทำ compliance ให้ครบ":
  - [x] มีไฟล์ `LICENSE` (GPLv3) ใน repo แล้ว
  - [ ] คง copyright notices ของ Termux ไว้ครบทุกไฟล์ที่นำมาใช้
  - [ ] ระบุส่วนที่เราดัดแปลง + วันที่ให้ชัด (prominent notices)
  - [x] เปิดซอร์สใน repo สาธารณะนี้ (= Corresponding Source พร้อมใช้)
  - [ ] ใส่ Appropriate Legal Notices ในหน้า About/Settings ของแอป (ทำใน Phase 25)
- **วิธีฝัง:** git submodule ปักหมุด commit + integration module ของเราเอง
  รายละเอียดดู `docs/TERMINAL_PLAN.md`
