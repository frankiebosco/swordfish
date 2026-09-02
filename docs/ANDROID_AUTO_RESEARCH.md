# Android Auto — Can We Draw a Real Gauge on the Head Unit?

**Researched 2026-08-19 against current Google documentation.**

**Short answer: yes.** A custom-drawn gauge on the head unit is achievable. It requires
declaring the app as a **navigation** app, taking a `Surface` from the
`NavigationTemplate`, and distributing via **Play Internal App Sharing** rather than
sideloading. The one hard cost is a **$25 Play Console account**; there is no app review.

This document supersedes the earlier "phone-screen-first, head unit gets a text list"
plan, which was based on an incorrect assumption that custom drawing was closed to
non-approved apps.

---

## 1. Surface access is real, and broader than expected

Three app categories can obtain a drawing `Surface` — not navigation alone:

| Category | Templates with surface access |
|---|---|
| **Navigation** | `NavigationTemplate`, `MapWithContentTemplate` |
| **POI** | `MapWithContentTemplate` |
| **Weather** | `MapWithContentTemplate` |

On that surface you may render with the **Canvas API**, a `VirtualDisplay` +
`Presentation`, or **Jetpack Compose** via `ComposeView`. It is a genuine rendering
surface — the same mechanism Google Maps and Waze use to draw their live maps — not a
templated list.

### Manifest requirements

```xml
<uses-permission android:name="androidx.car.app.NAVIGATION_TEMPLATES" />
<uses-permission android:name="androidx.car.app.ACCESS_SURFACE" />

<service android:name=".SwordfishCarAppService" android:exported="true">
  <intent-filter>
    <action android:name="androidx.car.app.CarAppService" />
    <category android:name="androidx.car.app.category.NAVIGATION" />
  </intent-filter>
</service>
```

`ACCESS_SURFACE` is the permission that actually unlocks drawing; the category
declaration is what makes the app eligible to request it.

### Getting the surface

```kotlin
carContext.getCarService(AppManager::class.java).setSurfaceCallback(callback)

class GaugeSurfaceCallback : SurfaceCallback {
  override fun onSurfaceAvailable(c: SurfaceContainer) { /* c.surface, c.dpi, c.width, c.height */ }
  override fun onVisibleAreaChanged(area: Rect) { /* area likely unobstructed */ }
  override fun onStableAreaChanged(area: Rect) { /* minimum guaranteed visible */ }
  override fun onSurfaceDestroyed(c: SurfaceContainer) { /* release */ }
}
```

**Design consequence:** head-unit geometry is not known ahead of time. `SurfaceContainer`
carries width/height/DPI at runtime, and `onVisibleAreaChanged` / `onStableAreaChanged`
report which rectangle is actually unobstructed by host chrome. The gauge must lay itself
out from those callbacks, not from fixed pixel positions. Anything critical belongs inside
the **stable area**.

---

## 2. The distribution problem, and its exact solution

### Plain sideloading does NOT work

Android Auto's developer-mode "Unknown sources" toggle exists, and it is widely written
about, but Google's testing documentation is explicit that it does not cover this case:

> "This setting applies to media, messaging notifications, and parked apps but **doesn't
> apply to apps built using the Android for Cars App Library**."

So the popular "enable Unknown Sources and sideload" advice is real, but it applies to
other app types. A Car App Library app installed by `adb install` will not appear on a
real head unit.

### Internal App Sharing does work, with no review

From the same document:

> "To test your app in real vehicles, you must install it from a trusted source such as
> Google Play... You can use **Internal App Sharing** or an **Internal Test Track** to
> distribute your app to devices **without going through the Google Play review
> process**."

Internal App Sharing:

- **No app review** — neither the standard review nor the car-specific manual review
- **No car app category declaration** to the Play Console
- Requires a **Play Console developer account — $25 one-time**
- Upload an APK/AAB, get a shareable link, install from it on your own phone
- Up to 100 testers; links expire after 60 days (re-share to refresh)
- Debuggable artifacts allowed; version codes may be reused

That is the whole cost of head-unit access: $25 once, and an upload step in the build
loop instead of `adb install`.

---

## 3. The quality criteria are policy, not enforcement

This is the finding that makes the project viable, and it is worth being precise about.

Google publishes car app quality criteria that would, read naively, forbid what we want
to build:

- **`SA-1` (Screen Animation)** — "The app must not display animated elements on the
  screen, such as animated graphics or video." Exception for canvas animations while
  parked.
- **`NF-2` (Navigation Functionality)** — "The app draws only map content on the surface
  of the navigation templates... Additional information relevant to the drive, speed
  limit, road obstructions, etc., can be drawn on the safe area of the map."
- **`NF-6`** — the app must handle navigation intents and, on
  `onAutoDriveEnabled()`, simulate navigation for reviewer verification.
- **`IU-1`** — restrictions on displaying images.

**These are Play Store publishing requirements enforced by manual review**, not technical
restrictions in the framework:

> "Apps for cars are subject to an additional manual review beyond normal Play Store
> review processes. Your app is tested to ensure compliance against the applicable
> criteria."

