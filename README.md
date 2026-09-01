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
- Records sessions to gzipped CSV, flushed row by row, into a folder chosen from
  the system picker.
- Opens recordings back up: its own `.csv`/`.csv.gz`, and `.sm2` files written by
  the Scanmatik Windows software.
- Serves ELM327 on `127.0.0.1:35000` for other apps on the same phone.
- Installs richer parameter catalogues as plugins, from several sources, over
  HTTPS with a token or over SFTP with an SSH key.
- English, Spanish and German; light and dark.

It never writes to a module. See [Only reading](#only-reading).

## What it does not do yet

- **Command anything.** The gate and the device-lock prompt exist; there is
  nothing behind them.
- **Reach the whole catalogue with confidence.** A brand catalogue lists 4 993
  distinct identifiers and 4 775 of them do not fit in a byte, so mode 01 cannot
  ask for them. The request form is known — the factory capture contains one
  `22 F8 02`, which the catalogue lists as the VIN — so the app asks with
  service `0x22` and marks those parameters as unconfirmed, dropping any that
  does not answer three times running. What is not known is how many of the
  other 4 774 answer at all; that is a measurement to make on a car, not a claim
  to put in a README.
- **Stream.** GM service `0xAA` asks a module to push a packet of parameters
  continuously, defined beforehand with `0x2C`; the app polls one at a time. The
  mechanism is understood and documented, not implemented.
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

The folder comes from the system picker, not from a typed path. Android has not
let an app write to an arbitrary path for several versions, so a text field
asking for one would be a field that cannot work; what is stored is the tree URI
the picker returns, with its permission persisted across reboots. With no folder
chosen, recordings go to app storage, which is fine until the app is
uninstalled.

### Viewing one

A twenty-three minute session is not legible as one picture, so the viewer shows
a window and moves it: five seconds by default, anything from fifty milliseconds
to the whole recording, one to four charts on screen, and a picker for which of
the thirty channels to look at.

Dragging moves every chart and the scrub bar together. The fling uses very
little friction on purpose — a flick at the start coasts through the whole
session, which is the closest thing to watching it happen.

Press and hold puts a dashed marker across every visible chart; hold with two
fingers to take it away. The marker keeps its place on the screen rather than
its moment in the recording, which sounds backwards until the recording is
moving: pinned to the glass, the values under it change as the session runs
past, so it reads out rather than bookmarks.

Vertical scale goes either way, from a dropdown. Scaled to the whole recording —
the default — the box is fixed, so flat looks flat and the trace does not
rescale under your finger while the fling is running; the price is that one
spike elsewhere in the session flattens everything either side of it. Scaled to
the window, every trace fills its box, so a wobble of a tenth of a degree is as
visible as a climb of a hundred. Both ends of whichever range is in force are
printed under the parameter name, so it is never a guess which you are looking
at.

### Reading .sm2

The format the Windows software writes, worked out from the files:

```
0x000  "SMFS" and a version
0x01d  Windows FILETIME — when the recording started
0x410  from here, SECTORS of 0x400 bytes
```

Every sector opens with a nine-byte record, `01 <u32 previous> <u32 next>` — a
linked list, with 0xFFFFFFFF for the first one's previous and the last one's
next. Checked sector by sector on a 23-minute recording: 958 of 958.

Strip those nine bytes and the rest is one continuous stream: the UTF-16
parameter names, then pairs of `<u32 milliseconds><double value>` cycling
through the parameters, then 0xff padding to the end of the last sector.

The sectors are the whole difficulty. Those nine bytes go in wherever the
boundary falls, cutting in half whatever they land in — a value pair, or a
parameter name. An earlier version of this reader did not know about them and
resynchronised by scanning for the next place four pairs made sense. That cost
953 resynchronisations, three parameter names, and, worse, the alignment between
a reading and its channel: the channel comes from the reading's position in the
rotation, so skipping bytes moved everything after it. Charts came out with one
parameter's values under another's name.

Read with the markers removed first, the same file gives 80 670 readings, no
resynchronisation at all, and 80 670 is exactly 2689 × 30. The series comes out
23:13.7 long, and the header inside the file says "23:13.740".

**How many channels** is not the number of names. The header carries a summary
line — `30 of 30 items 23:13.740` — where the second number is how many
parameters were recorded and the first is how many the Scanmatik window happened
to be showing when the file was saved. A recording saved with the view filtered
down to three still holds all thirty, and counting the visible names spread
thirty channels of readings across three of them.

Two independent checks agree with that line. The reading count divides by thirty
exactly; and the sampling rhythm — the gap between two readings of the same
channel, which is the poll period and barely varies when the split is right —
comes out 518.3 ms with 4.7% spread at thirty, against 4.8% and worse at every
neighbouring value.

The file carries no units at all: the header holds exactly as many text records
as there are channels, and each one is a name. Those names are SAE J1979, so
`Units.kt` maps the ones whose unit the standard fixes and leaves the rest blank
rather than guessing.

---

## Plugins

Standard OBD-II PIDs are public — SAE J1979 — and ship with the app, so it works
on any car with nothing installed.

Richer catalogues are per-brand plugins: parameter names, scaling formulas,
units, ranges, module layouts and fault-code ownership, generated from a GDS2
installation. They are **not** distributed here; the data belongs to GM. If you
have the software you can generate your own.

A source publishes a `brands.txt` index — tab-separated: name, parameter count,
languages — and the app lists what is in it beside what is already on disk, one
row each, with an arrow to fetch and a bin to remove. A source that publishes no
index still works; you just have to know the directory name and type it.

Sources are configured one per repository, each with its own credential, so a
credential that reads one cannot reach another.

Two kinds, because the two kinds of host want different things:

- **A forge** — GitHub, GitLab, Gitea — over HTTPS with a token. Four small
  requests fetch a catalogue, the token is scoped to one repository, and it is
  revoked from a web page if the phone is lost.
- **Any other SSH host** — a home server, a NAS, another machine, a directory
  someone else exports for you — over SFTP with a key. The app generates an
  RSA-3072 pair or imports one you already have; the public half is shown to
  copy into `authorized_keys`, the private half stays in app-private storage.
  Host keys are trust-on-first-use in a `known_hosts` file, and a changed key
  fails the connection rather than asking, because the answer to that question
  in a car park is always yes.

SSH does not reach a GitHub repository, and that is deliberate rather than
missing. A forge serves files over SSH only through the git wire protocol, which
would mean a packfile reader on the phone and the whole history downloaded to
end up with four text files. For a forge, use a token.

---

## Appearance

Dark by default, with light and follow-the-system in settings, and an amber
palette that reads like an instrument cluster rather than like a form. The
window background is set in `themes.xml` for both, so launching does not flash
white before Compose paints.

Language is per-app — English, Spanish, German — set from within the app and, on
Android 13 and later, visible in the system settings too. Changing it recreates
the activity, which is how Android applies a configuration change; the
navigation state is saved across that, so the screen comes back where it was
instead of dropping you on the first tab.

Five destinations, one short word each. A navigation bar splits its width evenly
between items, so a long label does not shrink — it wraps, and "Recordings"
arrives as "Recordin" over "gs". The Spanish and German strings are held to the
same length for the same reason.

---

## Building

```
cd core && cargo test
gradle :android:assembleDebug
```

CI runs both. `cargo test` is the gate that matters: it replays 200 recorded
writes and checks the computed fingerprint against what the device put on the
wire.

Minimum Android 8.0, compiled against API 37 with AGP 9.3.2, Kotlin 2.2.10 and
JDK 21 — the same toolchain as the other Android project on this machine, so a
Gradle sync does not fail for reasons that have nothing to do with the app.

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
same protocol is not: it has been read carefully, but the only thing that proves
a port is a car.

Service `0x22` is the other open edge. One request of that shape appears in the
factory capture and was answered, which fixes the form; whether the other 4 774
catalogue identifiers answer to it is unmeasured. The app asks, labels them
unconfirmed, and drops the ones that stay silent — so using it is what produces
the measurement.
