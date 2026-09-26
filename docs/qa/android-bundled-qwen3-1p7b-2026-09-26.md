# Android: เปลี่ยน AI ใน APK เป็น Qwen3-1.7B (2026-09-26)

**สถานะ:** แก้โค้ดบน `feature/android-qwen3-1p7b-bundled-2026` ซึ่งแตกจาก BYOK branch `99ce52e`; **รอ CI ของ commit นี้และทดสอบเครื่องจริง**. ไม่ใช่ release APK และไม่แก้ `main` ของเอเจนต์อื่น. ประวัติ CP-147 ที่ใช้ Qwen3-4B ในอดีตไม่ถูกลบหรือเขียนทับ.

## คำสั่งเจ้าของ/ขอบเขต

เจ้าของยืนยันว่า “ตัว 1.7” คือ **Qwen3-1.7B** ที่ฝังใน Android APK แทน **Qwen3-4B**, ไม่ใช่โมเดลที่จะยิงผ่าน API. เลือก quantization Q4_K_M เช่นเดียวกับรุ่นก่อน; BYOK 16 เจ้า/การเลือกโมเดล API ยังคงตาม branch เดิม. ปรับเฉพาะตัว AI ในเครื่อง, build/QA และชื่อที่แสดง. ไม่เพิ่ม engine ใหม่.

## ที่มา/การพิสูจน์ไฟล์

