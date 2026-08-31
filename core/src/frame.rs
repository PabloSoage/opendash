//! Message framing.
//!
//! Every message is a 16-byte header followed by `len` bytes of data:
//!
//! ```text
//! 0..1   seq    u16 LE   USB: a counter · TCP: always 0xffff
//! 2..3   token  u16 LE   USB: the driver's echo · TCP: always 0
//! 4..7   h4     u32 LE   fingerprint over seq, h8, the data and the padding
//! 8..11  h8     u32 LE   context / handle
//! 12     op
//! 13..14 len    u16 LE
//! 15     chk    (op + len_lo + len_hi + 0x55) & 0xff
//! 16..   data
//! ```
//!
//! What matters and is easy to get wrong: a message does **not** occupy
//! `16 + len` bytes on the wire. It occupies that rounded up to a multiple of
//! four. Send the short version and the device waits for the missing bytes,
//! the stream desynchronises and the connection is reset. Measured over 4066
//! messages from two independent sessions: the gap between one message and the
//! next is always exactly this padding.

pub const HEADER: usize = 16;

/// Bytes a message really occupies, padding included.
pub const fn stride(len: usize) -> usize {
    (HEADER + len + 3) & !3
}

pub const fn checksum(op: u8, len: u16) -> u8 {
    (op as u16)
        .wrapping_add(len & 0xff)
        .wrapping_add((len >> 8) & 0xff)
        .wrapping_add(0x55) as u8
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Message {
    pub seq: u16,
    pub token: u16,
    pub h4: u32,
    pub h8: u32,
    pub op: u8,
    pub data: Vec<u8>,
}

impl Message {
    /// Serialise, padding to the four-byte stride. The padding the device
    /// sends is stale buffer content, not zeros, but its value does not
    /// matter — only that the bytes are there.
    pub fn encode(&self) -> Vec<u8> {
        let len = self.data.len();
        let mut out = vec![0u8; stride(len)];
        out[0..2].copy_from_slice(&self.seq.to_le_bytes());
        out[2..4].copy_from_slice(&self.token.to_le_bytes());
        out[4..8].copy_from_slice(&self.h4.to_le_bytes());
        out[8..12].copy_from_slice(&self.h8.to_le_bytes());
        out[12] = self.op;
        out[13..15].copy_from_slice(&(len as u16).to_le_bytes());
        out[15] = checksum(self.op, len as u16);
        out[HEADER..HEADER + len].copy_from_slice(&self.data);
        out
    }

    /// Parse one message at the start of `buf`. Returns it with the number of
    /// bytes consumed, which is the padded stride.
    pub fn decode(buf: &[u8]) -> Option<(Message, usize)> {
        if buf.len() < HEADER {
            return None;
        }
        let op = buf[12];
        let len = u16::from_le_bytes([buf[13], buf[14]]);
        if buf[15] != checksum(op, len) {
            return None;
        }
        let len = len as usize;
        if buf.len() < HEADER + len {
            return None;
        }
        let msg = Message {
            seq: u16::from_le_bytes([buf[0], buf[1]]),
            token: u16::from_le_bytes([buf[2], buf[3]]),
            h4: u32::from_le_bytes([buf[4], buf[5], buf[6], buf[7]]),
            h8: u32::from_le_bytes([buf[8], buf[9], buf[10], buf[11]]),
            op,
            data: buf[HEADER..HEADER + len].to_vec(),
        };
        Some((msg, stride(len).min(buf.len())))
    }
}

/// Walk a stream, skipping anything that does not parse.
pub fn split(buf: &[u8]) -> Vec<Message> {
    let mut out = Vec::new();
    let mut off = 0;
    while off + HEADER <= buf.len() {
        match Message::decode(&buf[off..]) {
            Some((m, used)) => {
                off += used;
                out.push(m);
            }
            None => off += 1,
        }
    }
    out
}
