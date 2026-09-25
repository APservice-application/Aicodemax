# Video Studio P0 — ทดสอบเสียงจากคลิปหลัง composited render

วันที่ 2026-09-25 | สถานะ: **code + JVM tests; รอ CI และทดสอบ Android เครื่องจริง**. งานนี้เป็น checkpoint แรกของสเปก Video Studio 92 หมวด ไม่ใช่การรับรองว่า 92 หมวดครบ.

## สาเหตุที่พบก่อนแก้

- `AndroidRenderPort.planRender()` เลือกเสียงเฉพาะแทร็ก `A*`; full render (`transcode`) จึงทิ้งเสียงจากคลิปวิดีโอที่เล่นใน preview. Fast trim กลับคัดลอกแทร็กเสียงจากวิดีโอโดยไม่ดูการ mute หรือ `includeAudio=false`.
- วงจรส่งเฟรมให้ AVC/AAC encoder เดิมรอจนส่ง input/EOS ครบก่อนอ่าน output; codec ที่ output queue เต็มจะไม่คืน input buffer → เสี่ยงค้าง.
- ตัว mixer เดิมถอด *ทั้งไฟล์* ลง PCM แม้ใช้แค่ช่วงสั้นของวิดีโอ; ขนาดไฟล์วิดีโอ >200 MB ถูกปฏิเสธและเสียงยาวใช้หน่วยความจำมาก.

## การแก้ใน checkpoint นี้

- เลือกเสียงในวิดีโอเป็น source ของ audio graph เดียวกับ A-track; เคารพ video track mute/hidden, clip volume 0, การแยกเสียง (ต้นฉบับ volume 0), และ `RenderPreset.includeAudio`.
- Fast copy ใช้เฉพาะคลิปเดี่ยวที่เสียงต้นฉบับไม่ถูกปรับ หรือไม่มีเสียงที่เลือก; audio track ถูกคัดลอกเฉพาะเมื่อ `plan.wantAudio`. QC บังคับมี/ไม่มี audio track ให้ตรงแผน.
- Full mix ถอดเฉพาะ source time window และผสมเป็นช่วง output 5 วินาทีแบบ frame-indexed; สุ่มตัวอย่าง mono/stereo, trim, rate/curve/reverse, volume keyframes/fades/overlap แล้ว encode AAC. WAV PCM/float ใช้ random-access range read.
- AVC และ AAC encoder ระบายผลระหว่างป้อนข้อมูล เพิ่ม watchdog เพื่อไม่ปล่อยให้ค้างไม่สิ้นสุด. ระยะ full mix **ยังจำกัด 3 นาที**, แต่แจ้ง fail ตั้งแต่ก่อน encode video; fast-copy คลิปเดี่ยวที่ไม่ปรับเสียงไม่ติดข้อจำกัดนี้.

## Manual E2E บน Android จริง — จำเป็นก่อนติ๊ก DONE

ใช้คลิปที่เจ้าของมีสิทธิ์ใช้งานและรู้ว่ามีเสียงสเตอริโอ (ถ้าได้: วิดีโอ 10–20 วินาที มีตัวนับเวลาเสียง/ภาพ), วิดีโอไม่มี audio track, เสียงเพลง WAV/MP3 และไฟล์วิดีโอที่มีขนาด >200 MB. เก็บชื่อ device/Android version/commit/วิดีโอต้นฉบับไว้ในบันทึกผล.

| เคส | ขั้นตอน | สิ่งที่ต้องตรวจจากไฟล์ MP4 ที่ export จริง |
|---|---|---|
| P0-01 fast-copy native | เพิ่มวิดีโอมีเสียง 10s ไม่แต่ง; export | มีภาพและเสียงตรงกัน ไม่ถูกตัดหัว/ท้าย |
| P0-02 full composite native | เพิ่ม text หรือ crop ให้ forced full render; export | เสียงต้นฉบับไม่หาย, timeline ยาวใกล้ต้นฉบับ, export เสร็จไม่ค้าง |
| P0-03 split + trim | ตัดคลิปกลางเรื่อง แล้ว split; export | ได้เสียงเฉพาะช่วงที่เลือกต่อเนื่องกับภาพ ไม่ย้อนกลับไปเสียงต้นไฟล์ |
| P0-04 mute/volume | video volume 0, video track muted, video volume 50% ทีละเคส | 0/mute = ไม่มีแทร็กเสียงถ้าไม่มีแหล่งอื่น; 50% ลดระดับเสียงจริง |
| P0-05 detached audio | กดแยกเสียง และ export ที่มี A-track | เสียงไม่ซ้อนทับ 2 เท่า, A-track ยังดัง, video volume 0 |
| P0-06 overlay | คลิปมีเสียง + วาง WAV/MP3 อีกแทร็ก | ได้ยิน 2 แหล่ง, ไม่มีเสียงแตก/ค้าง, ย้าย audio clip แล้วเสียงตามตำแหน่ง |
| P0-07 timing/speed | เพิ่ม clip speed 2×/reverse หรือ fades แล้ว export | จังหวะเสียงสัมพันธ์กับภาพในช่วงที่ renderer รองรับ; ไม่ค้าง |
| P0-08 includeAudio=false | ใช้ preset ที่ `includeAudio=false` (ถ้ามีหน้าทดสอบ/เรียกพอร์ต) | MP4 **ไม่มี** audio track แม้ต้นฉบับมีเสียง |
| P0-09 video without sound | composite คลิปไม่มี audio track | ไม่สร้างแทร็กเสียงปลอม/QC แจ้งถูก |
| P0-10 >200MB source, short trim | นำเข้าวิดีโอ >200 MB แต่เลือกช่วงสั้น 10s แล้ว full render | รันได้โดยไม่ถอดเสียงทั้ง source, ไม่ค้างหรือ OOM |
| P0-11 limits | full mix >180s และ fast-copy >180s | full mix fail **ก่อน** encode และอธิบายขีดจำกัด; fast-copy ยังได้เสียงเดิม |

**เกณฑ์รับ:** ใช้ `ffprobe -show_streams` ตรวจ audio stream และ `ffprobe -show_format` ตรวจ duration; ฟังไฟล์ MP4 ที่ส่งออก ไม่ตัดสินจาก preview หรือจาก `assembleDebug` อย่างเดียว. ถ้าภาพ/เสียงเลื่อน, PCM ผิด, โปรแกรมค้าง, หรือ audio track ของ MP4 ผิด ให้คง NOT DONE และเปิด bug พร้อมตัวอย่างไฟล์.

## ช่องว่างที่ยังต้องทำใน checkpoint ถัดไป

- ไม่รับรอง video compositor/preview ตรงกับ timeline สำหรับ gap, overlap, multilayer และ frame-accurate playback; งาน full engine 92 หมวดยังไม่ได้รับรอง E2E. QC ปัจจุบันตรวจ *การมีแทร็กเสียง* ไม่ได้วิเคราะห์เนื้อหาความดังหรือ A/V lip-sync.
- การเรนเดอร์เสียงแบบ time-stretch preserving pitch ยังไม่มี; การเปลี่ยน speed นี้ปรับ pitch ตาม sample-rate mapping. อุปกรณ์บางรุ่นอาจไม่รองรับ codec/PCM format ต้นฉบับ; แสดง failure จริงแทนการ export เงียบ.
- ยังไม่มี mid-render cancel, ยังไม่มีผลทดสอบแอปบนเครื่องจริง. ห้ามอ้างว่าเทียบเท่า CapCut หรือครบข้อในสเปก.
