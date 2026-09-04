# The Scanmatik SM3 protocol

Recovered by capture and measurement. Nothing here comes from documentation;
every claim below has a count behind it. What is still open says so.

## Transport

Over Wi-Fi the device is a plain TCP server on `192.168.81.1:777`, reachable
from its own access point. Over USB the same messages travel inside
`DeviceIoControl` on `\.\SmUsb_PID_0002_000`. **From byte 8 onwards the two
are identical**, which is why a capture from either side is worth the same.

## Framing

```
0..1   seq    u16 LE   USB: a counter · TCP: always 0xffff
2..3   token  u16 LE   USB: the driver's echo · TCP: always 0
4..7   h4     u32 LE   fingerprint (see below)
8..11  h8     u32 LE   sum of the data words (see below)
12     op
13..14 len    u16 LE
15     chk    (op + len_lo + len_hi + 0x55) & 0xff
16..   data
```

**Every answer carries `op = 0x00`, whatever was asked.** Counted over the whole
factory capture: 1502 answers to `0x20`, 1387 to `0x1c`, 603 to `0x50`, 599 to
`0x52`, 86 to the greeting — all `0x00`. Nothing in the reply says what it
answers, so the only thing that pairs a reply with its request is the order they
arrive in. Messages with `op = 0xfe` are the exception and are not answers at
all: those are frame deliveries the device sends unasked, interleaved with
everything else.

That has a consequence worth stating plainly, because getting it wrong is
invisible. A client that writes a request and then reads whatever is in the
socket will sooner or later read the answer to something else. The answer to a
bus poll is 28 bytes, and its first two bytes read as millivolts give **0.640 V**
— which is what 1069 of the 1387 poll answers in the capture do. A battery
reading of about two thirds of a volt on a car with a good battery is that bug,
not a flat battery.

A message occupies **`align(16 + len, 4)`** bytes on the wire, not `16 + len`.
Measured across 4066 messages from two independent sessions: the gap between
one message and the next is always exactly that padding, with a single
exception that is a dropped capture segment.

This is easy to miss and expensive. A first replay attempt sent `16 + len`; the
two messages that need no padding went through and the first one that does hung
the stream and then reset it. Sending the padded form, the whole opening
sequence completes and the bus can be read.

The padding bytes are stale buffer content, not zeros. Their value does not
appear to matter for acceptance, but they are covered by `h4`.

## `h4` and `h8`, the two derived words

Every message carries two words the firmware computes rather than carries: `h4`
at offset 4 and `h8` at offset 8. Both are checked. Over a raw socket, a
greeting whose `h4` differs by one bit is answered with a TCP reset — three
values, three samples each, nine out of nine — and that reset is the only error
signal the firmware has ever produced.

### `h8` is a sum

Read the twenty data bytes as five little-endian 32-bit words and add them,
discarding the carry:

```
h8 = data[0..3] + data[4..7] + data[8..11] + data[12..15] + data[16..19]
```

**708 of 708** recorded writes agree, exactly. This was taken for a per-message
stamp or a channel handle for a long time, and it is neither: it moves with the
payload and nothing else, which is why the same request always carries the same
`h8` and why a frame built by changing a payload has to recompute it.

### `h4` is a CRC

It is affine over GF(2): 717 checks of "same input difference gives the same
output difference" with no contradiction. Being affine is enough to use it
without naming it:

```
h4(new) = h4(reference) XOR contributions(bits that changed)
```

and for a long time that was as far as this went — the contributions were
*fitted*, from a one-bit sweep plus writes with random payloads plus real
captures. Fitting was the mistake. The recorded traffic never varies some bits,
so the system is underdetermined (rank 118 of 209 unknowns with `seq` and `h8`
carried as free variables), and an underdetermined solve hands back one of many
answers with no warning attached.

The contributions are not fitted any more. Laid out in order they satisfy

```
contribution(n) = (contribution(n+1) >> 1) XOR (0x9960034C if odd)
```

which is the recurrence of a CRC with reflected polynomial `0x9960034C` — not
one from any catalogue, which is why the earlier search over 2688 standard
combinations of region, seed, polynomial, reflection and byte order found
nothing. The whole 160-entry table therefore follows from a single anchor, with
no holes anywhere in it.

What that buys, all of it checkable without a car:

* every contribution the captures pin down independently — 52 of them, spread
  over nine different data bytes — agrees with the chain, and so do **45 of 45**
  consecutive pairs, including across byte boundaries;
* from the reference frame this app ships, the chain reproduces the `h4` of all
  **135** other recorded payloads;
