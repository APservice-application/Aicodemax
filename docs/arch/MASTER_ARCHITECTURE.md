> SOURCE OF TRUTH #1 — สถาปัตยกรรมหลักของโปรเจกต์ (เจ้าของกำหนด 2026-09-21)
> ต้นฉบับ: สถาปัตยกรรมใหม่.txt (ที่ปรึกษาวิศวกร) — ห้าม AI เปลี่ยนเอง ต้องขออนุมัติเจ้าของก่อน
> หลัก: AI คิดเป็น Capability ไม่ใช่ Terminal Command; Terminal = Compatibility Layer (CP-31/32/33)

---

MASTER ARCHITECTURE

OWN AI + CAPABILITY-CENTRIC CODING RUNTIME PLATFORM

0. ARCHITECTURE LOCK

เอกสารนี้คือสถาปัตยกรรมหลักของแอป AI Development Environment ที่กำลังพัฒนา

ต้องถือเอกสารนี้เป็น Source of Truth ด้านสถาปัตยกรรม

ห้าม AI Developer:

- เปลี่ยนสถาปัตยกรรมเองโดยไม่มีเหตุผลและการอนุมัติ
- ย้อนกลับไปใช้ Terminal เป็นแกนกลาง
- สร้างระบบซ้ำซ้อน
- สร้าง Engine ใหม่ทั้งที่มี Engine เดิมรับผิดชอบอยู่แล้ว
- ข้ามลำดับ Checkpoint
- รายงานว่างานเสร็จเพียงเพราะ compile ผ่าน
- ทำงานหลาย Checkpoint พร้อมกัน
- เดาความต้องการที่สถาปัตยกรรมกำหนดไว้แล้ว
- ถามผู้ใช้ในเรื่องที่เอกสารนี้กำหนดไว้ชัดเจนแล้ว

หลักสำคัญ:

«AI ต้องคิดเป็น "Capability" ไม่ใช่ "Terminal Command"»

«Terminal เป็น Tool/Compatibility Layer ไม่ใช่ Brain/Core Architecture»

«Application เป็นเจ้าของ Orchestration, Capability, UI, State, Verification และ Recovery»

«Runtime ภายนอกเป็น Execution Foundation ไม่ใช่สมองของแอป»

---

1. PRODUCT IDENTITY

แอปนี้ไม่ใช่ Chatbot ที่มีเครื่องมือเสริม

แอปนี้คือ:

AI DEVELOPMENT ENVIRONMENT

หรือ

AI WORKSPACE + CODING RUNTIME PLATFORM

เป้าหมายคือให้ผู้ใช้สามารถ:

- สนทนากับ AI
- สร้างและแก้ไขโค้ด
- สร้าง Project
- เปิดเว็บไซต์
- ใช้ Browser
- จัดการไฟล์
- Run Code
- Build Application
- Test
- Debug
- ใช้ Git
- ใช้ GitHub
- จัดการ Models
- จัดการ Tools
- จัดการ Agents
- จัดการ Runtime
- ตรวจสอบ Resource
- ดู Activity
- Review AI Actions
- ให้ AI ทำงานอัตโนมัติ
- หยุด/พัก/อนุมัติ/ปฏิเสธงาน AI
- กู้คืนเมื่อเกิดข้อผิดพลาด

ทั้งหมดต้องทำได้ผ่าน UI และ Internal APIs

ผู้ใช้ไม่ควรถูกบังคับให้เปิด Terminal เพื่อทำงานปกติ

---

2. CORE ARCHITECTURAL PRINCIPLE

Architecture เดิม:

USER
↓
AI
↓
TERMINAL
↓
COMMAND
↓
TOOLS

ถือว่าไม่ใช่ Architecture หลักอีกต่อไป

Architecture ใหม่:

USER
↓
APPLICATION UI
↓
AI ORCHESTRATOR
↓
CAPABILITY RESOLVER
↓
ENGINE / TOOL / RUNTIME
↓
EXECUTION
↓
VERIFICATION
↓
RESULT
↓
MEMORY / STATE / AUDIT

Terminal อยู่เป็นเพียงหนึ่งใน Execution Adapter

---

3. CAPABILITY-CENTRIC ARCHITECTURE

AI ไม่ควรคิดว่า:

"ต้องพิมพ์คำสั่งอะไร?"

AI ต้องคิดว่า:

"ต้องใช้ความสามารถอะไรเพื่อทำงานนี้?"

ตัวอย่าง:

ผู้ใช้:
"Build APK"

AI:

ต้องการ Capability:
BUILD_ANDROID

จากนั้น:

Capability Resolver
↓
หา Build Engine
↓
ตรวจ Runtime
↓
ตรวจ JDK
↓
ตรวจ Android SDK
↓
ตรวจ Gradle
↓
Preflight Resource
↓
Execute Build
↓
Verify APK
↓
ส่งผลลัพธ์ให้ผู้ใช้

AI ไม่จำเป็นต้องสร้าง:

./gradlew assembleDebug

ด้วยตัวเอง

คำสั่งดังกล่าวอาจถูกใช้ภายใน Adapter แต่ไม่ใช่ระดับความคิดของ AI

---

4. HIGH-LEVEL ARCHITECTURE

