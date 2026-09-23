EMBEDDED NATIVE AI ARCHITECTURE — MASTER SPECIFICATION

0. เป้าหมายสูงสุดของระบบ

Application นี้ต้องเป็น Mobile AI Agent Development Environment ที่มี Local AI Runtime ฝังอยู่ภายใน Application โดยตรง

AI ต้องสามารถทำงานได้โดยไม่ต้องพึ่งพา:

- Termux
- Python
- External Terminal Application
- localhost server
- CLI process ภายนอก
- การเปิด inference server แยก
- การติดตั้ง model ผ่าน terminal

หลังติดตั้ง Application แล้ว ระบบต้องสามารถมี Local AI ทำงานภายใน Application ได้ทันทีตามทรัพยากรของอุปกรณ์

Terminal เป็นเพียง Tool หนึ่งของระบบ ไม่ใช่สมองหลักของ Application

---

1. CORE ARCHITECTURE

โครงสร้างระดับสูง:

Android Application
→ UI Layer
→ AI Service
→ Agent Orchestrator
→ Tool Router
→ Local AI Runtime
→ JNI
→ Native Inference Engine
→ GGUF Model

โดยมีระบบสนับสนุน:

- Model Manager
- Tool Registry
- Tool Executor
- Context Manager
- Resource Manager
- Memory Manager
- Runtime Manager
- Validation / Retry System
- Storage Manager
- Provider Manager
- Crash Recovery

---

2. AI LAYER

AI ต้องแบ่งเป็นหลายชั้น ไม่ให้ UI ติดต่อ inference engine โดยตรง

UI
↓
AIService
↓
AgentRuntime
↓
ModelProvider
↓
NativeRuntime
↓
JNI
↓
libllama.so
↓
GGUF Model

AIService เป็น API กลางของ Application

ตัวอย่างแนวคิด:

- sendMessage()
- generate()
- stopGeneration()
- getStatus()
- getCurrentModel()
- switchModel()
- unloadModel()

UI ไม่ควรรู้รายละเอียดของ llama.cpp หรือ JNI

---

3. LOCAL AI RUNTIME

ใช้ inference engine ที่สามารถ compile เป็น native Android library ได้ เช่น llama.cpp

เป้าหมาย:

- C/C++
- Android NDK
- arm64-v8a
- ไม่มี Python dependency
- ไม่มี external CLI dependency
- ไม่มี localhost inference server
- inference อยู่ใน Application process

โครงสร้าง:

Android App
↓
JNI Bridge
↓
libllama.so
↓
Model Context
↓
GGUF Model
↓
Inference

Native library ต้องทำงานภายใน process ของ Application

---

4. JNI INTERFACE

สร้าง Native API ที่เป็น abstraction ระหว่าง Kotlin/Java กับ C/C++

อย่างน้อยต้องรองรับ:

loadModel()
generate()
stopGeneration()
unloadModel()
isModelLoaded()
getModelInfo()
getRuntimeInfo()
setGenerationParameters()

เพิ่มเติมได้ตามความจำเป็น เช่น:

- context size
- temperature
- top_p
- top_k
- threads
- batch size
- GPU/offload configuration หากรองรับ
- cancellation
- token streaming

JNI ต้องไม่เปิด shell และไม่ spawn inference process แยก

---

5. MODEL SYSTEM

Application ต้องมีระบบ Model Manager

หน้าที่:

- ตรวจสอบ model
- โหลด model
- unload model
- เปลี่ยน model
- ตรวจสอบขนาด model
- ตรวจสอบ architecture
- ตรวจสอบ quantization
- ตรวจสอบ compatibility
- ตรวจสอบ storage
- ตรวจสอบ RAM
- จัดการ model versions
- จัดการ optional model packs

Model ต้องเป็น GGUF หรือ format ที่ native engine รองรับ

---

6. EMBEDDED DEFAULT MODEL

Application ต้องออกแบบให้มี Default Local Model

แนวคิดคือ:

ติดตั้ง APK
↓
เปิด Application
↓
ตรวจสอบ Local Runtime
↓
ตรวจสอบ Default Model
↓
Initialize
↓
Local AI Ready

ผู้ใช้ทั่วไปไม่ควรต้อง:

- เปิด Terminal
- ติดตั้ง llama.cpp
- ติดตั้ง Python
- ดาวน์โหลด executable
- รัน command
- เปิด localhost

