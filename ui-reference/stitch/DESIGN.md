---
name: LedgerPay Simulator
colors:
  surface: '#f7f9ff'
  surface-dim: '#d5dae2'
  surface-bright: '#f7f9ff'
  surface-container-lowest: '#ffffff'
  surface-container-low: '#eff4fc'
  surface-container: '#e9eef6'
  surface-container-high: '#e3e9f0'
  surface-container-highest: '#dde3eb'
  on-surface: '#161c22'
  on-surface-variant: '#414844'
  inverse-surface: '#2b3137'
  inverse-on-surface: '#ecf1f9'
  outline: '#717974'
  outline-variant: '#c1c8c3'
  surface-tint: '#426657'
  primary: '#3f6355'
  on-primary: '#ffffff'
  primary-container: '#587c6d'
  on-primary-container: '#f5fff8'
  inverse-primary: '#a8cfbd'
  secondary: '#71585f'
  on-secondary: '#ffffff'
  secondary-container: '#f9d7e0'
  on-secondary-container: '#755c63'
  tertiary: '#2d6087'
  on-tertiary: '#ffffff'
  tertiary-container: '#4879a1'
  on-tertiary-container: '#fdfcff'
  error: '#ba1a1a'
  on-error: '#ffffff'
  error-container: '#ffdad6'
  on-error-container: '#93000a'
  primary-fixed: '#c4ebd9'
  primary-fixed-dim: '#a8cfbd'
  on-primary-fixed: '#002116'
  on-primary-fixed-variant: '#2a4d40'
  secondary-fixed: '#fbdae3'
  secondary-fixed-dim: '#debec7'
  on-secondary-fixed: '#29161d'
  on-secondary-fixed-variant: '#574148'
  tertiary-fixed: '#cde5ff'
  tertiary-fixed-dim: '#9bcbf8'
  on-tertiary-fixed: '#001d32'
  on-tertiary-fixed-variant: '#104a70'
  background: '#f7f9ff'
  on-background: '#161c22'
  surface-variant: '#dde3eb'
typography:
  headline-lg:
    fontFamily: Inter
    fontSize: 32px
    fontWeight: '700'
    lineHeight: 40px
    letterSpacing: -0.02em
  headline-md:
    fontFamily: Inter
    fontSize: 24px
    fontWeight: '600'
    lineHeight: 32px
    letterSpacing: -0.01em
  headline-sm:
    fontFamily: Inter
    fontSize: 20px
    fontWeight: '600'
    lineHeight: 28px
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
  label-md:
    fontFamily: Inter
    fontSize: 13px
    fontWeight: '500'
    lineHeight: 16px
    letterSpacing: 0.03em
  data-mono:
    fontFamily: JetBrains Mono
    fontSize: 13px
    fontWeight: '400'
    lineHeight: 20px
  headline-lg-mobile:
    fontFamily: Inter
    fontSize: 24px
    fontWeight: '700'
    lineHeight: 32px
rounded:
  sm: 0.125rem
  DEFAULT: 0.25rem
  md: 0.375rem
  lg: 0.5rem
  xl: 0.75rem
  full: 9999px
spacing:
  base: 4px
  xs: 8px
  sm: 12px
  md: 20px
  lg: 32px
  xl: 48px
  container-max: 1440px
  gutter: 24px
---

## Brand & Style

The design system is built on a **Modern Minimalist** aesthetic with **Tactile** nuances, specifically tailored for a developer-centric payment gateway simulator. The goal is to transform complex financial data into an approachable, high-clarity workspace that feels less like a bank and more like a high-end productivity tool.

The visual language emphasizes:
- **High Information Density:** Utilizing tight vertical rhythm and generous horizontal space to display complex transaction flows.
- **Approachable Professionalism:** Steering away from "Financial Blue" in favor of organic, muted tones to reduce user anxiety during debugging and testing.
- **Clarity & Logic:** Using distinct surface containers and "console" styles to separate the user interface from machine-readable data.

## Colors

