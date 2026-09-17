package com.varuna.opendash.obd

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Commanding a module, and the lock in front of it.
 *
 * Everything else in this package reads. This is the one place that does not,
 * so it is also the place that says exactly how far it goes and why it stops
 * where it does.
 *
 * ## What the car speaks
 *
 * Not UDS. This is GMLAN (GMW3110), which is why the heartbeat is a bare `3E`
 * with no sub-function and why live data comes from `2C`/`AA` rather than a
 * stream of `22`. The actuator service is therefore **`$AE` DeviceControl**,
 * not the `0x2F` InputOutputControl a UDS car would use — a distinction that
 * matters because sending the wrong service is not a polite no-op, it is an
 * unknown byte sent to an engine.
 *
 * ## What is known and what is not
 *
 * `$AE` takes a **CPID** — a control parameter identifier — and control bytes
 * whose count and meaning the module defines per CPID. That namespace is not
 * the parameter namespace: `0x32A8` reads what the ECU is commanding the EGR
 * to do, and says nothing about which CPID would let a tool command it.
 *
 * The captured factory session contains **no `$AE` at all**: 480 requests,
 * every one of them a read. So this car's CPID table has never been observed,
 * and guessing one would mean sending invented bytes to a running engine.
 *
 * What is therefore implemented here is everything that is known:
 *
 *  - [RETURN_ALL], `AE 00`. CPID zero is defined by the protocol rather than by
 *    the module: it hands every device under control back to the ECU. It is the
 *    one command that is safe by construction, and it is the one you want if
 *    anything is ever left held.
 *  - [request], which builds a well-formed `$AE` for a CPID once one is known.
 *
 * Naming actuators the app cannot yet command may look like half a feature. It
 * is the honest half: the list below is what this engine reports it drives, it
 * is read from the car rather than assumed, and it is what the probe at the car
 * will be run against. A dialog full of invented CPIDs would look finished and
 * be worse.
 *
 * ## The lock
 *
 * [unlocked] is off at every start and is not persisted. It is turned on only
 * by the device credential — see `SettingsScreen` — and turned off again by
 * [lock] whenever the link drops, because a permission granted for a car that
 * is no longer on the other end of the socket is not a permission for the next
 * one.
 */
object Actuation {

    /** GMLAN DeviceControl. */
    const val SERVICE = 0xAE

    /** A positive answer to [SERVICE]. Request and response differ by 0x40. */
    const val POSITIVE = 0xEE

    /**
     * Hand every device back to the module.
     *
     * CPID 0 is the protocol's own, so this needs no table and works on a
     * module whose CPIDs are unknown — which is every module here.
     */
    val RETURN_ALL = byteArrayOf(SERVICE.toByte(), 0x00)

    /** Whether this app may emit [SERVICE]. Off until the device lock says so. */
    var unlocked by mutableStateOf(false)
        private set

    fun unlock() {
        unlocked = true
    }

    fun lock() {
        unlocked = false
    }

    /** A well-formed DeviceControl for [cpid]. */
    fun request(cpid: Int, control: ByteArray): ByteArray =
        byteArrayOf(SERVICE.toByte(), cpid.toByte()) + control

    /**
     * Whether a catalogue row names something the module drives.
     *
     * The rule is the suffix, and deliberately only the suffix. GDS2 names an
     * output it commands "… Command" — `EGR Command`, `Glow Plug Command`,
     * `Cooling Fan Relay 1 Command` — and that ending is the marque's own
     * convention rather than a guess about semantics.
     *
     * Anything looser fails on this car. Matching "Control" anywhere takes in
     * `Cruise Control Cancel Switch`, which is a switch the driver presses;
     * matching "Test" takes in `HO2S Test Status`, which is a result; matching
     * "Learn" takes in `Immobilizer Password Learn`, which is a routine on a
     * different service and not something to offer beside a cooling fan. Of the
     * 331 rows this engine answered with its own width, a loose rule matches
     * 68 and the suffix matches 29 — and it is the 29 that are outputs.
     *
     * Note what this flag does and does not mean. It marks a row as *naming*
     * something commandable, which is why the padlock sits on it. It is not a
     * claim that a command exists for it yet — see the class comment.
     */
    fun isCommandable(name: String): Boolean = name.trimEnd().endsWith(SUFFIX)

    /**
     * Identifiers carrying the module's own device-control status.
     *
     * These are not outputs, so they get no padlock. They are the scoreboard,
     * and they are worth more than the padlock is: they answer the question it
     * raises, which is whether the module would let you do the thing at all.
     *
     * `0x32EB` is the device-control register — "Device Control Requested While
     * Other Device Controls Active", "Function Aborted", "Learn Procedure
     * Already Performed This Ignition Cycle", "Turn the Ignition Off and
     * Restart the Engine". `0x32EC` is why a request was refused: coolant,
     * engine speed, rail pressure, intake air or transmission fluid out of
     * range. `0x326D` is nineteen bits of regeneration interlock — "Diesel
     * Particulate Filter Regeneration is Not Allowed", "Soot Mass Too Large",
     * "Gear Engaged or Clutch Pressed", "Accelerator Pedal Pressed", and
     * control lost because the tool stopped talking, which is the readable face
     * of the five-second timeout the heartbeat exists to avoid.
     *
     * Their presence is also the evidence that this module implements device
     * control at all. The captured session never sends `$AE`; the ECU keeps the
     * status words for it regardless.
     */
    val STATUS_IDENTIFIERS = setOf(0x32EB, 0x32EC, 0x326D)

    fun isControlStatus(pid: Int): Boolean = pid in STATUS_IDENTIFIERS

    private const val SUFFIX = "Command"
}