เพื่อให้ Local AI ใช้งานได้

---

7. MODEL DELIVERY

ต้องรองรับ 2 ระดับ

Default Model

เป็น model ขนาดเล็กที่ Application กำหนดไว้เป็น Local AI เริ่มต้น

เป้าหมาย:

- เปิดใช้ได้ทันที
- ไม่ต้องพึ่ง external application
- เหมาะกับอุปกรณ์ RAM ต่ำ
- ใช้สำหรับ chat / routing / tool calling / basic coding assistance

Optional Model Packs

ผู้ใช้สามารถติดตั้ง model ขนาดใหญ่ขึ้นภายหลังจากภายใน Application

ตัวอย่าง:

Small
Medium
Large

ModelManager ต้องเป็นผู้จัดการทั้งหมด

ห้ามบังคับให้ผู้ใช้ใช้ Terminal ในการติดตั้ง model

---

8. IMPORTANT: APK SIZE

APK ปัจจุบันประมาณ 42MB และยังไม่มี AI model

ดังนั้นต้องแยก:

Application binaries
+
Native libraries
+
Default model
+
Optional models

ออกจากกันอย่างเป็นระบบ

ถ้า model ใหญ่เกินข้อจำกัดของ base APK ให้ใช้ระบบ asset/model delivery ที่เหมาะสมกับ distribution platform

สถาปัตยกรรมต้องไม่ผูกติดกับสมมติฐานว่า model ต้องอยู่ใน base APK เสมอ

แต่จากมุมมองผู้ใช้:

"ติดตั้ง Application แล้วสามารถใช้งาน Local AI ได้"

คือ requirement หลัก

---

9. MODEL PROVIDER ABSTRACTION

อย่าผูก Agent เข้ากับ llama.cpp โดยตรง

สร้าง interface กลาง:

ModelProvider

สามารถมี:

LocalModelProvider
CloudModelProvider
RemoteModelProvider
HybridModelProvider

ในอนาคตสามารถเพิ่ม provider ได้โดยไม่ต้องแก้ UI และ Agent architecture

---

10. AGENT CORE

Agent Core เป็นสมองสำหรับการตัดสินใจว่าจะทำงานอะไร

โครงสร้าง:

User
↓
Agent Orchestrator
↓
Context Manager
↓
Tool Router
↓
Local AI / Provider
↓
Tool Executor
↓
Result
↓
Agent
↓
User

Agent ต้องสามารถ:

- วิเคราะห์คำสั่ง
- วางแผน
- เลือก tool
- เรียก tool
- อ่านผลลัพธ์
- ตรวจสอบผล
- retry
- ทำงานหลายขั้นตอน
- ขอข้อมูลจาก user เมื่อจำเป็น
- หยุดเมื่อเสร็จงาน

---

11. TERMINAL IS A TOOL

Terminal ห้ามเป็น core runtime ของ AI

Terminal เป็น Tool เช่นเดียวกับ:

- Browser
- Files
- GitHub
- Code Editor
- Build System
- Media Editor
- Storage
- Web Automation

ตัวอย่าง:

User
↓
AI
↓
ต้อง build project
↓
AI เลือก code.build
↓
Build Tool
↓
ผลลัพธ์กลับ AI

AI ไม่ควรถูกออกแบบว่า:

AI
↓
Terminal
↓
ทุกอย่าง

---

12. TOOL REGISTRY

สร้าง Tool Registry กลาง

ตัวอย่าง:

browser.open
browser.click
browser.scroll
browser.type

files.read
files.write
files.delete
files.rename

github.search
github.read
github.write
github.commit
github.pull

terminal.execute

code.analyze
code.edit
code.build
code.test

media.import
media.cut
media.trim
media.extractAudio

และสามารถเพิ่ม tools ใหม่ได้โดยไม่ต้องแก้ Agent Core ทั้งระบบ

ทุก Tool ต้องมี metadata:

- tool name
- description
- parameters
- parameter types
- required parameters
- permissions
- preconditions
- execution handler
- result schema
- risk level

---

13. TOOL RETRIEVAL

ห้ามส่ง tools ทั้งหมดเข้า model ทุกครั้ง

เมื่อ user ส่งคำสั่ง:

