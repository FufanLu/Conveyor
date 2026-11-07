---
name: Conveyor Lines
description: Order fulfillment drawn as a transit network; every order rides a line, every saga step and courier scan is a station.
colors:
  ground: "#f4f5f3"
  surface: "#ffffff"
  ink: "#141414"
  ink-2: "#4a4e4a"
  ink-3: "#6b706b"
  rule: "#d6d9d5"
  rule-2: "#e7e9e6"
  band: "#141414"
  band-2: "#232523"
  band-ink: "#ffffff"
  band-ink-2: "#b9bdb9"
  east: "#1f4bd8"
  central: "#0b8043"
  south: "#f2a900"
  west: "#8e3ccb"
  fail: "#e0351b"
  fail-on-band: "#ff6a50"
  unreached: "#b3b8b3"
typography:
  display:
    fontFamily: "Barlow Semi Condensed, Barlow, sans-serif"
    fontSize: "clamp(40px, 6.4vw, 76px)"
    fontWeight: 700
    lineHeight: 0.95
    letterSpacing: "0.02em"
    fontFeature: "tnum"
  headline:
    fontFamily: "Barlow Semi Condensed, Barlow, sans-serif"
    fontSize: "44px"
    fontWeight: 600
    lineHeight: 1
    letterSpacing: "-0.01em"
  title:
    fontFamily: "Barlow Semi Condensed, Barlow, sans-serif"
    fontSize: "22px"
    fontWeight: 600
    lineHeight: 1.2
    letterSpacing: "-0.01em"
  figure:
    fontFamily: "Barlow Semi Condensed, Barlow, sans-serif"
    fontSize: "20px"
    fontWeight: 600
    lineHeight: 1.1
    fontFeature: "tnum"
  body:
    fontFamily: "Barlow, system-ui, sans-serif"
    fontSize: "15px"
    fontWeight: 400
    lineHeight: 1.47
    fontFeature: "tnum"
  label:
    fontFamily: "Barlow Semi Condensed, Barlow, sans-serif"
    fontSize: "12px"
    fontWeight: 600
    lineHeight: 1
    letterSpacing: "0.07em"
  plate:
    fontFamily: "Barlow Semi Condensed, Barlow, sans-serif"
    fontSize: "13px"
    fontWeight: 600
    lineHeight: 1.2
    letterSpacing: "0.05em"
rounded:
  plate: "2px"
  control: "3px"
  pill: "17px"
  bullet: "50%"
spacing:
  s1: "4px"
  s2: "8px"
  s3: "12px"
  s4: "16px"
  s5: "24px"
  s6: "32px"
  s7: "48px"
  s8: "64px"
components:
  button-primary:
    backgroundColor: "{colors.ink}"
    textColor: "{colors.band-ink}"
    typography: "{typography.title}"
    rounded: "{rounded.control}"
    padding: "0 16px"
    height: "40px"
  button-primary-hover:
    backgroundColor: "{colors.band-2}"
  button-ghost:
    backgroundColor: "transparent"
    textColor: "{colors.ink}"
    rounded: "{rounded.control}"
    padding: "0 16px"
    height: "40px"
  button-ghost-hover:
    backgroundColor: "{colors.ink}"
    textColor: "{colors.band-ink}"
  input:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    rounded: "{rounded.control}"
    padding: "0 12px"
    height: "40px"
  plate-status:
    backgroundColor: "{colors.ink}"
    textColor: "{colors.band-ink}"
    typography: "{typography.plate}"
    rounded: "{rounded.plate}"
    padding: "3px 7px"
  plate-fail:
    backgroundColor: "{colors.fail}"
    textColor: "{colors.band-ink}"
    typography: "{typography.plate}"
    rounded: "{rounded.plate}"
    padding: "3px 7px"
  route-bullet:
    backgroundColor: "{colors.east}"
    textColor: "{colors.band-ink}"
    rounded: "{rounded.bullet}"
    size: "28px"
  facet-chip:
    backgroundColor: "transparent"
    textColor: "{colors.band-ink}"
    rounded: "{rounded.pill}"
    padding: "0 12px"
    height: "34px"
  sign-band:
    backgroundColor: "{colors.band}"
    textColor: "{colors.band-ink}"
