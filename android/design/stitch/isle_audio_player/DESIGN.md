---
name: Isle Audio Player
colors:
  surface: '#101419'
  surface-dim: '#101419'
  surface-bright: '#36393f'
  surface-container-lowest: '#0a0e13'
  surface-container-low: '#181c21'
  surface-container: '#1c2025'
  surface-container-high: '#262a30'
  surface-container-highest: '#31353b'
  on-surface: '#e0e2ea'
  on-surface-variant: '#b9cac5'
  inverse-surface: '#e0e2ea'
  inverse-on-surface: '#2d3136'
  outline: '#84948f'
  outline-variant: '#3b4a46'
  surface-tint: '#00dfc4'
  primary: '#70ffe5'
  on-primary: '#00372f'
  primary-container: '#00e5c9'
  on-primary-container: '#006155'
  inverse-primary: '#006b5d'
  secondary: '#44e2cd'
  on-secondary: '#003731'
  secondary-container: '#03c6b2'
  on-secondary-container: '#004d44'
  tertiary: '#d1ecff'
  on-tertiary: '#00354a'
  tertiary-container: '#8cd5ff'
  on-tertiary-container: '#005d7f'
  error: '#ffb4ab'
  on-error: '#690005'
  error-container: '#93000a'
  on-error-container: '#ffdad6'
  primary-fixed: '#41fcdf'
  primary-fixed-dim: '#00dfc4'
  on-primary-fixed: '#00201b'
  on-primary-fixed-variant: '#005046'
  secondary-fixed: '#62fae3'
  secondary-fixed-dim: '#3cddc7'
  on-secondary-fixed: '#00201c'
  on-secondary-fixed-variant: '#005047'
  tertiary-fixed: '#c4e7ff'
  tertiary-fixed-dim: '#7bd0ff'
  on-tertiary-fixed: '#001e2c'
  on-tertiary-fixed-variant: '#004c69'
  background: '#101419'
  on-background: '#e0e2ea'
  surface-variant: '#31353b'
typography:
  display-lg:
    fontFamily: Inter
    fontSize: 44px
    fontWeight: '700'
    lineHeight: 52px
    letterSpacing: -0.02em
  display-lg-mobile:
    fontFamily: Inter
    fontSize: 32px
    fontWeight: '700'
    lineHeight: 40px
    letterSpacing: -0.01em
  headline-lg:
    fontFamily: Inter
    fontSize: 28px
    fontWeight: '600'
    lineHeight: 36px
    letterSpacing: -0.01em
  headline-md:
    fontFamily: Inter
    fontSize: 22px
    fontWeight: '600'
    lineHeight: 28px
  headline-sm:
    fontFamily: Inter
    fontSize: 18px
    fontWeight: '600'
    lineHeight: 24px
  body-lg:
    fontFamily: Inter
    fontSize: 16px
    fontWeight: '400'
    lineHeight: 24px
  body-md:
    fontFamily: Inter
    fontSize: 14px
    fontWeight: '400'
    lineHeight: 20px
  body-sm:
    fontFamily: Inter
    fontSize: 12px
    fontWeight: '400'
    lineHeight: 16px
  label-lg:
    fontFamily: JetBrains Mono
    fontSize: 13px
    fontWeight: '500'
    lineHeight: 18px
    letterSpacing: 0.02em
  label-md:
    fontFamily: JetBrains Mono
    fontSize: 11px
    fontWeight: '500'
    lineHeight: 16px
    letterSpacing: 0.04em
  label-sm:
    fontFamily: JetBrains Mono
    fontSize: 10px
    fontWeight: '500'
    lineHeight: 14px
    letterSpacing: 0.05em
rounded:
  sm: 0.5rem
  DEFAULT: 1rem
  md: 1.5rem
  lg: 2rem
  xl: 3rem
  full: 9999px
spacing:
  gutter: 1rem
  gutter-sm: 0.5rem
  margin: 1rem
  margin-expanded: 1.5rem
  space-xs: 0.25rem
  space-sm: 0.5rem
  space-md: 1rem
  space-lg: 1.5rem
  space-xl: 2rem
---

## Brand & Style

This design system delivers an intimate, atmospheric listening sanctuary on Android. Built upon modern Material 3 fundamentals, it prioritizes a dark-first sonic environment where interface elements fade gracefully into the background, leaving focus purely on sound and album art. 

The aesthetic is characterized by deep volcanic obsidian, muted slate tones, and an electric mineral teal pulse. Tactile interactions evoke the physical sensation of precision audio hardware—smooth potentiometers, edge-lit glass dials, and micro-luminescent indicators—reimagined as a minimal, ultra-fluid digital surface. The emotional tone is meditative, focused, and acoustically pure.

## Colors

The palette is tuned specifically for OLED efficiency and low-glare nighttime playback.

- **Primary (`#00E5C9`)**: Electric cyan. Used strictly for active audio feedback, progress scrubbers, active playback states, and micro-luminescent badges.
- **Secondary (`#2DD4BF`)**: Mineral teal. Serves as a softer complementary accent for secondary toggles, volume fills, and active playlist indicators.
- **Tertiary (`#38BDF8`)**: Ethereal sky. Reserved for contextual metadata highlights, codec badges (FLAC, DSD), and ambient audio frequency visualizations.
- **Neutral (`#0E1217`)**: Deep obsidian base. Foundations scale upward through tonal zinc tiers:
  - Surface Floor: `#090C0F` (backdrop canvas)
  - Surface Level 1: `#0E1217` (default cards, background track listings)
  - Surface Level 2: `#151B22` (interactive sheets, bottom player bar)
  - Surface Level 3: `#1F2733` (dialogs, elevated popovers)