User Query
↓
Tool Retrieval
↓
เลือก candidate tools 3–5 ตัว
↓
ส่งเฉพาะ candidate tools ให้ AI
↓
AI เลือก tool

ระบบสามารถใช้:

- keyword matching
- embedding retrieval
- semantic retrieval
- tool category
- context
- previous successful tool calls

เพื่อลดจำนวนตัวเลือก

---

14. GRAMMAR-CONSTRAINED TOOL CALLING

AI ต้องไม่สามารถสร้างชื่อ Tool มั่วได้

Tool call ต้องมี structured format เช่น:

{
"tool": "browser.open",
"params": {
"url": "..."
}
}

ใช้ grammar-constrained decoding เช่น GBNF ที่ native engine รองรับ

Grammar ต้องกำหนด:

- JSON structure
- tool name
- parameter structure
- allowed value types

ดังนั้น token ที่ไม่ตรงกับ schema สามารถถูกป้องกันตั้งแต่ขั้น decoding

---

15. TOOL VALIDATION

หลัง AI สร้าง tool call:

AI Output
↓
Schema Validator
↓
Permission Validator
↓
Precondition Validator
↓
Tool Executor

ถ้าไม่ผ่าน:

Validation Error
↓
AI Retry
↓
ตรวจสอบใหม่

กำหนด retry สูงสุดประมาณ 2–3 ครั้ง

ถ้ายังไม่ผ่าน:

Fallback
↓
ถาม User หรือหยุดอย่างปลอดภัย

---

16. DYNAMIC FEW-SHOT

ระบบสามารถเก็บตัวอย่าง tool call ที่ถูกต้อง

เมื่อ user ส่ง query:

Query
↓
ค้นหา examples ที่คล้ายกัน
↓
เลือกตัวอย่างที่เกี่ยวข้อง
↓
เพิ่มเข้า context
↓
AI generate

ไม่จำเป็นต้อง hard-code examples ทั้งหมดใน system prompt

---

17. FUTURE LoRA

ไม่ต้องทำ LoRA ตั้งแต่เริ่มต้น

ลำดับ:

Phase 1
Grammar

Phase 2
Tool Retrieval

Phase 3
Validation + Retry

Phase 4
Dynamic Few-shot

Phase 5
เก็บ successful/failed tool calls

Phase 6
สร้าง dataset

Phase 7
LoRA fine-tuning

Phase 8
Merge adapter

Phase 9
Quantize

Phase 10
Convert/validate GGUF

Phase 11
ติดตั้ง model ใหม่ใน Model Manager

ดังนั้นระบบต้องออกแบบตั้งแต่ต้นให้เปลี่ยน model ได้

---

18. RESOURCE MANAGER

AI ต้องไม่ assume ว่า Android ทุกเครื่องมีทรัพยากรเท่ากัน

ResourceManager ต้องตรวจสอบ:

- RAM
- Available RAM
- Storage
- CPU cores
- CPU architecture
- thermal state หากเข้าถึงได้
- battery state หากจำเป็น
- model size
- context size
- thread count

จากนั้น Runtime Manager สามารถปรับ:

- context length
- threads
- batch size
- memory usage
- model selection

ตาม hardware

---

19. MEMORY PRESSURE

หาก RAM ไม่พอ:

ResourceManager
↓
ตรวจพบ memory pressure
↓
ลด context
↓
ลด runtime resources
↓
หยุด generation หากจำเป็น
↓
unload model
↓
release native resources
↓
recover

ต้องป้องกันไม่ให้ native inference ทำให้ Application crash โดยไม่จำเป็น

---

20. RUNTIME MANAGER

Runtime Manager เป็นเจ้าของ lifecycle ของ Local AI

ตัวอย่าง state:

UNINITIALIZED
INITIALIZING
READY
LOADING_MODEL
GENERATING
STOPPING
UNLOADING
ERROR
RECOVERING
OFFLINE

UI ต้องแสดง state เหล่านี้อย่างเหมาะสม

---

21. APP STARTUP

ห้ามโหลด model แบบ blocking ใน Application.onCreate()

ไม่ควร:

Application starts
↓
load huge model synchronously
↓
UI waits

เพราะอาจทำให้:

- startup delay
- ANR
- UI freeze
- memory spike
- crash

ให้ใช้:

Application Start
↓
Initialize basic runtime
↓
เปิด UI
↓
Background Runtime Initialization
↓
Load Model
↓
Ready

