## What this is

A personal project that happens to be public, developed against **one car** — a
2023 Mazda MX-5 ND2 Club. It is not a product, there is no roadmap, and issues
may sit for a while. Everything here is MIT licensed: forking is genuinely
encouraged, and for many ideas a fork is a better fit than a PR.

## The most useful thing you can contribute

**Data from a different car.** Almost every limitation in this project traces
back to having exactly one vehicle to test against:

- Which OBD-II PIDs your car answers (`docs/VEHICLE_SURVEY.md` shows the sweep
  for the ND2)
- MS-CAN arbitration IDs and what they carry — see
  [docs/MSCAN_SIGNALS.md](docs/MSCAN_SIGNALS.md) for the ones decoded so far
  and how they were identified
- Whether the road-load coefficients in `Vehicle.kt` are anywhere near right
  for another chassis

Even a "this PID returns nothing on my car" report is useful. Negative results
are how the vehicle survey got written.

## Before opening a PR

**Run all three test suites.** `./gradlew test` covers `:physics`, `:app` and
`:layout-harness`. Note that `ship.bat` runs only `:physics:test`, which has let
regressions through twice.

**Match the commenting style.** The code explains *why*, not *what* — usually
the failure that motivated the design. If you fix a bug, the comment should say
what went wrong, ideally with the observed numbers. That convention is the main
reason this codebase is navigable after months away.

**Add a test that fails without your change.** Several tests here exist
specifically to pin a bug that came back. If you cannot write a failing test,
say so in the PR and explain why.

**Do not touch the tuned gauge layout** without running the layout harness
(`./gradlew :layout-harness:run`) and reading
[docs/INSTRUMENT_PANEL.md](docs/INSTRUMENT_PANEL.md). That surface was tuned
pixel by pixel against a real head unit, and a reasonable-looking change has
broken it before.

## Safety

This code runs on a screen in a moving car and talks to a vehicle's diagnostic
port.

- **Keep the OBD connection read-only.** Standard Mode 01 requests and passive
  monitoring only. Nothing that writes to a vehicle module, clears codes, or
  changes a setting will be merged.
- Nothing here is a safety system, and no change should imply otherwise. See
  [NOTICE.md](NOTICE.md).

## Do not commit

`.gitignore` covers these, but worth stating plainly:

- **Drive logs and MS-CAN captures.** A drive log is a GPS trace, and for a car
  parked at home each night that is a home address with timestamps. Findings
  belong in `docs/`; raw files stay on your machine.
- **Signing material.** `*.jks`, `keystore.properties`, `local.properties`.

## Building

`./gradlew :app:assembleDebug` works from a clean clone — the signing config is
skipped when `keystore.properties` is absent. See `docs/DEV_SETUP.md`.
