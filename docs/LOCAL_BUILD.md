# บิลด์ APK ที่มี AI ใช้งานได้ (LOCAL_BUILD)

> ปัญหาที่พบบ่อย: บิลด์ APK จาก Android Studio/Gradle เองแล้วแชทขึ้น
> "ไม่พบ native lib" — เพราะ APK นั้นขาดของ 2 อย่างที่ปกติ **CI สร้างให้**
> (ไม่ได้อยู่ใน repo):
>
> 1. `libaicode_jni.so` (+ whisper/pty) — native lib สำหรับ arm64
> 2. `assets/ai/builtin-model.gguf` — โมเดล AI (~470MB)
>
> เลือกทางใดทางหนึ่ง:

## ทาง A — ใช้ APK จาก CI (ง่ายสุด)

1. เปิด GitHub repo → แท็บ **Actions**
2. เลือกรันล่าสุด (ติ๊กเขียว ✅) ของสาขา `main`
3. ด้านล่างสุดโหลด **Artifacts → app-debug**
4. แตก zip ติดตั้ง APK ลงเครื่อง arm64
5. เปิดแอปครั้งแรก: แอปแตกไฟล์ AI ในเครื่อง (~1 นาที) แล้วแชทได้ทันที

## ทาง B — บิลด์เองให้ครบ (นักพัฒนา)

### ของที่ต้องมี

- JDK 17 + Android Studio (SDK + Platform-Tools)
- **NDK 27.0.12077973** + **CMake**: ติดตั้งจาก
  Android Studio > Settings > Appearance > System Settings >
  Android SDK > SDK Tools > ติ๊ก NDK (เลือก 27.0.12077973) + CMake > Apply
- `git cmake curl python3` ใน PATH + เน็ต + เวลา ~15 นาที (ครั้งแรก)

### ขั้นตอน

```bash
git clone <repo> && cd Aicodemax

# 1) โหลดโมเดล AI ใส่ assets (~470MB, ครั้งเดียว)
bash scripts/fetch-builtin-model.sh

# 2) บิลด์ native libs ด้วย NDK (~15 นาทีครั้งแรก)
bash scripts/build-native-local.sh

# 3) บิลด์ APK
./gradlew assembleDebug

# 4) ติดตั้ง (เสียบมือถือ arm64 + เปิด USB debugging)
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

ไฟล์ที่สคริปต์สร้าง (`jniLibs/`, `assets/ai/*.gguf`) ถูก gitignore ไว้แล้ว —
`git status` จะสะอาด ไม่เผลอคอมมิตไบนารี

### หมายเหตุ

- รองรับเครื่อง **arm64-v8a** เท่านั้น (มือถือแอนดรอยด์ปัจจุบันเกือบทั้งหมด)
- `libffmpeg/libffprobe` สคริปต์ข้ามให้ (ใช้กับงานตัดต่อวิดีโอเท่านั้น) —
  ถ้าไม่มี งานนั้นจะแจ้งว่าใช้ไม่ได้อย่างซื่อสัตย์ ไม่กระทบแชท AI
- เปิดแอปครั้งแรกต้องรอแตกไฟล์ AI (~1 นาที ห้ามปิดแอป)

## ตารางแก้ปัญหา (ดู checklist ในการ์ด "AI ในตัว" ประกอบ)

| ข้อความ | สาเหตุ | วิธีแก้ |
|---|---|---|
| ❌ ไม่พบ native lib … บิลด์โดยไม่มี .so | APK บิลด์เองโดยไม่รันสคริปต์ | ทาง A หรือทาง B ข้อ 2 |
| ⚠️ เครื่องนี้ (…) ไม่ใช่ arm64 | มือถือ/อีมูเลเตอร์ 32-bit | ใช้เครื่อง arm64 |
| ❌ ไม่มีโมเดลใน APK | ขาดข้อ 1 | `bash scripts/fetch-builtin-model.sh` แล้วบิลด์ใหม่ |
| ❌ ไฟล์โมเดลที่แตกไว้ไม่สมบูรณ์ | ปิดแอประหว่างแตกไฟล์ครั้งแรก | ล้าง data แอปแล้วเปิดใหม่ |