- **Text & Iconography**:
  - High-emphasis: `#F1F5F9` (slate 100)
  - Medium-emphasis: `#94A3B8` (slate 400)
  - Low-emphasis / Disabled: `#475569` (slate 630)

## Typography

Typography pairs the structural clarity of **Inter** for core UI, song names, and artist titles with the technical precision of **JetBrains Mono** for audio metrics, track durations, sample rates, and bit depths.

- Track titles use `headline-sm` or `headline-md` with semi-bold weights to retain legibility over dark backgrounds.
- Timestamps, bitrate counters (`96kHz / 24bit`), and playlist index numbers rely strictly on `label-*` roles set in JetBrains Mono to avoid layout shifts during real-time playback.
- Line heights remain tight to support dense music library parsing without visual fatigue.

## Layout & Spacing

The layout is built upon an Android-optimized 8dp grid system adhering to Material 3 motion and spatial standards.

- **Canvas & Grids**: Handheld layouts use a fluid single-column model bounded by a `margin` of `1rem` (16dp). Foldable and landscape devices expand to a 2-column asymmetric split (left pane: persistent abstract vinyl disc/playback controls, right pane: queue/lyrics) with `1.5rem` (24dp) gutters.
- **Rhythm**: Component interiors follow dense multi-stop padding: `space-xs` (4dp) for micro badge gaps, `space-sm` (8dp) for list item interleaves, and `space-md` (16dp) for main control cluster separations.
- **Touch Target Safeguard**: All interactive elements maintain a rigid minimum bounding box of 48×48dp, even when rendered visuals are significantly smaller (e.g., 6dp timeline scrubber thumbs).

## Elevation & Depth

Visual hierarchy is articulated through chromatic luminance and surface stacking rather than muddy drop shadows.

- **Tonal Elevation (M3)**: Instead of high-blur drop shadows, surfaces lift by sampling progressively lighter zinc tints (`#090C0F` → `#0E1217` → `#151B22` → `#1F2733`).
- **Subtle Borders**: Active containers and resting cards are defined by a hairline (1dp) low-contrast outline: `rgba(241, 245, 249, 0.08)`.
- **Soft Luminescent Glow**: Floating active elements (such as the main Play/Pause FAB or the current track progress head) emit an atmospheric radial aura: `0 0 20px rgba(0, 229, 201, 0.25)`.
- **Glass Shrouds**: Persistent bottom navigation bars and the collapsed Mini-Player utilize background blurs (`backdrop-filter: blur(16px)`) over `rgba(14, 18, 23, 0.75)` to let album artwork gently bleed through during scrolling.

## Shapes

This design system employs a **Pill-shaped (Level 3)** geometry to provide smooth, pebble-like surfaces that feel frictionless under thumb navigation.

- Full pills (`rounded-full` / 9999px) govern primary action buttons, filter tags, active track badges, and player progress caps.
- Elevated cards, modal bottom sheets, and the main disc viewport use `rounded-xl` (1.5rem to 2rem) corner treatments.
- Vinyl disc art embraces a pure circular mask (`50%` radius) complemented by micro concentric grooved borders to simulate physical records.

## Components

### Buttons & Mini Actions
- **Primary Play/Pause**: Floating 64×64dp circular or pill-shaped container filled with `#00E5C9`, displaying an obsidian glyph (`#090C0F`). Emits a soft cyan ambient glow when engaged.
- **Secondary Controls (Shuffle, Repeat, Next/Prev)**: 48×48dp borderless hit targets. Inactive icons sit at medium slate (`#94A3B8`); active states tint to `#00E5C9` with a subtle 4dp dot indicator beneath.

### Chips & Filter Pills
- Filter chips (e.g., "Artists", "Playlists", "Hi-Res") utilize pill containers with an obsidian base and a hairline `rgba(241, 245, 249, 0.1)` border. When selected, the fill transitions to `rgba(0, 229, 201, 0.12)` with a `#00E5C9` border and matching text.

### Tactile Sliders & Scrubbers
- Track scrubber and volume controls feature a 4dp track height (`rgba(241, 245, 249, 0.12)`) and a vibrant `#00E5C9` active progress fill.
- The thumb is an 8dp glow-illuminated pill that subtly expands to 14dp upon active press/drag, providing direct tactile visual feedback without obstructing the timecode labels.

### Lists & Queue Items
- Single-line and two-line track items use standard 56dp and 72dp heights.
- Selected / Currently Playing track features an animated 3-bar equalizer pulse in primary teal along with a `rgba(0, 229, 201, 0.06)` container tint. Left edge features a hairline teal stroke.

### Abstract Audio Disc (Player Centerpiece)
- The hero viewport houses a rotating or static virtual disc holding album imagery within a circular frame, bounded by concentric grooved acrylic rings (`1dp` stroke at 5% white opacity).
- Features dynamic ambient backlighting that extracts the dominant shade from the album sleeve and diffuses it outward onto the `#090C0F` surface canvas.

### Inputs & Search
- Rounded full-pill search field with a recessed surface color (`#090C0F`), slate placeholder text, clear button micro-interactions, and a hairline cyan focus ring.