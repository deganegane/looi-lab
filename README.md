# LOOI Lab

A test bench for the LOOI desk robot base, without the official app or its servers.

- **Vožnja:** joystick → `FED0` (speed) + `FED1` (turn), every 60 ms.
- **Animacije:** the 25 motion scripts the official app sent, replayed byte for byte through `FE00`.
- **Kanali:** hold one script channel at a value, to find out what each channel moves.
- **Sirovo:** write or read any known characteristic by hand.
- **Dnevnik:** everything the base sends (`FED9` events), decoded where we know the meaning.

## Protocol (from a HCI snoop log of the official app, 2026-11-10)

Service `0x00FF`, no pairing, no encryption.

| Char | Use |
|------|-----|
| `FED0` | drive speed, `00 <int8>`, write without response |
| `FED1` | turn, `00 <int8>`, write without response |
| `FE00` | motion script: `[fragment, script id, keyframes…]`, keyframe = `(tick, int8 value, channel)`, tick ≈ 40 ms, `ff 00 ch` ends a channel; `00 00 01 ff 00 01` stops |
| `FED9` | notify: `01 a b c d` four sensors (edge?), `02 hi lo` analog (tilt?), `07 id 00` script done, `09/0A/0B x` events |
| `FEDA` | control: `03` hello, `81 01` start |
| `FEF0` | clock: `A2026 11 10 20 41 39`, answers `Y` |
| `FED8` | read every 30 s, answers `00 01` |

The APK is built by GitHub Actions on every push to `main` and published under Releases.