* the requests the app sends — `1A 90`, `1A 92`, `1A 97`, `1A 98`, `01 00`,
  `09 02` — rebuild **byte for byte identical** to the frames the manufacturer's
  own tool put on the wire, headers included.

### Why the old table was worse than it looked

The fitted table shipped with a companion mask meant to say which bits it could
vouch for, and the mask answered a different question from the one it was asked.
It reported which **columns had been pivots** in the Gaussian elimination. A
pivot column is not a determined variable: back-substitution sets the free
variables to zero and solves the pivots *in terms of that choice*, so a pivot
whose row touches a free column takes whichever value the arbitrary choice gives
it. The test that belongs here is whether the change vector lies in the **row
space** of the training data, and by that test 22 of the 74 bits the mask called
known were not.

They were not harmless bits either. `1A 90` — read the VIN from the engine, the
first thing any diagnostic tool asks — needs two of them, and so do `3E 00` and
`01 20`. So identification, the tester-present heartbeat and the second half of
the supported-PID scan each produced a confident, wrong fingerprint, and the
adapter answered the only way it knows: on the car, the write went out at
0.683 s and the reset arrived at 0.686 s.

## Reading the bus

The adapter pushes. Counted over the factory session: **226 121 frame blocks
arrived unasked against 1744 polls the tool sent** — 130 pushed for every one
requested — at 135 blocks a second on average, 639 while a packet was
streaming, up to 61 KiB/s. The polls themselves come at a median of one a
second, and the tool never sustains more than 27 in a second.

So `op = 0x1c` is a status read, not a way to fetch frames, and a client that
reads the socket only while a poll is outstanding leaves everything pushed in
between unread. A request waiting for its answer should read; polling at ten
milliseconds, as this once did, is ninety times anything the manufacturer tool
does and fetches nothing that would not have arrived anyway.


`op = 0x1c` with subcommand `40 80 02` reads. Replies come as `op = 0xfe`
blocks: a 32-byte block header, a frame count in the high nibble of byte 34,
then 16-byte records of `len u32 | id u32 | 8 data bytes`.

A client built from this read **33 054 frames covering all 59 CAN ids** of a
live car, with no resynchronisation, matching what the manufacturer's own
driver sees.

## Writing to the bus

Same opcode with subcommand `60 80 02`, followed by `len u32 | id u32 | 8
bytes`.

Every write is derived from one recorded frame, so **that frame has to be a
frame the device actually accepted**, byte for byte, and both derived words
have to be recomputed for the payload that replaces it. Getting either wrong
costs more than it looks, and this app got both wrong in turn.

The first was the reference frame. A template carrying the right four `h4`
bytes in the wrong order, `40 6d 9b c6` where the capture says `c6 9b 6d 40`,
paired with an `h8` taken from some other message; the frame appears in no
capture. The check that catches that one is external, in the analysis
repository: the template must appear verbatim in a capture, and it now does.