UI ต้องมีสถานะ:

Initializing AI
Loading Model
AI Ready
AI Unavailable
Recovery Required

---

22. CRASH / RECOVERY

JNI/native code สามารถทำให้ process ทั้ง Application crash ได้

ดังนั้นต้องออกแบบ defensive architecture

ต้องมี:

- model validation ก่อน load
- memory checks
- input validation
- output validation
- cancellation
- controlled unload
- error reporting
- recovery state
- persistent runtime state

หาก native runtime มีปัญหา Application ต้องสามารถกลับไป UI ได้ถ้าเป็นไปได้

---

23. STORAGE ARCHITECTURE

แยก storage:

/app
/runtime
/models
/models/default
/models/optional
/tools
/workspaces
/cache
/logs
/agent
/projects

ห้ามให้ model และ temporary files ปะปนกับ application data โดยไม่มีระบบจัดการ

---

24. MODEL SECURITY / INTEGRITY

ก่อนโหลด model ต้องตรวจสอบ:

- file exists
- file size
- expected format
- metadata
- compatibility
- integrity/checksum หากมี

ห้าม load file ที่เสียหรือไม่ตรงกับ model profile โดยตรง

---

25. MODEL LICENSE

ทุก model ที่นำมาแจกพร้อม Application ต้องตรวจสอบ license ก่อน

ต้องสามารถระบุ:

- model name
- model version
- source
- license
- quantization
- size
- context
- architecture
- intended usage

อย่าฝัง model ใดลง production โดยไม่ตรวจสอบสิทธิ์ในการ redistribution

---

26. OFFLINE-FIRST

Application ต้องยังสามารถทำงานพื้นฐานได้แม้ไม่มี Internet

Local AI:

Internet OFF
↓
Local Model
↓
ยังสามารถ chat / analyze / code / tool operation บางประเภทได้

Tools ที่ต้อง Internet เช่น:

- Web
- GitHub
- online APIs

ให้แสดงว่า tool นั้นต้องการ network

---

27. HYBRID AI

ในอนาคตสามารถใช้:

Local Model
+
Cloud Model

ตัวอย่าง:

Local model:

- routing
- simple chat
- tool selection
- simple coding
- local analysis

Cloud model:

- difficult reasoning
- large context
- complex coding
- large project analysis

Agent ไม่ควรผูกติดกับ provider ใด provider หนึ่ง

---

28. PERMISSION SYSTEM

Tool ทุกตัวต้องมี permission/risk level

ตัวอย่าง:

LOW

- read file
- inspect page

MEDIUM

- edit file
- create file

HIGH

- execute command
- delete files
- commit code
- external action

Critical actions ต้องสามารถขอ confirmation จาก user

Authentication ต้องให้ user เป็นผู้ดำเนินการเองเมื่อระบบไม่สามารถทำแทนได้

---

29. BROWSER AS A FIRST-CLASS TOOL

Browser ไม่ใช่ WebView ธรรมดาเพียงอย่างเดียว

ต้องรองรับ:

- tabs
- page state
- cookies
- cache
- local storage
- navigation
- click
- scroll
- type
- select
- upload
- download
- page inspection

แต่ละ tab สามารถมี context/state ของตัวเอง

AI สามารถควบคุม browser ผ่าน Tool API

Human intervention ต้องเหลือเฉพาะกรณีที่จำเป็น เช่น authentication, CAPTCHA หรือ security confirmation

---

30. CONTEXT MANAGER

Context Manager ต้องแยก:

Global Context
Conversation Context
Project Context
Browser Tab Context
Tool Context
Model Context

ไม่ควรนำข้อมูลทุกอย่างยัดเข้า model ทุกครั้ง

ใช้ retrieval และ summarization ตามความจำเป็น

---

31. AGENT MEMORY

ต้องมี memory หลายระดับ:

Short-term memory
→ conversation ปัจจุบัน

Task memory
→ งานที่กำลังทำ

Project memory
→ project/workspace

Tool memory
→ รูปแบบการใช้งาน tool ที่เคยสำเร็จ

Long-term memory
→ preferences/configuration ที่ผู้ใช้อนุญาตให้บันทึก

Memory ต้องสามารถลบ/แก้ไขได้

---

32. OBSERVABILITY