┌───────────────────────────────────────────────┐
│                 ANDROID APP                   │
│                                               │
│  Compose UI / Navigation / Workspace         │
└───────────────────────┬───────────────────────┘
                        │
                        ▼
┌───────────────────────────────────────────────┐
│              APPLICATION LAYER                │
│                                               │
│ Navigation / State / Use Cases / Controllers │
└───────────────────────┬───────────────────────┘
                        │
                        ▼
┌───────────────────────────────────────────────┐
│                 AI CORE                       │
│                                               │
│ AI Orchestrator                              │
│ Context Engine                               │
│ Memory                                       │
│ Planner                                      │
│ Task Engine                                  │
│ Agent Runtime                                │
│ Model Router                                 │
│ Tool/Capability Registry                     │
│ Permission Manager                           │
│ Verification Engine                          │
│ Recovery Engine                              │
└───────────────────────┬───────────────────────┘
                        │
                        ▼
┌───────────────────────────────────────────────┐
│            CAPABILITY PLATFORM                │
│                                               │
│ Code Engine                                  │
│ File Engine                                  │
│ Project Engine                               │
│ Build Engine                                 │
│ Test Engine                                  │
│ Debug Engine                                 │
│ Git Engine                                   │
│ GitHub Engine                                │
│ Browser Engine                               │
│ Package Manager                              │
│ Dependency Resolver                          │
│ Artifact Engine                              │
│ Verification Engine                          │
└───────────────────────┬───────────────────────┘
                        │
                        ▼
┌───────────────────────────────────────────────┐
│             RUNTIME MANAGER                   │
│                                               │
│ Python / Java / Kotlin / JS / TS             │
│ C / C++ / Gradle / CMake / SDK / etc.        │
└───────────────────────┬───────────────────────┘
                        │
                        ▼
┌───────────────────────────────────────────────┐
│         COMPATIBILITY / CLI ADAPTER           │
│                                               │
│ Terminal / CLI tools when Native Adapter      │
│ does not exist                               │
└───────────────────────────────────────────────┘

---

5. IMPORTANT: TERMINAL IS NOT CORE

Terminal ต้องไม่เป็น:

- AI brain
- Main execution controller
- Universal API
- Required dependency for every operation
- Main development interface
- Single point of execution

Terminal เป็น:

OPTIONAL COMPATIBILITY / DEVELOPER TOOL

ใช้เมื่อ:

1. ยังไม่มี Native Engine
2. เครื่องมือภายนอกมีเฉพาะ CLI
3. Developer ต้องการตรวจสอบ command-level behavior
4. Debugging ขั้นลึก
5. Compatibility testing

ดังนั้น:

Terminal สามารถมีอยู่ได้

แต่ Application ต้องสามารถทำงานหลักได้โดยไม่ต้องพึ่ง Terminal

---

6. NATIVE ENGINE PRINCIPLE

ทุกความสามารถที่ใช้บ่อยควรมี Native Engine หรือ Application API ของตัวเอง

ตัวอย่าง:

File

FileEngine

แทน:

cp
mv
rm
mkdir
cat
find

Git

GitEngine

แทน:

git status
git add
git commit
git push
git pull
git branch

Build

BuildEngine

แทน:

./gradlew build
./gradlew assembleDebug

Python

PythonRuntime

แทนการให้ AI คิด command เอง

Test

TestEngine

แทนการบังคับให้ AI เปิด terminal เพื่อรัน test

---

7. DO NOT REINVENT EXISTING RUNTIMES

ห้ามสร้าง Python ใหม่

ห้ามสร้าง Java ใหม่

ห้ามสร้าง Kotlin compiler ใหม่

ห้ามสร้าง Git ใหม่

ห้ามสร้าง Gradle ใหม่

ห้ามสร้าง CMake ใหม่

ห้ามสร้าง Android SDK ใหม่

ห้ามสร้าง Browser engine ใหม่โดยไม่มีเหตุผล

ให้ใช้ Runtime/SDK/Engine ที่พิสูจน์แล้ว

สิ่งที่เราสร้างคือ:

- Control Plane
- UI
- API
- Capability Layer
- Orchestration
- Resource Management
- Permission
- Verification
- Recovery
- State Management
- AI Integration

กล่าวง่าย ๆ:

«เราไม่ได้สร้างเครื่องยนต์ทุกตัวใหม่ เราสร้างระบบควบคุมที่ทำให้ AI และผู้ใช้ควบคุมเครื่องยนต์เหล่านั้นได้อย่างเป็นระบบ»

---

8. CAPABILITY RESOLVER

เป็นหัวใจสำคัญระหว่าง AI กับ Tools/Engines

ตัวอย่าง:

AI REQUEST
    ↓
Capability Resolver
    ↓
BUILD_ANDROID
    ↓
ค้นหา Engine
    ↓
BuildEngine
    ↓
ตรวจ Dependencies
    ↓
Runtime Manager

Resolver ต้องรู้:

- Capability ID
- Engine
- Runtime
- Version
- Availability
- Health
- Dependencies
- Permissions
- Resource Requirements
- Network Requirements
- Fallback
- Compatibility
- Verification Method

---

9. NATIVE-FIRST EXECUTION

ทุก Capability ต้องเลือกวิธีทำงานตามลำดับ:

