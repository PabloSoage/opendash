# opendash

An ELM327 bridge for the Scanmatik SM3, and the diagnostics app on top of it.

The SM3 is a capable J2534 interface, but it only talks to its own Windows
software. opendash speaks its protocol directly and presents it to a phone as
an ELM327 over Wi-Fi, so apps like ScanMyOpelCAN can use it while you drive.

The protocol was recovered by capture and measurement — see
[`docs/protocol.md`](docs/protocol.md), which also records what is still open.

## State

| | |
|---|---|
| Framing, padding, checksum | done, 4066 messages |
| Reading the bus without the vendor driver | done, 33 054 frames, 59 of 59 ids |
| `h4` fingerprint | usable, 4000 of 4000 real writes — with a documented gap |
| ISO-TP reassembly | done, 168 884 frames from a factory-tool session |
| ELM327 command set | the part real apps use |
| Android app | not started |

## Layout

```
core/     the protocol, in Rust: framing, h4, ISO-TP, ELM327
docs/     what was established, and how
```

`core` is a library with no I/O: bytes in, bytes out. That keeps it testable on
a host without a device, which matters because the interesting parts were
recovered from captures and the tests replay those captures.

## Tests

```
cd core && cargo test
```

The fixtures are frames the device actually sent or accepted. The `h4` test
takes 200 pairs of real writes and checks that the computed fingerprint is the
one the device put on the wire.

## Plugins

The app ships with the standard OBD-II PIDs, which are public. Richer
catalogues — a manufacturer's parameter names, scaling formulas, units and
module layout — are per-brand plugins, generated locally from a GDS2
installation by a tool in this repository.

The generated catalogues are **not** distributed here. They are derived from
proprietary data; if you have the software you can generate your own.

## Honesty about what is not proven

`h4` is the one place where the code can be wrong in a way that matters, and it
says so: the linear system that recovers it has rank 118 of 209, so a payload
unlike anything observed may fall in a hole. `h4::unverified_bits` reports which
bits, and `h4::is_verified` answers for a specific frame, so a caller can refuse
to send rather than have the device silently drop it.

Everything else in `core` is exercised against real captures.
