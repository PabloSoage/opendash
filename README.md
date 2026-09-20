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
- Asks the car what it is. GM service `0x1A` returns the VIN, the supplier, the
  engine code and the date it was programmed, so there is no make-and-model menu
  to get wrong and a swapped engine identifies itself correctly.
- Reads standard OBD-II live data. It first asks which PIDs the engine answers,
  so nothing offered can be refused. Values and charts, any number at once.
- Reads a module's **own** parameters, from the manufacturer's catalogue, with
  service `0x22`. Chosen by module first and variant second, because a marque
  catalogue is every configuration ever shipped and a flat list of it is not a
  list.
- **Asks the car which of them it actually has.** The catalogue cannot say — the
  factory packages carry no model-to-variant table — so the module is asked,
  once, and the answer is kept against the VIN. On the test vehicle that is
  2 638 identifiers asked and 204 rows kept of the engine's 5 249.
- **Lets the module do the sending.** Declares data packets with `2C` and starts
  them with `AA 04`, which is how the factory tool reads live data. Seven packets
  emit at 98.7 Hz each, so a round of 31 parameters arrives at **3 060 samples a
  second** -- against the four or five a second that polling shares between all
  of them. What a round can hold was measured rather than assumed: forty
  identifiers, refused at forty-one, with bytes reaching 49 without complaint.
- **Rotates when a selection does not fit, and pins what must not rotate.** More
  than forty identifiers become rounds, declared one after another. That buys
  rate at the cost of each parameter being dark between its turns -- so rows that
  cannot afford a gap, road speed above all, ride in every round instead. The
  packet layout is on screen, with the bytes each packet uses and how long each
  round is dark, because the order decides which parameters share a frame and
  two in one frame are the same instant.
- **Reads standard OBD in batches.** Mode 01 takes six PIDs to a request, so
  twenty-five parameters are five requests and not twenty-five.
- Reads readiness monitors and fault codes, stored and pending.
- Reads the whole identification block out of every module that answers: part
  numbers, alpha codes, programming date, traceability number, broadcast code.
- Records sessions to gzipped CSV, flushed row by row, into a folder chosen from
  the system picker.
- Opens recordings back up: its own `.csv`/`.csv.gz`, and `.sm2` files written by
  the Scanmatik Windows software.
- Serves ELM327 on `127.0.0.1:35000` for other apps on the same phone, with a
  self-test that connects to it the way such an app would and shows the answer.
- Installs richer parameter catalogues as plugins, from several sources, over
  HTTPS with a token or over SFTP with an SSH key.
- English, Spanish and German; light and dark.