1. Native Engine
2. Managed Runtime Adapter
3. External SDK/API
4. CLI Adapter
5. Ask User / Block

ตัวอย่าง:

BUILD_ANDROID
↓
BuildEngine
↓
Android Build Runtime
↓
ถ้า Native integration ใช้ไม่ได้
↓
Managed CLI Adapter
↓
Gradle

Terminal จึงเป็น Fallback ไม่ใช่ Default

---

10. CORE AI LOOP

AI ต้องทำงานตาม Loop:

OBSERVE
↓
UNDERSTAND
↓
PLAN
↓
RESOLVE CAPABILITY
↓
PRECHECK
↓
ACT
↓
OBSERVE RESULT
↓
VERIFY
↓
REFLECT
↓
CONTINUE / RECOVER / ASK
↓
COMPLETE

AI ห้าม:

ACT
↓
บอกเสร็จ

โดยไม่มี Verification

---

11. AI ORCHESTRATOR

เป็นศูนย์กลางควบคุม AI

รับผิดชอบ:

- รับ User Request
- วิเคราะห์ Intent
- สร้าง Plan
- สร้าง Task
- เลือก Agent
- เลือก Model
- เลือก Capability
- ตรวจ Permission
- ตรวจ Resource
- Execute
- Monitor
- Verify
- Recover
- Update Memory
- Update Checkpoint
- รายงานผล

AI Orchestrator ไม่ควรผูกติดกับ Terminal

---

12. TASK ENGINE

ทุกงานต้องเป็น Task

State:

CREATED
QUEUED
PLANNING
READY
RUNNING
WAITING
WAITING_USER
WAITING_PERMISSION
VERIFYING
RETRYING
COMPLETED
FAILED
BLOCKED
CANCELLED
PAUSED

Task ต้องมี:

- ID
- Goal
- Plan
- Steps
- Current Step
- Agent
- Model
- Capability
- Tool
- Permission
- Resource
- Result
- Verification
- Error
- Recovery
- Checkpoint
- Timestamp

---

13. AGENT SYSTEM

Agents เป็นผู้ปฏิบัติงานเฉพาะด้าน

ตัวอย่าง:

- Main Agent
- Planner Agent
- Coding Agent
- Browser Agent
- File Agent
- Build Agent
- Test Agent
- Debug Agent
- Git Agent
- Research Agent
- Verification Agent

Agent ห้าม bypass:

- Permission Manager
- Resource Manager
- Capability Registry
- Verification
- Audit

---

14. MODEL SYSTEM

Model เป็น Compute Provider

ไม่ใช่ Architecture Core

ระบบต้องรองรับ:

- Local Model
- External Provider
- API Provider
- Downloaded Model
- Bundled Bootstrap Model

Model Manager:

- Discover
- Install
- Download
- Pause
- Resume
- Cancel
- Update
- Remove
- Load
- Unload
- Health Check
- Benchmark

---

15. MODEL ROUTER

เลือก Model ตาม:

- Task
- Capability
- Context
- RAM
- CPU
- GPU/NPU
- Availability
- Performance
- Latency
- Cost
- Token Limit
- Previous Health

Fallback:

PRIMARY
↓
COMPATIBLE MODEL
↓
ALTERNATIVE PROVIDER
↓
LOCAL MODEL
↓
USER DECISION

---

16. RUNTIME MANAGER

Runtime Manager เป็นผู้ดูแล Runtime ทั้งหมด

ตัวอย่าง:

Python
Java
Kotlin
JavaScript
TypeScript
C
C++
Gradle
CMake
Android SDK
NDK
Git
Other Runtime

หน้าที่:

- Detect
- Install
- Update
- Remove
- Configure
- Version Management
- Health Check
- Compatibility Check
- Resource Estimate
- Start
- Stop
- Repair

---

17. LANGUAGE RUNTIME MANAGER

ต้องรองรับการทำงานโดยไม่บังคับผู้ใช้ใช้ Terminal

ตัวอย่าง Python:

Python Project
↓
Run
↓
Python Runtime
↓
Execute
↓
Output
↓
Verification

Java:

Java Project
↓
Build / Run / Test
↓
Java Runtime
↓
Result

Kotlin:

Kotlin Project
↓
Build / Run / Test

---

18. CODE ENGINE

Code Engine รับผิดชอบ:

- Create Code
- Read Code
- Modify Code
- Refactor
- Format
- Analyze
- Explain
- Diff
- Apply
- Reject
- Rollback

AI ต้องสามารถสร้าง Patch ก่อน Apply ได้

ไม่ควรแก้ไฟล์สำคัญแบบเงียบ ๆ โดยไม่มี State/History

---

19. FILE ENGINE

รับผิดชอบ:

- Browse
- Open
- Create
- Rename
- Copy
- Move
- Delete
- Duplicate
- Search
- Sort
- Filter
- Import
- Export
- Archive
- Extract
- Metadata

ต้องมี Sandbox และ Project Root

---

20. PROJECT ENGINE

Project เป็น Container หลักของงาน

ประกอบด้วย:

Project
├── Files
├── Git
├── Tasks
├── Agents
├── Memory
├── Models
├── Tools
├── Runtime
├── Build Config
├── Test Config
├── Browser Sessions
├── Checkpoints
└── Activity

