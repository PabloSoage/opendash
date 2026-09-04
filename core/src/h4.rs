//! The `h4` fingerprint and the `h8` sum.
//!
//! Every message carries two derived words in its header, and the firmware
//! checks both. A greeting with one bit flipped in `h4` is answered with a TCP
//! reset, nine times out of nine. So a client that wants to write to the bus
//! has to get them right, and for a long time this one got them wrong: it
//! computed `h4` from an incomplete table and never recomputed `h8` at all,
//! which is why every request the app ever sent to the car was dropped and the
//! adapter closed the connection three milliseconds later.
//!
//! # `h8` is a sum
//!
//! Read the twenty data bytes as five little-endian 32-bit words and add them,
//! discarding the carry out of the top. That is `h8`, exactly, for all 708
//! recorded writes. It is not a timestamp and not a handle — it moves when the
//! payload moves, so it has to be recomputed for every frame that is built
//! rather than replayed.
//!
//! # `h4` is a CRC
//!
//! `h4` is affine over GF(2), so a new frame's fingerprint is the reference
//! frame's fingerprint XOR the contribution of every data bit that differs:
//!
//! ```text
//! h4(new) = h4(reference) XOR contributions(bits that changed)
//! ```
//!
//! What was missing was the contributions themselves. They used to be fitted,
//! and a fit leaves holes: the recorded traffic never varies some bits, so the
//! solver has nothing to say about them and quietly reports zero. The table is
//! no longer fitted. The contribution of one bit is the contribution of the
//! next one shifted down by one, with the reflected polynomial `0x9960034C`
//! folded in when a one falls off the bottom — the recurrence of a CRC — so
//! the whole of [`H4_BITS`] follows from a single anchor and there are no holes
//! left to fall into.
//!
//! # How far this goes
//!
//! * every contribution the captures pin down independently — 52 of them, in
//!   nine different bytes — agrees with the chain;
//! * from the reference frame this crate ships, the chain reproduces the
//!   fingerprint of all 135 other recorded payloads;
//! * the requests the app actually sends — `1A 90`, `1A 92`, `1A 97`, `1A 98`,
//!   `01 00`, `09 02` — come out byte for byte identical to the frames the
//!   manufacturer's own tool put on the wire, header included.
//!
//! There is no longer an `is_verified`: with a complete table there is nothing
//! to refuse. The old one answered from a mask of which columns had been
//! pivots in the elimination, which is not the same question as which
//! contributions the data determines, and it waved through 22 bits it could
//! not actually vouch for.

use crate::frame::{Message, HEADER};
use crate::h4_table::H4_BITS;

/// How many data bytes take part in both derived words.
const COVERED: usize = 20;

/// `h8` for a write record: the data read as little-endian 32-bit words, added.
///
/// Short data is padded with zeros, which is what the wire carries — the frame
/// is aligned to four bytes anyway.
pub fn h8(data: &[u8]) -> u32 {
    let mut sum = 0u32;
    for word in 0..COVERED / 4 {
        let mut v = 0u32;
        for byte in 0..4 {
            v |= u32::from(data.get(word * 4 + byte).copied().unwrap_or(0)) << (byte * 8);
        }
        sum = sum.wrapping_add(v);
    }
    sum
}

/// `h4` for `data` given a reference frame whose seq and padding are reused.
///
/// `data` is the payload plus the padding bytes, i.e. the same length the
/// reference frame carries after its header.
pub fn from_reference(reference: &[u8], data: &[u8]) -> Option<u32> {
    if reference.len() < HEADER {
        return None;
    }
    let mut h4 = u32::from_le_bytes([reference[4], reference[5], reference[6], reference[7]]);
    let tail = &reference[HEADER..];
    for byte in 0..COVERED {
        let diff = tail.get(byte).copied().unwrap_or(0) ^ data.get(byte).copied().unwrap_or(0);
        if diff == 0 {
            continue;
        }
        for bit in 0..8 {
            if diff & (1u8 << bit) != 0 {
                h4 ^= H4_BITS[byte * 8 + bit];
            }
        }
    }
    Some(h4)
}

/// Rebuild a frame with new data, fixing up both derived words.
///
/// Both, not one: `h8` moves with the payload just as `h4` does, and a frame
/// that carries the reference's `h8` alongside a recomputed `h4` is rejected
/// exactly like one that carries neither.
pub fn reframe(reference: &[u8], data: &[u8]) -> Option<Vec<u8>> {
    let h4 = from_reference(reference, data)?;
    let (mut msg, _) = Message::decode(reference)?;
    msg.data = data[..msg.data.len().min(data.len())].to_vec();
    msg.h4 = h4;
    msg.h8 = h8(data);
    let mut out = msg.encode();
    // keep the reference's padding: it is inside what both words cover
    let len = msg.data.len();
    for (i, b) in data.iter().enumerate().skip(len) {
        if HEADER + i < out.len() {
            out[HEADER + i] = *b;
        }
    }
    Some(out)
}