It never writes to a module. See [Only reading](#only-reading).

## What it does not do yet

- **Command anything to speak of.** The gate and the device-lock prompt exist;
  behind them is one known output, and it works: the EGR valve, CPID `0x1C`,
  commanded at the car with the air flow falling from 9.33 to 4.45 g/s at a
  constant 750 rpm — and rising when the valve is closed below what the ECU
  was doing by itself. The service is GMLAN `$AE` DeviceControl and it takes a
  CPID, which is a different namespace from the parameter identifiers -- the
  table for this ECU appears in no catalogue and had to be captured off the
  factory tool actuating a valve. One is known that way; the rest are not, and a
  sweep does not find them, because on this ECU all 255 answer alike including
  the one that exists.
- **Tell one row of a name from another on its own.** A catalogue key names a
  quantity, not a row: 2 859 of this marque's 11 480 keys carry more than one
  parameter, and fifteen rows are called "Exhaust Gas Temperature Sensor 1",
  each reading its own identifier with its own scaling. Choosing the variant
  does not thin them out, because a variant names keys and one key carries all
  fifteen. The only discriminator is which identifier the car answers to, which
  is what asking the car produces — but picking between two that both answer is
  still the reader's job, so every row shows its identifier.
- **Choose a module's configuration by itself.** The factory tool resolves it by
  reading attributes off the bus and evaluating conditions held in its own
  vehicle database. The condition language and the database format are both
  understood; what is not settled is which identifier each attribute check
  requests, so for now the variant is a dropdown.
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

**h4 and h8.** Two words the firmware checks — a greeting with one bit flipped
is answered with a TCP reset, nine times out of nine.

`h8` is the sum of the data as little-endian 32-bit words, **including the
padding**, which is not zero: a 305-byte block ends in `1e a1 f1`, and zeroing
it changes the answer.

`h4` is a CRC with reflected polynomial `0x9960034C`. It was first fitted as an
affine function over GF(2) — which is true, and enough to alter a recorded
message, and quietly wrong the moment a message of a different length is
wanted. Identifying the polynomial closed it: the chain for a message of any
length is anchored at the last bit of the padded data. That is the difference
between replaying the manufacturer's messages and writing one's own, and
everything below depends on it.

**Reading.** Opcode `0x1c` with subcommand `40 80 02`; replies are `0xfe` blocks
of 16-byte records. A client built on this read 33 054 frames covering all 59
CAN ids of a live car, with no resynchronisation.

**Writing.** Same opcode, subcommand `60 80 02`, followed by the record. The
eight bytes of that record are **the CAN frame itself**, not a length and seven
bytes of payload. Sent the other way, a flow-control frame goes out as
`08 30 00`, the module never continues a multi-frame answer, and a VIN comes
back as its first four characters.

**The channel.** Opening the bus is opcode `0x50`: the speed at data+134, the
filter count at 142, 59-byte filter records from 305. Opened with a single
`PASS mask=0 pattern=0` — send me everything — the app saw 1 340 frames a second
across 51 addresses, 173 a second of them belonging to nobody it had asked;
the factory tool saw 9 addresses. Four pass filters fix that, and building one
means signing a 560-byte message, which is what `h4` above is for.

**This bus is GMLAN, not UDS.** TesterPresent is a bare `3E`: `3E 00` comes back
`7F 3E 12`. A module answering on `0x64X` **emits** on `0x54X`, and the engine
answers on `0x7E8` but emits on `0x5E8` — which is why streaming looked dead
while the frames were arriving the whole time on an address nothing was
listening to. A module told to emit then hears nothing from the tester and
stops after about five seconds, so a stream needs the same heartbeat a sweep
does.

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

Installs are per brand and not per model, because that is the shape of the
source data. GDS2 organises by module configuration, not by car: the 221
variants in the Opel catalogue are things like `ECM EDC17 C49 Bosch UDS` and
`Engine Control Module - ECM - Global A - E78 Ref`. Two of them name a model at
all, and both are peripherals — an instrument cluster and a trailer interface.
So there is no Astra J bundle to download, and there is nothing to build one
from.

Narrowing happens at use, not at install, with a variant picker on the live
screen. It is where the win is anyway: the whole Opel catalogue is 15 MB, which
is nothing on a phone, but it is 21 382 parameters, 14 117 of them two-byte, and
that is a list nobody can read. One variant is a median of 47 parameters, and an
engine module a few hundred — 292 for the EDC17 C49, 1745 for the E78.

Which variant is your car is not answerable from the data. The car reports
`DENSO0100` and `A17DTJ` over service `0x1A`, and neither string appears
anywhere in any of the five packages; GDS2 does not carry a model-to-variant
table either, it asks the car and matches at run time. So the picker shows every
variant with its parameter count and you choose once.

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

Minimum Android 8.0, compiled against API 37 with AGP 9.4.1, Kotlin 2.4.20 and
JDK 21 — the same toolchain as the other Android project on this machine, so a
Gradle sync does not fail for reasons that have nothing to do with the app.

One thing in `packaging` is not tidiness and will bite anyone who removes it.
Bouncy Castle ships `META-INF/LICENSE.md` and `NOTICE.md` in each of its three
artefacts, and three identical files at one path is a **failed**
`mergeReleaseJavaResource`, not a warning — so those paths are excluded. That is
not dropping the licences: they stay in the sources, and an APK has nowhere to
show a `META-INF` file anyway.

---

## What it has actually done

Not a demo. One Opel Astra J 1.7 CDTI (A17DTJ, Denso ECU), five sessions with the car
present between 17 and 21 September 2026, and **two hours and twenty-one minutes of
driving recorded through the app in four files**:

| Recording | Duration | Readings | Parameters | Rate |
|---|---|---|---|---|
| A Coruña → motorway | 64.8 min | 5 667 101 | 40 | 1 459/s |
| Pontevedra → Bueu | 21.7 min | 1 926 110 | 40 | 1 479/s |
| Bueu → Hío | 22.0 min | 1 937 918 | 40 | 1 467/s |
| Bueu → motorway | 32.5 min | 4 930 369 | 32 | **2 527/s** |

**14.5 million readings**, 55 MB gzipped. The last one uses the single-round profile,
which is why it is nearly twice the rate on fewer parameters: nothing rotating, so every
magnitude is live at once and any two of them can honestly be compared.

The files themselves are not in this repository. They are somebody's driving — road speed,
braking, pedal, a route anyone could reconstruct by integrating one of those columns — and
that belongs to whoever drove.

Nor is what they say about that particular engine, which is a diagnosis and not a tool.
What matters on this page is only that four recordings of that length came home whole.

---

## What is not proven

Most of what used to be here has since been settled at the car, over five
sessions with a vehicle in front of it: the session and the channel, the
identification block, mode 01 and its six-PID batching, service `0x22` against
2 638 catalogue identifiers, streaming end to end with seven packets at 98.7 Hz
each, the limit of a declaration found by moving one variable at a time, a
channel with real filters built and signed here rather than replayed, and the
ELM327 bridge answering a client through all of it. Two and a half hours of
driving were recorded through it in one file.

Two ceilings were believed and then measured away, both found the same wrong
way -- by hitting the first refusal in a short list instead of looking for the
boundary. First that the module charged a price per packet, which put five
packets in a round and left seven-second gaps that swallowed a braking event
whole. Then that the limit was 35 declared bytes. It is 40 identifiers, and
bytes are nearly free. **A measurement that stopped at the first failure is not
a limit**, and this repository has now paid for that lesson twice.

What is left:

**The bridge, from the phone.** Its protocol half is proven against the car —
every command a scan tool sends first, answered correctly. That the foreground
service starts and an outside app reaches the socket is not. The Test button on
the Link screen exists for exactly this question.

**One implementation, two languages.** Everything in `core` is exercised against
real captures; `bridge/ElmSession.kt` mirrors it in Kotlin and is not. Two
implementations of one command set will drift.

**Which identifier resolves a module's configuration.** The factory tool's
attribute checks compare against two-byte values; the catalogue has a parameter
called *Diagnostic Data Identifier*, `0x9A`, two bytes wide. It fits, nothing in
the database ties the two together, and it is one request at a car to find out.

**Anything that writes.** Not a limitation being worked around — see
[Only reading](#only-reading). Every service this sends is a read, and the
client refuses the rest. The one exception is behind the padlock and is
described under [What it does not do yet](#what-it-does-not-do-yet).

**Whether a recording survives being left alone for hours.** Two have not. The
first stopped at 65 minutes, the second at 32, both with the rate perfectly
healthy up to the last sample — 2 443 samples a second in the first minute of
that one and 2 542 in the thirty-first, which is what rules out running out of
memory: that decays, these fell off a cliff.

What the second one showed is that a read finding nothing **is not a failure**.
It returns a batch of zero frames, which is exactly what an idle moment looks
like, and with a single round there is no dwell to expire — so the loop span,
for ever, while the app went on reporting that it was recording at zero samples
a second. Three seconds of silence now ends the round and declares the packets
again, and the heartbeat moved onto the thread that owns the socket, because
the thread whose job it was could be starved of the lock by the draining.

Both fixes are certain to help and neither is a cause that is understood. The
next long drive is the test.

---

## License

Copyright (C) 2026 Pablo Soage Rodas

This program is free software: you can redistribute it and/or modify it under
the terms of the **GNU General Public License v3.0** as published by the Free
Software Foundation.

This program is distributed in the hope that it will be useful, but WITHOUT ANY
WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
PARTICULAR PURPOSE. See the `LICENSE` file for more details.

You should have received a copy of the GNU General Public License along with
this program. If not, see https://www.gnu.org/licenses/.
