# Layout harness

Renders the **real** gauge panel to PNGs on the desktop, so panel layout can
be seen and measured without a phone, a car, or the DHU.

```bash
./gradlew :layout-harness:run      # 8 PNGs -> tools/layout-harness/out/
./gradlew :layout-harness:probe    # the same geometry as numbers
./gradlew :layout-harness:tune     # drag-to-tune window
./gradlew :layout-harness:test     # 27 tests
```

`run` and `probe` take about a second. `tune` opens a window: drag the red lines and the
green boxes, then **Save changes** writes the values straight into `PanelLayout.kt` (a
`.bak` is kept beside it) and prints exactly what changed.

**Saving does not reach the car.** Run `tools\dbg.bat` afterwards to build and install.

## Why it exists

Layout was previously tuned by shipping a build, driving, glancing at the
screen, and describing what looked wrong. That loop is minutes long, needs a
car, and `GaugeRenderer.kt` already carried the verdict:

> LAYOUT BUGS CANNOT BE DIAGNOSED FROM A SCREENSHOT. Several rounds of this
> were spent inferring the rect from pixel measurements of a DHU window and
> getting it wrong.

## How it works, and why it is not a mockup

`tools/layout-harness/src/main/kotlin/android/` contains Java2D-backed
stand-ins for the handful of `android.graphics` types the renderer touches
(10 Canvas methods, 7 Paint members, 5 Path methods). The module's
`build.gradle.kts` then adds **the app's own `GaugeRenderer.kt` as a source
directory** and compiles it against them.

So the pixels come from the shipping renderer, not a reimplementation. Edit
`GaugeRenderer.kt` and the next run reflects it with no sync step. A mockup
would drift within a week and be worse than useless.

This is necessary because `:app` unit tests run with
`isReturnDefaultValues = true`: there, `drawText` does nothing and
`measureText` returns 0, so rendering produces a blank image.

## The geometries

`Geometry.ND2` is not invented. It is what the head unit reported in
`SwordfishGeom` on the 2026-08-24 drive:

```
container=800x400  stableArea=Rect(24, 88 - 776, 388)
```

Note the stable area is **not** the container: 752x300 of an 800x400
surface, with an 88px top inset. The harness reproduces the car's own
derived numbers exactly -- `probe` prints `scope radius 78.8` against the
car's logged `navR`, from the same arithmetic.

### The two rects, and why they differ

The head unit reports both a STABLE and a VISIBLE area, and the difference is what
separates two events that otherwise look alike:

| Event | stableArea | visibleArea |
|---|---|---|
| Settled, full width | 752x300 | 752x300 |
| Collapsed (driver taps the tile) | **442x342** | 442x342 |
| Action strip open | 752x300 | 752x**364** |

**The stable rect never shrinks for the action strip** -- the strip GROWS the visible one.
A smaller stable area therefore always means a genuine layout change and must always be
honoured. Getting this backwards shipped a bug that made collapsed mode do nothing; see
`CollapsedViewTest` and the design notes of the same name.

## Fidelity limits -- read before trusting a pixel

- **Java2D is not Skia.** Glyph advances differ slightly, so text may sit a
  pixel or so from where the head unit puts it. Fine for PLACEMENT, which is
  what this is for.
- **Not evidence about anti-aliasing, colour management, or the real
  screen's contrast in daylight.** Only the car answers those.
- **The shim covers only what the renderer calls today.** Unsupported paths
  throw rather than silently drawing nothing -- a preview that quietly omits
  an element looks like a layout decision, which is the worst failure mode.

## What it found on the first run

All four were real bugs in shipping code, none visible from the car:

1. **START and SPEED overlapped by 1.2px** at WIDE. Hidden because
   `tripStartDeltaV` is null until the fuel tracker seeds, so the line only
   appears mid-drive.
2. **The trip-start line was clipped off the panel bottom by 6.1px** in
   radar mode -- `WIDE_READOUT_BAND` reserved space for the readout row but
   not the line beneath it.
3. **MINIMAL ignored its own element set**, drawing Isp and SPEED on a
   surface declared too short to stack anything, producing a smear.
4. **The design notes contradicted the code** about whether the navball is dropped
   in WIDE radar mode. The code and its tests were right.

Fixes are pinned by `CentreColumnSpacingTest`, `RadarReadoutBandTest` and
`MinimalModeContractTest` in `:physics`, so they are arithmetic a test can
check rather than something only a screenshot can reveal.

## The boxes

Seven elements have draggable boxes: NAVBALL, SCOPE, STATS, ISP, DELTA-V,
SPEED, READOUTS. Drag the middle to move, a corner to resize. Each is backed
by a `*_SCALE` / `*_DX` / `*_DY` triple in `PanelLayout`.

The COMPASS deliberately has none -- it is expressed in navball radii and
scales with the ball as one instrument.

Boxes are rectangles, not squares (the text blocks are much wider than they
are tall), and overlapping boxes resolve to the smallest match so inner
elements stay reachable.

`Boxes.kt` reproduces placement arithmetic from `drawWide`. If the renderer's
placement changes and this does not, boxes drift off their instruments --
which is what `BoxOverlayTest` exists to catch, by finding the circle in the
rendered pixels rather than comparing formula to formula.

**WIDE and NARROW hold SEPARATE values.** Each knob has a `_N` twin, and a box edits
whichever breakpoint the selected surface resolves to (the label shows `[collapsed]`).
They were shared at first, and a scope size dialled in against the full panel was also
applied to the collapsed one -- which has a fraction of the width and none of the slack.
`BreakpointIsolationTest` pins that neither side can affect the other.

## Adding a case

Edit `defaultCases()` in `Snapshot.kt`. Keep the set small: a hundred
snapshots nobody opens is the same as no snapshots.
