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
//! [`H4_BITS`] comes from three sources: a one-bit sweep of 228 chosen writes,
//! 425 writes with a random id and a random payload, and 480 writes from real
//! captures.
//!
//! # How far this goes
//!
//! * **85 of 85** held-out writes with a random id and a random payload —
//!   messages the model had never seen — get exactly the `h4` the device put
//!   on the wire;
//! * **4000 of 4000** pairs of recorded writes differing only in id and
//!   payload;
//! * a `0100` request assembled from scratch reproduces the recorded `h4`.
//!
//! For the shape a bridge actually sends — an 11-bit CAN id and eight payload
//! bytes — this computes `h4` for anything.
//!
//! # What is still not determined
//!
//! The system reaches rank 129 of 209. What remains are fields that never vary
//! by construction: the `60 80 02` subcommand, the record length, and the id
//! bytes above eleven bits. A bridge does not vary them either, so the gap is
//! theoretical rather than practical — but [`unverified_bits`] still reports
//! it and [`is_verified`] answers for a specific frame, so a caller can refuse
//! instead of sending something the device drops in silence.

use crate::frame::{Message, HEADER};
use crate::h4_known::H4_KNOWN;
use crate::h4_table::H4_BITS;

/// Bits the sweeps never pinned down. A zero in [`H4_BITS`] alone does not say
/// this — a contribution can be genuinely nil — so the answer comes from the
/// separate mask the solver emits alongside the table.
pub fn unverified_bits() -> Vec<(usize, u8)> {
    (0..20)
        .flat_map(|byte| (0..8).map(move |bit| (byte, bit)))
        .filter(|&(byte, bit)| H4_KNOWN[byte] & (1u8 << bit) == 0)
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
            if diff & (1u8 << bit) != 0 {
                h4 ^= H4_BITS[byte * 8 + bit as usize];
            }
        }
    }
    Some(h4)
}

/// Does this change touch a bit the sweeps never pinned down?
pub fn is_verified(reference: &[u8], data: &[u8]) -> bool {
    if reference.len() < HEADER {
        return false;
    }
    let tail = &reference[HEADER..];
    (0..20).all(|byte| {
        let diff = tail.get(byte).copied().unwrap_or(0) ^ data.get(byte).copied().unwrap_or(0);
        diff & !H4_KNOWN[byte] == 0
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
