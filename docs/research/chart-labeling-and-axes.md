# Labeling a Two-Segment Stacked Bar: Legend vs. Direct Labels, and Axis Units

Research brief. Primary/academic sources prioritised; blogs excluded unless they quote a
primary source verbatim. Every claim carries its source. Unverifiable items are marked
UNVERIFIED and are **not** used to support any recommendation.

---

## 1. Verdict

**Use direct labels on the bars; do not ship a legend.**

Strength of evidence: **Moderate for the mechanism, weak for the magnitude.**

- The *mechanism* is well supported and consistent across independent authorities: a legend
  forces the reader to hold a colour→name mapping in working memory and re-look it up
  repeatedly. Removing that lookup is a real, replicated cost, and it scales with the number
  of series. For a **two**-series chart the mapping is small enough to eliminate entirely.
- The *magnitude* of the win for a 2-series stacked bar is **not** established. I found no
  controlled study that measures comprehension accuracy or time for a 2-series stacked bar
  with a legend versus direct labels. The supporting evidence is (a) one controlled
  experiment on **line graphs** with **9 series** (Milroy & Poulton 1978), (b) a
  crowdsourced study that found direct labelling of *annotations* gave **no advantage** over
  indirect labelling (Chun 2020), and (c) practitioner authority (Tufte, Few, Shah & Hoeffner).
- For a mobile screen with 24 narrow bars, Tufte's and Few's *space/clutter* arguments apply
  directly and independently of any comprehension effect. This is the strongest practical
  case, and it does not depend on the disputed comprehension literature.

**Do not read this as "labels always beat legends."** See §3.

---

## 2. Findings with citations

### 2.1 Perceptual foundations (Cleveland & McGill)

- **Cleveland, W. S. & McGill, R. (1984). "Graphical Perception: Theory, Experimentation, and
  Application to the Development of Graphical Methods." *JASA* 79(387), 531–554.**
  https://www.tandfonline.com/doi/abs/10.1080/01621459.1984.10478080
  Full text read: https://math.pku.edu.cn/teachers/xirb/Courses/biostatistics/Biostatistics2016/GraphicalPerception_Jasa1984.pdf
  - Ranks ten elementary perceptual tasks by decoding accuracy. Top four:
    1. position along a common scale, 2. position along nonaligned scales,
    3. length/direction/angle, 4. area. Bottom of the list: shading/colour saturation.
  - **Directly relevant to this chart.** On divided (stacked) bar charts: *"the totals of A
    and B can be compared by perceiving position along the scale. Position judgments can also
    be used to compare the two bottom divisions in each case… All other values must be decoded
    by the elementary task of perceiving different lengths."* → In a 2-segment stack, **only
    the bottom segment gets a common baseline.** The top segment is read as a length against
    a floating baseline, one rank worse.
  - Design guideline they state: *"Graphs should employ elementary tasks as high in the
    ordering as possible."*
  - **They deliberately removed axis ticks and labels in their own experiments** to isolate
    the perceptual task, and flag this as a limit on external validity: *"We have no proof that
    our laboratory results are realistic and work in the field."* This is a caution against
    over-reading bare-minimum charts.
  - They also recommend *against* 3-D and, on principle, "radical surgery" on popular forms.
    Their proposed replacements (dot charts, framed-rectangle charts) are **not** what this
    app should do — the context (non-technical user scanning their own day) is not an analysis
    task. The ranking is useful; the prescriptions are not automatically transferable.

- **Cleveland, W. S. & McGill, R. (1985). "Graphical perception and graphical methods for
  analyzing scientific data." *Science* 229(4716), 828–833.** doi:10.1126/science.229.4716.828
  - The peer-reviewed companion summarising the same programme. Cited here as the canonical
    short-form reference for the ranking. **Note:** the task-ranking claim I quote is verified
    from the 1984 JASA full text, not from this Science article (which I did not obtain).

- **"A test of competing perceptual encoding hypotheses" (Cleveland, McGill & McGill).**
  **UNVERIFIED.** Repeatedly referenced in secondary literature and named in your brief, but I
  could not locate an accessible copy. It appears to be a Bell Labs / SUNY Buffalo technical
  report. Its existence is plausible; **I have not read it and make no claim about its content.**
  Do not cite it on my authority.