- โมเดลต้นทาง Qwen3-1.7B; quantization **Unsloth/Qwen3-1.7B-GGUF**, license `Apache-2.0` ตาม model card/revision. [Model card](https://huggingface.co/unsloth/Qwen3-1.7B-GGUF); [ไฟล์รุ่นที่ตรึง](https://huggingface.co/unsloth/Qwen3-1.7B-GGUF/blob/cc27747d7419139e44ba97777c2f2fd5dca92ee1/Qwen3-1.7B-Q4_K_M.gguf). Official `Qwen/Qwen3-1.7B-GGUF` revision ที่ตรวจเผยแพร่ไฟล์ Q8_0 ไม่ใช่ Q4_K_M; จึงไม่อ้างว่าไฟล์ Q4 นี้เป็น quantization ที่ Qwen เผยแพร่โดยตรง.
- Commit ของ repository quantization: `cc27747d7419139e44ba97777c2f2fd5dca92ee1` (ไม่ใช้ `main` ที่เปลี่ยนได้). ขนาด GGUF **1,107,409,376 bytes**; SHA-256 จาก Hugging Face LFS metadata **`ba491cf470c3cadc624e4c8d6c9a27c998809e8ba8eb938d1689ae87e024b6b7`**. Build ตรวจทั้งขนาด/hash/magic ก่อนแพ็ก และแคชต้องผ่าน hash อีกครั้ง.
- แบ่ง **3 parts**: 536,870,912 + 536,870,912 + 33,667,552 bytes; manifest มีจำนวนส่วน/ขนาด/model ID/SHA-256. CI ต่อตรวจ SHA, รัน host inference จริง และ stream ตรวจทุก part ใน debug APK ว่าไม่มีส่วนของ 4B หลุดมา. APK ยังมี JNI llama.cpp, Whisper, FFmpeg และฟีเจอร์เดิม.

## การย้ายรุ่น/พื้นที่เก็บข้อมูล

- ชื่อไฟล์ที่ติดตั้งใหม่คือ `builtin-qwen3-1.7b-q4_k_m.gguf`; manifest ของ 4B เก่าหรือคนละ hash จะไม่ถูกเข้าใจว่าเป็น 1.7B. ไฟล์ 4B ที่ provision ใน app-private ของรุ่นก่อน (`builtin-qwen3-4b-q4_k_m.gguf`) จะถูกลบ **หลังยืนยันว่ามี manifest ของ 1.7B และ parts ครบ** และก่อนคัดลอกไฟล์ใหม่ เพื่อไม่ให้ต้องเก็บ 2.5GB+1.1GB เพิ่มพร้อมกัน. ไฟล์ดาวน์โหลดเอง/โปรเจกต์ผู้ใช้ไม่ถูกลบ.
- ตอนคัดลอกจาก assets แบบ streaming จะคำนวณ SHA-256 อีกครั้งและไม่โหลดไฟล์ไม่ครบ/ผิด hash. ถ้าอัปเดต debug APK ติดตั้งทับไม่ได้เพราะลายเซ็น CI คนละใบ ให้ **สำรองข้อมูลก่อนถอนติดตั้ง**; การถอนติดตั้งลบข้อมูลแอปและคีย์ BYOK ใน Keystore ต้องกรอกใหม่.
- ลดขนาดโมเดลจาก 2,497,280,256 เป็น 1,107,409,376 bytes (ลดประมาณ 55.7% เฉพาะ weights). ไม่ได้สรุปว่า APK/พื้นที่หลังติดตั้งจะมีขนาดเท่าตัวเลขนี้; ต้องดูขนาด artifact จริงจาก CI.

## ผล RAM / สิ่งที่ยังไม่พิสูจน์

- `ResourceManager` ประมาณหน่วยความจำที่ต้องพร้อมโหลดขั้นต่ำ context 1024: `floor((1,107,409,376 / 1,048,576) × 1.3) + 40 ≈ 1,413 MiB` **RAM ที่ว่าง** (ไม่ใช่ RAM เครื่องทั้งหมด). เป็นเกณฑ์หยาบ; JNI/runtime/OS อาจใช้เพิ่ม. มือถือของเจ้าของอาจยังโหลดไม่ไหว; **ห้ามรับประกันว่า 1.7B ใช้ได้ทุกเครื่อง**. หากถูก RAM gate ปฏิเสธ BYOK ยังคงเป็น fallback เมื่อมีคีย์/consent/เน็ต.
- CI host smoke test ไม่ได้พิสูจน์ RAM/ความเร็ว/คุณภาพภาษาไทย/การทำงานบนเครื่อง arm64 ของเจ้าของ. การเป็นรุ่นเล็กกว่าอาจให้คุณภาพ tool-calling และวางแผนต่ำกว่า 4B; ต้องทดสอบจริง.
- ข้อจำกัด multimodal BYOK ที่ยังไม่ครบ `all_models_now` ไม่ได้ถูกแก้ด้วยการเปลี่ยนโมเดลใน APK นี้: [BYOK QA](android-byok-ai-providers-2026-09-26.md).

## เช็กลิสต์ QA หลัง CI ผ่าน

1. CI JVM + Android tests, checksum ของ GGUF ก่อน/หลังแพ็ก, `llama-cli` สร้างโทเคนตอบ `สวัสดี /no_think` จริง, assembleDebug + verifyNativeSymbols + APK 3 parts + artifact `app-debug` **ทั้งหมดต้องเขียว**. ไม่ build/publish release.
2. เจ้าของสำรองข้อมูลก่อนติดตั้ง debug ใหม่; ตรวจ Settings ว่า Qwen3-1.7B, Models รายงานไฟล์ 1.1GB และสถานะ RAM gate; ตรวจว่าไม่ต้องดาวน์โหลดโมเดลอีกบนเครื่อง.
3. ปิดอินเทอร์เน็ตชั่วคราว, ทดสอบคำตอบไทย, งานไฟล์/เครื่องมือที่ต้องขออนุญาต, planner/re-planner และประเมินคุณภาพจริง; เก็บสถานะ RAM ที่หน้า Models. อย่าสรุปว่าผ่านจาก CI เท่านั้น.
4. เปิดเน็ตอีกครั้ง; ตรวจว่า BYOK ที่เคยตั้งไว้ยังอยู่ถ้าอัปเดตแบบติดตั้งทับได้; ถ้าถอนติดตั้งเพราะลายเซ็นต่าง ต้องกรอกคีย์ BYOK ใหม่ในแอป **อย่าส่ง API key มาในแชต**.
