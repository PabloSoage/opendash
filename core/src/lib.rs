//! The opendash core: everything needed to talk to a Scanmatik SM3 and to
//! present it to a phone app as an ELM327.
//!
//! The protocol was recovered by capture and measurement, not from any
//! documentation. `docs/protocol.md` records what was established and how,
//! including what is still open.

pub mod elm327;
pub mod frame;
pub mod h4;
mod h4_table;
pub mod isotp;

pub use h4_table::H4_BITS;
