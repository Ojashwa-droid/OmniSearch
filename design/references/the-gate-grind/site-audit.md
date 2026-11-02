# Site audit: The GATE Grind

Reference: [thegategrind.online](https://thegategrind.online/)  
Viewed: 2026-11-02

## Observed visual language

- **Editorial, high-contrast foundation:** white or warm-white surfaces, dark text, thin rules, generous breathing room, and large concise headings.
- **Technical accent layer:** a dark hero with bright cyan/blue emphasis, compact monospaced labels, grid-paper/coordinate graphics, and small numeric annotations.
- **Friendly study cues:** yellow sticky-note-like callouts, rounded white calls to action, and pastel note cards. The cards have a crisp dark offset shadow that makes them feel tactile.
- **Mixed typographic roles:** large clean sans-serif display text, readable sans-serif body copy, and compact mono-like labels for metadata. A script/handwritten treatment appears as a small accent in one graph section.
- **Structured color:** pastel mint, pink, yellow, peach, and pale blue/lilac are applied to individual note/card surfaces; they do not replace the neutral page foundation.

## Page and component patterns

1. **Sticky navigation** provides persistent access to the brand and theme control.
2. **Hero** pairs a concise headline with a rotating branch/topic word treatment, a distinctive pixel/block word, a small countdown note, and direct calls to action.
3. **Numbered feature notes** sit on a subtle grid-paper field. Cards are individually colored, slightly varied in placement, and visually click-like; a small instruction explains that notes navigate to sections.
4. **Technical explainer section** uses graph/coordinate visuals behind light cards, with a limited accent strip and short handwritten labels.
5. **Interactive learning areas** include tab-like selectors, quiz/demo affordances, and an FAQ accordion. The visible interaction model gives clear, compact controls instead of turning every section into a large panel.
6. **Theme control** switches between a bright neutral presentation and a dark presentation with vivid green technical grid accents and light cards.

## Interaction and motion observations

- The navigation remains available while browsing down the page.
- A theme switch changes the page between light and dark appearances.
- The hero's branch/topic word changes over time or as the page is explored; the exact trigger and timing were not measured.
- Feature notes and learning controls are presented as interactive entry points. Hover/focus feedback should be treated as a pattern to inspect and implement intentionally, not inferred as a precise copied animation.
- Video/demo controls are present. Their media was not inspected as part of this visual audit.

## Practical strengths

- Neutral structure keeps a long page calm while colorful modules create landmarks.
- A grid motif reinforces the technical subject without adding heavy decoration everywhere.
- Numbering and concise labels make sections easy to scan.
- Rounded actions soften an otherwise precise, ruled layout.
- Light and dark modes are designed as complete themes rather than a single background inversion.

## Items not verified

- Exact font files/family names and weights
- Exact color values, spacing scale, radii, and shadow dimensions
- CSS animation curves, durations, and reduced-motion handling
- Responsive breakpoints and small-screen behavior
- Whether the rotating hero word is time-driven, scroll-driven, or both

These require source-style inspection or measured captures before anyone treats them as implementation specifications.