Since Internal App Sharing bypasses review entirely, none of these gate a personal build.
The framework will render whatever we draw.

**What this means in practice:** Swordfish is a **sideload-only project by design**. It
would not survive Play Store review as a navigation app — it provides no turn-by-turn
guidance (`NF-6`), draws non-map content (`NF-2`), and a live gauge is inherently animated
(`SA-1`). That is an accepted, deliberate trade, not an oversight. Publishing was never
the goal.

---

## 4. Turn-by-turn is not technically required

Confirmed explicitly: real navigation is an `NF-6` **policy** requirement, not a framework
one. The technical minimum for a working navigation app is:

1. `CarAppService` declaring `androidx.car.app.category.NAVIGATION`
2. `NAVIGATION_TEMPLATES` + `ACCESS_SURFACE` permissions
3. A `Session` returning a `Screen` whose `onGetTemplate()` returns `NavigationTemplate`
4. `NavigationManager` lifecycle calls

`NavigationManager` deserves a note: it is described as required for proper functioning,
and it is how the app tells the host it is actively navigating. Practically, Swordfish
should call `navigationStarted()` when a drive begins so the host treats it as the active
navigation app, `updateTrip()` periodically, and `navigationEnded()` when done. It must
also implement `NavigationManagerCallback.onStopNavigation()` to shut down gracefully when
the host asks — e.g. when the user starts Google Maps.

**Open question for the build phase:** what `Trip` object to pass to `updateTrip()` when
there is no route. A trip with travel estimates but no steps may satisfy the host; this
needs empirical testing on the DHU. Worst case we present a nominal "destination" and let
the estimates read as the fuel budget — which arguably suits the theme.

---

## 5. Interaction is deliberately limited

Surface interaction is restricted to a small set of `SurfaceCallback` methods —
`onClick`, `onScale`, `onScroll`, `onFling`. There is no arbitrary touch handling, no
text input, no scrolling list of settings on the surface.

**Design consequence:** all configuration — crew masses, cargo, fill-up confirmation,
units — happens on the **phone**, not the head unit. The head unit is a read-only
instrument with at most a tap to cycle display modes.

---

## 6. Development loop

| Stage | Tool | Review? |
|---|---|---|
| Day-to-day iteration | **Desktop Head Unit (DHU)** emulator | No — pure local, `adb` install |
| Real-car verification | **Internal App Sharing** link | No |
| Public release | Play Store | Yes — and we would fail. Not pursued. |

The DHU is the workhorse: it emulates a head unit on the development machine, takes
locally-installed builds, and needs no Play distribution at all. Internal App Sharing is
only needed when validating in the actual car.

---

## 7. What this means for Swordfish's design

**Head unit is the product.** The phone screen is demoted to a development and
configuration surface.

**The gauge must survive a hostile layout environment.** Unknown resolution, unknown DPI,
host chrome overlapping the edges. Lay out from `SurfaceContainer` dimensions and the
stable-area rect; scale everything; assume nothing.

**Dark theme is mandatory.** `CarContext.isDarkMode()` must be honoured and redraws
triggered from `Session.onCarConfigurationChanged()`. Convenient for us — a KSP-styled
instrument wants a dark palette anyway.

**Full instrument panel — this is the point.** The `SA-1` "no animated elements" rule is
a Play Store criterion we are already outside of, and a static delta-V readout would be a
worse version of the trip computer the car already has. Build the navball. Build the six
readouts. The whole premise is that driving efficiently should feel like flying a
spacecraft, and a spacecraft has an instrument panel.

Two things keep this from being irresponsible rather than merely silly:

- **A glanceable primary.** One element — the Δv figure — must be readable in the half
  second a driver actually spares. Everything else is there to reward a longer look while
  stopped, or a passenger's attention. Density is fine; an undifferentiated wall of
  equal-weight numbers is not.
- **Nothing that demands interaction.** The panel is read-only. No element should invite
  the driver to reach for the screen mid-drive.

See `docs/INSTRUMENT_PANEL.md` for the layout.

**Cluster display is a stretch goal.** Navigation apps can optionally draw to the
instrument cluster behind the wheel via `androidx.car.app.category.FEATURE_CLUSTER`.
Whether the ND2's cluster supports it is unknown and untested — but a Δv readout in the
gauge binnacle is the ideal end state, so the category is worth declaring early.

---

## Sources

- [Draw maps — Android for Cars](https://developer.android.com/training/cars/apps/library/draw-maps)
- [Test Android apps for cars](https://developer.android.com/training/cars/testing)
- [Build a navigation app](https://developer.android.com/training/cars/apps/navigation)
- [Car app quality criteria](https://developer.android.com/docs/quality-guidelines/car-app-quality)
- [Play Internal App Sharing](https://play.google.com/console/about/internalappsharing/)
- [Use the Android for Cars App Library](https://developer.android.com/training/cars/apps)
- [Test using the Desktop Head Unit](https://developer.android.com/training/cars/testing/dhu)
