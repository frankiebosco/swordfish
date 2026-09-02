# Development Environment Setup

Everything needed to build the Swordfish Android app and its Android Auto head-unit
interface, on this Windows machine.

**Machine state as surveyed 2026-08-19:**

| Tool | Status |
|---|---|
| Java 17.0.6 LTS | ✅ present (`JAVA_HOME` unset, but Studio brings its own JDK) |
| Git 2.53.0 | ✅ present |
| Python 3.14.3 | ✅ present |
| Gradle 8.5 | ✅ at `C:\tools\gradle-8.5` (physics module only) |
| **Android Studio** | ❌ not installed |
| **Android SDK** | ❌ not installed |
| **adb** | ❌ not present |
| **Desktop Head Unit (DHU)** | ❌ not installed |

---

## Machine resources — no constraints

**97 GB free, 31.9 GB RAM** (confirmed 2026-08-19, after clearing space). A full Android
setup wants ~20 GB, so there is ample headroom for the SDK, Gradle caches, and anything
else the project needs.

| Component | Size |
|---|---|
| Android Studio | ~4 GB |
| Android SDK (platform + build-tools) | ~6 GB |
| Gradle caches (grows over time) | ~3–8 GB |
| Emulator system image (optional) | ~4 GB each |

### On the phone emulator

Still **optional, and not needed for the critical path**: an emulator cannot speak
Bluetooth to an OBD dongle, so every real telemetry test needs the physical phone
regardless. The Desktop Head Unit plus a real phone covers the whole loop.

But with 97 GB and 32 GB of RAM it is now cheap to install one, and there is a genuine use
for it: **testing the phone-side configuration UI** — crew masses, cargo, fill-up
confirmation, the debug view — without repeatedly deploying to the phone. If you want that
convenience, take the API 35 image; nothing about it hurts.

The recommendation is simply that it is a nice-to-have, not a prerequisite. Install Studio
first, get the DHU working, and add an emulator later if the config screens start feeling
tedious to iterate on.

---

## 1. Android Studio

### ⚠️ The download page will refuse this machine — use winget instead

The Android Studio download page runs a **client-side CPU check** and shows
*"Download Not Available — Your current device is not supported"* on this PC. The stated
minimum is "CPU microarchitecture after 2017 / Intel 8th gen", and this machine is an
**i7-4770 (Haswell, 4th gen, 2013)**.

**This is an age check, not a capability check.** Android Studio is a JVM application and
requires no instruction set Haswell lacks. This machine exceeds every other minimum in
Google's table:

| Requirement | Minimum | This machine |
|---|---|---|
| RAM | 8 GB (16 with emulator) | **32 GB** ✅ |
| Disk | 8 GB (16 with emulator) | **97 GB free** ✅ |
| OS | 64-bit Windows 10 | **Win 10 Pro 22H2 64-bit** ✅ |
| Virtualization | VT-x required | **VT-x + SLAT enabled** ✅ |
| GPU (emulator only) | 4 GB VRAM | **GTX 1050 Ti, 4 GB** ✅ |
| CPU | post-2017 / 8th gen | i7-4770, 4C/8T @ 3.4 GHz ⚠️ |

The block is entirely in the web page. Google's servers serve the installer to anyone, and
`winget` — Microsoft's package manager, already installed here — pulls the same official
Google build with no such check:

```powershell
winget install Google.AndroidStudio
```

Verified 2026-08-19: `winget` offers **2026.1.3.7**, publisher *Google LLC*, sourced from
`developer.android.com`. That is the current stable release.

**Expect it to be usable but not fast.** A 2013 quad-core will do Gradle builds slower than
a modern chip — a clean build of a small Android module might take a couple of minutes
rather than thirty seconds. For a project this size that is an annoyance, not a blocker,
and the 32 GB of RAM helps considerably. The `:physics` module already builds in about 5
seconds on this machine.

### Alternative: direct download

If you would rather have the installer file, Google's CDN serves it directly — the URLs are
not gated:

```
https://redirector.gvt1.com/edgedl/android/studio/install/<version>/android-studio-<version>-windows.exe
https://redirector.gvt1.com/edgedl/android/studio/ide-zips/<version>/android-studio-<version>-windows.zip
```

