# Design System (normative tokens)

Master spec §4–9, §68–69, §72. When a screen spec omits a value, use these.

## Colors

Dark: bg-primary #0B0D10, bg-secondary #11151A, surface #171B21,
surface-elevated #1D222A, surface-active #252B34, text #F5F7FA / #A7AFBA /
#737C88, border #2A3038, divider #222831, accent #6C8CFF / strong #5275FF,
success #32D583, warning #F5B942, error #FF5C6C, info #5BA7FF.
Light: bg #FFFFFF / #F7F8FA, surface #FFFFFF / elevated #F2F4F7, text
#15181D / #5E6672 / #8A929D, border #DDE1E6, accent #4F6FFF,
success #19A974, warning #D99A00, error #D92D3A, info #2878D7.

## Typography (system/Roboto-compatible)

Display 28, Headline 24, Title 20, TitleSmall 18, BodyLarge 16, Body 15,
BodySmall 14, LabelLarge 14, Label 13, Caption 12 (sp). Chat message
15–16sp, line height ~1.45–1.6. Minimum 12sp.

## Spacing / radius / elevation

4dp grid: 4/8/12/16/20/24/32/40/48/56/64. Screen horizontal padding 16dp
(24–32dp large workspace). Radius: control 8, input 12, card 14, dialog 20,
bottom sheet 24, composer 24, large workspace 16. Elevation: card 1–3,
dialog/sheet 6–12, floating 4–8. Dark UI prefers surface contrast + border
over shadows.

## Touch targets

Minimum 44×44dp; preferred 48×48dp. Icon 20–24dp inside 48dp area.

## Component states (§68)

- Buttons: default / pressed / focused / disabled / loading / success / error.
- Inputs: empty / focused / filled / error / disabled.
- Cards: default / selected / disabled / loading / error.

## Screen states (§67)

INITIAL / LOADING / READY / EMPTY / EDITING / PROCESSING / SUCCESS / ERROR /
OFFLINE / PERMISSION_REQUIRED / CONFIRMATION_REQUIRED. Every screen defines
all applicable states — READY alone is not acceptable.

## Animation (§69)

Fast 120–160ms, normal 180–250ms, complex 250–350ms. Animate navigation,
state change, expansion, selection, loading, completion only.

## Implementation

`ui/designsystem`: `AicodeTheme` (dark/light palettes above), `Spacing`
(4dp grid), `AicodeTopBar` (56dp), `AicodeButton` (48dp target),
`AicodeTextField` (12dp radius), `AicodeCard` (14dp), `AicodeDialog` (20dp),
`AicodeBottomSheet` (24dp), `StateViews` (loading skeleton / empty /
error w/ [View details] [Retry] / offline / permission / confirmation).