---

21. BUILD ENGINE

Build Pipeline:

CONFIGURE
↓
PRECHECK
↓
RESOLVE DEPENDENCIES
↓
PREPARE RUNTIME
↓
COMPILE
↓
PACKAGE
↓
SIGN (ถ้าจำเป็น)
↓
VERIFY
↓
ARTIFACT

Build UI ต้องแสดง:

- Current Step
- Progress
- Duration
- Logs
- Warnings
- Errors
- Artifact
- Verification

Exit Code 0 ไม่ถือว่า Build สำเร็จโดยอัตโนมัติ

ต้องตรวจ Artifact

---

22. TEST ENGINE

รองรับ:

- Unit Test
- Integration Test
- UI Test
- Runtime Test
- Build Verification
- Regression Test
- Stress Test
- Low RAM Test
- Restart Test
- State Restoration Test

ผลลัพธ์:

PASS
FAIL
SKIPPED
BLOCKED

---

23. DEBUG ENGINE

ต้องรองรับ:

- Error Inspection
- Stack Trace
- Breakpoint
- Variable Inspection
- Execution State
- Log Inspection
- AI Debugging
- Retry
- Patch
- Re-run

AI ต้องสามารถ:

Error
↓
Analyze
↓
Identify Cause
↓
Propose Fix
↓
Apply Patch
↓
Test
↓
Verify

---

24. DEPENDENCY RESOLVER

เมื่อ Capability ต้องการ Dependency:

REQUEST
↓
Detect Requirement
↓
Check Installed
↓
Check Version
↓
Check Compatibility
↓
Estimate Resource
↓
Install / Update
↓
Verify

ห้ามให้ AI เดาสุ่ม dependency

---

25. PACKAGE MANAGER

รองรับ package ecosystem ตาม Runtime

ต้องมี:

- Search
- Install
- Update
- Remove
- Version
- Lock
- Dependency Tree
- Conflict Detection
- Security/Integrity Check

---

26. GIT ENGINE

Git ต้องถูกห่อด้วย Application API

รองรับ:

- Init
- Clone
- Status
- Add
- Commit
- Push
- Pull
- Fetch
- Branch
- Checkout
- Merge
- Rebase
- Diff
- Stash
- Reset
- Revert
- Tag
- Log
- Remote
- Conflict

AI ใช้:

GitEngine

ไม่ใช่คิดคำสั่ง Git เป็นหลัก

---

27. GITHUB ENGINE

รองรับ:

- Repository
- Branch
- Commit
- Pull Request
- Issue
- Actions
- Release
- File
- Remote

Secret/Token ต้องผ่าน Secret Manager

ห้าม Hardcode Token

---

28. BROWSER ENGINE

Browser เป็น Workspace จริง

รองรับ:

- Tabs
- Multiple Sessions
- Navigation
- Search
- URL
- Back
- Forward
- Reload
- Stop
- History
- Bookmark
- Download
- Upload
- Cookies
- Cache
- Local Storage
- Session Storage
- JavaScript
- DOM
- Accessibility Tree
- Screenshot
- Find
- Zoom
- User Agent
- Permissions
- Popup
- Redirect

AI สามารถควบคุม Browser ผ่าน Browser Capability

Human Authentication:

- Login
- Password
- OTP
- Payment
- Security Confirmation

ต้องสามารถส่งต่อให้มนุษย์ควบคุมได้

---

29. TERMINAL / CLI ADAPTER

Terminal ถูกจัดเป็น:

COMPATIBILITY LAYER

หน้าที่:

- Execute CLI-only tools
- Developer debugging
- Compatibility testing
- Legacy tools
- Advanced operations

แต่ไม่ใช่:

- AI Core
- Main Tool Gateway
- Required runtime
- Main development workflow

Terminal ต้องเรียกผ่าน:

CompatibilityEngine
↓
CLI Adapter
↓
Terminal Process

---

30. TOOL / CAPABILITY REGISTRY

ทุก Capability ต้องลงทะเบียน

ข้อมูล:

ID
Name
Category
Purpose
Engine
Runtime
Capabilities
Operations
Inputs
Outputs
Dependencies
Permissions
Resources
Network
Storage
Compatibility
Fallback
Verification
Recovery
Version
State

State:

UNAVAILABLE
INSTALLING
READY
STARTING
RUNNING
BUSY
WAITING
ERROR
RECOVERING
STOPPING
STOPPED

---

31. RESOURCE MANAGER

ต้องติดตาม:

- RAM
- CPU
- Storage
- Battery
- Network
- GPU
- NPU
- Thermal
- Processes
- Model Memory
- Workspace
- Ports

ก่อนงานหนัก:

PRECHECK
↓
ESTIMATE
↓
ALLOW / DEFER / DENY
↓
EXECUTE

ถ้า Resource ไม่พอ:

Reduce Load
↓
Switch Model
↓
Switch Runtime
↓
Pause
↓
Recover
↓
Ask User

---

32. PERMISSION MANAGER

Permission:

READ
WRITE
EXECUTE
NETWORK
SYSTEM
SECRET
DESTRUCTIVE

Sensitive Action ต้องถามผู้ใช้

ตัวอย่าง:

- Login
- Password
- OTP
- Payment
- Delete
- Production deployment
- Secret access

