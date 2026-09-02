# Notice

## Ownership and trademark

Swordfish is copyright © 2026 **Bergen Palisades Technology LLC**, and is
released under the [MIT License](LICENSE).

The MIT License covers the **source code**. It does not grant rights to the
name **“Swordfish”**, the Bergen Palisades Technology name, or any associated
logo or branding. Forks are welcome — please give them their own name so users
can tell them apart, and so a bug in a fork does not become a support question
for this project.

If you build something on this, an attribution link back is appreciated but
not required. The one thing MIT does require is keeping the copyright notice
in any copy or substantial portion.

## Safety

This software displays information on a vehicle head unit while the vehicle is
in motion.

- **Its OBD-II connection is read-only.** It issues standard Mode 01 diagnostic
  requests and, for MS-CAN work, passive monitor commands. It does not write to
  any vehicle module, clear codes, or alter any setting.
- **It is not a safety system.** Nothing it displays should be relied on for
  any driving decision. It has no relationship to the vehicle's own
  instruments, warnings, or driver-assistance features.
- **The driver is responsible for paying attention to the road.** Read it the
  way you would read a trip computer: with a glance, at an appropriate moment,
  or not at all.
- **Fuel and delta-V readings are a model, not a measurement.** See the
  accuracy notes below.
- The MS-CAN capture mode places the adapter in a passive monitoring state. It
  does not transmit onto the vehicle bus.

Use is at your own risk. See the warranty disclaimer in [LICENSE](LICENSE).

## Accuracy — known limitations

These are documented because a plausible-looking number is more dangerous than
an obviously missing one:

- **Fuel flow is derived, not measured.** This vehicle answers PID `015E`
  (engine fuel rate) but has only ever returned zero, so fuel flow comes from
  mass airflow corrected by the ECU's commanded lambda (PID `0144`) and fuel
  trims (PIDs `0106`/`0107`).
- **Stoichiometric AFR is set to 14.7:1**, the pure-gasoline figure. E10 pump
  gasoline is nearer 14.1:1, so fuel flow is systematically under-reported by
  roughly 4%, biasing specific impulse and delta-V optimistic by about the same
  margin. The constant is tunable (`Units.STOICH_AFR`); the vehicle does not
  report the blend in the tank, so it is not corrected automatically.
- **Road-load coefficients are published catalogue values** for the ND2
  (Cd 0.35, frontal area 1.79 m², Crr 0.012), not a coastdown test on any
  specific car. Roof position, roof racks, tyre pressure and load all shift
  them.
- **Delta-V is nearly linear with remaining fuel.** The mass ratio of a car is
  about 1.03, which puts it at the foot of the logarithm in the rocket
  equation. This is physically honest rather than a defect: it correctly
  reports that a car is a poor rocket. Specific impulse is the term that
  actually varies with driving.
- **MS-CAN signal identifications are empirical**, obtained by correlating bus
  bytes against reference signals over real drives. They are documented with
  their confidence level in [docs/MSCAN_SIGNALS.md](docs/MSCAN_SIGNALS.md).
  They are not from any manufacturer specification and may be wrong.

## Vehicle compatibility

Developed against a 2023 Mazda MX-5 ND2 Club (6MT). The physics model is
general, but the vehicle constants, PID availability and MS-CAN arbitration IDs
are specific to that car. Other vehicles will need at minimum a new `Vehicle`
definition, and MS-CAN findings should be assumed not to transfer at all.

## Third-party data sources

This project uses public data. It is not affiliated with, endorsed by, or
sponsored by any of these organisations:

- **NOAA / National Weather Service** — nowCOAST MRMS base reflectivity mosaic,
  for the weather radar. Public domain, keyless.
- **USGS** — 3DEP Elevation Point Query Service, for surveyed ground elevation.
  Public domain, keyless.
- **Mazda** is a trademark of Mazda Motor Corporation. This project is an
  independent work and has no connection to Mazda Motor Corporation.
- **Android** and **Android Auto** are trademarks of Google LLC.

Please be a considerate consumer of the public services above: they are free,
unmetered, and paid for by taxpayers. Do not remove the request rate limits.
