//! The `h4` fingerprint.
//!
//! The firmware checks it: over a raw socket, a greeting with one bit flipped
//! in `h4` is answered with a TCP reset, nine times out of nine. So a client
//! that wants to write to the bus has to get it right.
//!
//! `h4` is affine over GF(2) — 717 checks of "same input difference, same
//! output difference" with no contradiction — which means it can be used
//! without being named. Take a frame that was actually recorded, change the
//! CAN id and the payload, and XOR in the contribution of every bit that
//! differs:
//!
//! ```text
//! h4(new) = h4(reference) XOR contributions(bits that changed)
//! ```
//!
//! The contributions in [`H4_BITS`] were recovered from a one-bit sweep: 228
//! writes whose content we chose, plus 480 writes from real captures.
//!
//! # What this is not
//!
//! The full function is not known. The linear system has rank 118 of 209, so
//! the contribution of a bit that never varied in the training data is not
//! determined and is stored as zero. In practice:
//!
//! * validated on **4000 of 4000** pairs of real writes that differ only in
//!   the id and the payload, and on a `0100` request built from scratch, whose
//!   computed `h4` matched the recorded one exactly;
//! * **not proven** for a payload unlike anything seen. [`unverified_bits`]
//!   reports which bits fall in that hole so a caller can refuse rather than
//!   send a frame the device will silently drop.
//!
//! Closing the hole needs another sweep with random payloads. It needs the
//! device powered but not the car.

use crate::frame::{Message, HEADER};
use crate::h4_table::H4_BITS;

/// Bits whose contribution the sweep never exercised. A frame that changes one
/// of these relative to its reference may get an `h4` the device rejects.
pub fn unverified_bits() -> Vec<(usize, u8)> {
    (0..20)
        .flat_map(|byte| (0..8).map(move |bit| (byte, bit)))
        .filter(|&(byte, bit)| H4_BITS[byte * 8 + bit as usize] == 0)
        .collect()
}

/// `h4` for `data` given a reference frame whose `seq`, `h8` and padding are
/// reused unchanged. `data` is the payload plus the padding bytes, i.e. the
/// same length the reference frame carries after its header.
pub fn from_reference(reference: &[u8], data: &[u8]) -> Option<u32> {
    if reference.len() < HEADER {
        return None;
    }
    let mut h4 = u32::from_le_bytes([reference[4], reference[5], reference[6], reference[7]]);
    let tail = &reference[HEADER..];
    let n = tail.len().max(data.len()).min(20);
    for byte in 0..n {
        let diff = tail.get(byte).copied().unwrap_or(0) ^ data.get(byte).copied().unwrap_or(0);
        if diff == 0 {
            continue;
        }
        for bit in 0..8 {
            if diff & (1 << bit) != 0 {
                h4 ^= H4_BITS[byte * 8 + bit];
            }
        }
    }
    Some(h4)
}

/// Does this change touch a bit the sweep never exercised?
pub fn is_verified(reference: &[u8], data: &[u8]) -> bool {
    if reference.len() < HEADER {
        return false;
    }
    let tail = &reference[HEADER..];
    (0..20).all(|byte| {
        let diff = tail.get(byte).copied().unwrap_or(0) ^ data.get(byte).copied().unwrap_or(0);
        (0..8).all(|bit| diff & (1 << bit) == 0 || H4_BITS[byte * 8 + bit] != 0)
    })
}

/// Rebuild a frame with a new payload, fixing up `h4`.
pub fn reframe(reference: &[u8], data: &[u8]) -> Option<Vec<u8>> {
    let h4 = from_reference(reference, data)?;
    let (mut msg, _) = Message::decode(reference)?;
    msg.data = data[..msg.data.len().min(data.len())].to_vec();
    msg.h4 = h4;
    let mut out = msg.encode();
    // keep the reference's padding: it is inside what h4 covers
    let len = msg.data.len();
    for (i, b) in data.iter().enumerate().skip(len) {
        if HEADER + i < out.len() {
            out[HEADER + i] = *b;
        }
    }
    Some(out)
}
