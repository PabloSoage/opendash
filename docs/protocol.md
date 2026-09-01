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
8..11  h8     u32 LE   context / handle
12     op
13..14 len    u16 LE
15     chk    (op + len_lo + len_hi + 0x55) & 0xff
16..   data
```

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

## `h4`

The firmware checks it. Over a raw socket, a greeting whose `h4` differs by one
bit is answered with a TCP reset — three values, three samples each, nine out
of nine. It is the only error signal the firmware has ever produced.

It is affine over GF(2): 717 checks of "same input difference gives the same
output difference" with no contradiction. It depends on `seq`, `h8`, the data
**and the padding** — dropping any one of those from the model collapses
leave-one-out accuracy from 40/40 to 2/40 or worse. It is not a textbook
CRC-32: 2688 combinations of region, seed, polynomial, reflection, xorout and
byte order all fail, and recovering a polynomial by GCD gives degree zero.

Being affine is enough to use it without naming it:

```
h4(new) = h4(reference) XOR contributions(bits that changed)
```

The contributions came from three sources: a one-bit sweep of 228 chosen
writes, 425 writes with a random id and a random payload, and 480 writes from
real captures. What that buys:

* **85 of 85** held-out writes with a random id and a random payload — messages
  the model had never seen — get exactly the `h4` the device put on the wire;
* **4000 of 4000** pairs of recorded writes differing only in id and payload;
* a `0100` request assembled from scratch reproduces the recorded `h4`.

The one-bit sweep alone was not enough, and it is worth saying why: changing a
single bit per message leaves the `seq`, `h8` and padding varying alongside it,
so the directions stay entangled and the rank stalls at 118. Random payloads
separate them — each message added exactly one to the rank until it saturated.

**What is still not determined.** Rank 129 of 209. The rest are fields that
never vary by construction: the `60 80 02` subcommand, the record length, and
the id bytes above eleven bits. A bridge does not vary them either.
`h4::unverified_bits` still reports them and `h4::is_verified` answers for a
given frame.

## Reading the bus

`op = 0x1c` with subcommand `40 80 02` reads. Replies come as `op = 0xfe`
blocks: a 32-byte block header, a frame count in the high nibble of byte 34,
then 16-byte records of `len u32 | id u32 | 8 data bytes`.

A client built from this read **33 054 frames covering all 59 CAN ids** of a
live car, with no resynchronisation, matching what the manufacturer's own
driver sees.

## Writing to the bus

Same opcode with subcommand `60 80 02`, followed by `len u32 | id u32 | 8
bytes`.

## Channel setup

`op = 0x50` configures the physical layer: the bit rate is a `u32 LE` at offset
134 of the data, the filter count is at byte 142, and filters are 59 bytes each
starting at offset 305, so the block length is `305 + n * 59`. The block does
**not** carry the protocol: the protocol is whichever opcode follows the `0x50`.

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
behind it. Over a 23-minute session that came to 33 925 frames for one packet and
33 920 for another, against one round trip per value the other way.

Three things the capture settles about `0x2C`, which matter because it is the one
piece here that is not a read:

- All seven went to the engine and all seven were accepted, with no
  `DiagnosticSessionControl` first. The only `10 03` in the capture is addressed
  to `0x94DA45F1`, not to `0x7E0`.
- The same packet was redefined mid-session with different contents — `2c fc 00
  0d` and later `2c fc 20 60`. A module does not allow that on anything it
  stores; it is scratch space.
- What it defines is what the module *reports*, not what it does.

What the capture does **not** settle is how the seven data bytes are divided
between the fields of a multi-field packet. An earlier reading claimed it did,
because the `FE` packet returned seven bytes and its two parameters were four
and three bytes in the catalogue. That proves nothing: every frame on `0x5E8` is
eight bytes, including the ones for packets whose two fields are one byte each. A
CAN frame is eight bytes.

`0x2C` is therefore absent from the app's allowed services, and streaming with it
is not implemented. It is a decision, not an oversight.

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

Eight identifiers are left deliberately unlabelled — `0x22`, `0x2F`, `0x30`,
`0x3D`, `0x41`, `0x5E`, `0x75`, `0xDF`. The only catalogue entries carrying those
numbers are mode 01 PIDs that share them by accident: a local identifier and a
mode 01 PID are different namespaces over the same integers. They are read and
shown as bytes rather than labelled with something plausible and wrong.
