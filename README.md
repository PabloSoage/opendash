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
| `h4` fingerprint | done, 85 of 85 unseen random writes |
| ISO-TP reassembly | done, 168 884 frames from a factory-tool session |
| ELM327 command set | the part real apps use |
| Android app | scaffold: socket, foreground service, Spanish and English |

## Layout

```
core/     the protocol, in Rust: framing, h4, ISO-TP, ELM327
android/  the app: ELM327 socket, foreground service, Compose UI
docs/     what was established, and how
```

The app ships in English and Spanish from the first commit, with a picker that
uses the per-app language API so it can be set without changing the phone.
Adding a language after the fact means auditing every string, and it never
happens.

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

`h4` was the one place the code could be wrong in a way that matters. It now
computes the right fingerprint for writes it has never seen — 85 of 85 with a
random id and a random payload — so the shape a bridge sends is covered.

The linear system still stops at rank 129 of 209, but what it misses are fields
that never vary: the subcommand, the record length, the id bytes above eleven
bits. `h4::unverified_bits` reports them and `h4::is_verified` answers for a
specific frame, so a caller can still refuse rather than have the device
silently drop a frame.

Everything in `core` is exercised against real captures.
