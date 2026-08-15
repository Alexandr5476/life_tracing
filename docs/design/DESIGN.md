---
name: Calibrated Precision
colors:
  surface: '#f7f9ff'
  surface-dim: '#d7dadf'
  surface-bright: '#f7f9ff'
  surface-container-lowest: '#ffffff'
  surface-container-low: '#f1f4f9'
  surface-container: '#ebeef3'
  surface-container-high: '#e5e8ee'
  surface-container-highest: '#e0e3e8'
  on-surface: '#181c20'
  on-surface-variant: '#40484e'
  inverse-surface: '#2d3135'
  inverse-on-surface: '#eef1f6'
  outline: '#71787f'
  outline-variant: '#c0c7cf'
  surface-tint: '#1a648c'
  primary: '#00567c'
  on-primary: '#ffffff'
  primary-container: '#2a6f97'
  on-primary-container: '#d7ecff'
  inverse-primary: '#8fcefa'
  secondary: '#4b6172'
  on-secondary: '#ffffff'
  secondary-container: '#cbe3f7'
  on-secondary-container: '#4f6576'
  tertiary: '#714800'
  on-tertiary: '#ffffff'
  tertiary-container: '#8e5f15'
  on-tertiary-container: '#ffe5c8'
  error: '#ba1a1a'
  on-error: '#ffffff'
  error-container: '#ffdad6'
  on-error-container: '#93000a'
  primary-fixed: '#c8e6ff'
  primary-fixed-dim: '#8fcefa'
  on-primary-fixed: '#001e2f'
  on-primary-fixed-variant: '#004c6e'
  secondary-fixed: '#cee5f9'
  secondary-fixed-dim: '#b2c9dd'
  on-secondary-fixed: '#051e2c'
  on-secondary-fixed-variant: '#334959'
  tertiary-fixed: '#ffddb5'
  tertiary-fixed-dim: '#f8bb6a'
  on-tertiary-fixed: '#2a1800'
  on-tertiary-fixed-variant: '#643f00'
  background: '#f7f9ff'
  on-background: '#181c20'
  surface-variant: '#e0e3e8'
typography:
  display:
    fontFamily: Inter
    fontSize: 32px
    fontWeight: '600'
    lineHeight: '1.2'
    letterSpacing: -0.02em
  headline-sm:
    fontFamily: Inter
    fontSize: 20px
    fontWeight: '600'
    lineHeight: '1.4'
    letterSpacing: -0.01em
  headline-sm-mobile:
    fontFamily: Inter
    fontSize: 18px
    fontWeight: '600'
    lineHeight: '1.4'
    letterSpacing: -0.01em
  title-md:
    fontFamily: Inter
    fontSize: 16px
    fontWeight: '500'
    lineHeight: '1.5'
    letterSpacing: '0'
  body-lg:
    fontFamily: Inter
    fontSize: 16px
    fontWeight: '400'
    lineHeight: '1.6'
    letterSpacing: '0'
  body-md:
    fontFamily: Inter
    fontSize: 14px
    fontWeight: '400'
    lineHeight: '1.5'
    letterSpacing: '0'
  label-md:
    fontFamily: Inter
    fontSize: 12px
    fontWeight: '600'
    lineHeight: '1'
    letterSpacing: 0.02em
  label-sm:
    fontFamily: Inter
    fontSize: 11px
    fontWeight: '500'
    lineHeight: '1'
    letterSpacing: 0.01em
rounded:
  sm: 0.25rem
  DEFAULT: 0.5rem
  md: 0.75rem
  lg: 1rem
  xl: 1.5rem
  full: 9999px
spacing:
  unit: 4px
  container-margin: 24px
  gutter: 16px
  stack-sm: 8px
  stack-md: 16px
  stack-lg: 32px
---

## Brand & Style

This design system is built for a personal activity tracking and planning environment that prioritizes clarity, focus, and utility. The brand personality is disciplined, premium, and calm, leaning heavily into a **Minimalist Modern** aesthetic. It eschews the playful, oversized trends of consumer apps in favor of a dense, professional information architecture inspired by high-end productivity tools.