ต้องมีระบบ log ที่ช่วยตรวจสอบว่า AI ทำอะไร

ตัวอย่าง:

User Request
↓
Retrieved Tools
↓
Model Decision
↓
Tool Call
↓
Validation
↓
Execution
↓
Result
↓
Next Action

Log ต้องสามารถช่วย developer debug tool-selection errors ได้

แต่ต้องไม่เก็บข้อมูล sensitive โดยไม่มีเหตุผลหรือ permission

---

33. UI STATE

AI UI ต้องแสดงสถานะชัดเจน

ตัวอย่าง:

● AI Ready

◐ Loading Model

◐ Thinking

◐ Running Tool

✓ Tool Completed

⚠ Tool Failed

↻ Retrying

○ Offline

UI ไม่ควรทำให้ผู้ใช้เข้าใจผิดว่า AI กำลังทำงานเมื่อจริง ๆ ไม่ได้ทำงาน

---

34. MODEL MANAGER UI

ต้องมีหน้า:

Models

แสดง:

- Installed
- Available
- Size
- RAM requirement
- Context
- Quantization
- Provider
- Status

Actions:

- Load
- Unload
- Switch
- Delete
- Install
- Update
- Set as Default

---

35. RUNTIME MANAGER UI

แสดง:

AI Runtime

- Engine
- Version
- Current Model
- RAM usage
- CPU threads
- Context
- Runtime status

Actions:

- Reload
- Stop
- Unload
- Diagnostics

---

36. TOOL MANAGER UI

แสดง:

Installed Tools
Available Tools
Tool Status
Permission
Dependencies

ตัวอย่าง:

Browser
✓ Ready

GitHub
✓ Ready

Terminal
✓ Ready

Android SDK
○ Not Installed

NDK
○ Optional

Gradle
○ Available

Tools ขนาดใหญ่สามารถติดตั้งภายหลังแบบ on-demand

---

37. TERMINAL ARCHITECTURE

Terminal ต้องเป็น embedded tool

ไม่ใช่ dependency หลักของ AI

สามารถทำงานกับ Application sandbox เช่น:

/workspace
/projects
/tools
/tmp

ต้องเคารพ Android sandbox

ไม่ assume ว่าสามารถเข้าถึง root/system ได้

---

38. DEVELOPMENT TOOLS

Application สามารถมี:

- File Manager
- Code Editor
- Terminal Tool
- Git
- GitHub
- Build Tools
- Project Manager
- Browser
- Debug Tools

แต่แต่ละอย่างต้องเป็น module/tool ที่ Agent เรียกใช้ได้

---

39. FUTURE MEDIA SYSTEM

Media Editor ก็ต้องอยู่ใน architecture เดียวกัน

User
↓
Import Video
↓
AI วิเคราะห์
↓
Media Tools
↓
Timeline Engine
↓
AI-selected edits
↓
Preview
↓
Export

AI ไม่ควรเป็น video decoder/editor เอง

AI ใช้ media tools เป็น execution layer

---

40. FINAL ARCHITECTURE

ภาพรวมสุดท้าย:

User
│
▼
Chat / Browser / Editor / Media UI
│
▼
AI Service
│
▼
Agent Orchestrator
│
├── Context Manager
├── Memory Manager
├── Planner
├── Tool Router
├── Permission Manager
└── Provider Manager
│
├── Local Model Provider
│       │
│       ▼
│    Native Runtime
│       │
│       ▼
│      JNI
│       │
│       ▼
│   libllama.so
│       │
│       ▼
│    GGUF Model
│
└── Cloud/Remote Provider

Agent
│
▼
Tool Registry
│
├── Browser Tool
├── File Tool
├── GitHub Tool
├── Terminal Tool
├── Code Tool
├── Build Tool
├── Media Tool
└── Future Tools
│
▼
Tool Executor
│
▼
Validation
│
▼
Result
│
▼
Agent
│
▼
User

Supporting systems:

ResourceManager
RuntimeManager
ModelManager
StorageManager
CrashRecovery
Observability
PermissionSystem

---

41. IMPLEMENTATION ORDER

ห้ามพัฒนาแบบสุ่มหรือทำทุกอย่างพร้อมกัน

ให้ทำตามลำดับ:

PHASE 1
Audit Application architecture ปัจจุบัน