Fixing it was not enough, which is the more useful half of the story. With a
genuine reference in place the writes were still refused, because the
contribution table used to derive `h4` had holes the code believed were not
there — see [`h4` and `h8`](#h4-and-h8-the-two-derived-words) — and because
`h8` was copied from the reference rather than recomputed, when it is the sum of
the data and moves with every payload.

What that looks like from outside is worth recording, because it looks like
almost anything except a bad fingerprint. The greeting, the channel setup and
the battery all work — they are recorded messages replayed unchanged, with
nothing to compute. The link comes up, the serial and the firmware are right,
the voltage is right and stable. But a write the firmware refuses gets no
answer and no error: the adapter drops the connection. Identification "loses
the link", a PID scan "loses the link", and an ELM327 client on the bridge sits
at "identifying vehicle" for ever, because the bridge's own link died under it
and it had nothing to report.

Two things follow from that, both of them now true of this app. A request has to
be signed correctly rather than optimistically — `1A 90`, `1A 92`, `1A 97`,
`1A 98`, `01 00` and `09 02` all rebuild byte for byte identical to the recorded
frames, which is the strongest check available without a car. And the adapter
has to be handed back even when the link has already gone, or the session stays
held and the next application to try, the manufacturer's own included, finds it
refusing everything until it is unplugged.

## Channel setup

`op = 0x50` configures the physical layer: the bit rate is a `u32 LE` at offset
134 of the data, the filter count is at byte 142, and filters are 59 bytes each
starting at offset 305, so the block length is `305 + n * 59`. The block does
**not** carry the protocol: the protocol is whichever opcode follows the `0x50`.

**A bit rate of zero closes the channel.** 268 of the 603 channel blocks in the
capture carry zero, and the last three messages of the whole session are one of
them followed by the two status reads that follow every channel change. That is
the teardown, and it is worth sending: the device takes one client and does not
notice a socket that merely goes away, so a session left open is a session that
keeps the next one out — the manufacturer's own application included, which is
what makes the adapter look locked up until it is unplugged.

One message earlier there is an `op = 0x53` carrying `10 00`, and the opening
sends the same opcode carrying `30 00`: stop and start. The capture holds 98 of
the first against 49 of the second, the last of them immediately before the
last channel block. Of the 268 zero-rate blocks, all carry the same body but
for a per-message stamp — so the block closes whatever is open rather than
naming a channel, and one of them is enough.

Two things about *when* to send it, both learnt from the same symptom. The
teardown used to be skipped whenever anything had gone wrong earlier in the
session, which is backwards: one request timing out is an ordinary event, and
from then on every disconnect dropped the socket without a word. And an app
that is swiped shut has to say goodbye too, or it leaves the session held in
exactly the same way.

The session in the capture also had **filters set**. Only `0x5E8` and the
diagnostic response ids come up the channel: 23 distinct ids in 168 404 frames,
and not one broadcast frame among them. Worth knowing before trying to use that
capture to learn anything about the rest of the bus.

## `op = 0x20` — the device's own voltages

No payload, always answered, and the answer is six bytes that appear nowhere
else in the protocol. Read as three `u16 LE`, the first is the battery voltage
in millivolts: over one session it ranged from 8765 while cranking to 14 674
with the alternator charging, sitting at 12 348 with the engine off. That is
the source for `ATRV`.

## Live data, the fast way

The factory tool does not poll one PID at a time. It defines a packet and asks
the module to stream it. Both halves are settled, from the four `0xAA` forms and
the seven `0x2C` forms the capture contains:

```
2C <dpid> <id16> <id16> …     define packet <dpid> as these parameters
6C <dpid>                     accepted

AA 04 <dpid> <dpid> …         start sending them
AA 00                         stop
```

There is no ISO-TP answer to `0xAA`. The answer is the stream: raw 8-byte CAN
frames on `0x5E8`, the packet id in the first byte and seven bytes of data
behind it. Over a 29.7-minute session that came to 33 925 frames for one packet
and 33 920 for another, against one round trip per value the other way.

### The rate is 100 Hz per packet

Not 100 Hz shared out. Counting frames per second against how many packets were
active at that second, over the whole capture:

| packets active | frames/s | per packet |
|---|---|---|
| 2 | 200 | 100 Hz |
| 3 | 300 | 100 Hz |
| 4 | 400 | 100 Hz |
| 6 | 600 | 100 Hz |
| 7 | 714 | 102 Hz |

One packet asked for on a car gave 102 frames a second, which is the same
number from an independent session. Worth knowing before asking for many: seven
packets is 714 eight-byte frames a second, about a sixth of a 500 kbit/s bus.

### It stops about five seconds after the tester goes quiet

This is the part that costs a trip to the car if it is not known. A module
streams only while the tester keeps talking to it. Two measurements, and they
agree:

* On a car, `AA 04` with nothing sent afterwards produced **three bursts of
  4.914, 4.895 and 4.911 seconds**, each starting at a request and stopping on
  its own, with recording windows of 10, 15, 10, 8 and 25 seconds around them
  that caught nothing else.
* In the factory capture, the longest the tool ever went without speaking to the
  engine **while the stream was still running is 3.29 seconds**, over 29.7
  minutes. It keeps that up with `3E` TesterPresent to the engine 141 times, a
  median of 3.08 seconds apart. Every one of the 22 bursts in that session ends,
  and is followed by 22 to 32 seconds of the tool saying nothing to the engine.

So anything that streams has to send `3E` about every two seconds, in its own
thread, from before the first request until after the last. A `7F .. 78`
"response pending" has the same clock: waiting quietly for the real answer is
waiting for one that will not come.

Three things the capture settles about `0x2C`, which matter because it is the one
piece here that is not a read:

- All seven went to the engine and all seven were accepted, with no
  `DiagnosticSessionControl` first. The only `10 03` in the capture is addressed
  to `0x94DA45F1`, not to `0x7E0`.
- The same packet was redefined mid-session with different contents — `2c fc 00
  0d` and later `2c fc 20 60`. A module does not allow that on anything it
  stores; it is scratch space.
- What it defines is what the module *reports*, not what it does.

### How the seven bytes are divided

The capture could not settle this. An earlier reading claimed it did, because
the `FE` packet returned seven bytes and its two parameters were four and three
bytes in the catalogue. That proved nothing: every frame on `0x5E8` is eight
bytes, including the ones for packets whose two fields are one byte each.

A car settled it. The same packet was defined twice, fifteen seconds apart:

| definition | condition | frame |
|---|---|---|
| `2C FD 00 0C` | ignition on, engine stopped | `fd 00 00 `**`00`**` 00 00 00 00` |
| `2C FD 00 0C 00 05` | idling, 750 rpm | `fd 0b bb `**`45`**` 00 00 00 00` |

`0x000C` is engine speed, two bytes; `0x0005` is coolant temperature, one. Bytes
1 and 2 give `0x0bbb / 4 = 749 rpm`, the idle that was there. Byte 3 is
`0x45 = 69`, and `69 - 40 = 29 °C`.

What makes it a proof rather than a plausible reading is byte 3 of the first
row. With the ignition on and the engine stopped the coolant sensor reads
ambient — known, because forty seconds later it read 24 °C — so if temperature
were always in the frame, `0x40` would have been there. It was `0x00`. Same
physical conditions, different definition, different byte 3.

```
0x5E8:  <dpid> <field 1> <field 2> … <zero padding>
```

Fields go **in the order they were asked for**, each the width the catalogue
gives, big-endian, and the rest of the eight bytes is zero. No length header, no
separators. The catalogue is what makes it sliceable.

`0x2C` is still absent from the app's allowed services and streaming with it is
still not implemented. That is now a decision about writing to a module rather
than a gap in what is known.

## Identifying the car

Not from make and model. GM service `0x1A` with a local identifier. The engine
answers directly to what it is:

```
1A 90  ->  W0LP-------------   VIN
1A 92  ->  DENSO0100           supplier identification
1A 97  ->  A17DTJ              system name or engine type
1A 98  ->  O100------          subscriber ID
1A 99  ->  20 11 10 25         date programmed, BCD
1A B4  ->  86AAS-----------    manufacturer's traceability number
```

The VIN answered consistently from eight different modules on one car.

That is the readable end of a block of 58 identifiers the factory tool reads to
fill its ECU ID screen, and the answers carry no labels of their own. The names
above are not from a specification: the catalogue extracted from GDS2 lists those
same identifiers as parameters with a name and a byte count, and the join is
accepted only where the catalogue's length equals the number of bytes the car
actually returned. 56 of the 58 match.

Two structures fall out that could not arise from a bad join. `0xC1`–`0xCC` are
part numbers and `0xD1`–`0xDC` are their alpha codes, paired one for one down the
range; and `0x42`–`0x49` come out as Calibration Part Number 12 through 19, eight
in a numbered row.

Eight were left deliberately unlabelled — `0x22`, `0x2F`, `0x30`, `0x3D`,
`0x41`, `0x5E`, `0x75`, `0xDF` — because the only catalogue entries carrying
those numbers were mode 01 PIDs sharing them by accident: a local identifier and
a mode 01 PID are different namespaces over the same integers.

Asking all 58 of them to every module that answers has since closed four of the
eight. The extra leverage is that a module's address gives its name, and a name
restricts the catalogue to that module's own variants, which is a far narrower
join than "any parameter with this number".

* **`0xDF` on the engine is the odometer.** One name, from a variant of the
  engine itself, with the catalogue's own scale (`X * 0.015625`, km):
  `00 fc 69 40` is 16 542 016 / 64 = **258 469 km**. The body control module
  answers the same identifier with `00 fc 6a 24` three times over — GM keeps the
  odometer in triplicate — and its copy is 3.6 km ahead of the engine's.

  **The dashboard on that car reads 258 470 km.** Which settles the scale as
  well as the label: the catalogue carries several formulas under the name
  *Odometer*, and the others would give 165 420 km or 16.5 million. Landing
  within a kilometre happens only with the one the catalogue attaches to
  identifier 223. A label can be guessed right by accident; a scale to that
  precision cannot.
* **`0x41` on the body control module is Calibration Part Number 11**, one name
  from that module's variants, completing the run: `0x42`–`0x49` were already
  Calibration Part Number 12 to 19.
* **`0x5E` and `0x75` are not implemented here.** All four modules answer
  `7F 1A 31`, request out of range. Nothing to name.
* `0x22`, `0x2F`, `0x30` and `0x3D` stay unlabelled, now for a measured reason:
  several parameters of the same module share the number and the length, and
  there is nothing to break the tie. `0x3D` on the engine, one byte, ties
  between *Calculated Fuel Economy Setup*, *Data Version* and three
  *NDP_Message*. Picking one would be inventing.