The visual direction centers on "The Grid of Life"—a structured, organized approach to data where hierarchy is established through precise typography and deliberate whitespace rather than decorative elements. The emotional response should be one of "effortless control," reducing cognitive load for users managing complex daily schedules.

## Colors

The palette is rooted in a "cool-neutral" foundation to ensure a serene user experience. 

- **Background & Surfaces:** A triple-layered approach using `#F8F9FA` for the main canvas, `#FFFFFF` for primary interaction modules, and `#F1F3F5` for inset grouping or secondary navigation.
- **Accent:** A singular, muted deep teal (`#2A6F97`) is used exclusively for primary actions, active states, and critical progress indicators. It should be used sparingly to maintain its impact.
- **Typography & Borders:** Grayscale values are tightly controlled to ensure legibility while maintaining a soft, professional contrast. High-frequency UI elements use `#DEE2E6` for subtle definition.

## Typography

The typography system utilizes **Inter** for its exceptional legibility at small sizes and its neutral, systematic character. 

- **Scale:** Avoid oversized headings. Even the largest display type is restrained to preserve information density.
- **Hierarchy:** Use weight (`Medium` to `SemiBold`) and color (`primary-text` vs `secondary-text`) to differentiate information rather than massive size jumps.
- **Labels:** Small, uppercase labels with slight letter-spacing should be used for category headers or metadata to maintain a clean, architectural feel.

## Layout & Spacing

This design system employs a **Fixed-Fluid Hybrid** grid. On desktop, content is centered within a 1200px max-width container. On mobile, it utilizes a 4-column fluid grid with 24px side margins.

- **Rhythm:** A 4px baseline grid ensures tight vertical alignment.
- **Density:** Spacing is intentionally compact. Use `stack-sm` (8px) for related elements within a component and `stack-md` (16px) for spacing between distinct component blocks.
- **Alignment:** All text elements should align to the left to create strong vertical "rule lines" that guide the eye through the schedule or activity list.

## Elevation & Depth

This design system moves away from traditional shadows, utilizing **Tonal Layers** and **Low-Contrast Outlines** to define hierarchy.

- **Flat Depth:** Depth is communicated through color shifts. A primary surface (`#FFFFFF`) sits atop the background (`#F8F9FA`).
- **Borders:** Use 1px solid dividers (`#DEE2E6`) to separate list items and section headers. 
- **Active State:** Selection is indicated by a subtle background shift to `subtle-accent-surface` or a 2px left-accent border in the `primary` color, rather than a lift or shadow effect.
- **Floating Elements:** If a modal or dropdown is required, use a single, ultra-diffused shadow: `0 4px 20px rgba(0,0,0,0.05)`.

## Shapes

The shape language is "Geometric-Soft." 

- **Standard Radius:** 8px (`rounded`) is the default for buttons, input fields, and small modules.
- **Large Radius:** 16px (`rounded-xl`) is reserved for the most prominent containers or cards, though these should be used sparingly.
- **Pills:** Avoid pill-shaped buttons; stick to the standard 8px radius to maintain the professional, "pro-tool" aesthetic.

## Components

- **Buttons:** Compact height (36px or 40px). Primary buttons use a solid teal background with white text. Secondary buttons use a subtle gray fill (`#F1F3F5`) with `primary-text`.
- **List Items:** The core of the tracking experience. High-density layouts with a 48px min-height. Use thin 1px bottom borders for separation.
- **Input Fields:** Outlined style with a 1px border. Focus state is a simple color change of the border to the primary teal; avoid thick focus rings.
- **Chips/Tags:** Rectangular with small 4px radius. Use `subtle-accent-surface` for background and `secondary-text` for labels.
- **Progress Bars:** Thin (4px - 6px height) with a grey track and teal fill. No rounded caps; use the 2px radius for a cleaner, more precise look.
- **Cards:** Defined by a 1px border (`#DEE2E6`) rather than a shadow. Use white backgrounds for interactable cards and secondary-surface backgrounds for informational read-only cards.
- **Icons:** Use 20px or 24px thin-stroke (1.5px) outline icons. Icons should always be the same color as the text they accompany.