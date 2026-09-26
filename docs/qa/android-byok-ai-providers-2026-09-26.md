# Android BYOK AI — checkpoint และ QA เครื่องจริง (2026-09-26)

**สถานะ:** IMPLEMENTED IN FEATURE BRANCH, **ยังไม่ใช่ DONE ของคำขอทั้งหมด**; ตรวจ CI/เครื่องจริงก่อนอ้างว่าใช้ได้. Branch `feature/android-byok-multiprovider-ai-2026` เริ่มจาก `413c16f` (Qwen3-4B + Video Studio P0), ไม่ใช่ `main`; ห้ามเรียกว่า release.

## การเปลี่ยน scope โดยเจ้าของ

2026-09-26 เจ้าของยืนยันในคำถาม UI ว่าอนุญาต Android ติดต่อ API ภายนอก, เลือกต้องการโมเดล **ทุกประเภททันที**, และยอมให้สลับไปทุกผู้ให้บริการที่ผู้ใช้ตั้งค่าหลังยินยอมเรื่องข้อมูลและค่าใช้จ่าย. ดังนั้น local-only ที่เคยเลือกไม่ครอบคลุมฟีเจอร์ BYOK อีกต่อไป. ตัว AI ในเครื่องยังใช้ได้ตามเดิม; ไม่มี developer key หรือ backend/proxy ของเรา.

## สิ่งที่มีโค้ดจริงใน checkpoint นี้

- หน้า Models → **สมอง AI ผ่าน API (BYOK)**: เลือกผู้ให้บริการ, เพิ่ม/ลบคีย์หลายใบ (ไม่มีจำนวนสูงสุดที่ hardcode), รีเฟรชรายชื่อโมเดล, ค้นหา/กรองประเภท, dropdown แบ่งหน้าให้เลื่อนถึงทุกรุ่นที่ API ส่งกลับ, เลือกรุ่นและ provider หลักโดย **ไม่ต้องกรอก base URL หรือ model ID**.
- รายชื่อโมเดลอ่านสดจาก **ทุกคีย์ที่ตั้งค่า** แล้ว union รายการที่แต่ละคีย์มีสิทธิ์ผ่าน `/models` ของแต่ละเจ้า รวม pagination ตาม documented `nextPageToken` (Google), `last_id` (Anthropic), `next_page_token` (Cohere). หยุดเมื่อเจอ 429 ไม่วนคีย์เพื่อหลบ rate limit และระบุ catalog ว่าไม่ครบหากบางคีย์/หน้าอ่านไม่ได้. ไม่ตัดรายการประเภท image/audio/video/embedding/rerank ออกจาก **catalog**. รุ่นที่ไม่ทราบความสามารถแสดง `UNKNOWN`; ห้ามอ้างว่าใช้ได้กับสมอง AI เพียงเพราะอยู่ในรายการ.
- พอร์ตแชตของ Google `:generateContent`, Anthropic `/messages`, Cohere `/v2/chat`, และ OpenAI-compatible `/chat/completions` (OpenAI รุ่น reasoning ที่ระบุใช้ `/responses`) สร้างข้อความจริง; รองรับ image input ใน Google/Anthropic/OpenAI-compatible ในระดับ request adapter; ถ้าผู้ให้บริการไม่รองรับไฟล์รูปจะไม่ทิ้งไฟล์แล้วแกล้งเรียกเป็นข้อความ.
- ต่อเข้ากับ `ServiceLocator.setLlm`/`RoutedChatBrain` ที่สมอง AI ใช้อยู่ รวม LLM planner/re-planner และ subtitle LLM; AI ในเครื่องยังเป็นตัวแรก, ถ้าเครื่องโหลดโมเดลไม่ไหวจะลอง BYOK เมื่อผู้ใช้ตั้งค่าและยินยอมแล้ว. หน้า Models แสดงผู้ให้บริการ/โมเดล/ท้ายคีย์ที่ **ตอบสำเร็จล่าสุด** เพื่อแยก fallback ที่ถูกใช้จริง. ตัว router เดิมเคยอาจยิง manual provider ซ้ำสองครั้งเมื่อผิดพลาด; แก้เพื่อป้องกันคิดเงินซ้ำ.
- คีย์เข้ารหัส AES-256-GCM ด้วย **Android Keystore**; ciphertext อยู่ใน `noBackupFilesDir`, ไม่ใช่ APK/Git/log/Android Auto Backup. ข้อความสถานะแสดงเพียงท้ายคีย์ 4 ตัว. ล้างข้อมูล/ถอนติดตั้งแล้วคีย์หาย; ต้องกรอกใหม่. Consent การส่ง prompt/บริบทไป API และค่าใช้จ่ายปิดโดยปริยายและถูกรีเซ็ตเมื่อเพิ่ม/ลบคีย์หรือเปลี่ยนโมเดล/ผู้ให้บริการหลัก.
- ส่งเฉพาะ HTTPS ไปยัง host ที่กำหนดไว้ล่วงหน้า, **ห้ามตาม redirect ที่อาจส่ง token ข้าม host**. ไม่ลองส่ง prompt ซ้ำเมื่อ timeout, HTTP 5xx (รวม 502/503/504) หรือ 2xx อ่านไม่ได้ (อาจถูกคิดเงินแล้ว). เมื่อ HTTP 401 หรือ 404 (รุ่นไม่มีสิทธิ์สำหรับคีย์นี้) ลองคีย์ถัดไปของเจ้าเดิม; 403 หยุดไม่หลบ policy; 429 ไม่วนคีย์ของเจ้าเดิมเพื่อหลบโควตา แต่ข้ามไปเจ้าอื่นที่ยินยอม; 402 ข้ามไปเจ้าอื่น. คำตอบ 400 ไม่ส่งซ้ำ.

