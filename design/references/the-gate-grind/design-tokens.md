# Design tokens and visual observations

This file separates **verified project values** from **visual estimates** of the reference site. The reference observations are descriptive, not production-ready hex values.

## OmniSearch: current confirmed foundation

The current app stylesheet defines these values in `src/main/resources/static/css/omnisearch.css`:

| Role | Current value |
| --- | --- |
| Paper | `#f3eee4` |
| Raised paper | `#faf6ee` |
| Ink | `#1c1915` |
| Secondary ink | `#5c564c` |
| Muted ink | `#8a8378` |
| Rule | `#d6cfc2` |
| Strong rule | `#c4bbab` |
| Accent red | `#b42318` |
| Dark accent red | `#8f1c14` |
| Accent wash | `#f4e4e1` |
| Clip surface | `#fffdf8` |
| Typography | IBM Plex Sans, including the UI, mono, and article roles |

These are the app's existing design decisions, not values sampled from The GATE Grind.

## Reference site: visual estimates only

| Role | Observation | Confidence |
| --- | --- | --- |
| Base surface | White to warm off-white | Visual estimate; exact value unknown |
| Main text | Near-black | Visual estimate; exact value unknown |
| Hero | Very dark field with a bright cyan/blue highlight | Visual estimate; exact value unknown |
| Technical graph | Green/teal linework on a darker field in one section/theme | Visual estimate; exact value unknown |
| Note colors | Muted mint, pink, yellow, peach, and pale blue/lilac | Approximate color family only |
| Borders | Thin dark or neutral outlines | Weight/value not measured |
| Feature-card shadow | Hard, dark offset shadow | Offset/size not measured |
| Type roles | Display sans, body sans, compact mono-like labels, occasional script accent | Font families unverified |

## Candidate semantic tokens for a future app adaptation

These are role names, not values to apply automatically:

```css
--evidence-mint: /* choose and verify if adopted */;
--evidence-rose: /* choose and verify if adopted */;
--evidence-amber: /* choose and verify if adopted */;
--evidence-blue: /* choose and verify if adopted */;
--technical-grid: /* low-contrast grid line */;
--focus-ring: /* visible keyboard focus */;
```

Keep these accents subordinate to OmniSearch's paper/ink/red palette. Validate contrast before selecting actual values.