---

# Design System: Conveyor Lines

## Overview

**Creative North Star: "The Transit Network"**

Conveyor is drawn as a transit system in the Vignelli and Beck tradition: schematic lines plus station signage. Orders ride lines, saga steps and courier scans are stations, and fulfillment centers are interchanges. The ground is a pale platform wall. Black sign bands carry white legends set in Barlow Semi Condensed. Each region owns one saturated line colour, and vermilion means failure and nothing else.

Density is high but legible. Tables, timetables and figures all use tabular numerals, and identifiers such as order ids and SKUs are set in the sign face because they are the headlines, not metadata. Ornament is absent. Structure comes from 2px ink rules under section heads, 1px rules between rows, and the lines themselves. Motion is tied to real events only: a station lights, the train advances, the courier bullet moves, the live dot blinks.

This system rejects the card-grid SaaS dashboard and the marketplace-clone storefront. Interactions expand in place rather than opening modals: a catalog row opens into its buy panel, and an order's status reads as a line rather than as a card.

**Key Characteristics:**
- Platform-wall ground with full-bleed black sign bands at the top of every page
- One saturated line colour per region; route bullets are lettered circles (E, C, S, W)
- Stations are ringed dots on 6px lines; bends are 45 degrees
- Vermilion appears only on failure: plates, termini, error rules
- Tabular numerals everywhere; identifiers set in the sign face
- Flat surfaces separated by rules, never by shadows

## Colors

A neutral signage palette (black, white and wall grey) carries four saturated region line colours and a single alarm colour.

### Primary
- **Line Cobalt** (east): The East line, and the system's default line colour. It is also the focus ring (3px outline, 2px offset) and the wordmark glyph.

### Secondary
- **Line Green** (central): The Central line.
- **Line Amber** (south): The South line. Bullets and the train on this line take ink-coloured letters instead of white, because white on amber is illegible.
- **Line Violet** (west): The West line.

### Tertiary
- **Signal Vermilion** (fail): Failure only. It marks failed status plates, the 45-degree failure branch and its square terminus, the 4px top rule of error blocks, and the failure square in the platform readout.
- **Signal Vermilion, band tint** (fail-on-band): The failure state written as text on a black sign band, where the base vermilion is too dark to read.

### Neutral
- **Platform Wall** (ground): Page background below the bands.
- **Paper White** (surface): Maps, expanded rows, inputs, failure notes, and the scan rows of the report log.
- **Signal Black** (ink / band): Text, primary buttons, rules under section heads, and the sign bands. Band and ink share one value.
- **Band Shade** (band-2): Primary button hover, and dividers inside bands.
- **Graphite** (ink-2): Secondary text, table heads, and field labels.
- **Ash** (ink-3): Placeholders, unreached station labels, and gap annotations on the line.
- **Rule Grey** (rule) and **Hairline** (rule-2): Row dividers, map frames, and disabled fills.
- **Band White** (band-ink) and **Band Silver** (band-ink-2): Primary and secondary legends on black bands.
- **Unreached** (unreached): Stations not yet reached, the dotted line ahead, and the grey cross-region trunks on the network map.

### Named Rules
**The Vermilion Is Failure Rule.** The fail colour marks only REJECTED, PAYMENT_FAILED, CANCELLED, DELIVERY_FAILED, and error states. It is never an accent, a CTA, or emphasis.

**The One Line Per Region Rule.** A region's colour appears only where that region's line, bullet, courier, or FC cluster is drawn. Do not borrow region colours for chrome or decoration.

## Typography

