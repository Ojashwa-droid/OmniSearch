# Adapting selected patterns to OmniSearch

## Keep as the product foundation

- Preserve OmniSearch's newsroom/article layout, paper background, ink typography, IBM Plex Sans, red citation markers, source cards, and conversation flow.
- Keep answers readable as long-form articles and keep the persistent composer and chat history/source behavior intact.
- Do not recreate The GATE Grind's brand, hero copy, logo, study content, artwork, or exact page composition.

## Patterns worth borrowing selectively

### 1. Pastel evidence accents

Use a restrained pastel tint to distinguish source groups, research status, or small metadata chips. Keep source titles and URLs legible, preserve numbered citations, and do not assign meaning through color alone. The core source cards can remain neutral; pastel can be a small edge, label, or selected-state treatment.

### 2. Technical grid as a rare surface

Use a very low-contrast grid only behind a purposeful technical visualization, search status graphic, or empty-state illustration. Avoid adding a grid behind every answer; long-form reading should retain the calm paper surface.

### 3. Tactile emphasis for a few actions

Consider a crisp offset shadow for a primary action or a small featured module. Keep ordinary source cards and article blocks quiet, so emphasis remains meaningful and the layout does not become noisy.

### 4. Compact technical labels

Use short uppercase labels and restrained mono-like numerals for metadata only where they aid scanning. IBM Plex Sans remains the consistent family; no extra script/display font is needed to borrow the visual idea.

### 5. Short, purposeful motion

Use brief transitions for opening the session source panel, changing chat, and loading state. Respect `prefers-reduced-motion`, keep keyboard focus visible, and do not animate streaming answer text in a way that makes reading difficult.

## Suggested application order

1. Define semantic color roles and check contrast against the existing paper/ink system.
2. Prototype one source panel or empty/loading state with a subtle grid or pastel accent.
3. Review at desktop and mobile widths, including expanded source lists and long answers.
4. Keep the treatment only if it improves hierarchy without competing with article content.

This is inspiration for future, separately approved UI work. It does not authorize changing the current app styles by itself.