- **On the "redundant encoding" principle as a legend-vs-label rule: it does not exist in these
  papers.** I searched the full text of the 1984 JASA article: the string *"redundant coding"*
  occurs **0 times**; *"stacked"* occurs **0 times**; *"label"* occurs only twice, both times
  about *removing* axis labels in the lab. Cleveland & McGill's 1984 contribution is the
  **perceptual-task ranking**, not a legend/direct-label doctrine. The "redundant coding" idea
  is associated in the popular literature with *The Elements of Statistical Graphics*
  (Cleveland, McGill & McGill, 1988) — **UNVERIFIED**: I could not obtain the book text, so I
  cannot confirm the principle's wording or that it addresses legends at all. **Recommendation:
  do not cite "redundant coding" as a legend-vs-label authority.**

### 2.2 Tufte

- **Tufte, E. R. (1983/2001). *The Visual Display of Quantitative Information*, Graphics Press.**
  Full text read: https://ia800408.us.archive.org/15/items/tufte_visual_display_of_quantitative_information_low-res/tufte_visual_display_of_quantitative_information_low-res_text.pdf
  - **Measured fact about the book:** the word "legend" appears **3 times** in ~270,000
    characters. Tufte's case against legends is brief, not a sustained argument.
  - On integrating the key: *"integrate the caption and legend into the design so that the eye
    is not required to dart back and forth between textual material and the graphic."*
  - His "friendly vs. unfriendly data graphic" table states the target directly. **Friendly:**
    *"elaborately encoded shadings, cross-hatching, and colors are avoided; instead, labels are
    placed on the graphic itself; no legend is required."* **Unfriendly:** *"obscure codings
    require going back and forth between legend and graphic."*
  - "Above all else show the data" and the data-ink ratio are stated as a five-principle
    theory of data graphics (maximise data-ink, erase non-data-ink, erase redundant data-ink,
    revise).
  - **He does not frame this as a comprehension/accuracy claim.** It is an economy argument:
    ink that names a colour is not ink that shows data.
  - **Caveat:** Tufte is a normative essay, not experimental research. No data, no controls.
    Tufte also repeatedly argues for *deflating* charts to ink-minimal forms; his prescriptions
    are most defensible for print/静态 exhibits, and are a weaker fit for a touch screen where
    24 bars are each only a few pixels wide.

- **Tufte, E. R. (1990). *Envisioning Information*.** (Listed in your brief; see §4 — I did
  not verify the specific "words and numbers" passage.)

### 2.3 Stephen Few — the genuine counterweight

- **Few, S. (2008). "With Dashboards, Formatting and Layout Definitely Matter." Perceptual Edge.**
  https://perceptualedge.com/articles/Whitepapers/Formatting_and_Layout_Matter.pdf
  - **Few does not advocate deleting legends.** He argues legends should be *de-emphasised and
    relocated*: *"Placing a legend in the upper left corner of the dashboard—the most expensive
    real estate available… gives this information prominence it doesn't deserve. Few people
    would need to read this legend more than once. Assuming that it must take up permanent
    residence on the dashboard, it would be better placed in less prominent location."*
  - Also: *"Placing content in places that fail to support its use" — *"Positioning pieces of
    information apart from one another can cause confusion."* (i.e. keep the key near its data)

- **Few, S. "Dashboard Design for Rich and Rapid Monitoring." Perceptual Edge.**
  https://perceptualedge.com/articles/visual_business_intelligence/dd_for_rapid_monitoring.pdf
  - Consistent with the above, and again about *proximity and prominence*, not elimination:
    *"The legend takes up too much space and is much too visually dominant. The legend merely
    plays a support role in the dashboard, so it should not take up much space or demand much
    attention."* And criticises legends grouped far from the graphs they explain.
  - **This is the single most important counter-citation.** Few treats a legend as legitimate
    furniture that is badly placed, not as an error. For a 2-series chart his own logic points
    the *other* way from the usual reading of his work: with only two entries, a compact
    de-emphasised key costs almost nothing, and is a smaller total footprint than painting
    text into or beside 24 bars.