Both forms were confirmed to return HTTP 206 with real content. Version strings come from
<https://developer.android.com/studio/archive>. The `.zip` needs no installer and no admin
rights — unzip and run `bin\studio64.exe`.

`winget` is simpler; the direct URLs are the fallback if it misbehaves.

---

### Original download page (blocked on this machine)

<https://developer.android.com/studio>

During the setup wizard:

- Choose **Standard** installation
- Accept the SDK licences
- The Android Virtual Device / emulator image is optional (see above) — take it or leave
  it, disk is not a constraint

Studio bundles its own JDK (JetBrains Runtime 21), so the existing Java 17 is not used for
Android builds and does not need changing. It stays useful for the `:physics` module via
the standalone Gradle.

### After install — SDK components

**Finding the SDK Manager depends on where you are:**

- **On the Welcome screen** (no project open): the **⚙ gear icon** top-right → *SDK
  Manager*; or **Customize** → *All settings…* → **Languages & Frameworks → Android SDK**
- **With a project open**: **Tools → SDK Manager**

The `Tools` menu does not exist on the Welcome screen — this trips people up.

Then:

**SDK Platforms tab:**
- The setup wizard installs a recent platform automatically (**API 37** on this machine as
  of 2026-08-19, with build-tools 36.0.0). That is our compile target — no action needed
  unless you want an older platform to test against.

**SDK Tools tab** (tick *Show Package Details* bottom-right for versioned entries).

The wizard already installs Build-Tools, Platform-Tools (`adb`) and the Emulator. What it
does **not** install, and what you must add manually:

- ☑ **Android SDK Command-line Tools (latest)** — `sdkmanager`, `avdmanager`
- ☑ **Android Auto Desktop Head Unit Emulator** — **the critical one.** Without it there is
  no way to develop the head-unit interface outside the car.
- ☑ **Google USB Driver** — Windows-only, needed for phone `adb` over USB

### API level choices for this project

| Setting | Value | Why |
|---|---|---|
| `compileSdk` | 37 | Whatever the wizard installed; newest available |
| `targetSdk` | 37 | Play Console requires recent targets even for Internal App Sharing |
| `minSdk` | **28** (Android 9) | Car App Library 1.7.0 requires API 23; 28 avoids a pile of legacy Bluetooth permission branches |

---

## 2. Environment variables

Studio sets these for its own use, but `adb` from a terminal needs them. Set as **User**
variables (Win+R → `sysdm.cpl` → Advanced → Environment Variables):

```
ANDROID_HOME = C:\Users\<you>\AppData\Local\Android\Sdk
```

Append to `Path`:

```
%ANDROID_HOME%\platform-tools
%ANDROID_HOME%\cmdline-tools\latest\bin
```

Verify in a **new** terminal:

```bash
adb version
sdkmanager --list | head
```

---

## 3. Desktop Head Unit (DHU)

**This is the piece that makes head-unit development possible without sitting in the car**,
and it is easy to miss because it is not installed by default.

**SDK Manager → SDK Tools tab → tick "Android Auto Desktop Head Unit Emulator".**

