# opendash

An ELM327 bridge for the Scanmatik SM3, and a diagnostics app on top of it.

The SM3 is a capable J2534 interface that only talks to its own Windows
software. opendash speaks its protocol directly and presents it to a phone as an
ELM327 over a local socket, so an app like ScanMyOpelCAN can drive it — and
reads the bus itself for the things a generic scan tool cannot do.

None of the protocol was documented. It was recovered by capturing the driver
that ships with the device and measuring what came back;
[`docs/protocol.md`](docs/protocol.md) records what was established, how, and
what is still open.

---

## What it does

- Opens a session with the device over its Wi-Fi access point and puts the CAN
  channel into a state where the bus can be read.
- Asks the car what it is. GM service `0x1A` returns the VIN, the system name
  and the engine code, so there is no make-and-model menu to get wrong and a
  swapped engine identifies itself correctly.
- Reads standard OBD-II live data. It first asks which PIDs the engine answers,
  so nothing offered can be refused. Values and charts, any number at once.
- Reads readiness monitors and fault codes, stored and pending.
- Records sessions to gzipped CSV, flushed row by row.
- Opens `.sm2` recordings written by the Scanmatik Windows software.
- Serves ELM327 on `127.0.0.1:35000` for other apps on the same phone.
- Installs richer parameter catalogues as plugins, from several sources.