UI ต้องบอก:

WHAT
WHY
SCOPE
RISK
DURATION

ตัวเลือก:

ALLOW ONCE
ALLOW FOR TASK
DENY

---

33. VERIFICATION ENGINE

ห้ามเชื่อเพียงว่า Process จบ

Verification ต้องตรวจผลลัพธ์จริง

ตัวอย่าง:

Build

APK Exists?
Valid?
Correct Version?
Correct Package?
Readable?

Git

Expected Commit?
Expected Branch?
Clean/Expected Status?

File

File Exists?
Correct Content?
Correct State?

Browser

Expected URL?
Expected Page?
Expected Data?

Test

Expected Tests Passed?

---

34. RECOVERY ENGINE

Error Categories:

- Dependency
- Runtime
- Permission
- Network
- Resource
- Tool
- Model
- Data
- Build
- Test
- Git
- Browser
- Unknown

Recovery:

RETRY
↓
REINITIALIZE
↓
REPAIR
↓
SWITCH TOOL
↓
SWITCH MODEL
↓
REDUCE RESOURCE
↓
RESTORE CHECKPOINT
↓
ASK USER
↓
BLOCK

ห้าม Retry ไม่จำกัด

---

35. MEMORY SYSTEM

Memory:

Short-Term
Working Memory
Project Memory
Long-Term Memory
User Preferences

Memory ต้อง:

- Scoped
- Auditable
- Editable
- Deletable
- Privacy-aware

AI ห้ามสร้างความทรงจำที่ไม่มีหลักฐาน

---

36. EVENT BUS

ระบบทั้งหมดต้องสื่อสารผ่าน Event Architecture

ตัวอย่าง:

TaskCreated
TaskStarted
TaskCompleted
TaskFailed

ModelLoaded
ModelFailed

ToolReady
ToolError

BuildStarted
BuildCompleted
BuildFailed

TestStarted
TestCompleted
TestFailed

GitChanged

BrowserChanged

PermissionRequested
PermissionGranted

ResourceWarning

---

37. AI ACTIVITY CENTER

ผู้ใช้ต้องมองเห็นสิ่งที่ AI กำลังทำ

แสดง:

Task
Plan
Agent
Model
Capability
Tool
Input
Output
Verification
Decision
Error
Recovery
Permission
Result

ผู้ใช้สามารถ:

- Pause
- Resume
- Stop
- Inspect
- Approve
- Deny
- Take Control

---

38. USER INTERFACE PRINCIPLE

UI ต้องให้ความรู้สึก:

«"ฉันมี Intelligent Development Workspace ที่มี AI อยู่ข้างฉัน"»

ไม่ใช่:

«"ฉันมี Chatbot ที่เรียก Terminal ได้"»

---

39. MAIN UI

Bottom Navigation:

Home
Chat
Projects
Tasks
AI

Global Workspace สามารถเข้าถึง:

- Browser
- Editor
- Files
- Build
- Test
- Git
- Models
- Tools
- Agents
- Resources
- Settings

---

40. CHAT

Chat เป็น Control Surface ของ AI

Composer:

[ + ] [Mode/Model] [Message................] [Voice] [Send]

ระหว่าง AI ทำงาน:

[Pause] [Stop]

Message รองรับ:

- Markdown
- Code
- Files
- Images
- Links
- Task Cards
- Tool Results
- Verification
- Artifacts

---

41. AI TASK CARD

ทุกงานใหญ่ต้องแสดง:

Task Name
Current Step
Progress
Agent
Model
Capability
Status
Verification

Actions:

Pause
Stop
Details
Approve
Take Control

---

42. PROJECT WORKSPACE

Tabs:

Overview
Files
Chat
Tasks
Build
Test
Git

Project ต้องจำ State

เมื่อกลับเข้ามาต้องสามารถ Restore Workspace

---

43. CODE EDITOR UI

ต้องเป็น Editor จริง ไม่ใช่ TextArea ธรรมดา

รองรับ:

- Syntax Highlight
- Line Numbers
- Find
- Replace
- Go To Line
- Undo
- Redo
- Multi Cursor
- Selection
- Format
- Diff
- AI Edit
- Apply
- Reject
- Rollback

---

44. FILE MANAGER UI

รองรับ:

- List
- Grid
- Search
- Sort
- Filter
- Multi-select
- Context Menu
- Details
- Preview

ทุก Operation ต้องผ่าน File Engine

---

45. BUILD UI

แสดง:

BUILDING...

Preparing Runtime
Resolving Dependencies
Compiling
Packaging
Verifying

เมื่อสำเร็จ:

BUILD SUCCESS

Artifact
Version
Size
Build Time

[Install]
[Open]
[Share]
[Files]

เมื่อ Fail:

BUILD FAILED

[Fix with AI]
[View Error]
[Retry]
[Details]

---

46. TEST UI

แสดง:

Passed
Failed
Skipped
Duration

สามารถ:

- Open Failure
- AI Analyze
- Rerun
- Run Selected
- Run All

---

47. GIT UI

แสดง:

Current Branch
Changes
Commits
Branches
Diff
Remote

Actions:

Commit
Push
Pull
Fetch
Branch
Merge
Rebase

ไม่จำเป็นต้องเปิด Terminal

