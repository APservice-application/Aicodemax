# บิลด์ในเครื่อง (Local Build)

> ตั้งแต่ CP-146: **การบิ้วปกติฝัง AI เข้า APK ให้อัตโนมัติ** ไม่ต้องรันสคริปต์
> ไม่ต้องตั้งค่าอะไรเพิ่ม — ขอแค่บิ้วแบบธรรมดา

## สิ่งที่ต้องมีในเครื่อง (ครั้งเดียว)

- JDK 17
- Android SDK (platform-34 + build-tools 34.0.0) + `cmdline-tools`
- อินเทอร์เน็ต **ตอนบิ้วครั้งแรกเท่านั้น** (ดึง engine/model มาแคชไว้)

> NDK 27 + CMake 3.22.1: Gradle จะติดตั้งให้เองอัตโนมัติตอนบิ้วครั้งแรก
> (ถ้าไม่มี `cmdline-tools` ให้ติดตั้งเอง 1 คำสั่ง:
> `sdkmanager "ndk;27.0.12077973" "cmake;3.22.1"`)

## วิธีบิ้ว

```sh
./gradlew assembleDebug
```

คำสั่งเดียวจบ — Gradle จะทำทั้งหมดให้เอง:

| ขั้นตอน | ทำโดย | เกิดเมื่อไหร่ |
|---|---|---|
| ติดตั้ง NDK + CMake | `ensureNativeBuildTools` | ขาดเมื่อไหร่ ติดตั้งให้ |
| ดึงโมเดล Qwen3-4B (2.5GB) → assets | `fetchBuiltinModel` | ครั้งเดียว แล้วแคชไว้ (ตรวจขนาด+GGUF ทุกครั้ง) |
| ดึง ffmpeg/ffprobe arm64 → jniLibs | `fetchFfmpegLibs` | ครั้งเดียว แล้วแคชไว้ |
| compile llama.cpp + whisper.cpp + pty → `.so` | CMake/NDK (`externalNativeBuild`) | ทุกครั้งที่ซอร์สเปลี่ยน (incremental) |
| แพ็กทุกอย่างเข้า APK | AGP (merge) | ทุกบิ้ว |
| ตรวจ APK ว่ามี AI ครบ | `verifyEmbeddedAi` | ทุกบิ้ว — **ขาด = บิ้วล้มเหลวทันที** |

ไฟล์ที่ดึงมาเก็บใน `app/src/main/assets/ai/` และ `app/src/main/jniLibs/`
(ไม่อยู่ใน git) — บิ้วครั้งต่อไปใช้ของเดิม ไม่ดาวน์โหลดซ้ำ

## ผลลัพธ์

APK ที่ได้ = **ONE APPLICATION + ONE BUILT-IN AI**:

- ติดตั้งบนเครื่องสะอาด → เปิดแอป → เปิดแชท → พิมพ์ "สวัสดี"
- **ปิดเน็ตก็ใช้ได้** (โมเดล+engine อยู่ใน APK แล้ว)
- ไม่ต้องดาวน์โหลดโมเดล ไม่ต้อง API key ไม่ต้องตั้งค่าอะไร

## ถ้าบิ้วมีปัญหา

| อาการ | แก้ |
|---|---|
| `Android SDK not found` | ตั้ง `sdk.dir` ใน `local.properties` หรือ `ANDROID_HOME` |
| ไม่มี NDK/CMake และไม่มี cmdline-tools | `sdkmanager "ndk;27.0.12077973" "cmake;3.22.1"` (ครั้งเดียว) |
| `BUILD=FAIL ... lacks embedded AI` | บิ้วไม่สมบูรณ์ — `./gradlew clean assembleDebug` ใหม่อีกรอบ |
| เน็ตหลุดตอนดึงโมเดล | รันบิ้วใหม่ มันจะดึงต่อ/ดึงใหม่เอง (ไฟล์ `.part` ไม่ถูกใช้) |
