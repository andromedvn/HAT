# Encoding Two Categories in a Stacked Bar Under a One-Hue Constraint

**Question:** Can the two stacked segments in HAT's activity chart be distinguished by lightness alone
(same hue, same saturation, different lightness), or is that a known failure mode?

**Verdict: PARTIALLY DEFENSIBLE — but not at the values currently proposed, and not on lightness alone as the
only cue.** Lightness-only separation is legitimate, sanctioned, and empirically supported *in principle*; it
fails at the specific magnitudes in the brief (L=80% vs L=52%) and it violates Brewer's expressiveness rule
for nominal data unless the chart also carries a legend. There is **no published validated minimum lightness
delta for this task**; the only defensible numeric target is the WCAG 3:1 contrast ratio, and at one hue that
requires roughly ΔL* ≥ 33.

---

## 1. Findings with real citations

### Cleveland & McGill (1984) — the canonical ranking

> Cleveland, W. S., & McGill, R. (1984). Graphical Perception: Theory, Experimentation, and Application to the
> Development of Graphical Methods. *Journal of the American Statistical Association*, 79(387), 531–554.
> DOI 10.1080/01621459.1984.10478080 · full text: https://dsciclass.org/dsci310/Notes/Cleveland_McGill_EPT.pdf

- The 10 elementary perceptual tasks, **"ordered from most to least accurate"** (verbatim, p. 536):
  1. Position along a common scale
  2. Positions along nonaligned scales
  3. Length, direction, angle
  4. Area
  5. Volume, curvature
  6. **Shading, color saturation**
- **CRITICAL — this does not say "don't encode quantity with colour."** The paper explicitly *excludes*
  colour hue and texture from its task list for a specific reason (p. 532, verbatim):

  > "for example, color hue and texture (Bertin 1973) are two elementary tasks excluded from the list because
  > they do not have an unambiguous single method of ordering from small to large and thus might be regarded as
  > better for encoding **categories rather than real variables**."

  Cleveland & McGill therefore endorse hue/colour as a **category** channel and exclude it from the
  *ordered-data* hierarchy. HAT's segments are categories. The classic ranking does not forbid this design; it
  simply means the reader cannot *order or measure* the categories by colour — only *identify* them.
- On **stacked/divided bar charts specifically**: Cleveland & McGill's conclusion is that the divided bar
  chart should be replaced. *"A divided bar chart can always be replaced by a grouped bar chart; again, we
  prefer a grouped dot chart to a grouped bar chart"* (p. 545), and *"neither graphical form should be used
  because other methods are demonstrably better."* This is a critique of **position/length** decoding, not of
  the colour channel.

### Brewer / ColorBrewer — the rule that actually binds this design

> Harrower, M., & Brewer, C. A. (2003). ColorBrewer.org: An Online Tool for Selecting Colour Schemes for Maps.
> *The Cartographic Journal*, 34(1), 29–37. PDF: https://www.cs.rpi.edu/~cutler/classes/visualization/S18/papers/colorbrewer.pdf
> Scheme type definitions: https://www.colorbrewer2.org/learnmore/schemes_full.html

- **Lightness ramp ⇒ ordered data.** Verbatim: *"Sequential colour schemes imply order… Lightness steps
  dominate the look of these schemes… Sequential schemes can be either single hue (e.g. same blue, with
  different lightness and saturation levels) or multi-hued."*
- **Hue ⇒ categorical data.** Verbatim: *"Qualitative colour schemes rely primarily on differences in hue to
  create a colour scheme that does not imply order, merely difference in kind. **Since there is no conceptual
  ranking in nominal data it is inappropriate to imply order when depicting these data with colour (for example,
  by using a light-to-dark single-hue sequence).** Qualitative schemes work best when hue is varied and
  saturation and lightness are kept or nearly constant."*
- **The sanctioned exception exists and is exactly HAT's case.** Verbatim from colorbrewer2.org: *"Paired
  Scheme: This scheme presents a series of lightness pairs for each hue (e.g. light green and dark green). Use
  this when you have categories that should be visually related, though they are not explicitly ordered."*
  HAT's "app usage" and "away/offline" are exactly two categories that should look related. Brewer's Paired
  scheme is the canonical, intended mechanism for lightness-only categorical encoding.