---

48. MODEL CENTER

แสดง:

- Installed Models
- Available Models
- Download
- Loading
- RAM Requirement
- Context Size
- Provider
- Token Limit
- Health
- Current Model
- Fallback

---

49. TOOL CENTER

แสดง:

Tool
Status
Version
Runtime
Dependencies
Resource Requirement
Health

Actions:

Install
Update
Repair
Enable
Disable
Test

---

50. AGENT CENTER

แสดง:

- Agent
- Role
- Model
- Tools
- Permissions
- Current Task
- State
- Memory

---

51. RESOURCE CENTER

แสดง:

RAM
CPU
Storage
Battery
Network
Thermal
Model Memory
Workspace

และบอกว่า:

Safe
Warning
Critical

---

52. SETTINGS

ต้องมี:

- Appearance
- AI
- Models
- Providers
- Runtime
- Tools
- Browser
- Projects
- Git
- GitHub
- Security
- Permissions
- Storage
- Network
- Notifications
- Developer Mode
- Compatibility / CLI
- Logs
- Backup
- Reset

---

53. DEVELOPER MODE

Developer Mode สามารถเปิดข้อมูลระดับลึก:

- Agent State
- Model Router
- Capability Resolver
- Tool Calls
- Runtime
- Event Bus
- Memory
- Resource Decisions
- Execution Logs
- Verification
- Recovery

แต่ผู้ใช้ทั่วไปไม่ควรถูกบังคับให้เห็นข้อมูลเหล่านี้

---

54. ARCHITECTURAL DEPENDENCY RULE

Dependency ต้องไหลแบบ:

UI
↓
Application
↓
Domain
↓
Engine / Capability
↓
Runtime
↓
External Implementation

ไม่อนุญาต:

UI
↓
Terminal
↓
ทุกอย่าง

และไม่อนุญาต:

AI
↓
Random Shell Command

---

55. SINGLE OWNER RULE

แต่ละหน้าที่ต้องมีเจ้าของเดียว

ตัวอย่าง:

File Operation → File Engine

Git → Git Engine

Build → Build Engine

Test → Test Engine

Model → Model Manager

Resource → Resource Manager

Permission → Permission Manager

Verification → Verification Engine

Recovery → Recovery Engine

Terminal → Compatibility Engine

ห้ามสร้างระบบ File Manager สองตัวที่ทำหน้าที่เหมือนกัน

ห้ามสร้าง Git Manager หลายตัว

ห้ามสร้าง Build System ซ้ำ

---

56. NO DUPLICATION RULE

ก่อนสร้าง Component ใหม่ AI ต้องตรวจ:

1. มี Component นี้แล้วหรือไม่
2. มี Engine ที่รับผิดชอบแล้วหรือไม่
3. มี Service ที่ทำหน้าที่เดียวกันหรือไม่
4. มี Interface อยู่แล้วหรือไม่
5. สามารถ Reuse ได้หรือไม่

ถ้ามีแล้ว:

«ห้ามสร้างซ้ำ»

---

57. CHECKPOINT DEVELOPMENT SYSTEM

การพัฒนาต้องแบ่งเป็น Checkpoint

AI ต้องทำทีละ Checkpoint เท่านั้น

Workflow:

READ ARCHITECTURE
↓
READ CURRENT PROGRESS
↓
SELECT ONE CHECKPOINT
↓
PLAN
↓
IMPLEMENT
↓
TEST
↓
VERIFY
↓
SAVE CHECKPOINT
↓
UPDATE PROGRESS
↓
REPORT
↓
STOP

คำว่า:

DONE

หมายถึง:

«Checkpoint ปัจจุบันเสร็จ»

ไม่ใช่:

«Project ทั้งหมดเสร็จ»

---

58. NEW CHECKPOINT STRUCTURE

ลำดับหลักต้องเปลี่ยนจาก Terminal-Centric เป็น Capability-Centric

FOUNDATION

CP-00 Architecture Lock

CP-01 Native Android Shell

CP-02 Core State + Event Bus

CP-03 Core Data Layer

CP-04 Resource Manager

CP-05 Permission + Security Foundation

AI CORE

CP-06 Native/Local AI Runtime

CP-07 Model Manager

CP-08 Model Router + Fallback

CP-09 Context + Memory

CP-10 AI Orchestrator

CP-11 Task Engine

CP-12 Agent Runtime

CAPABILITY PLATFORM

CP-13 Capability Registry + Gateway

CP-14 Coding Runtime Foundation

CP-15 Language Runtime Manager

CP-16 Dependency Resolver

CP-17 Package Manager

CP-18 Code Engine

CP-19 File Engine

CP-20 Project Engine

CP-21 Build Engine

CP-22 Test Engine

CP-23 Debug Engine

CP-24 Git Engine

CP-25 GitHub Engine

CP-26 Browser Engine

CP-27 Artifact Engine

CP-28 Verification Engine

CP-29 Recovery Engine

CP-30 Checkpoint/Rollback

COMPATIBILITY

CP-31 Compatibility Engine

CP-32 CLI Adapter

CP-33 Optional Terminal / Developer Workspace

Terminal ต้องอยู่หลัง Native Capability Architecture

USER EXPERIENCE

CP-34 Chat UI

CP-35 Home + Navigation