- **Few, S. "The Information Cannot Speak for Itself." Perceptual Edge.**
  https://www.perceptualedge.com/articles/ie/information.pdf
  - The stronger pro-direct-labelling case, from Few: on a 9-series bar graph with a legend,
    *"To interpret which bar represents which product, you must shift your attention back and
    forth between the legend and the bars again and again. This is time-consuming and
    frustrating."*
  - **Read this carefully as evidence:** the example is **9 series**, where the mapping really is
    costly. It is not evidence about 2 series.

### 2.4 Empirical / academic

- **Shah, P. & Hoeffner, J. (2002). "Review of Graph Comprehension Research: Implications for
  Instruction." *Educational Psychology Review* 14(1), 47–69.** doi:10.1023/A:1013180410169
  Full text read: https://nschwartz.yourweb.csuchico.edu/Shah,%20Hoeffner%20-%202002%20-%20Review%20of%20graph%20comprehension%20research%20Implications%20for%20instruction.pdf
  - **The single most on-point academic source.** It has a section titled **"Legend (or Key)
    vs. Labels"**. Verbatim: *"Because legends or keys require that graph readers keep
    referents in memory, legends pose special demands on working memory… Thus, the
    conventional wisdom is that graph designers should avoid using legends (except when labels
    would lead to too much visual clutter) and instead label graph features directly with
    their referents."*
  - **Two things to note, and they matter.** (a) The authors attribute this to **conventional
    wisdom**, citing Kosslyn (1994) — it is *not* presented as their own experimental result.
    (b) They attach an explicit **escape clause**: *"except when labels would lead to too much
    visual clutter."* For 24 bars, clutter is a live concern. This source cuts both ways and
    does not settle your case.
  - Their summary guideline #5: *"Reduce working memory demands. Keeping track of referents is a
    cognitively demanding task. Use meaningful colors, symbols, and labeling lines or bars,
    rather than legends, to reduce cognitive load."*
  - On colour: *"color does not accurately represent precise quantitative information"*
    (agreeing with Cleveland & McGill's rank-6) and *"is inadequate as the only source of
    precise quantitative information."*
  - On stacked/divided bars: *"pie charts are more accurate for making part/whole judgments
    than divided bar graphs are because divided bar graphs often require adding up information
    from different parts of the bar (Simkin & Hastie, 1986)"; but "divided bar charts may be
    better when absolute values as well as proportions are important to communicate
    (Kosslyn, 1994)."* → **Your chart stacks two *absolute durations*, not parts of a fixed
    total, so the absolute-value case applies and the pie-chart warning does not transfer.**

- **Milroy, R. & Poulton, E. C. (1978). "Labelling graphs for improved reading speed."
  *Ergonomics* 21(1), 55–61.** doi:10.1080/00140137908922241
  - **The only genuine controlled experiment I found** on labelling method vs. reading speed.
  - Secondary sources consistently report the finding as: **direct labelling of the functions
    accelerates decoding relative to a key/legend.** E.g. Sonning (Univ. of Helsinki), *Drawing
    on principles of perception: The line plot*: *"Line plots allow for direct encoding of
    attributes by placing labels inside the display rather than in an external key. Direct
    labeling accelerates decoding (Milroy & Poulton 1978; Parkin 1983, cited in Pinker 1990:
    114)."*
  - **Limits I must flag:** it is a **line graph** study, and per the IEEE abstract
    (https://ieeexplore.ieee.org/document/6500181/) it compared **three** labelling methods:
    *"direct labeling of the functions; a key inserted…"* I could **not** obtain the full text
    (Taylor & Francis returned 403), so I cannot verify N, effect size, or whether the
    comparison is 2-way. **Do not over-claim from this.**

- **Chun, R. (2020). "Giving Guidance to Graphs: Evaluating Annotations of Data Visualizations
  for the News." *Visual Communication Quarterly*.** doi:10.1080/15551393.2020.1749842
  - **The strongest contrary evidence, and it is recent.** Crowdsourced experiment on three
    real newsroom visualizations (NYT, Washington Post, WSJ). Verbatim from the abstract:
    *"Our results demonstrate that direct labeling offers no advantage over indirect
    labeling. More significantly, annotations on visualizations do no better to enhance
    comprehension than visualizations without them, contradicting data visualization
    orthodoxy."*
  - **Scope caveat, stated plainly:** this tests the **annotation layer** (callouts/guidance
    text), **not a legend/key**. It is evidence that the Tufte orthodoxy is not self-validating,
    and it is the best available reason to be humble. It is **not** a direct test of
    legend-vs-direct-label. Do not present it as one.

- **Knoll, C., Möller, T., Gregory, K. & Koesten, L. (2025). "The Gulf of Interpretation: From
  Chart to Message and Back Again." *CHI '25*.** arXiv:2310.05752 — https://arxiv.org/abs/2310.05752
  - Mixed-methods study with non-technical audiences (students, job seekers, designers,
    senior citizens) on eight real published charts. **Directly relevant to your user base.**
  - Finding: *"Despite being categorized as an 'easy' chart by the producer, the stacked bar
    chart was the hardest to understand in all aspects (harder than a Sankey diagram)."* Only
    ~70% of messages about their stacked bar were semantically correct.
  - **But the diagnosed cause was vocabulary, not labelling:** *"This mainly was because
    participants did not know the meaning of the gender pay gap… An explanation of the term
    could have helped participants grasp the information."*
  - **Implication for you:** the failure mode for non-technical readers of a stacked bar was
    *unfamiliar terminology*. The same risk applies to "offline time" / "away". Naming the
    segments in plain words matters as much as where the names sit.

- **Bailey, C. & Gleicher, M. (2025). "Anchoring and Alignment: Data Factors in Part-to-Whole
  Visualization." IEEE VIS 2025 (preprint).**
  https://graphics.cs.wisc.edu/Papers/2025/BG25/PartWhole-Preprint.pdf
  - Experiments on stacked bar and pie charts. Conclusion: *"Anchoring appears to be a key
    mechanism in perception of these charts, and partially explains their data-dependent
    performance."* Data value, alignment, and viewer rounding all drive accuracy.
  - **Actionable:** the *anchored* (bottom) segment is read more accurately than the
    unanchored one. This corroborates the Cleveland & McGill common-baseline point
    independently, and it means **segment order matters**: put the quantity the user most needs
    to read precisely at the bottom.

- **Talbot, J., Setlur, V. & Anand, A. (2014). "Four Experiments on the Perception of Bar Charts."
  *IEEE TVCG* 20(12).** https://vis.cs.ucdavis.edu/vis2014papers/TVCG/papers/2152_20tvcg12-talbot-2346320.pdf
  - Four experiments extending Cleveland & McGill's bar-chart work. Useful as the current
    rigorous treatment of bar-chart perception. **It contains zero mentions of "legend"** —
    it is about bar arrangement and ordering, not labelling. Cited here to show what the
    serious bar-chart perception literature does and does not cover.

- **Munzner, T. (2009). "A Nested Model for Visualization Design and Validation." *IEEE TVCG*
  15(6), 921–928.** https://www.cs.ubc.ca/labs/imager/tr/2009/NestedModel/NestedModel.pdf
  Full text read.
  - Four nested levels: domain problem → operation & data-type abstraction → visual encoding &
    interaction → algorithm. *"An upstream error inevitably cascades to all downstream levels."*
  - Four threat categories, memorably: *"wrong problem: they don't do that; wrong abstraction:
    you're showing them the wrong thing; wrong encoding/interaction: the way you show it doesn't
    work; wrong algorithm: your code is too slow."*
  - **Honest reading for your case:** the model is about *validating a design against a stated
    task*, and it is **silent on legends vs. direct labels**. It supplies the frame in which
    your chart should be justified (state the task; choose the encoding for that task), not a
    verdict on labelling. Citing Munzner for a legend decision would be a misattribution.
  - Its relevant contribution here: it insists you name the *operation*. "How much time did I
    spend on apps vs. away, per hour?" is a **compare/lookup** task on a **quantitative,
    ordered** data type — the case for length on a common baseline, and for anchoring the more
    important series at the bottom.

- **Ten Simple Rules for Better Figures — Rougier, N. P., Droettboom, M. & Bourne, P. E. (2014).
  *PLoS Comput Biol* 10(9), e1003833.** doi:10.1371/journal.pcbi.1003833
  - Rule 1, *Know Your Audience*, is the load-bearing one for you: *"the general public may be
    the most difficult audience of all since you need to design a simple, possibly approximated,
    figure that reveals only the most salient part of your research."*
  - Rule 7, *Do Not Mislead the Reader*: *"make sure to always use the simplest type of plots
    that can convey your message and use labels, ticks, title, and the full range of values when
    relevant."* Note: this argues for **retaining ticks/labels**, against stripping a mobile
    chart bare.
  - **Note on a common miscitation:** this paper is sometimes cited for axis labelling and units.
    I read the full text: "axis" appears **0 times** and "legend" **0 times**. It does not
    support a units convention. Rule 7 is a *do-not-truncate-the-axis* rule. Use it for that only.

### 2.5 Axis labelling with units

This part of the evidence base is **style-guide consensus, not experimental research.** I found
no controlled study measuring whether units in an axis title versus on tick labels change
reader accuracy. Treat the following as documented convention.

- **APS (American Physical Society) style note H-18, "Axis Labels and Scales on Graphs" (Nov 2012).**
  https://journals.aps.org/authors/axis-labels-and-scales-on-graphs-h18
  - The canonical formulation: quantity named, then unit, e.g. *Time (min)*.
  - Verbatim rules: *"Use standard abbreviations for the units."* / *"Add units of before
    nonstandard units for clarity."* / *"Use small spaces, not hyphens, dots, or other symbols,
    between the parts of compound units."* / *"Use decimal points (not commas)."* /
    *"Avoid the ambiguity of using more than one solidus."*
  - **The images that carry the "Preferred style" examples did not load for me** (they are
    rendered as pictures). The specific preferred *format string* — i.e. whether the unit goes
    in the axis title or on the topmost tick — is **UNVERIFIED**; I could only read the
    bulleted text. Do not attribute a specific format to APS on my authority. Check the page's
    images yourself.

- **Royal Statistical Society, "Principles and elements of visualisations."**
  https://royal-statistical-society.github.io/datavisguide/docs/principles.html
  - *"A good choice of axes, axis limits, labels and symbols can facilitate substantially the
    identification of patterns in the data, whereas poor choices for any of these elements can
    substantially hamper the extraction of information."* Also: *"all numeric axes, except for
    those showing time… should have gridlines"*, and that **gridlines should always be
    labelled** — *"We do not use gridlines without labels."*

- **UK Office for National Statistics, "Chart details: Axes and gridlines."**
  https://service-manual.ons.gov.uk/data-visualisation/guidance/axes-and-gridlines
  - Bar charts must start at zero: *"For charts that use a filled area or line starting from
    the axis to represent data values, the axis should always start at zero. This includes bar
    charts and area charts."*
  - **Relevant to your stacked bars:** the *total* is read by length, so a truncated axis
    misstates both segments. The one documented exception is time series where zero hides the
    trend, and even then they require a visible gap.
  - Gridline count guidance scales with display: *"use 6 to 10 gridlines for a standard desktop
    view"*; *"Smaller charts, such as those for mobile displays, should have fewer gridlines.
    In most cases, use three to six gridlines for a mobile view."* ← directly applicable.
  - *"When using a date axis with many dates, it is generally more effective to label spans of
    time (such as months, quarters, or years) rather than individual dates."* ← supports
    labelling every 3rd or 6th hour in the 24-bar day view rather than all 24.

### 2.6 Labelling what one bar represents when the x-axis has no ticks

- I found **no study** that tests the specific question of how to communicate "bar width = one
  hour" when an axis is unlabelled. **UNVERIFIED as an empirical question.**
- What *is* supported, from sources above: the x-axis must not simply vanish silently. Two
  independent lines support an explicit statement of the time unit: ONS requires time axes to
  be identifiable and labelled by span, and Rougier et al. Rule 7 warns against stripping
  labels/ticks. This is a **convention + logical-necessity argument, not an empirical result.**
  Say so if asked.

---

## 3. Genuine disagreement

1. **Tufte vs. Few on whether a legend should exist at all.**
   Tufte: *"labels are placed on the graphic itself; no legend is required"* — the legend is
   redundant data-ink. Few: a legend is a legitimate support element that should be
   **de-emphasised and placed near the data it explains**, not deleted. These are different
   positions, not merely different emphasis. **Do not resolve this falsely** — Few's own
   critique of a 9-series legend is genuinely weaker evidence for a 2-series chart.

2. **Expert orthodoxy vs. recent measurement.** The "avoid legends" position is presented by
   Shah & Hoeffner as *conventional wisdom* rather than as a finding. Chun (2020) found direct
   labelling of annotations gave **no advantage** over indirect, explicitly calling this a
   contradiction of "data visualization orthodoxy." The orthodox position is under active
   challenge, and at least one recent well-designed study failed to reproduce its predicted
   benefit.

3. **Shah & Hoeffner's own escape clause vs. their recommendation.** They advise direct labels
   *"except when labels would lead to too much visual clutter"* — while 24 bars on a phone is
   precisely a clutter case. Their source does not adjudicate it.

4. **Common practice vs. the clutter argument.** 24 bars is far past the point where inline text
   fits. Almost no practitioner would hand-label 24 stacked bars; a compact key is the normal
   answer. The research literature reviewed here does not test that regime.

5. **Tufte's general deflating agenda vs. this context.** Tufte optimises for printed exhibits
   read closely. Your user is glancing at their own day on a phone. Applying "erase
   redundant data-ink" literally to a 24-bar touch chart risks the bare-chart failure mode that
   Cleveland & McGill themselves flagged when they removed axis labels.

---

## 4. Explicitly NOT verified

- **"A test of competing perceptual encoding hypotheses" (Cleveland, McGill & McGill)** — could
  not locate an accessible copy. Contents unknown. **Do not cite on my authority.**
- **The "redundant encoding" principle** as it relates to legend-vs-direct-label. The phrase
  does **not** appear in the 1984 JASA paper (verified: 0 occurrences). Its attribution to
  *The Elements of Statistical Graphics* (1988) is plausible but **UNVERIFIED** — I could not
  obtain that book. **Do not use "redundant encoding" as a legend-vs-label citation.**
- **Tufte, *Envisioning Information* (1990)** — the specific "words and numbers" argument. I
  verified the *Visual Display* text; I did not verify the *Envisioning Information* passage.
- **Few, *Information Dashboard Design* / *Now You See It*** — I verified Few's position from
  his Perceptual Edge whitepapers, which are by the same author and consistent. I did not
  obtain the books. Book-page citations are **UNVERIFIED**.
- **Milroy & Poulton (1978)** — N, effect size, and exact comparison structure. Paywalled (403).
  The direction of the finding is corroborated by two independent secondary sources; the
  magnitude is unknown.
- **APS H-18's preferred format string** — the examples are images that did not load. Only the
  bulleted rules were readable.
- **Any controlled study of legend vs. direct label on a 2-series stacked bar.** **None found.**
  This is the central gap and it is a real gap, not an oversight.
- **"Empirical HCI measuring stacked-bar comprehension with vs. without a legend."** Not found.
  The nearest are Knoll et al. (2025), which found stacked bars hardest but attributed the
  failure to vocabulary, and Bailey & Gleicher (2025), which is about anchoring, not labelling.
- **Untick-free "one bar = one time unit" labelling.** No study found.
- **Whether units belong in the axis title or on the top tick** — style guides were reached but
  the decisive examples were images; treat the specific choice as a convention you are choosing,
  not a finding.

---

## 5. Recommendations

Each is tagged with what actually supports it. Where support is weak, the tag says so.

1. **Use a compact key; do not hand-label all 24 bars.**
   *Support: space/clutter — Tufte (1983, "no legend is required" / data-ink) and Few (2008,
   legends should not dominate; keep near the data), plus the ONS mobile gridline guidance
   showing that mobile budgets are tight.* **This deliberately departs from Tufte's stated
   preference**, because the clutter escape clause in Shah & Hoeffner and the physical reality
   of 24 bars make inline labels unworkable. Be aware you are choosing the weaker-supported
   side here.

2. **Place the key immediately under or above the plot, not in a corner, and de-emphasise it.**
   *Support: Few, strongly and repeatedly (perceptualedge.com whitepapers, quoted above).
   This is the one labelling decision where Few and Tufte do not conflict, and it is the
   cheapest win available.*

3. **Label the two segments in plain, non-technical words — and put the more important quantity
   in the more prominent position.**
   *Support: Knoll et al. 2025 (stacked bars hardest for non-technical audiences; the
   diagnosed cause was unfamiliar terminology), Munzner 2009 (name the task; here the task is
   app time), and the app's own title already says "Apps Usage + Offline Time".*
   For a non-technical user of their own phone data, prefer wording like **"On apps"** and
   **"Away from phone"** over "offline time", which is a network term and will read as
   "no Wi-Fi".

4. **Put the anchored/bottom segment first — make app time the bottom segment.**
   *Support: Cleveland & McGill 1984 (only the bottom division of a divided bar is comparable
   by position along a common scale; the rest fall to length), corroborated independently by
   Bailey & Gleicher 2025 (anchoring drives part-to-whole accuracy).* App usage is the primary
   quantity in the chart's title, so it gets the accurate encoding position. **Strongest
   evidence-backed recommendation in this brief.**

5. **State the y-axis unit explicitly, using the quantity-plus-parenthetical-unit convention.**
   *Support: APS H-18 (text rules verified), Rougier et al. 2014 Rule 7 (keep labels and the
   full range of values; do not mislead), ONS (bar charts start at zero).*
   Concretely: axis title **`Time (minutes)`** (or `Time in use (min)`). Put the unit in the
   title, not repeated on every tick — that is the standard convention and the one I'd pick;
   note APS's specific format examples were images I could not read, so treat the
   title-vs-tick choice as convention rather than verified prescription.

6. **Zero-base the y-axis.**
   *Support: ONS verbatim ("A bar chart's axis should never start above zero"), Rougier et al.
   Rule 7, and it is forced by the perceptual ranking — a truncated axis misstates a length.*
   For a day view, the natural full range is 0–60 min per hour-bar, which is also self-explaining.

7. **Label the x-axis by time span, not by every individual unit.**
   *Support: ONS ("it is generally more effective to label spans of time… rather than
   individual dates").* In the 24-hour view, label e.g. 00, 06, 12, 18 (or every 3 h) and state
   **"each bar = 1 hour"** in the axis title or a subtitle. The "each bar = 1 hour" statement is
   a **logic/convention necessity, not an empirically validated practice** — see §2.6. It is
   cheap and it removes the single most likely misreading, so do it on that basis and not on
   the basis of a study.

8. **Keep the title, and make it do the work the legend would otherwise do.**
   *Support: Tufte's data-text integration ("integrate the caption and legend into the design"),
   and Knoll et al.'s finding that missing terminology — not missing marks — caused failures.*
   The current title "Apps Usage + Offline Time" already names both series. Keep it; it is
   doing legend work for free.

9. **Use a colourblind-safe, luminance-differentiated pair, and do not rely on colour alone.**
   *Support: Tufte (colour must not be the only cue; his friendly/unfriendly table calls out
   colour-deficient viewers explicitly), Shah & Hoeffner 2002 (colour is rank-6 for quantity and
   "inadequate as the only source of precise quantitative information"), Cleveland & McGill
   1984 (shading/colour saturation is the least accurate channel).*

10. **Do not add a 3-D effect, drop shadows, or heavy gridlines.**
    *Support: Cleveland & McGill 1984 (depth cues degrade reading; they recommend against 3-D),
    Shah & Hoeffner 2002 (summarising Fischer 2000, Zacks et al. 1998 — slight accuracy loss
    from 3-D, though they honestly note other studies find little difference), Tufte
    (chartjunk).*

11. **Mobile gridline budget: about 4–6 y-axis gridlines.**
    *Support: ONS, explicitly for mobile.* ("three to six gridlines for a mobile view.")

---

## Bottom line

Drop the legend-as-ornament, keep a small key next to the bars, and put the effort into plain
wording and an explicit y-axis unit. The two recommendations with genuinely strong evidence are
**(4) app time in the bottom segment** and **(5) an explicit y-axis unit in the axis title**.
The "no legend at all" position is orthodox but **not** established for a 2-series stacked bar
on a small screen, and one recent study found the orthodox benefit absent.