## Provider ที่มี adapter + catalog URL ใน checkpoint นี้

| Provider | Catalog endpoint | Chat endpoint | หมายเหตุ |
|---|---|---|---|
| OpenAI | `api.openai.com/v1/models` | `/v1/chat/completions` หรือ `/v1/responses` บางรุ่น | `/models` คืนแค่ชื่อ/เจ้าของ; ไม่ยืนยันราคา/ความสามารถทุกตัว |
| Google Gemini Developer API | `generativelanguage.googleapis.com/v1beta/models` | `models/{id}:generateContent` | ไม่ใช่ Vertex AI ซึ่งต้องใช้ project/location/บัญชีเพิ่มเติม |
| Anthropic | `api.anthropic.com/v1/models` | `/v1/messages` | paginated |
| OpenRouter | `openrouter.ai/api/v1/models` | `/api/v1/chat/completions` | catalog รวมโมเดลของหลายเจ้าผ่าน aggregator; ราคาอาจเปลี่ยน |
| Hugging Face Inference Providers | `router.huggingface.co/v1/models` | `/v1/chat/completions` | รายชื่อเฉพาะ *chat models ที่ Inference Providers เปิดให้บริการ* ไม่ใช่โมเดลทั้งหมดใน HF Hub |
| Perplexity Agent API | `api.perplexity.ai/v1/models` | `/v1/responses` | ใช้ Responses protocol ของ Agent API; ไม่ใช่ Sonar chat endpoint เดิม |
| Groq | `api.groq.com/openai/v1/models` | `/openai/v1/chat/completions` | model entitlements เปลี่ยนได้ |
| Together AI | `api.together.ai/v1/models` | `/v1/chat/completions` | บางชนิดโมเดลไม่ได้รองรับ chat |
| Mistral AI | `api.mistral.ai/v1/models` | `/v1/chat/completions` | capability metadata เมื่อ API ส่งมา |
| DeepSeek | `api.deepseek.com/models` | `/chat/completions` | รุ่น/ราคาเปลี่ยนตาม API |
| xAI | `api.x.ai/v1/models` | `/v1/chat/completions` | catalog อาจรวม image/video |
| Cerebras | `api.cerebras.ai/v1/models` | `/v1/chat/completions` | provider-specific entitlements |
| Cohere | `api.cohere.com/v1/models` | `/v2/chat` | catalog รวม chat/embed/rerank |
| Novita AI | `api.novita.ai/openai/v1/models` | `/openai/v1/chat/completions` | model list เฉพาะ LLM ตาม docs |
| SiliconFlow | `api.siliconflow.cn/v1/models` | `/v1/chat/completions` | รายชื่อรวมหลายประเภท, โมเดลบางชนิดต้อง endpoint อื่น |
| SambaNova Cloud | `api.sambanova.ai/v1/models` | `/v1/chat/completions` | SambaCloud key-only; ไม่ใช่ dedicated SambaStack ที่ต้องใช้ endpoint เฉพาะ |