The palette is anchored by **Muted Sage (#6b9080)**, which provides a calm, stable primary touchpoint for active states and primary buttons. The background uses a **Warm Off-White (#faf9f6)** to prevent the "screen fatigue" common in stark white developer tools.

- **Primary:** Used for key actions, progress indicators, and active workflow steps.
- **Accents:** Lavender is reserved for secondary developer actions (e.g., "Copy API Key"), while Sky Blue is used for informational tooltips.
- **Data Surfaces:** Pure white surfaces sit atop the warm background, delineated by subtle 1px borders rather than heavy shadows.
- **Status Tones:** Success, failure, and pending states use desaturated versions of green, coral, and amber to maintain the "lightweight" feel without sacrificing accessibility.

## Typography

This design system utilizes **Inter** for its exceptional legibility in data-heavy environments. A secondary monospaced font, **JetBrains Mono**, is introduced specifically for API responses, payload logs, and transaction IDs to distinguish "system data" from "interface text."

- **Hierarchy:** Use Bold (700) for page titles and section headers to anchor the eye.
- **Labels:** Medium (500) weight is used for form labels and table headers, often paired with a slight letter spacing increase for clarity at small sizes.
- **Data:** All numerical transaction data should prioritize tabular lining to ensure columns of figures align perfectly.

## Layout & Spacing

The layout follows a **Desktop-First Fixed Grid** philosophy to ensure the simulation cockpit remains stable during complex testing.

- **Main Grid:** A 12-column grid with a 1440px max-width.
- **Sidebar:** A fixed 280px navigation rail on the left.
- **Contextual Panels:** The right-hand "Console" panel for API logs should occupy 4 columns (or a fixed 400px) to allow for "side-by-side" debugging.
- **Vertical Rhythm:** Use a 4px baseline. Components like buttons and inputs use `sm` (12px) padding, while page sections use `lg` (32px) to provide breathing room between disparate data sets.

## Elevation & Depth

This design system uses **Low-Contrast Outlines** and **Tonal Layers** rather than traditional elevation.

- **Base Level:** Background (#faf9f6) is the foundation.
- **Level 1 (Cards):** White surfaces with a 1px solid border (#e9ecef). Use a very soft, diffused shadow (`0 2px 4px rgba(0,0,0,0.02)`) only to separate cards from the background.
- **Level 2 (Modals/Popovers):** Standard white surfaces with a more pronounced shadow (`0 10px 25px rgba(0,0,0,0.05)`) to indicate temporary interaction.
- **Console Depth:** System logs and code blocks should be slightly inset using a subtle inner shadow or a darker neutral background (#f1f3f5) to feel "contained" within the interface.

## Shapes

The shape language is **Soft (0.25rem)**, providing a professional yet modern feel. 

- **Components:** Buttons, inputs, and tags use the 4px (0.25rem) radius.
- **Containers:** Large layout blocks and cards use `rounded-lg` (8px/0.5rem) to soften the overall architecture of the high-density layout.
- **Connectors:** Lines in the workflow steps should have 2px rounded caps to match the stroke weight of icons.

## Components

### Buttons & Inputs
- **Primary Button:** Solid Sage Green with white text. No gradient.
- **Secondary Button:** Ghost style with Sage Green border and text.
- **Inputs:** White background, 1px Gray border, focusing to a 2px Sage Green ring.

### Status Badges
- **Success:** Soft Mint background with Dark Green text.
- **Failure:** Soft Coral background with Dark Red text.
- **Pending:** Soft Amber background with Brown text.
- All badges should use `label-md` typography for high legibility.

### Workflow Steps
- Visualized as a sequence of connected horizontal blocks. 
- **Completed steps** use a Sage Green checkmark icon.
- **Active steps** use a Sage Green border and Bold text.
- **Inactive steps** use light gray text and dashed borders.

### Console Panels
- Used for API responses. Dark charcoal background (#212529) with Sage Green or Sky Blue syntax highlighting.
- Use `data-mono` font. Header of the console should include a "Copy" button in the Lavender accent color.

### Timeline Feed
- A vertical line (2px, gray) connecting small circular nodes.
- Each node represents a system event. Use color-coded nodes (success/error) to allow users to scan the history of the simulation session quickly.