**Display Font:** Barlow Semi Condensed (with Barlow, sans-serif), the sign face
**Body Font:** Barlow (with system-ui, sans-serif)

**Character:** A narrow, upright signage face for everything a passenger would read from a distance (legends, ids, figures, buttons, labels), paired with its own regular-width family for running text. Both families set numerals tabular by default.

### Hierarchy
- **Display** (700, clamp 40 to 76px, 0.95): The order id at the top of the order page. The identifier is the headline.
- **Headline** (600, 44px, 34px on mobile, 1): Page names on sign bands, such as "Network".
- **Title** (600, 22px): Section heads, which always sit on a 2px ink rule.
- **Figure** (600, 20px): Values in band readouts (now, next, total, arrives by). The platform "now and next" readout steps up to 700 at 22px uppercase.
- **Body** (400, 15px, 1.47): Running text and table cells (13 to 14px in tables). Explanatory copy is capped at about 60ch.
- **Label** (600, 12 to 13px, 0.04 to 0.08em, uppercase): Field labels, table heads, and readout terms. Labels always name a value or a control directly beside them.
- **Plate** (600, 13px, 0.05em, uppercase): Status plates and live-state text.

### Named Rules
**The Identifiers Are Headlines Rule.** Order ids, SKUs and shipment ids are always set in the sign face at 600 with tabular figures and 0.03em tracking, at whatever size the context gives them.

**The Tabular Everywhere Rule.** `tabular-nums` is set on the body. Figures in columns, timestamps and money must align.

## Layout

Pages stack full-bleed bands over a centred column (max 1320px, 24px gutters, 16px under 760px). Every page opens with the black top bar (56px, 52px on mobile) followed by a black sign band that holds the page's headline and its key readout. Below the band, content sits in sections, each introduced by a section head: the title on the left, a quiet meta note on the right baseline, and a 2px ink rule beneath.

Spacing follows a 4px-based scale (s1 to s8: 4, 8, 12, 16, 24, 32, 48, 64px). Section heads open with s6 above. The order route grid is map plus a 320px log column. The ops grid is 1.55fr to 1fr. Both collapse to one column (at 900px and 1100px respectively). On mobile the top nav wraps to a full-width scrolling row with 44px targets, and the line diagram turns vertical.

Lines on diagrams are schematic. Directions are simplified, and segment lengths carry real data where stated (elapsed time on the order line, distance on the route map).

## Elevation & Depth

The system is flat. No surface casts a drop shadow. Depth comes from tone (black band over wall grey, white surface on wall grey) and from rules (1px rule-grey frames, 2px ink under heads). `box-shadow` appears only as an inset stroke: the 1px outline of an open plate, the 4px ink rail on an expanded catalog row, the selected region pick, and the 3px halo of the live dot.

### Named Rules
**The Rules Not Shadows Rule.** Separate things with a rule or a tone change. When a box-shadow is used, it is inset or a ring and acts as a stroke, never as lift.

## Shapes

Corners are near-square. Controls and inputs use 3px, and plates use 2px. Round forms are reserved for transit vocabulary: route bullets and stations are circles, and facet chips and region picks are full pills (17 to 20px). Lines are 6px with round caps and joins. They run horizontal, vertical, or at 45 degrees (octolinear) and never curve. Termini are squares: vermilion for failure, an outlined square for the door that fills ink on delivery. FC interchanges are heavy ink rings (3 to 3.5px stroke) with a dot. Unreached track is a 2px dotted stroke (2/6 dash).

## Components

### Buttons
- **Shape:** Near-square (3px), 2px border, 40px minimum height, sign face 600 at 16px.
- **Primary:** Ink fill with white legend. Hover goes to band shade. Active nudges down 1px. Disabled uses rule-grey fill with ash text.
- **Ghost:** Transparent with an ink border, and it inverts to ink on hover. On a band, its border is band silver, and on hover it inverts to white with ink text.
- **Band inverse:** The catalog search button is a white button on the black band, 56px tall.