- **Measured Brewer Paired pairs** (computed from the official `colorbrewer_schemes.js` dataset at
  https://colorbrewer2.org/colorbrewer_schemes.js): light/dark pairs run **ΔL\* ≈ 15–50**, contrast ratios
  **1.55:1 – 4.62:1**. Notably her own orange pair `#FDBF6F`/`#FF7F00` has **ΔL\* = 14.6 and ratio 1.55:1** —
  below 3:1. So **3:1 is not required by Brewer's design practice for map enumeration units**, which are
  *separated by border lines*, a condition HAT's stacked segments do not share.
- **Simultaneous-contrast warning, directly applicable.** Verbatim: *"A well-known problem with choropleth
  maps is simultaneous contrast (Brewer, 1997). For example, a single enumeration unit of medium lightness
  that is surrounded by dark enumeration units will appear lighter than it actually is. Thus, the map-reader
  will not be able to accurately match these outlying enumeration units with colours in the legend because they
  will appear lighter on the map than they do in the legend."* HAT has **no legend**, so this failure mode has
  nowhere to recover.
- **Legend ≠ map.** Verbatim: *"just because a map reader can see subtle differences between the individual
  colour patches in a legend does not mean they will be able to recognize those same differences on the map."*

### Ware — Information Visualization: Perception for Design

> Ware, C. (2019 [2008]). *Information Visualization: Perception for Design*, 4th ed., Morgan Kaufmann.
> ISBN 978-0-12-812475-0 · full text consulted: https://ia800809.us.archive.org/3/items/in.ernet.dli.2015.474270/2015.474270.Information-Visualization_text.pdf

- **Lightness is a first-order channel, not a weak one.** Verbatim: *"Luminance channel information dominates
  our perception of shape, space, motion. It is essential that good luminance contrast be available in an image
  for any of these to be seen clearly."*
- **Simultaneous lightness contrast is the mechanism that will distort HAT's two fills.** Verbatim:
  *"Contrast effects can radically alter the shape of the color space. Small patches of light give different
  results from those of large patches. In general, we are much more sensitive to differences between large
  patches of color."*
- **WARM HUES ARE SPECIALLY FRAGILE ON DARK BACKGROUNDS — most important finding for HAT.** Verbatim
  (chapter 4, "Brown"): *"Brown requires that there be a reference white somewhere in the vicinity for it to be
  perceived… There is no such thing as an isolated brown light in a dark room, but when a yellow or yellowish
  orange is presented with much brighter surrounding colors, brown appears."* Practical consequence: the darker
  of the two orange fills on HAT's dark surface shifts perceptually toward **brown/olive**, not "dark orange" —
  a hue shift that reinforces, rather than fixes, the "one undifferentiated blob" problem.
- **On JNDs.** Verbatim: *"A value of 1 for ΔE_uv is an approximation to a just noticeable difference (JND)."*
  He offers **no psychophysical lightness threshold for categorical segment discrimination.**
- **Recommended labels are hue+lightness, not lightness-only.** Ware's list of twelve coding colors includes
  both light and dark of each family, and his caveats emphasise *"significant luminance difference"* and
  *"substantial color-blind population."*

### WCAG 2.1 — the only published numeric thresholds that actually apply

> W3C, WCAG 2.1 (current REC https://www.w3.org/TR/WCAG21/) · Understanding SC 1.4.11:
> https://www.w3.org/WAI/WCAG21/Understanding/non-text-contrast · SC 1.4.1:
> https://www.w3.org/WAI/WCAG21/Understanding/use-of-color

- **1.4.11 Non-text Contrast (AA), verbatim:** *"The visual presentation of the following have a contrast
  ratio of at least 3:1 against adjacent color(s): … **Graphical Objects** Parts of graphics required to
  understand the content…"*
- **The Understanding doc names HAT's exact chart.** Verbatim (Figure 39): *"To understand the pie chart you
  have to discern each slice of the pie chart from the others. The graphical objects are the slices of the pie
  (chart)."* A stacked bar is the same case. Also: *"a border line between contrasting backgrounds is
  considered to be subsumed into the color closest in brightness."*
- **1.4.1 Use of Color, verbatim — this is the decisive sentence for lightness-only:** *"If content is
  conveyed through the use of colors that differ not only in their hue, but that also have a significant
  difference in lightness, then this counts as an additional visual distinction, as long as the difference in
  relative luminance between the colors leads to a contrast ratio of **3:1 or greater**."*
- **Android's own guideline, verbatim** (https://developer.android.com/design/ui/mobile/guides/foundations/accessibility):
  *"Use a 3:1 ratio between surfaces and non-text elements. For example, the ratio of a background to an icon
  would be 3:1."*

### G209 — the officially sanctioned technique for same-hue adjoining segments

> W3C, Technique G209: Provide sufficient contrast at the boundaries between adjoining colors.
> https://www.w3.org/WAI/WCAG21/Techniques/general/G209

Verbatim: *"When selecting colors for graphics with multiple colors, consider adjoining colors and test that the
contrast ratio is at least 3:1. **If adjoining colors have less than 3:1 color contrast ratio difference add a
border with at least a 3:1 color contrast with each color.**"*

**Example 2 is HAT's chart**: *"The contrast between the segments of the pie chart range from **1.1 to 1.8**. A
border has been added with at least a 3:1 contrast ratio with the colors to distinguish between segments."*

This is the load-bearing citation: **WCAG explicitly accepts sub-3:1 lightness-only fills when a 3:1 separator
is present.** Note this also means the darkening artefact in Ware's brown effect must be re-checked against
the border option, since a light fill plus a light border on a dark background is low-contrast at the border.

### CIE — threshold data, and an explicit refusal to set a threshold

> Richter, M. *Colour Spaces, Colour Differences, and Line Elements*, TU Berlin, chapter C §2 (summarising
> **CIE 230:2019** Technical Report, Division 1, DOI 10.25039/TR.230.2019).
> https://color.li.tu-berlin.de/CEA_S.HTM · CIE 230:2019: https://cie.co.at/publications/validity-formulae-predicting-small-colour-differences

- Verbatim: *"in many applications a lightness difference of **delta_L\* = 1** is assumed as the threshold for
  separated colours on a grey surround. Then the threshold is by a factor 3 smaller for adjacent compared to
  separated colours on the same grey background."*
- Verbatim: *"Several available datasets of CIE TC1-81 have shown approximately a threshold **ΔL\*_JND ≈ 0.30 for
  adjacent colours. However, Members of CIE TC1-81 could not agree to recommend this threshold for adjacent
  colours.** Therefore in CIE 230:2019 further research is recommended."*

**This is the honest answer on "minimum lightness delta":** the detection JND is ΔL\* ≈ 0.3–1, but that is a
*notice*, not a *discrimination*, and **CIE explicitly declined to recommend it**. Note these figures are for
grey surrounds; HAT's surround is a saturated near-black.

### Empirical: lightness-based categorical palettes lose to hue-based ones

> Tseng, C., Wang, A. Z., Quadri, G. J., & Szafir, D. A. (2024). Revisiting Categorical Color Perception in
> Scatterplots: Sequential, Diverging, and Categorical Palettes. *CGF / EuroVis*, 43(3).
> arXiv:2404.03787 · https://arxiv.org/html/2404.03787v2

- Mean-value-judgment accuracy by palette family: **multi-hue categorical 91.44%**, diverging 86.78%,
  perceptually-uniform multi-hue sequential 86.67%, multi-hue sequential 82.56%, **single-hue sequential
  (i.e. lightness-only) 81.11%** — the lowest. F(4,95) = 12.25, p < .0001.
- Verbatim: *"while hue variation dominates performance, lightness variation and general perceptual distance
  also play a notable role."*
- **Critical mitigating finding for HAT:** *"Sequential and diverging palettes remained notably robust to small
  numbers of categories (less than six)."* HAT has **two** segments. The 81.11% figure is an average over 2–10
  classes and is dominated by the 6–10 class range where all encodings degrade.
- Same group, CHI 2023 (arXiv:2303.15583, https://arxiv.org/pdf/2303.15583): exploratory regression found
  **lower L\* variance** associated with *better* accuracy (L\* variance F = 9.88, p = .0005, negative trend),
  while L\* magnitude trended positive (p = .07, n.s.). Verbatim: *"in contrast of some existing guidelines for
  color palettes, our results indicate maximizing luminance variation may hinder analysis."*
  ⚠ These two papers disagree in emphasis; the 2024 paper is the larger, more direct comparison of
  lightness-only *as a family*, and both used **white backgrounds**, not dark themes.

### Explicitly NOT found

- **No** published threshold for minimum lightness delta distinguishing stacked-bar segments. Searched
  Cleveland & McGill, Brewer/ColorBrewer, Ware, CIE/ISO, WCAG techniques, and the IEEE TVCG / EuroVis /
  CHI stacked-bar literature. **NO PUBLISHED THRESHOLD FOUND.**
- **No** study of lightness-ramp discriminability of a **warm hue on a dark background**. Ware's brown effect
  is the closest verified source and it is qualitative, not a threshold.
- Indratmo et al. (2018), "The efficacy of stacked bar charts in supporting single-attribute and overall-attribute
  comparisons," *Visual Informatics* (https://roam.macewan.ca/items/a72028c5-78b9-436d-8217-d3b1e3f21ed4/full)
  compares **classical / inverting / diverging** stack geometry — it varies *layout*, not segment colour, and
  does not address lightness discriminability. **Not applicable.**
- Unverified: whether Android's 3:1 non-text guidance is legally binding on a personal, non-commercial app.
  WCAG applies to web content; HAT is a native Android app. Treated here as the design standard of practice,
  **not** as a compliance obligation.

---

## 2. Numeric thresholds, quoted exactly

| Threshold | Value | Source | Status |
|---|---|---|---|
| Non-text contrast, graphical objects | "at least 3:1 against adjacent color(s)" | WCAG 2.1 SC 1.4.11 | Normative (web) |
| Lightness-only counts as non-colour distinction | "as long as the difference in relative luminance between the colors leads to a contrast ratio of 3:1 or greater" | WCAG 2.1 Understanding 1.4.1 | Interpretive |
| Sub-3:1 fill permitted with separator | "If adjoining colors have less than 3:1 color contrast ratio difference add a border with at least a 3:1 color contrast with each color" | W3C G209 | Sufficient technique |
| Worked example of that permission | "The contrast between the segments of the pie chart range from 1.1 to 1.8" | W3C G209 Example 2 | Sufficient technique |
| Android non-text / surface ratio | "Use a 3:1 ratio between surfaces and non-text elements" | Android Developers, Accessibility | Platform guidance |
| Separation JND (grey surround, adjacent) | "ΔL\*_JND ≈ 0.30 for adjacent colours" — **CIE declined to recommend** | CIE 230:2019 via Richter | Detection only |
| Separation JND (grey surround, separated) | "delta_L\* = 1 is assumed as the threshold" | CIE 230:2019 via Richter | Assumption, not a standard |
| Ranking position of colour | rank 6 of 6: "Shading, color saturation" | Cleveland & McGill 1984, p. 536 | Peer-reviewed |

**Measured for HAT** (computed from sRGB → CIELAB and WCAG relative luminance; background assumed `#121212`):

| Proposed pair | ΔL\* | Contrast between segments | Verdict vs 3:1 |
|---|---|---|---|
| **L=80% vs L=52%** (the brief's proposal) | 17.3 | **1.67:1** | **FAIL** |
| L=72% vs L=45% | 19.2 | 1.81:1 | FAIL |
| L=60% vs L=32% | 28.4 | 2.62:1 | FAIL |
| L=55% vs L=28% | 31.1 | 2.93:1 | FAIL (just misses) |
| **L=63% vs L=30%** | 33.0 | **3.05:1** | **PASS** (both vs bg ≥ 3:1) |

Base accent `#FF8000` = HSL(30.1°, 100%, 50%), CIELAB L\* 67.1, contrast 7.44:1 against `#121212`.

**Minimum ΔL\* at a single hue that satisfies both constraints simultaneously (≥3:1 between segments AND
≥3:1 for the darker segment against the dark background): ≈ 33, at every saturation tested (100%, 70%, 45%).**

---

## 3. Recommendations, ranked by strength of evidence

### 1. Do not ship L=80% vs L=52%. — STRONGEST (normative + measured)
That pair measures 1.67:1, half the required 3:1. It is the exact configuration Brewer calls inappropriate for
nominal data ("inappropriate to imply order... by using a light-to-dark single-hue sequence"), and it is
below the floor that WCAG 1.4.11 and G209 require when no border is present. ΔL\* 17 simply is not enough.

### 2. Add a legend, even if brief. — STRONG (normative + design-system consensus)
Brewer's whole framework presumes the reader can map a mark back to a class name; her own simultaneity
warning says differences visible in a legend are not automatically visible in the graphic. With no legend, a
lightness-only encoding is **unfalsifiable** — the reader cannot tell which fill is usage and which is away.
This is the cheapest fix and it converts the lightness ramp from "implied quantitative ranking" into "labelled
pair", which is exactly Brewer's Paired-scheme use case. Do this regardless of which colour option is chosen.

### 3. Push the pair to ΔL\* ≈ 33+, or use a 3:1 separator hairline. — STRONG (W3C G209 Example 2)
Two paths, both explicitly sanctioned:
- **Fill-only:** `#FFAE5C` (usage, L\* 77.5) + `#A35200` (away, L\* 44.0) → 3.04:1 between them,
  10.22:1 and 3.36:1 against `#121212`. ΔL\* 33.5.
- **Fill + hairline:** keep a modest lightness step (say L=68% / L=45%) and draw a 1dp separator at the
  segment boundary that has ≥3:1 against **both** fills. G209 Example 2 validates precisely this at 1.1–1.8:1
  fill contrast. ⚠ Measured caveat: a light separator on a dark theme fails against the *light* fill
  (`#FFFFFF` is only 1.46:1 vs a light tint); a mid-grey `#757575` reaches 3.16:1 vs the light fill and
  1.83:1 vs `#FF8000` — so the separator must be *checked against both fills*, not just the background.

### 4. Treat the darker fill as visually brown, not orange. — MODERATE (Ware, peer-reviewed book, qualitative)
The lighter fill should be the **usage** fill and the darker one the **away** fill, not vice versa. Ware:
brown *"requires that there be a reference white somewhere in the vicinity"* and *"There is no such thing as
an isolated brown light in a dark room."* A dark saturated orange on `#121212` reads as brown; that is a
qualitatively correct cue for "away/inactive" but it is an uncontrolled hue shift, and it means the pair is
not strictly "one hue." Verify on-device.

### 5. Consider desaturating the away fill instead of / as well as darkening it. — MODERATE (Brewer)
Brewer's own sequential single-hue schemes vary *"lightness and saturation levels"* together, and her 3-class
Oranges ramp (`#FEE6CE / #FDAE6B / #E6550D`) spans ΔL\* ≈ 19 *and* a saturation drop. Dropping saturation on
the away fill buys lightness separation without deepening it toward brown, which also helps against the dark
background. This is a design choice, not a validated rule.

### 6. Do not rely on colour to let users *order or compare* the two segments. — STRONG (Cleveland & McGill)
Colour is excluded from Cleveland & McGill's quantitative hierarchy because it *"might be regarded as better for
encoding categories rather than real variables."* Users will be able to tell the segments **apart**; they will
not be able to read a **quantity** off the colour. If HAT ever wants someone to judge "is the orange part
bigger than the brown part," that must come from segment **height** (length/position), not from which shade
looks heavier. This is already true of a stacked bar and should not be fought.

### 7. Guard the accent's own contrast against the dark surface. — MODERATE (measured)
`#FF8000` is 7.44:1 against `#121212` — fine. But if the away fill is pushed dark (option 3), it drops toward
3:1 against the background and below 3:1 against a *lighter* card surface (`#1E1E1E` measured 2.99:1). Keep
the darker fill above 3:1 against whatever surface the chart actually sits on, not just the page background.

---

## 4. Bottom line for the designer

Lightness-only is **not** a known failure mode for two related categories — it is Brewer's documented Paired
scheme, and WCAG 1.4.1 explicitly counts a ≥3:1 lightness difference as a legitimate non-colour distinction.
But it is defensible **only** at a much larger separation than the brief proposes (ΔL\* ≈ 33, not 17), and
only with a legend, because without one the lightness ramp implies an ordering that nominal data does not have.
At L=80%/L=52% it fails on both counts.