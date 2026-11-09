# Chart accessibility on a 360dp phone at 200% font scale — cited brief

**Scope:** Jetpack Compose / Material 3 stacked-bar activity chart, offline, Android 13, 720×1612 @ 320dpi (~360dp wide), axis labels at 10sp, user font scale up to 200%.

**Labelling convention used throughout:**
- **[W3C-NORMATIVE]** — text from a W3C Recommendation success criterion.
- **[W3C-MOBILE-NOTE]** — text from WCAG2Mobile, a W3C **Group Draft Note**. *Informative only.* The document itself states it "provides informative guidance (guidance that is not normative and does not set requirements)" and that "Group Draft Notes are not endorsed by W3C nor its Members."
- **[ANDROID]** — Google/Android platform developer documentation (recommendation, not a spec).
- **[MATERIAL]** — Material Design guidance (design recommendation, not a spec).
- **[NO STANDARD]** — no published requirement located; see §3.
- **[UNVERIFIED]** — could not confirm from a primary source.

---

## 1. Hard requirements (normative W3C text)

WCAG 2.2 applies to *web content*. For a native Android app the W3C does not publish a normative standard; the recognised route is WCAG2Mobile, which maps WCAG 2.2 onto native apps but is **non-normative** ([W3C-MOBILE-NOTE], https://www.w3.org/TR/wcag2mobile-22). So the WCAG items below are normative *as web content*, and are the de-facto conformance target for Android via that mapping. Android's own developer docs are recommendations, not conformance criteria.

### 1.4.11 Non-text Contrast (Level AA) — applies directly to chart marks

**[W3C-NORMATIVE]** exact text:

> The visual presentation of the following have a contrast ratio of at least 3:1 against adjacent color(s):
> - **User Interface Components:** Visual information required to identify user interface components and states, except for inactive components or where the appearance of the component is determined by the user agent and not modified by the author;
> - **Graphical Objects:** Parts of graphics required to understand the content, except when a particular presentation of graphics is essential to the information being conveyed.

Source: https://www.w3.org/WAI/WCAG22/Understanding/non-text-contrast.html

WCAG2Mobile substitutes "user agent" with "user agent or platform software" and adds: "An example of appearance modification by the author is content that sets the visual style of a control, such as a color or border, to differ from the default style for the user agent or platform." An app that draws its own chart is unambiguously *modifying* appearance, so the user-agent exemption does not rescue low-contrast author-drawn marks. ([W3C-MOBILE-NOTE])

**Threshold is a hard floor, not rounded:** "The 3:1 contrast ratios referenced in this success criterion is intended to be treated as threshold values. When comparing the computed contrast ratio to the success criterion ratio, the computed values should not be rounded (e.g. 2.999:1 would not meet the 3:1 threshold)." (same Understanding doc)

### When 1.4.11 applies to chart marks vs decorative fills

The Understanding doc gives the precise test — a graphic is *not* subject to 1.4.11 when:

> - A graphic with text embedded or overlaid conveys the same information, such as labels and values on a chart. **Note:** Text within a graphic must meet 1.4.3 Contrast (Minimum).
> - The graphic is for aesthetic purposes that does not require the user to see or understand it to understand the content or use the functionality.
> - The information is available in another form, such as in a table that follows the graph, which becomes visible when a "Long Description" button is pressed.
> - The graphic is part of a logo or brand name (considered "essential").

The operative test for your chart: **"If a person needs to perceive a graphic, or part of a graphic (a graphical object) in order to understand the content it should have sufficient contrast."**

Applying that to your three visual elements:

| Element | 1.4.11 applies? | Reasoning |
|---|---|---|
| **Bar segments (the two stacked quantities)** | **YES — hard 3:1** | You cannot read the values without perceiving the segments. They are graphical objects "required to understand the content." |
| **Bar track (empty background of the bar area)** | **Judgment call — likely YES** | The track establishes each bar's full extent, i.e. the denominator against which the fill is read. Removing it does not destroy the data (the segment lengths and axis still carry it), so a strict reading of "required to understand" could exclude it. But if the track is what communicates "this bar can grow to here," it *is* load-bearing. **Treat it as required and hold it to 3:1.** |
| **Baseline / axis rule, gridlines** | Likely not required, but see below | Usually supporting, unless they are the only cue of the zero baseline. |

Important: raising the track's opacity to meet 3:1 is the **conservative, defensible** choice. There is no penalty for meeting a requirement that arguably does not apply; the risk is only aesthetic.

**Boundary between adjoining segments — technique G209 (directly on point for stacked bars):**

> "The objective of this technique is to ensure people with moderately low vision can distinguish boundaries between adjoining segments of color. … This technique focuses on solid color segments where the boundaries between colors convey meaning. When selecting colors for graphics with multiple colors, consider adjoining colors and test that the contrast ratio is at least 3:1. If adjoining colors have less than 3:1 color contrast ratio difference add a border with at least a 3:1 color contrast with each color."

Source: https://www.w3.org/WAI/WCAG22/Techniques/general/G209

This is a **Technique, not a criterion** — the W3C states plainly: "Techniques are examples of ways to meet Web Content Accessibility Guidelines (WCAG). They are not required to meet WCAG." But G209 is the AG WG's own worked example for pie charts, and stacked bar segments are the same problem. **If your two stack colours are within 3:1 of each other, you need a 3:1 separator between them.**

### 1.4.3 Contrast (Minimum) (Level AA) — applies to your 10sp axis labels

**[W3C-NORMATIVE]** exact text:

> The visual presentation of text and images of text has a contrast ratio of at least 4.5:1, except for the following:
> - **Large Text:** Large-scale text and images of large-scale text have a contrast ratio of at least 3:1;
> - **Incidental:** Text or images of text that are part of an inactive user interface component, that are pure decoration, that are not visible to anyone, or that are part of a picture that contains significant other visual content, have no contrast requirement.
> - **Logotypes:** Text that is part of a logo or brand name has no contrast requirement.

Source: https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum.html

**Your 10sp labels get 4.5:1, not 3:1.** "Large-scale text" means 18pt, or 14pt bold. From the same doc: "18 point text or 14 point bold text is judged to be large enough to require a lower contrast ratio." Converting per the doc's own rule ("1pt = 1.333px, therefore 14pt and 18pt are equivalent to approximately 18.5px and 24px") — 10sp is nowhere near the threshold. **Hold every axis label to 4.5:1.**

The same doc explicitly hands off charts to 1.4.11: "similar issues occur for content presented in charts, graphs, diagrams, and other non-text-based information, which is covered by Success Criterion 1.4.11 Non-Text Contrast."

### 1.4.4 Resize Text (Level AA) — the 200% requirement

**[W3C-NORMATIVE]** exact text:

> Except for captions and images of text, text can be resized without assistive technology up to 200 percent without loss of content or functionality.

Source: https://www.w3.org/WAI/WCAG22/Understanding/resize-text.html

**The mobile-specific carve-out, which is the single most important clause for you** ([W3C-MOBILE-NOTE]):

> Note 1 (Added) The Intent section refers to the ability to allow users to enlarge the text on screen at least up to 200% without needing to use assistive technologies. This means that the application provides some means for enlarging the text 200% (zoom or otherwise) without loss of content or functionality, **or that the application works with the platform accessibility features to meet this success criterion.**
>
> Note 2 (Added) For non-web software, there may be cases where the platform does not scale all text up to 200%. In such cases, authors are encouraged to meet user needs by scaling text to the extent supported by user settings in the platform.

Read Note 1 carefully: **on Android you satisfy 1.4.4 by honouring the system font scale — you are not required to build your own 200% zoom control.** That is the standard reading, and it is the basis of the recommendations in §5.

### 1.4.10 Reflow (Level AA) — 320 CSS px, and why your chart is excepted

**[W3C-NORMATIVE]** exact text:

> Content can be presented without loss of information or functionality, and without requiring scrolling in two dimensions for:
> - Vertical scrolling content at a width equivalent to 320 CSS pixels;
> - Horizontal scrolling content at a height equivalent to 256 CSS pixels.
>
> Except for parts of the content which require two-dimensional layout for usage or meaning.

Note 2: "Examples of content which requires two-dimensional layout are **images required for understanding (such as maps and diagrams)**, video, games, presentations, data tables (not individual cells) … It is acceptable to provide two-dimensional scrolling for such parts of the content."

Source: https://www.w3.org/WAI/WCAG22/Understanding/reflow.html

A 24-bar-per-day chart is a diagram required for understanding — it falls inside the two-dimensional exception. **You are not required to make the 24 bars reflow at 200% font scale.** The surrounding page content still must reflow.

Crucially, the exception covers the chart *as a unit*; it does not excuse **clipped or truncated axis text**, which is a 1.4.4 "loss of content" problem, not a reflow problem.

### 1.1.1 Non-text Content (Level A) — charts explicitly named

**[W3C-NORMATIVE]** SC 1.1.1 (Level A) requires a text equivalent for non-text content. The Understanding doc names charts directly:

> "For non-text content that is not covered by one of the other situations listed below, such as **charts**, diagrams, audio recordings, pictures, and animations, text alternatives can make the same information available in a form that can be rendered through any modality (for example, visual, auditory or tactile)."

Source: https://www.w3.org/WAI/WCAG22/Understanding/non-text-content.html

Combined with Android's platform guidance (§2.3), this is the basis for exposing the chart's data to TalkBack.

### 1.4.1 Use of Color (Level A) — if colour is the only cue for the two quantities

**[W3C-NORMATIVE]** "Color is not used as the only visual means of conveying information, indicating an action, prompting a response, or distinguishing a visual element."

https://www.w3.org/WAI/WCAG22/Understanding/use-of-color.html

If your two stacked quantities are distinguishable *only* by hue, this is a Level A failure. Per 1.4.11's Understanding doc, "Non-text information within controls that uses a change of hue alone to convey the value or state of an input, such as a 1-5 star indicator … is likely to fail the Use of color criterion rather than this one."

---

## 2. Soft guidance (recommendations, not requirements)

### 2.1 Material Design on chart typography — the closest thing to a chart-text-size spec

**[MATERIAL]** Material 2's data visualization page is the only Google document that gives an actual chart type scale. It is archived ("Material 2 is no longer maintained") and Material 3 has **no successor type scale for charts** — see §3.

> | Scale category | Typeface | Font | Size |
> |---|---|---|---|
> | 1. Chart title | Roboto | Regular | 18pt |
> | Chart subtitle | Roboto | Regular | 14pt |
> | 2. Data label | Roboto | Regular | 22pt |
> | Sub-label | Roboto | Regular | 14pt |
> | 3. **Axis labels** | Roboto | Regular | **12pt** |
> | 4. **Legend labels** | Roboto | Regular | **12pt** |

Source: https://m2.material.io/design/communication/data-visualization.html

**This is the single most citable number in this brief: 12pt for axis labels and legend labels.** Note the units: Material writes chart sizes in **pt**, not sp, and this is a desktop-era document. See §5 for the conversion.

Other relevant **[MATERIAL]** items from the same page:
- "Text labels should be placed horizontally on the chart so that they are easy to read. Text labels should not: Be rotated; Stacked vertically." And: "Don't rotate bar labels, as it makes them difficult to read."
- "Label usage should reflect the most important data insights in a chart … **Their presence should not inhibit reading the chart.**" / "Don't overload the chart with numerous axis labels."
- **Small displays:** "Charts displayed on wearables (or other small screens) should be a simplified version of the mobile or desktop chart." Plus: "Do annotate key points on the graph to describe the data… In this example, the highest and lowest data values are displayed."
- **Bar baseline:** "Bar charts should start at a baseline (the starting value on the y-axis) of zero."
- **Legends on mobile:** "On desktop, it's recommended to place a legend below a chart. **On mobile, place the legend above the chart** to keep it visible during interactions."
- **Don't use colour alone:** "Do reinforce the meaning of chart colors with other visual cues, like icons." / "Don't use color alone to indicate meaning."
- Accessibility sub-section: "you can use other methods to accentuate data, such as high-contrast shading, shape, or texture. Applying text labels to data also helps clarify its meaning, while eliminating the need for a legend."

### 2.2 Material 3 "Top Tips for Data Accessibility"

**[MATERIAL]** https://m3.material.io/blog/data-visualization-accessibility (Google Data Accessibility Working Group). The article opens by noting this "extends beyond simply meeting compliance requirements" — i.e. Google itself frames this as beyond-compliance, not as a standard. Relevant bullets:

- **#3 Focus on what matters:** "Use colors that achieve required contrast ratios with adjacent elements (for example, background, metrics, and interaction states) along with an additional element or encoding to draw focus and communicate consistent meaning."
- **#4 Provide structure:** "Provide labels for legends, data points, axes, and marks so that chart elements can be unambiguously interpreted." / "Offer flexible ways to explore and prioritize data so that people using assistive technology can manipulate the data using familiar tools."
- **#2 Be a helpful guide:** "Provide an accessible method for linking to the underlying data set so people can explore the data on their own (for example, link to downloadable data in CSV format or in an accessible table)."
- **#5 Embrace flexibility:** "Consider how chart elements (color palettes, axes, etc.) meet a variety of needs, abilities, **screen sizes**, and data types." / "If appropriate, provide discoverable options that allow people to choose the visualization's theme, contrast, and density based on their needs."

### 2.3 Android platform guidance on exposing chart data

**[ANDROID]** https://developer.android.com/guide/topics/ui/accessibility/principles — "Label elements":

> "It's important to provide users with useful and descriptive labels for each interactive UI element in your app. Each label must explain the semantics of a particular element — that is, the element's meaning and purpose. Screen readers such as TalkBack can announce these labels to users. In most cases, Compose APIs and Material have default accessibility support in place. However, if you need to manually specify a UI element's semantic properties, use the `semantics` modifier and the `contentDescription` property."

Same page, "Use cues other than color":

> "To assist users with color vision deficiencies, use cues other than color to distinguish UI elements within your app's screens. These techniques can include using different shapes or sizes, providing text or visual patterns, or adding audio- or touch-based (haptic) feedback to mark the elements' differences."

**[ANDROID]** https://developer.android.com/guide/topics/ui/accessibility/apps — Android restates the WCAG contrast rule in **sp**, which is more directly usable than the pt-based WCAG wording:

> "If the text is smaller than 18sp, or if the text is bold and smaller than 14sp, use foreground and background colors that result in a color contrast ratio of at least 4.5:1. For all other text, set the color contrast ratio to at least 3:1."

This is WCAG 1.4.3 expressed in platform units. **10sp labels → 4.5:1 required.** (Note: Android frames these as recommendations — "we recommend that the color contrast … be above a specific threshold" — not as a platform-enforced rule.)

### 2.4 Android font scaling behaviour (this is the part that will bite you)

**[ANDROID]** https://developer.android.com/about/versions/14/features:

> "**Non-linear font scaling to 200%** — Starting in Android 14, the system supports font scaling up to 200% … To prevent large text elements on screen from scaling too large, the system applies a nonlinear scaling curve. This scaling strategy means that **large text doesn't scale at the same rate as smaller text.** Nonlinear font scaling helps preserve the proportional hierarchy between elements of different sizes while mitigating issues with linear text scaling at high degrees (such as text being cut off …)."

**Your target is Android 13, where scaling is still linear.** So on your actual device 10sp × 2.0 = 20sp effective. That is a large, legible label — good news for legibility, bad news for layout, because at 20sp a "12" is roughly 20dp wide and 24 of them will not fit across 360dp minus the axis gutter.

For reference on Android 14+, the AOSP lookup table (`FontScaleConverterFactory.java`) shows the curve compressing large text and expanding small text. At scale 2.0: 10sp→20dp, 12sp→24dp, 14sp→26dp, 18sp→30dp, 24sp→36dp, 30sp→38dp. At scale 1.5: 10sp→15dp, 12sp→18dp, 18sp→24dp, 24sp→28dp. (Source: https://android.googlesource.com/platform/frameworks/base/+/HEAD/core/java/android/content/res/FontScaleConverterFactory.java)

### 2.5 Compose user-scalable content

**[ANDROID]** https://developer.android.com/develop/ui/compose/accessibility/scalable-content — offers pinch-to-zoom with two strategies: **density scaling** (everything scales, content reflows) and **font scaling** (text only). Noting: "For accessibility, typical min/max values are ~0.75x and ~3.5x." This is an *in-app zoom* feature, distinct from the system font scale that 1.4.4 actually requires — do not confuse them.

### 2.6 sp vs dp vs px

**[ANDROID]** https://developer.android.com/design/ui/mobile/guides/layout-and-content/grids-and-units:

> "Scalable pixels (sp): Scalable pixels serve the same function as dp, but for fonts. The default value of an sp is the same as the default value for a dp. **The Android system calculates the actual font size to use based on the device and the user's preference set in the device's settings.** Important: Always specify font sizes in sp units or scalable pixels. … The primary difference between these units of measurement is that **scalable pixels preserve a user's font settings.**"

Note also: "If using a baseline grid, stick to measurements of 4 and 8."

### 2.7 Touch targets — 48dp

**[ANDROID]** https://developer.android.com/guide/topics/ui/accessibility/apps: "we recommend that each interactive UI element have a focusable area, or touch target size, of at least 48dp × 48dp. Larger is even better."

Relevant if your Day-view bars are tappable/draggable for a tooltip — a 1dp-wide bar is not a 48dp target. WCAG 2.2 SC 2.5.8 Target Size (Minimum) is Level AA but is in the **mobile mapping appendix** of WCAG2Mobile, not the core criteria list — **[W3C-MOBILE-NOTE]**.

---

## 3. What has NO published standard

**→ NO PUBLISHED STANDARD: there is no WCAG, W3C, Material, or Android minimum legible font size for chart axis labels — anywhere.** I searched the WCAG 2.2 Recommendation, all four Understanding documents, WCAG2Mobile, Material 2 and Material 3 dataviz guidance, and the Android developer docs. No numeric minimum for chart text exists in any of them.

The strongest evidence is an explicit W3C WAI Interest Group thread on exactly this question (March 2023), archived at https://lists.w3.org/Archives/Public/w3c-wai-ig/2023JanMar/:

- **Guy Hickling** (W3C lists, 2023-03-08): *"For websites, I think it's generally accepted in the accessibility industry that text shouldn't be less than 16px as good practice, but **there is nothing in the WCAG about it**. (I do think there should be something — perhaps something to add in WCAG3.)"*
- **David Woolley:** *"any minimum font size that got close to covering all people with some ability to read visually, would be so large that it would pose significant usability problems for someone whose visual acuity was closer to the median. As such user ability to adjust font size is always going to be important."*
- **Hickling again:** *"So it seems to be generally recommended (but nothing for this in the WCAG) that 16px … is really as small as a design should ideally go."*

This is community-group commentary, not a W3C position — but it is W3C-hosted and it is unambiguous that the standard is silent.

**→ NO PUBLISHED STANDARD: Material 3 has no chart typography scale.** Material 2's 12pt axis-label figure is the last published Google number, and that page is explicitly archived: "Material 2 is no longer maintained. Upgrade to Material 3." The M3 dataviz guidance is six qualitative tips with no sizes. Do not cite 12pt as an M3 requirement.

**→ NO PUBLISHED STANDARD: no rule for minimum bar width / number of bars.** 24 bars on a 360dp screen is your design call. Material says only that small-screen charts should be "a simplified version."

**→ NO PUBLISHED STANDARD: no WCAG rule on axis-label *count* or interval.** Material gives only "Don't overload the chart with numerous axis labels."

**→ NO PUBLISHED STANDARD: no prescribed accessibility-node granularity for a chart.** WCAG 1.1.1 requires a text alternative; it does not say "one node per bar." Android provides `contentDescription`, `stateDescription`, and custom `SemanticsProperty` mechanisms but publishes no chart-specific pattern. The per-bar-node design is an implementation choice.

**→ NO PUBLISHED STANDARD: no WCAG requirement that chart text *itself* be 200%-scalable if it is an exception to reflow.** 1.4.4's "loss of content or functionality" is the operative phrase, and how much scaling a two-dimensionally-excepted chart must tolerate is not further specified. WCAG2Mobile Note 2 to 1.4.4 softens this to "the extent supported by user settings in the platform."

**Also worth stating explicitly: WCAG does not have a standalone "charts" success criterion.** Charts are governed by 1.1.1, 1.4.1, 1.4.3, 1.4.5, 1.4.10 and 1.4.11 as applicable. The W3C Accessible Infographics Community Group, which was to produce chart-specific guidance, was **closed for inactivity** in 2023 (https://w3.org/community/infographics) — it never produced a Recommendation. (The closure notice is at https://lists.w3.org/Archives/Public/public-infographics/2023Mar/0000.html.)

---

## 4. What I could NOT verify

- **[UNVERIFIED] No published Google/Play Store policy minimum font size.** I found no Google Play Console accessibility policy stating a minimum text size or a required max font-scale behaviour. Absence of a located policy is not proof none exists.
- **[UNVERIFIED] Whether WCAG 1.4.11 legally "applies" to a native Android app.** WCAG 2.2 is scoped to web content; WCAG2Mobile is informative and non-endorsed. Any conformance claim for a native app is a mapping decision, not a W3C conformance statement.
- **[UNVERIFIED] Material 3's stance on chart axis-label size.** I could not find any M3 document that addresses it. M3 dataviz content is a blog post; M2's spec is archived. The M3 foundations/typography pages describe the *type scale* (display/headline/title/body/label) and never mention charts.
- **[UNVERIFIED] The 12pt → dp/sp conversion.** Material writes chart sizes in "pt" without stating a density assumption. 12pt = 16 CSS px at 96dpi. At your 320dpi (2.0 density), 12pt of 16dp-equivalent typography = 24 physical px. Material's number is a *relative* size, not a pixel target — see the reasoning in §5.
- **[UNVERIFIED] Whether your specific 10sp label colour meets 4.5:1.** I do not have your colour values.
- **Could not access:** the `browser` tool (CDP handshake refused connection), and `m3.material.io` / `m2.material.io` raw HTML (Angular-rendered; content recovered via a text-rendering proxy). Material 2's page content is complete as retrieved.
- **[UNVERIFIED] Android 13's exact maximum font scale factor.** Android 14's docs describe 200%; I did not locate an equivalent authoritative Android 13 statement, though non-linear scaling is documented as an Android 14 addition, which implies 13 was linear.

---

## 5. Concrete, checkable recommendations

Target: 360dp wide, 10sp minimum chart text, up to 200% font scale (linear on Android 13).

### A. Contrast — the hard, testable items

1. **Axis labels (10sp) → ≥ 4.5:1** against the chart background. Non-negotiable: 10sp is far below the 18pt/14pt-bold "large text" threshold. Verify with the Accessibility Scanner app or a contrast checker.
2. **Bar segment fills → ≥ 3:1** against the surface behind them (the track, or the card background where the track is absent).
3. **The boundary between your two stacked segments → ≥ 3:1**, or add a separator line with ≥3:1 against **both** segment colours. Technique **G209**. This is the requirement most likely to be missed in a two-colour stack, and it is the reason the two fills cannot simply be two tints of one hue.
4. **The bar track → ≥ 3:1** if it carries the "full extent" meaning. You already raised its opacity; keep it, and re-verify at 3:1 exactly (no rounding — 2.999:1 fails).
5. **Do not encode the two quantities by hue alone.** Add a second cue: the legend swatch should carry a pattern, icon, or text label, and the segment boundary from item 3 doubles as the structural cue.

### B. Font scale — the layout problem, stated as tests

At 200% on Android 13 (linear), your 10sp labels render at **20sp ≈ 20dp height, and a glyph run of 2–3 characters is ~20–30dp wide.**

6. **Make the chart height a function of the scaled label size, not a fixed dp constant.** Measure the label with `TextMeasurer` / `rememberTextMeasurer()` at the *resolved* `LocalDensity.current.fontScale` and size the axis gutter and plot height from the result. This is the single change that prevents clipping.
7. **Reduce axis-label count as font scale rises — do not shrink the type.** Material: "Their presence should not inhibit reading the chart." At 200%, drop to the minimum ticks (e.g. every 6h on Day, not every hour) and keep the same sp. Honouring 1.4.4 means the text scales; the *number of labels* is what you adapt. Thinning labels is what keeps them legible.
8. **Never use `TextUnit` in a fixed, non-scaling context, and never convert sp→dp with the font scale stripped.** If you draw axis text on a `Canvas`, `drawText` with a `TextMeasurer`-produced layout does honour font scale; raw `Paint` work does not by default.
9. **Add a user-facing density control as a courtesy** (M3 tip #5 suggests it), but do **not** treat it as your 1.4.4 compliance route — WCAG2Mobile Note 1 says platform font-scale support is sufficient.

### C. Size — the honest answer

10. **Your 10sp is below Material's own chart guidance in relative terms.** Material's axis-label entry is 12pt; the M3 type scale's smallest role, `labelSmall`, is **11sp** (verified from AOSP `TypeScaleTokens.kt`: `LabelSmallSize = 11.sp`, `LabelMediumSize = 12.sp`, `BodySmallSize = 12.sp`). You are at 10sp — smaller than anything in the Material 3 type scale.
11. **Recommendation: raise the axis-label floor to 11sp (`labelSmall`)** if there is any layout headroom. This keeps you inside the M3 scale and above the 10sp you deliberately reverted to. It is a style recommendation, not a requirement — there is no standard behind it.
12. **If 11sp does not fit, keep 10sp but reduce label count (item 7) rather than the size.** 10sp on a 320dpi screen is ~20 physical px tall at 100% scale, which is physically readable; the risk is crowding, not illegibility. **[Designer's judgment — no standard supports either choice.]**
13. **Do not rotate axis labels** to save horizontal space. Material: "Don't rotate bar labels, as it makes them difficult to read." Diagonal y-axis ticks are the most common chart regression here.

### D. Small screen and 24-bar density

14. **Consider defaulting Day view to a coarser aggregation at large font scale.** Material: "Charts displayed on wearables (or other small screens) should be a simplified version of the mobile or desktop chart." At 200% font scale, 24 labelled bars is a simplified chart's worth of complexity in a compact chart's worth of space. Falling back to hourly *values* on tap (progressive disclosure — Material's "On mobile, a touch and hold gesture displays a tooltip placed above the chart") is a legitimate way to keep 24 bars without 24 labels.
15. **Annotate key points instead of labelling every bar.** Material small-displays guidance: "Do annotate key points on the graph to describe the data… the highest and lowest data values are displayed." This is the sanctioned way to reduce density.
16. **Place the legend above the chart on mobile**, per Material.

### E. Accessibility nodes and text alternatives (SC 1.1.1 + Android)

17. **Give the chart container a `contentDescription` that summarises it** — the M3 blog's caution applies: "Try not to rely on verbally summarizing the chart's visual elements, which can distract from the main data points." Summarise the *finding*, not the geometry.
18. **Expose per-bar values via `SemanticsProperty` / a custom semantics node** so TalkBack can reach individual values, not just one blob string. 24 bars = 24 focusable nodes is a lot of swipes; a pragmatic middle ground is per-bar nodes with a concise label ("Hour 14: 45 min screen, 12 min app"), and a whole-chart summary node. **[Designer's judgment — no standard prescribes granularity.]**
19. **Ensure the two series are distinguishable non-visually too** — TalkBack users cannot perceive the hue distinction. Each bar's semantic label must name both quantities.
20. **Offer a data table alternative.** Both WCAG (via the 1.4.11 "information is available in another form" carve-out) and Material's blog ("link to… an accessible table") support this. A plain-text or expandable list of the same values is cheap in an offline app and gives you a WCAG-defensible fallback.

### F. The minimum verification set

- [ ] Axis label colour ≥ 4.5:1 on its background (measure, don't eyeball)
- [ ] Each segment fill ≥ 3:1 vs the track
- [ ] Segment-vs-segment boundary ≥ 3:1, or a 3:1 separator present
- [ ] Track ≥ 3:1 (re-check after the opacity change)
- [ ] At 200% font scale: zero clipped/truncated/overlapping axis text
- [ ] At 200% font scale: no horizontal panning required to read the chart
- [ ] TalkBack reaches every bar's values
- [ ] Chart is reachable as one node with a meaningful description
- [ ] Two series distinguishable without colour
- [ ] Data table alternative available
