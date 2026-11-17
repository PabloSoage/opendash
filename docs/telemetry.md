# Telemetry out

The live screen can send every value it receives to another machine, and take
from it the selection of what to watch. The use it was written for is a head
unit with its own screen: a board in the dashboard that shows engine values next
to its own data, and needs road speed to lock a function while the car moves.
Anything that opens a TCP socket can be the client.

It is off until it is started from the Link screen ("Telemetry out"). It listens
on port 35001, on all interfaces by default, since the client is by definition
another machine (a phone hotspot, a USB tether or the car's own Wi-Fi). Loopback
is the other choice, for a client on the phone.

Nothing in it talks to the car. A selection is a list of rows to read, so the
worst a client can do is pick badly; the app still never writes to a module.

## Wire format

Protocol name `opendash-telemetry/1`. TCP, UTF-8, one JSON object per line,
`\n` at the end of each. Numbers use a dot and no grouping, whatever the phone's
locale. A value that is not a number is `null`.

### From the app

On connecting, the client gets, in this order:

```json
{"type":"hello","proto":"opendash-telemetry/1"}
{"type":"rows","module":"Engine Control Module","rows":[{"key":"cat:...","name":"Engine Speed","id":"0x000C","unit":"RPM"}, ...]}
{"type":"state","running":false,"rounds":0,"keys":[]}
```

`rows` is what the live screen can stream for the chosen module, as the scan
left it: the rows the car answered, or the catalogue when there is no scan. It
is sent again whenever that list changes. `key` is the same row key a preset
uses; `id` is the identifier the samples carry (`0x000C` for a service `0x22`
identifier, `PID 0D` for a standard OBD-II PID).

`state` is sent whenever a run starts or stops. `keys` are the rows being read
and `rounds` how many rounds the selection was split into (0 for polling). See
the README on rotation: more than forty identifiers do not fit one set of
packets, so they are declared in rounds, one after another.

Then one line per value, as it arrives:

```json
{"t":1763390000123,"n":"Engine Speed","id":"0x000C","u":"RPM","v":812.5}
```

`t` is the phone's clock in milliseconds since 1970. Two values decoded from the
same frame carry the same `t`, and are the same instant. These are exactly the
rows a recording would get, field for field.

### From the client

```json
{"select":["0x000C","0x000D","cat:..."],"always":["0x000D"],"start":true}
{"preset":"Astra J A17DTJ","start":true}
{"start":true}
{"stop":true}
```

- `select` replaces the selection on the live screen, the way applying a preset
  does. Each entry is a row key, or an identifier as the samples write it, which
  is simpler for a client and picks the first row with that identifier. The order
  is the packet layout: neighbours share a frame. `always` are rows that ride in
  every round instead of waiting for their turn, which is what road speed needs.
- `preset` applies a stored group by name.
- `start` starts reading the selection; on `select` or `preset` it starts it
  straight away. If a run is already going, a new selection always restarts it.
- `stop` stops.

Every command is answered with `{"type":"ack","ok":true}`, or with `ok` false and
a `why`. A command is only acted on while the live screen is open, since the
selection belongs to it; with the screen closed the answer says so.

Changing the selection while streaming takes about two seconds: the module's
packets are stopped, the new ones declared and started again (`AA 00`, `2C`,
`AA 04`), and nothing arrives in between. That is the same cost as changing it by
hand, and it is why a client should change it when it needs to rather than on
every screen it shows.

## Load

At the module's ceiling, seven packets at about 100 Hz with forty identifiers,
the app sends about 3,500 lines a second, some 250 kB/s. Each client has its own
queue of 16,384 lines; one that reads too slowly loses the oldest lines, counted
on the Link screen, and never holds up the thread that reads the car.

## Implementation

`android/src/main/java/com/varuna/opendash/data/Telemetry.kt` holds the server,
the protocol text and its parser, apart from Android, and
`android/src/test/java/com/varuna/opendash/data/TelemetryTest.kt` tests them on
the host: locale-proof numbers, the commands and what is refused, a client that
gets the greeting, a hundred samples in order and an answer, and a client that
never reads, which must lose lines without slowing the sender. The live screen
(`ui/LiveScreen.kt`) offers its rows and applies the commands, and the monitor
(`data/Monitor.kt`) sends each value where it also hands it to the recorder.