It never writes to a module. See [Only reading](#only-reading).

## What it does not do yet

- **Command anything.** The gate and the device-lock prompt exist; there is
  nothing behind them.
- **Request a whole catalogue.** A brand catalogue lists PIDs well beyond the
  mode 01 range — 4427, 54528 — read with a service that has not been worked
  out. The app reports how many parameters it can actually fetch rather than
  offering all of them and failing.
- **Stream.** GM service `0xAA` asks a module to push a packet of parameters
  continuously; the app polls one at a time. The mechanism is understood and
  documented, not implemented.
- **Share one implementation.** `bridge/ElmSession.kt` mirrors
  `core/src/elm327.rs` in Kotlin so the socket could be exercised before a JNI
  bridge exists. Two implementations of one command set will drift. The Rust one
  has the tests; the Kotlin one is scaffolding with an expiry date.

---

## Layout

```
core/       the protocol in Rust: framing, h4, ISO-TP, ELM327
  src/      no I/O — bytes in, bytes out, so it tests on a host
  tests/    fixtures are frames the device actually sent
android/    the app
docs/       what was established, and how
```

`core` has no I/O on purpose. Everything interesting was recovered from
captures, and the tests replay those captures, so they need a machine and not a
car.

---

## The protocol, in short

Full detail in [`docs/protocol.md`](docs/protocol.md). The parts that decide
whether an implementation works at all:

**Transport.** Over Wi-Fi the device is a plain TCP server on
`192.168.81.1:777`. Over USB the same messages travel inside `DeviceIoControl`.
From byte 8 onwards the two are identical, which is why a capture from either
side is worth the same.

**Framing.** A 16-byte header — `seq`, `token`, `h4`, `h8`, opcode, length,
checksum — then the data. A message occupies `align(16 + len, 4)` bytes on the
wire, **not** `16 + len`. Send the short form and the device waits for bytes
that never come, the stream desynchronises, and the connection resets. Measured
across 4066 messages from two independent sessions: the gap between one message
and the next is always exactly that padding.

**h4.** A 32-bit fingerprint the firmware checks — a greeting with one bit
flipped is answered with a TCP reset, nine times out of nine. It is affine over
GF(2), which means it can be used without being identified: take a recorded
frame, change the CAN id and payload, and XOR in the contribution of every bit
that differs. Those contributions came from a one-bit sweep of 228 chosen
writes, 425 writes with random ids and payloads, and 480 from real captures. The
result computes the right value for **85 of 85** held-out writes the model had
never seen, and for 4000 of 4000 recorded pairs.

**Reading.** Opcode `0x1c` with subcommand `40 80 02`; replies are `0xfe` blocks
of 16-byte records. A client built on this read 33 054 frames covering all 59
CAN ids of a live car, with no resynchronisation.

**Writing.** Same opcode, subcommand `60 80 02`, followed by the record.

**Battery.** Opcode `0x20` takes no payload and answers six bytes; the first
little-endian u16 is the battery in millivolts. Over one session it ran from
8765 while cranking to 14 674 with the alternator charging. That is where `ATRV`
comes from.

---

## Only reading

The app cannot write to a module, and that is enforced rather than intended:

```kotlin
private val readServices = setOf(0x01, 0x02, 0x03, 0x06, 0x07, 0x09,
                                 0x19, 0x1A, 0x22, 0xAA, 0x3E)
```

`0x2E` WriteDataByIdentifier, `0x2F` InputOutputControl, `0x31` RoutineControl,
`0x14` ClearDiagnosticInformation and `0x27` SecurityAccess are absent. A request
carrying one throws before it reaches the socket.

This matters more than it sounds. A catalogue extracted from the factory tool
lists 2517 parameters with "Command" in the name, 879 with "Test" and 782 with
"Learn". There they can be commanded as well as read, and the same identifier
that reports a relay state can also close it. Reading them is harmless. Writing
them moves things on a car that may have someone in it.

Actuation therefore sits behind a switch in settings that asks for the device
lock — fingerprint, face, PIN, whatever the phone already uses — and is off by
default. If the phone has no lock at all the switch refuses, rather than
silently opening up.

---

## Recordings

Gzipped CSV. Measured on a session the size of a 23-minute drive, 79 796
readings:

| | |
|---|---|
| plain CSV | 3.63 MB |
| the Scanmatik `.sm2` binary | 0.98 MB |
| CSV, gzipped | **0.60 MB** |

So the reasonable worry — that text is wasteful next to a proprietary binary —
is right about the text and wrong about the conclusion. A column of similar
numbers is what a compressor is best at, and a `.csv.gz` opens in a spreadsheet,
in pandas, in anything. A private binary opens in whatever we write for it.

Rows are flushed as they happen, with sync flushing on the compressed stream. A
recording ends when the car is switched off, not when someone presses stop, so
an interrupted session should be short rather than corrupt.

### Reading .sm2

The format the Windows software writes, worked out from the files:

```
0x00   "SMFS" and a version
0x1d   Windows FILETIME — when the recording started
0x410  header: <u32 marker=1><u32 characters><UTF-16>, title, summary,
       and every parameter name and unit
...    the series: pairs of <u32 milliseconds><double value>, cycling
       through the parameters and starting over
```

Between blocks there are control records that are not pairs, so a run that stops
making sense is resynchronised at the next place four consecutive pairs do.
Checked against a 23-minute drive: the series comes out 23:13.7 long, and the
header inside the file says "23:13.740".

---

## Plugins

Standard OBD-II PIDs are public — SAE J1979 — and ship with the app, so it works
on any car with nothing installed.

Richer catalogues are per-brand plugins: parameter names, scaling formulas,
units, ranges, module layouts and fault-code ownership, generated from a GDS2
installation. They are **not** distributed here; the data belongs to GM. If you
have the software you can generate your own.

Sources are configured per repository with their own credential, over HTTPS with
a token rather than SSH. A token can be scoped to one repository and revoked
from a web page if the phone is lost, which a key sitting in app storage cannot.
GitHub, GitLab and Gitea are handled.

---

## Building

```
cd core && cargo test
gradle :android:assembleDebug
```

CI runs both. `cargo test` is the gate that matters: it replays 200 recorded
writes and checks the computed fingerprint against what the device put on the
wire.

Minimum Android 8.0. English and Spanish, switchable inside the app without
changing the phone language.

---

## What is not proven

h4 was the one place this could be wrong in a way that matters, and it is now
the best-tested part. The linear system stops at rank 129 of 209, but what it
misses are fields that never vary by construction — the subcommand, the record
length, the id bytes above eleven bits — which a bridge does not vary either.
`h4::unverified_bits` reports them and `h4::is_verified` answers for a given
frame, so a caller can refuse rather than have the device drop a frame in
silence.

Everything in `core` is exercised against real captures. The Kotlin port of the
same protocol is not: it has been read carefully and it compiles, but the only
thing that proves a port is a car.