CP-36 Project Workspace

CP-37 AI Activity Center

CP-38 Models UI

CP-39 Tools UI

CP-40 Agents UI

CP-41 Browser UI

CP-42 File Manager UI

CP-43 Editor UI

CP-44 Build/Test UI

CP-45 Git/GitHub UI

CP-46 Settings + Security UI

CP-47 Cross-Tool Context

CP-48 Offline + Low Resource Mode

CP-49 Error/Empty/Loading/Permission UX Audit

FINAL INTEGRATION

CP-50 Security Audit

CP-51 Architecture Duplication Audit

CP-52 Integration Test

CP-53 Stress + Low RAM + Restart Test

CP-54 Release Candidate Audit

CP-55 Final Architecture Sign-Off

---

59. DEFINITION OF DONE

Checkpoint จะถือว่า DONE เมื่อ:

1. Implementation เสร็จ
2. Build ผ่าน
3. Unit/Relevant Tests ผ่าน
4. Integration ที่เกี่ยวข้องผ่าน
5. Runtime ตรวจสอบแล้ว
6. Verification ผ่าน
7. Error Path ตรวจสอบแล้ว
8. Resource Behavior ตรวจสอบแล้ว
9. Permission ตรวจสอบแล้ว
10. UI State ตรวจสอบแล้ว ถ้าเกี่ยวข้อง
11. ไม่มี Duplicate Responsibility
12. Documentation/Progress อัปเดต
13. Checkpoint Record ถูกบันทึก

---

60. AI DEVELOPMENT BEHAVIOR

AI Developer ต้อง:

- อ่าน Architecture ก่อน
- อ่าน Checkpoint Progress
- เข้าใจ Current State
- ทำเฉพาะ Checkpoint ปัจจุบัน
- ไม่กระโดด
- ไม่สร้างของซ้ำ
- ไม่เดา
- ไม่รายงานความสำเร็จปลอม
- ทดสอบจริง
- Verify จริง
- บันทึก Progress
- หยุดเมื่อ Checkpoint เสร็จ

ถ้าเจอ Blocker:

STOP
↓
REPORT BLOCKER
↓
WAIT

ถ้าเจอ Conflict:

STOP
↓
REPORT CONFLICT
↓
WAIT

ถ้าเจอ Permission ที่ต้องให้มนุษย์:

WAITING_PERMISSION

---

61. AI MUST NOT THINK TERMINAL-FIRST

ห้ามคิดแบบ:

"ต้องใช้คำสั่งอะไร?"

ให้คิดแบบ:

"ต้องการ Capability อะไร?"

ตัวอย่าง:

ต้องสร้างโฟลเดอร์

ไม่ใช่:

mkdir

แต่:

FileEngine.createDirectory()

ต้อง Commit

ไม่ใช่:

git add
git commit

แต่:

GitEngine.commit()

ต้อง Build

ไม่ใช่:

./gradlew assembleDebug

แต่:

BuildEngine.build()

ต้องรัน Python

ไม่ใช่:

python app.py

แต่:

PythonRuntime.execute()

Command อาจเกิดขึ้นภายใน Implementation แต่ AI ไม่ควรผูกกับ Command

---

62. UNIVERSAL EXECUTION CONTRACT

ทุก Engine ควรมีรูปแบบการทำงานมาตรฐาน:

REQUEST
↓
VALIDATE INPUT
↓
CHECK CAPABILITY
↓
CHECK PERMISSION
↓
CHECK RESOURCE
↓
PREPARE
↓
EXECUTE
↓
COLLECT RESULT
↓
VERIFY
↓
PERSIST STATE
↓
RETURN RESULT

Result ต้องมี:

success
status
output
error
warnings
artifacts
verification
duration
resourceUsage
logs

---

63. AI TOOL SELECTION

เมื่อ AI ต้องทำงาน:

Understand Task
↓
Identify Required Capability
↓
Find Registered Capability
↓
Check Availability
↓
Check Permission
↓
Check Resource
↓
Select Best Engine
↓
Execute
↓
Verify

ถ้าไม่มี Native Capability:

Capability Resolver
↓
Compatibility Engine
↓
CLI Adapter
↓
External CLI

ถ้ายังทำไม่ได้:

BLOCKED

ไม่ใช่เดาคำสั่งมั่ว

---

64. SECURITY PRINCIPLE

AI ไม่มีสิทธิ์โดยอัตโนมัติในการ:

- อ่าน Secret
- ใช้ Password
- ใช้ OTP
- จ่ายเงิน
- ลบข้อมูลสำคัญ
- Deploy Production
- เปลี่ยน Security Policy

ต้องผ่าน Permission Manager

ทุก Sensitive Action ต้อง Audit

---

65. RECOVERY PRINCIPLE

เมื่อ Tool ล้ม:

AI ไม่ควรจบทันที

ตัวอย่าง:

Build Failed
↓
Analyze Error
↓
Dependency Problem?
↓
Resolve Dependency
↓
Retry

ถ้า Runtime เสีย:

Repair
↓
Retry

ถ้า Model เสีย:

Fallback Model

ถ้า Resource ไม่พอ:

Reduce Load
↓
Switch Model
↓
Retry

ถ้ายังไม่ได้:

BLOCKED