Installs to `%ANDROID_HOME%\extras\google\auto\`.

### Running it

1. On the phone: enable **Developer mode** in Android Auto — Settings → Apps → Android
   Auto → Additional settings → tap **Version** 10 times
2. In Android Auto's ⋮ menu → **Developer settings** → enable **Start head unit server**
3. Connect the phone by USB, then:

```bash
adb forward tcp:5277 tcp:5277
cd %ANDROID_HOME%\extras\google\auto
desktop-head-unit.exe
```

The DHU window becomes the car screen. Our `NavigationTemplate` surface renders into it.

**Important:** the DHU accepts ordinary `adb install` builds — no Play Store involvement.
That is the whole day-to-day loop. Internal App Sharing is only needed to run on the
*real* head unit. See `ANDROID_AUTO_RESEARCH.md`.

---

## 4. Phone setup

- **Developer options**: Settings → About phone → tap **Build number** 7 times
- **USB debugging**: on
- **Android Auto developer mode + head unit server**: as in §3
- A **data-capable USB cable** — a charge-only cable is the single most common cause of
  both `adb` and Android Auto failures

Verify:

```bash
adb devices     # should list the phone as "device", not "unauthorized"
```

---

## 5. Play Console account — needed before the first real-car test

**$25 one-time**, at <https://play.google.com/console/signup>.

Not needed for DHU work, so it can wait — but **it is required before anything runs on the
actual Mazda head unit**, because Android Auto refuses Car App Library apps that were not
installed from a trusted source. Internal App Sharing is the no-review route. Details and
sourcing in `ANDROID_AUTO_RESEARCH.md`.

Signup can take a day or two to verify, so start it before you want to drive with it.

---

## 6. Project structure once Studio exists

The repo will become a two-module Gradle build:

```
swordfish/
├── settings.gradle.kts        include(":physics", ":app", ":layout-harness")
├── physics/                   pure Kotlin/JVM — 877 tests, no Android deps
├── tools/layout-harness/      desktop panel preview + drag-to-tune (not shipped)
└── app/                       Android + Car App Library
    └── src/main/
        ├── AndroidManifest.xml
        └── kotlin/dev/swordfish/
            ├── car/           CarAppService, Session, Screen, SurfaceCallback
            ├── obd/           Bluetooth transport + ELM327 protocol
            └── ui/            phone-side config and debug views
```

**`:physics` must stay free of Android dependencies.** It builds and tests standalone
today, and that is what keeps the model verifiable without hardware or an emulator.
`:app` depends on `:physics`, never the reverse.

### Key dependencies

Verified 2026-08-19. Car App Library **1.7.0** is current stable (released 2025-07-16).

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("androidx.car.app:app:1.7.0")            // core Car App Library
    implementation("androidx.car.app:app-projected:1.7.0")  // Android Auto (phone-projected)
    testImplementation("androidx.car.app:app-testing:1.7.0")

    implementation(project(":physics"))
}
```

Three things worth knowing about these artifacts:

- **`app-projected` is required for Android Auto.** The base `app` artifact alone is not
  enough — `app-projected` is the phone-projection half. (`app-automotive` is for
  Android Automotive OS, i.e. cars with Android built into the dash. Not us; the ND2
  runs Mazda Connect and projects from the phone.)
- **`app-testing` exists**, which means the `CarAppService` / `Screen` / template layer is
  unit-testable without the DHU. Worth using from the start given how much of this
  project is already test-driven.
- **1.7.0 fixes CVE-2024-10382** — do not pin an older version.

The library's own `minSdk` is API 23, so our choice of 28 is comfortably above it.

---

## 7. Verification checklist

Work through in order; each step depends on the one before.

- [x] Disk space — 97 GB free, 31.9 GB RAM. No constraint.
- [x] CPU — i7-4770 is below Google's stated *minimum generation*, but meets every real
      requirement. Download page blocks it; `winget install Google.AndroidStudio` does not.
- [ ] Android Studio installed, launches
- [ ] SDK Platform 35 + Build-Tools + Platform-Tools installed
- [ ] `ANDROID_HOME` set, `adb version` works in a fresh terminal
- [ ] DHU installed at `%ANDROID_HOME%\extras\google\auto\`
- [ ] Phone: developer options + USB debugging on
- [ ] `adb devices` lists the phone as `device`
- [ ] Android Auto developer mode enabled, head unit server starts
- [ ] `desktop-head-unit.exe` connects and shows the Android Auto screen
- [ ] *(later)* Play Console account created

The DHU connecting is the real milestone — at that point the entire head-unit development
loop works without leaving the desk.

---

## Notes on things that commonly go wrong

**`adb devices` shows `unauthorized`** — the phone is waiting on an "Allow USB debugging?"
dialog. Unlock the screen and accept.

**DHU says "waiting for device"** — the `adb forward tcp:5277 tcp:5277` step was missed, or
the head unit server was not started on the phone. Both are required, in that order.

**Android Auto developer settings menu is missing** — the Version row must be tapped
exactly 10 times; the menu then appears under ⋮.

**Gradle sync fails on first open with a JDK error** — Studio's bundled JDK should be
selected automatically. If not: Settings → Build → Gradle → Gradle JDK → the bundled
JetBrains Runtime. Do not point it at the system Java 17.
