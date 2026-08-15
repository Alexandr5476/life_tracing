# LifeTracing design references

This directory contains visual reference material for the LifeTracing Android UI.

## Authority and purpose

The screenshots in `reference/` are **visual references, not pixel-perfect UI specifications**.

For application behavior, data, state transitions, available actions, and exact product semantics, the authoritative sources are the documents in `docs/spec/`.

Priority when implementing UI:

1. `docs/spec/` — authoritative product/domain behavior.
2. Existing reusable LifeTracing Compose components and established design-system rules.
3. `docs/design/DESIGN.md` — general visual language and initial design tokens.
4. Screenshots in `docs/design/reference/` — examples of composition, density, hierarchy, and approximate screen structure.

A screenshot must never override product semantics from the specifications.

## Reference screenshots are intentionally approximate

The screenshots were generated during design exploration in Google Stitch. They are not a single internally consistent finished design.

Therefore:

- the same component may look slightly different in different screenshots;
- spacing, typography, corner radii, button shapes, colors, or other repeated details may differ accidentally;
- some controls or minor functionality may be missing;
- some visible functionality may be approximate or outdated;
- text content is illustrative;
- not every state or edge case is represented.

Do not reproduce these inconsistencies in the application.

Once a reusable component or token has been established in the codebase, use it consistently across screens instead of restyling it to match each screenshot individually.

Context-specific dimensions may vary when there is a real layout reason, but shared visual properties such as component shape, color semantics, typography role, interaction style, and general spacing rules should remain consistent.

## Design-system interpretation

`DESIGN.md` describes the initial visual direction named **Calibrated Precision**:

- calm, professional, information-dense interface;
- cool-neutral surfaces;
- restrained teal accent;
- typography-led hierarchy;
- compact spacing based on a 4 dp rhythm;
- tonal layers and subtle outlines rather than heavy shadows;
- geometric-soft shapes;
- restrained corner radii;
- compact controls rather than oversized consumer-app UI.

The structured token block at the beginning of `DESIGN.md` is the baseline for the initial Compose theme where exact token values are required.

The prose and screenshots explain visual intent. If an exact numeric value in the prose or a screenshot differs slightly from the structured token block, do not create duplicate tokens merely to match that individual example.

The initial light theme should preserve the overall visual character of the Stitch references. Dark theme is not fully specified by the references and should be a coherent dark counterpart of the same design language rather than an attempt to infer exact nonexistent reference values.

## Reusable components take precedence

As implementation proceeds, the real Compose design system becomes the canonical representation of recurring UI components.

For example, after the project establishes a standard:

- primary button;
- secondary button;
- card;
- section header;
- input field;
- list row;
- chip;
- progress indicator;

future screens should reuse those components.

Do not alter a shared component's radius, stroke, typography, or other design properties solely because one Stitch screenshot shows a slightly different rendition.

If a reference suggests a genuinely useful new variant, introduce it deliberately as a named component/variant rather than creating an ad-hoc one-screen exception.

## Specific reference notes

`activity-editor-full.png` and `activity-editor-collapsible.png` are two exploratory views of the same conceptual Activity editor.

They should be used to understand:

- collapsible sections;
- grouping;
- information hierarchy;
- possible controls and layouts.

They are not two independent specifications and do not need to agree in every repeated visual detail.

`statistics-running.png` is sufficient as the current Statistics visual reference. A separate Statistics overview reference was intentionally omitted because it did not provide a reliable additional design signal.

## Localization

Reference screenshots may contain English text, but production UI must not derive language behavior from screenshots.

LifeTracing initially supports:

- English;
- Russian.

User-facing production strings must use Android string resources and must not be hardcoded in Compose code.

Layouts should tolerate realistic Russian/English text-length differences instead of being tuned only for screenshot text.

## Scaling

Future Settings implementation will provide app-level scaling preferences:

| Preference | Minimum | Maximum | Step | Default |
| --- | ---: | ---: | ---: | ---: |
| Interface size | 90% | 120% | 5% | 100% |
| Text size | 90% | 130% | 5% | 100% |

These preferences are additive to Android system display and font scaling; app-level text scale must never neutralize or replace system accessibility font scaling. Layouts must reflow without assuming a fixed text height. Design-reference screenshots represent only the 100% / 100% baseline. Implementation is deferred until the Settings feature.

## Motion

LifeTracing motion is minimal, fast, functional, and restrained: no decorative bouncing, gratuitous delays, or slow cinematic transitions. State changes must never wait on decorative animation.

- Micro interaction: 100 ms.
- Normal UI transition: 160 ms.
- Structural expand/collapse: 220 ms; this is normally the upper bound for ordinary UI motion.

Future ordinary transitions should use the `LifeTracingMotion` duration tokens with explicit finite specs (normally tween-based), rather than accidental library default springs. Springs, bounce, and overshoot are not default behavior; introduce a spring only when a specific interaction genuinely benefits from physical motion. Do not disable Android/Compose motion-duration scaling, so standard Compose animation mechanisms can respect users who effectively disable animations.

## Interaction feedback

Keep Material press feedback and ripples enabled. Rounded interactive surfaces must use the same effective shape for their visual surface and interaction clipping. For custom click targets, prefer a shaped Material `Surface`, or clip to the intended shape before adding indication/clickable behavior; never place an unclipped rectangular indication layer over a rounded container.

## Evolution

These references are a starting point, not a permanent constraint on improvement.

The visual language may become more refined as real reusable Compose components are implemented. New screens should primarily follow the established application design system, using these screenshots as contextual inspiration rather than repeatedly reconstructing Stitch output.
