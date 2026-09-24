# Tool Layer

Master spec §30, §82–83.

## Contract

Single entry: `ToolGateway.call(ToolCall)` → registry → capability
validation → permission gate → executor → audit → bus event.
Every AI-invoked application action flows through this contract.

## Guarantees

- Schema: `ToolCallValidator` (capability exists, required args,
  preconditions) with suggestion errors + retry budget (3).
- Permission: autonomy levels ASK_ALWAYS / AUTO_SAFE / AUTO_ALL; dangerous
  actions surface explicit approval UI (§31).
- Audit: every call appended (actor, action, tool, detail, allowed).
- Observability: `ToolTracer` retrieve → decide → validate → result.

## UI automation fallback (§83)

Where no tool API exists (external sites/apps), AI may use accessibility /
DOM / browser automation (click, scroll, type, select). Human required for
authentication, OTP, CAPTCHA, sensitive authorization.

## Application tool surfaces (existing, preserved per §81)

browser.*, media.* (timeline/project/asset/render/text), image.*, audio.*,
files.*, editor.*, terminal.*, git.*, subtitle.*, render.*, memory.*,
skill.*, model.*, debug.*, tasks via TaskEngine.