### Status Plates
- **Style:** Small uppercase sign-face slabs (2px radius). In-progress states are solid ink. Opening states (PLACED, ORDERED) are outlined. Failure states are vermilion.
- **On bands:** The default plate inverts to white with ink text.

### Route Bullets
Lettered circles (28px; 20px small; 44px large) filled with the region colour and carrying E, C, S or W in the sign face at 700. They stand for a region wherever one is named: delivery destination, legend counts, region picks, the train, and the courier.

### Chips
- **Facets (on band):** 34px pills with a dark grey border and white text, plus a count. Pressed facets fill white.
- **Region picks:** Pills with a rule-grey border and a route bullet. When selected, the border thickens to 2px ink using an inset ring.
- **Segmented filter:** Ink-bordered strip of 34px buttons with rule dividers. The pressed button fills ink.

### Inputs / Fields
- **Style:** White, 1px ash border, 3px radius, 40px tall. Hover darkens the border to ink. Field labels sit above in the uppercase label style.
- **Focus:** Global 3px cobalt outline, 2px offset.
- **Hero search:** Borderless white field, 56px tall, sign face at 24px, on the shop band.

### Navigation
The top bar is a black band holding the wordmark (line glyph plus "Conveyor" at 700/22px), the nav, a "demo data" plate, the user, and ghost buttons. Nav items are sign face 600/16px in band silver. Hover turns them white. The current page is white with a 4px white underline flush with the band's bottom edge.

### Live State
An uppercase plate-style label with an 8px dot. While live, the dot is ink, ringed, and blinks in two steps (1.6s). While reconnecting, it is a hollow ring. When closed or connecting, it is unreached grey.

### Line Diagram (signature)
The order's line runs full width. Station rings are 9px when reached and 6px when not. The travelled segment is drawn in the region colour; the track ahead is dotted unreached grey. Segment lengths are proportional to real elapsed time, and the gaps are annotated (+2.4 s). Labels alternate above and below to avoid collisions. The train is the region bullet with a pulsing ring while live. A failure leaves the line at 45 degrees and ends in a vermilion square terminus with a vermilion label. On narrow screens the line runs vertically.

### Route and Network Maps (signature)
These are white framed panels (1px rule). The route map runs from the FC interchange to the door square, and the courier bullet moves along the line in real time. The network map draws region lines in colour, cross-region allocation trunks in unreached grey at 3px, and FCs as interchange rings. Labels are haloed with a 4px ground-coloured stroke. Small multiples (inset dot maps with 1px rule range rings) sit below.

### Expandable Row
Catalog results are timetable rows divided by 1px rules. Opening a row turns it into a white surface with a 4px ink rail on the left and a 2px ink bottom rule, and the buy panel unfolds inside the row. Nothing opens in a modal.

## Do's and Don'ts

### Do:
- **Do** open every page with a black sign band carrying the page's headline and its live readout.
- **Do** draw status as stations on a 6px line, with 45-degree bends and ringed dots.
- **Do** mark a region with its route bullet and line colour, and use ink letters on the South amber.
- **Do** set ids, figures and timestamps in tabular numerals, with ids in the sign face.
- **Do** introduce each section with a title on a 2px ink rule and a quiet meta note at the right.
- **Do** expand in place (rows, panels) rather than opening a modal.

### Don't:
- **Don't** use vermilion for anything except failure and error states.
- **Don't** use region colours for chrome, emphasis, or decoration.
- **Don't** lift surfaces with drop shadows; separate them with rules and tone.
- **Don't** draw curved or free-angle lines on diagrams; use horizontal, vertical and 45 degrees only.
- **Don't** let diagram lines run through label text; halo labels with the ground colour or route the line around them.
- **Don't** add uppercase labels that don't name an adjacent value or control.