หลักฐาน URL/รูปแบบ API: [OpenAI list](https://platform.openai.com/docs/api-reference/models/list), [Gemini list](https://ai.google.dev/api/models), [Anthropic list](https://docs.anthropic.com/en/api/models-list), [OpenRouter models](https://openrouter.ai/docs/guides/overview/models), [Hugging Face router](https://huggingface.co/docs/inference-providers/en/hub-api), [Perplexity compatibility](https://docs.perplexity.ai/docs/agent-api/openai-compatibility), [Groq list](https://console.groq.com/docs/api-reference), [Together list](https://docs.together.ai/reference/models), [Mistral models](https://docs.mistral.ai/api/endpoint/models), [DeepSeek models](https://api-docs.deepseek.com/api/list-models/), [xAI models](https://docs.x.ai/developers/rest-api-reference/inference/models), [Cerebras models](https://inference-docs.cerebras.ai/api-reference/models), [Cohere models](https://docs.cohere.com/reference/list-models), [Novita models](https://docs.novita.ai/api-reference/model-apis-llm-list-models), [SiliconFlow models](https://docs.siliconflow.cn/cn/api-reference/models/get-model-list), [SambaNova models](https://docs.sambanova.ai/docs/en/integrations/make).

## ข้อจำกัดที่ห้ามอ้างว่าเสร็จ

- **ยังไม่มี adapter สร้างภาพ/เสียง/วิดีโอ/embedding/rerank ผ่าน BYOK**. Dropdown แสดงชื่อประเภทเหล่านี้จริงแต่ `HostedLlmProvider` *ไม่* เรียกเป็นแชตและไม่ทำปุ่มหลอก. ผู้ใช้เลือก `all_models_now` จึงยังไม่ผ่านเกณฑ์คำขอโดยรวม; ต้องทำแยก modality/capability และเชื่อม output files/async jobs ลง Video Studio ใน checkpoint ถัดไปเมื่อรอบนี้ทดสอบผ่าน. ตัวอย่างเหตุผลที่ทำเป็น endpoint เดียวไม่ได้: [OpenAI speech ส่งไฟล์เสียง](https://platform.openai.com/docs/api-reference/audio/createSpeech), [Together image ใช้ `/images/generations`](https://docs.together.ai/reference/post-images-generations), [Google embeddings ใช้ `:embedContent`](https://ai.google.dev/gemini-api/docs/embeddings), [Cohere rerank ใช้ `/v2/rerank`](https://docs.cohere.com/reference/rerank), [Google Omni video ใช้ `/interactions` และงาน async](https://ai.google.dev/gemini-api/docs/omni).
- **ไม่มีหลักประกันว่าโมเดลครบทุกผู้ให้บริการ/ทุกรุ่นทั่วโลก**. ดึงได้เฉพาะรุ่นที่ list API เปิดเผยสำหรับคีย์นั้น (บางเจ้าไม่คืนสิทธิ์/ราคา, บางรุ่น private/region-bound หรือเปลี่ยน endpoint); โมเดลประเภทอื่นที่ OpenAI API ไม่ระบุ capability ชัดเจนถูกจัด UNKNOWN, ไม่ใช่ข้อมูลที่มั่นใจ. Catalog >100 หน้า/ผลลัพธ์เกิน 16 MiB ล้มเหลว/แสดง incomplete อย่างตรงไปตรงมา.
- Fireworks account models ต้องใช้ `account_id`; Azure OpenAI ต้องใช้ resource/deployment; Vertex AI ต้องใช้ project/region/OAuth — **ไม่ใช่แค่ API key**. ยังไม่ใส่ชื่อผู้ให้บริการเหล่านี้เป็นปุ่มลวง.
- ไม่ค้ำประกันการใช้งานฟรี, รายชื่อรุ่นใน catalog ไม่เท่ากับสิทธิ์เรียกใช้, และค่า API/โควตาขึ้นอยู่กับผู้ให้บริการ. ไม่สลับคีย์เพื่อหลบ rate limit/safety policy.
- ยังไม่มี UI เพื่อจัด priority ระดับรายคีย์/ทั้ง 16 เจ้า (provider หลักเลือกได้, ที่เหลือตามรายการ built-in); ยังไม่เรียก test จริงด้วยคีย์ของเจ้าของหรือวัด latency/cost. รองรับเครื่อง arm64 Android ตาม APK ปัจจุบัน; โปรดทดสอบกับเครื่อง RAM ต่ำ.

## QA เครื่องจริงที่เจ้าของควรทำ (อย่าส่ง API key ให้เอเจนต์)

1. สำรองโปรเจกต์ก่อนอัปเดต debug APK; เปิด Models ดูตัว AI ในเครื่องยัง OFFLINE ตาม RAM ต่ำ แต่ BYOK มีให้ใช้.
2. เลือก Google Gemini / OpenAI; ใส่คีย์ **ในแอป** แล้วรอรายชื่อโมเดลจริง; ค้นหา/เปลี่ยนหน้า dropdown, ตรวจว่ารายชื่อ image/audio/embedding ไม่ถูกซ่อน และไม่ถูกเข้าใจว่า chat ได้.
3. เลือกโมเดลที่เข้ากับแชต, ติ๊กยินยอม, คุยกับ AI จริงที่ Chat; ตรวจชื่อ provider ที่ตอบ, ทดลองแปลซับด้วย LLM. เช็กยอดใช้งาน/ค่าใช้จ่ายใน dashboard ของผู้ให้บริการเอง.
4. ในบัญชีทดสอบแยก ให้เพิ่มคีย์ invalid **ก่อน** แล้ว valid และดู fallback 401 / ชื่อผู้ให้บริการที่ตอบล่าสุด (ระวังคำขอที่คิดเงินจริง); เพิ่มอีก provider, เลือกโมเดลที่แต่ละเจ้า, ยืนยัน consent แล้วลอง fallback บนคีย์ใช้ไม่ได้; ไม่ทดสอบด้วยการฝืนโควตา/นโยบาย.
5. ปิดแอปเปิดใหม่: คีย์/รุ่นที่เลือกควรยังอยู่, ไม่มีคีย์เต็มบน UI/log; ลบคีย์/เปลี่ยนโมเดลแล้วต้องยินยอมใหม่. ทดสอบเครือข่ายหาย: แอปแจ้งข้อผิดพลาด ไม่ยิงซ้ำโดยพลการ.

**CI compile/test ≠ E2E Android.** อย่ารวมเข้า main หรือแจก release ก่อน QA, อย่าขอคีย์ผู้ใช้ในแชทหรือฝังไว้ในโค้ด.