---

66. BROWSER HUMAN HANDOFF

AI สามารถควบคุม Browser ได้

แต่ Authentication ที่มีความเสี่ยงต้องส่งให้มนุษย์:

AI
↓
Navigate
↓
Find Login
↓
WAITING_USER
↓
Human Login
↓
Human Complete OTP
↓
Return Control to AI
↓
Continue

---

67. OFFLINE / LOW RESOURCE MODE

แอปต้องยังทำงานพื้นฐานได้เมื่อ:

- Internet ไม่มี
- RAM ต่ำ
- Battery ต่ำ
- Model ใหญ่เกินไป
- Network ช้า

ระบบต้องเลือก:

Local Model
↓
Smaller Model
↓
Reduce Context
↓
Disable Heavy Features
↓
Queue Task

---

68. UI/UX RULE

ทุก Feature ต้องมี UI ที่สมบูรณ์

ห้ามสร้าง Feature แล้วให้ผู้ใช้ต้องใช้ Terminal เพื่อเข้าถึง

ตัวอย่าง:

ถ้ามี Git Engine:

ต้องมี Git UI

ถ้ามี Build Engine:

ต้องมี Build UI

ถ้ามี Test Engine:

ต้องมี Test UI

ถ้ามี Model Manager:

ต้องมี Model UI

ถ้ามี Runtime Manager:

ต้องมี Runtime UI

ถ้ามี Browser Engine:

ต้องมี Browser UI

---

69. UI DESIGN LANGUAGE

Style:

- Modern
- Premium
- Clean
- Professional
- Intelligent
- Calm
- Friendly

หลีกเลี่ยง:

- Hacker UI
- Cyberpunk overload
- Neon มากเกินไป
- Gradient มากเกินไป
- Glassmorphism มากเกินไป
- Clutter
- Terminal-centric layout

Design ต้องเหมือน Professional AI Development Workspace

---

70. RESPONSIVE DESIGN

รองรับ:

- Android Phone
- Tablet
- Desktop-sized layout

ต้องรองรับ:

- Rotation
- Process Death
- Background/Foreground
- State Restoration
- Low RAM
- Large Project
- Long Chat
- Long Running Task

---

71. MASTER ARCHITECTURAL RULE

กฎสำคัญที่สุด:

AI
ไม่ควรเป็น Terminal User

AI ต้องเป็น:

AI
↓
Orchestrator
↓
Capability
↓
Engine
↓
Runtime
↓
Execution
↓
Verification

Terminal เป็นเพียง:

Capability
↓
Compatibility Engine
↓
CLI Adapter
↓
Terminal

---

72. FINAL ARCHITECTURE

                        USER
                         │
                         ▼
                 ┌───────────────┐
                 │   ANDROID UI  │
                 └───────┬───────┘
                         │
                         ▼
                 ┌───────────────┐
                 │ AI ORCHESTRATOR│
                 └───────┬───────┘
                         │
          ┌──────────────┼──────────────┐
          ▼              ▼              ▼
       PLANNER         MEMORY        TASK ENGINE
          │              │              │
          └──────────────┼──────────────┘
                         ▼
                CAPABILITY RESOLVER
                         │
                         ▼
                TOOL/CAPABILITY REGISTRY
                         │
        ┌────────────────┼─────────────────┐
        ▼                ▼                 ▼
   CODE ENGINE      FILE ENGINE       PROJECT ENGINE
        │                │                 │
   BUILD ENGINE     TEST ENGINE       DEBUG ENGINE
        │                │                 │
   GIT ENGINE       GITHUB ENGINE      BROWSER ENGINE
        │                │                 │
   PACKAGE ENGINE   DEPENDENCY ENGINE  ARTIFACT ENGINE
        │                │                 │
        └────────────────┼─────────────────┘
                         ▼
                  RUNTIME MANAGER
                         │
       ┌─────────────────┼─────────────────┐
       ▼                 ▼                 ▼
    Python            Java/Kotlin       JS/TS
       │                 │                 │
       └─────────────────┼─────────────────┘
                         ▼
                COMPATIBILITY ENGINE
                         │
                         ▼
                    CLI ADAPTER
                         │
                         ▼
                     TERMINAL

---

73. THE CENTRAL IDEA

สถาปัตยกรรมนี้มีการเปลี่ยนแนวคิดจาก:

TERMINAL-CENTRIC DEVELOPMENT

ไปเป็น:

CAPABILITY-CENTRIC DEVELOPMENT

และเปลี่ยนจาก:

«"AI ต้องรู้ว่าต้องพิมพ์คำสั่งอะไร"»

เป็น:

«"AI ต้องรู้ว่าต้องใช้ความสามารถอะไร เพื่อให้บรรลุเป้าหมาย"»

และเปลี่ยนจาก:

«"ทุกอย่างต้องผ่าน Terminal"»

เป็น:

«"Native Engine เป็นทางหลัก และ Terminal เป็น Compatibility Layer"»

นี่คือหลักที่ต้องรักษาไว้ตลอดการพัฒนา

ห้าม Architecture ในอนาคตย้อนกลับไปเป็น Terminal-Centric โดยไม่ได้แก้ไข Master Architecture และได้รับการอนุมัติจากเจ้าของโปรเจกต์ก่อน