PHASE 2
สร้าง Native Runtime abstraction

PHASE 3
Integrate Android NDK

PHASE 4
Compile llama.cpp เป็น Android arm64 native library

PHASE 5
สร้าง JNI Bridge

PHASE 6
สร้าง LocalModelProvider

PHASE 7
สร้าง ModelManager

PHASE 8
สร้าง RuntimeManager

PHASE 9
สร้าง ResourceManager integration

PHASE 10
นำ Default GGUF model เข้า runtime

PHASE 11
ทำ Local Chat ให้ทำงานจริง

PHASE 12
สร้าง Tool Registry

PHASE 13
สร้าง Tool Router

PHASE 14
เพิ่ม Grammar-Constrained Tool Calling

PHASE 15
สร้าง Tool Validator

PHASE 16
เพิ่ม Retry / Recovery

PHASE 17
เชื่อม Browser/File/GitHub/Terminal tools

PHASE 18
สร้าง Permission System

PHASE 19
สร้าง Context Manager

PHASE 20
สร้าง Agent Orchestrator แบบหลายขั้นตอน

PHASE 21
ทำ Offline-first testing

PHASE 22
ทำ low-RAM testing

PHASE 23
ทำ crash/recovery testing

PHASE 24
ทำ real-device testing

PHASE 25
เก็บ tool-calling dataset

PHASE 26
ปรับปรุง prompt/few-shot

PHASE 27
LoRA fine-tuning เมื่อมี dataset เพียงพอ

PHASE 28
Quantize model

PHASE 29
Deploy updated model ผ่าน ModelManager

---

42. NON-NEGOTIABLE REQUIREMENTS

ห้ามเปลี่ยนแนวคิดเหล่านี้:

1. Local AI ต้องเป็น Native Runtime

2. Terminal ไม่ใช่ AI brain

3. ไม่พึ่ง Termux สำหรับผู้ใช้ทั่วไป

4. ไม่ใช้ localhost เป็น inference architecture หลัก

5. ไม่ใช้ external CLI เป็นตัวขับ AI

6. UI ห้ามผูกกับ llama.cpp โดยตรง

7. Agent ห้ามผูกกับ model provider เดียว

8. Tool calling ต้องมี schema validation

9. Tool selection ต้องมี retrieval

10. Tool output ต้องผ่าน validation

11. Native runtime ต้องมี lifecycle management

12. Model ต้องจัดการผ่าน ModelManager

13. Resource ต้องจัดการผ่าน ResourceManager

14. Application ต้องรองรับ model switching

15. Application ต้องรองรับ offline Local AI

16. ต้องรองรับ optional larger models ในอนาคต

17. ต้องไม่โหลด model แบบ blocking บน main thread

18. ต้องมี crash/recovery strategy

19. ต้องตรวจสอบ model license ก่อน redistribution

20. ทุก phase ต้องมี checkpoint และทดสอบก่อนเข้าสู่ phase ถัดไป

---

43. DEFINITION OF DONE

Embedded AI จะถือว่าสำเร็จเมื่อ:

- Application เปิดได้โดยไม่ต้องใช้ Termux
- Native runtime initialize ได้
- Model load ได้
- User พิมพ์ข้อความได้
- Local model generate response ได้
- Streaming output ทำงานได้
- Stop generation ได้
- Model unload ได้
- เปลี่ยน model ได้
- ResourceManager ตรวจ RAM ได้
- Application ยังทำงานได้เมื่อ Internet OFF
- AI สามารถเลือก Tool ที่มีอยู่จริง
- AI ไม่สามารถสร้างชื่อ Tool ที่ไม่มีอยู่
- Tool parameters ผ่าน schema validation
- Tool failure สามารถ retry ได้
- Tool permission ทำงาน
- Browser/File/Code/Terminal tools สามารถถูกเรียกจาก Agent
- Native runtime ไม่พึ่ง external process
- Application ไม่ต้องติดตั้ง Python/Termux เพื่อใช้งาน AI
- ระบบสามารถเพิ่ม model และ tools ในอนาคตโดยไม่ต้องรื้อ architecture หลัก

นี่คือ architecture เป้าหมายของ Application และทุกการพัฒนาหลังจากนี้ต้องตรวจสอบว่าไม่ทำให้ architecture หลักเบี่ยงออกจากข้อกำหนดดังกล่าว