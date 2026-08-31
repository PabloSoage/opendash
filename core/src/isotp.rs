//! ISO-TP reassembly.
//!
//! An ELM327 hands back a response already joined, so the bridge has to do the
//! joining itself. The first byte of a CAN frame carries the protocol control
//! information:
//!
//! ```text
//! 0x0X  single frame, X data bytes
//! 0x1X  first of several, 12-bit length in the low nibble plus the next byte
//! 0x2X  consecutive, X is the sequence number
//! 0x3X  flow control, no payload
//! ```
//!
//! Exercised against 168 884 real CAN frames from a factory-tool session,
//! which reassembled into 1133 packets — among them the identification of the
//! car: VIN, system name and engine code.

use std::collections::HashMap;

#[derive(Default)]
pub struct Reassembler {
    open: HashMap<u32, Pending>,
}

struct Pending {
    expected: usize,
    parts: Vec<u8>,
}

impl Reassembler {
    pub fn new() -> Self {
        Self::default()
    }

    /// Feed one CAN frame. Returns a complete packet when one finishes.
    pub fn push(&mut self, id: u32, frame: &[u8]) -> Option<Vec<u8>> {
        if frame.is_empty() {
            return None;
        }
        match frame[0] >> 4 {
            0 => {
                let n = (frame[0] & 0x0f) as usize;
                if n == 0 || frame.len() < 1 + n {
                    return None;
                }
                Some(frame[1..1 + n].to_vec())
            }
            1 => {
                if frame.len() < 2 {
                    return None;
                }
                let expected = (((frame[0] & 0x0f) as usize) << 8) | frame[1] as usize;
                self.open.insert(
                    id,
                    Pending {
                        expected,
                        parts: frame[2..].to_vec(),
                    },
                );
                None
            }
            2 => {
                let done = {
                    let p = self.open.get_mut(&id)?;
                    p.parts.extend_from_slice(&frame[1..]);
                    p.parts.len() >= p.expected
                };
                if done {
                    let p = self.open.remove(&id)?;
                    Some(p.parts[..p.expected].to_vec())
                } else {
                    None
                }
            }
            _ => None, // flow control carries nothing
        }
    }
}

/// Split a payload into CAN frames, single or segmented.
pub fn segment(payload: &[u8]) -> Vec<Vec<u8>> {
    if payload.len() <= 7 {
        let mut f = vec![payload.len() as u8];
        f.extend_from_slice(payload);
        f.resize(8, 0);
        return vec![f];
    }
    let mut out = Vec::new();
    let mut first = vec![
        0x10 | ((payload.len() >> 8) as u8 & 0x0f),
        payload.len() as u8,
    ];
    first.extend_from_slice(&payload[..6]);
    out.push(first);
    let mut n = 1u8;
    for chunk in payload[6..].chunks(7) {
        let mut f = vec![0x20 | (n & 0x0f)];
        f.extend_from_slice(chunk);
        f.resize(8, 0);
        out.push(f);
        n = n.wrapping_add(1);
    }
    out
}
