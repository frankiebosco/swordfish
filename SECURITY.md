# Security

This is a hobby project maintained by one person. There is no security team and
no guaranteed response time — but genuine issues are taken seriously, and this
file exists so there is somewhere to send them.

## Reporting

Open a **private** security advisory through GitHub:
*Security → Advisories → Report a vulnerability* on this repository. That keeps
the report out of public issues until it is resolved.

Please do not open a public issue for anything exploitable.

## Scope

Most relevant to this project:

- **Anything that could write to a vehicle.** The OBD connection is intended to
  be strictly read-only — standard Mode 01 requests and passive bus monitoring.
  A path that could transmit onto a vehicle bus, clear codes, or alter a module
  is the most serious class of bug here and will be treated that way.
- **Leaked credentials or personal data.** Drive logs contain GPS traces;
  captures contain raw bus frames. If you find any committed to this repository,
  report it privately rather than opening an issue.
- Anything that could crash or hang the head unit while driving.

## Not in scope

- The app displaying an inaccurate number. Known accuracy limits are documented
  in [NOTICE.md](NOTICE.md) — that is a modelling caveat, not a vulnerability.
- Issues requiring physical access to an unlocked phone that is already paired
  with the adapter.
