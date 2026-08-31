//! Tests against real captures. The fixtures are frames the device actually
//! sent or accepted, not values invented for the test.

use opendash_core::{elm327, frame, h4, isotp};

fn hex(s: &str) -> Vec<u8> {
    (0..s.len()).step_by(2).map(|i| u8::from_str_radix(&s[i..i + 2], 16).unwrap()).collect()
}

// ── framing ───────────────────────────────────────────────────────────────

#[test]
fn stride_rounds_up_to_four() {
    // Measured on 4066 messages from two independent sessions.
    assert_eq!(frame::stride(0), 16);
    assert_eq!(frame::stride(172), 188); // already a multiple of four
    assert_eq!(frame::stride(175), 192);
    assert_eq!(frame::stride(2), 20);
    assert_eq!(frame::stride(261), 280); // the one that broke the first replay
    assert_eq!(frame::stride(305), 324);
}

#[test]
fn checksum_matches_the_wire() {
    assert_eq!(frame::checksum(0x83, 0), 0xd8);
    assert_eq!(frame::checksum(0x88, 0), 0xdd);
    assert_eq!(frame::checksum(0x30, 261), 0x8b);
    assert_eq!(frame::checksum(0x1c, 19), 0x84);
    assert_eq!(frame::checksum(0x1c, 3), 0x74);
}

#[test]
fn greeting_round_trips() {
    let wire = hex("ffff0000ffff000000000000830000d8");
    let (m, used) = frame::Message::decode(&wire).expect("decodes");
    assert_eq!(used, 16);
    assert_eq!(m.op, 0x83);
    assert_eq!(m.seq, 0xffff);
    assert_eq!(m.h4, 0xffff); // len = 0 means h4 equals seq
    assert!(m.data.is_empty());
    assert_eq!(m.encode(), wire);
}

#[test]
fn a_write_round_trips_with_its_padding() {
    // A 0100 request to 0x7DF, as the official tool put it on the wire.
    let wire = hex("ffff0000406d9bc6708202e91c13008460800208000000df070000020100000000000000");
    let (m, used) = frame::Message::decode(&wire).expect("decodes");
    assert_eq!(m.op, 0x1c);
    assert_eq!(m.data.len(), 19);
    assert_eq!(used, 36); // 16 + 19 = 35, padded to 36
    assert_eq!(&m.data[0..3], &[0x60, 0x80, 0x02]);
    assert_eq!(u32::from_le_bytes([m.data[7], m.data[8], m.data[9], m.data[10]]), 0x7df);
}

// ── h4 ────────────────────────────────────────────────────────────────────

#[derive(serde::Deserialize)]
struct Pair {
    #[serde(rename = "ref")]
    reference: String,
    objetivo: String,
}

#[test]
fn h4_predicts_real_writes() {
    // Pairs of writes from the captures that share seq and h8 and differ only
    // in the CAN id and the payload.
    let raw = include_str!("fixtures/h4_pairs.json");
    let pairs: Vec<Pair> = serde_json::from_str(raw).expect("fixtures parse");
    assert!(pairs.len() >= 100, "expected a decent sample, got {}", pairs.len());

    let mut checked = 0;
    for p in &pairs {
        let reference = hex(&p.reference);
        let target = hex(&p.objetivo);
        let want = u32::from_le_bytes([target[4], target[5], target[6], target[7]]);
        let got = h4::from_reference(&reference, &target[frame::HEADER..]).expect("computes");
        assert_eq!(got, want, "h4 mismatch for {}", p.objetivo);
        checked += 1;
    }
    assert_eq!(checked, pairs.len());
}

#[test]
fn h4_reports_the_bits_it_cannot_vouch_for() {
    // Honest bookkeeping: the sweep did not exercise every bit, and the ones
    // it missed are stored as a zero contribution.
    let holes = h4::unverified_bits();
    assert!(!holes.is_empty(), "if this is empty the table was regenerated");
    // The subcommand and the record length never vary, so they are expected.
    assert!(holes.iter().any(|&(b, _)| b < 7));
}

// ── ISO-TP ────────────────────────────────────────────────────────────────

#[test]
fn single_frame_comes_straight_back() {
    let mut r = isotp::Reassembler::new();
    let out = r.push(0x7e8, &hex("064100983b201300")).expect("complete");
    assert_eq!(out, hex("4100983b2013"));
}

#[test]
fn segmented_response_is_joined() {
    // 17 bytes split across a first frame and two consecutive ones.
    let mut r = isotp::Reassembler::new();
    assert!(r.push(0x7e8, &hex("1011010203040506")).is_none());
    assert!(r.push(0x7e8, &hex("2107080910111213")).is_none());
    let out = r.push(0x7e8, &hex("2214151617000000")).expect("complete");
    assert_eq!(out.len(), 17);
    assert_eq!(&out[0..6], &hex("010203040506")[..]);
    assert_eq!(out[16], 0x17);
}

#[test]
fn segmenting_is_the_inverse_of_reassembly() {
    let payload: Vec<u8> = (1..=30).collect();
    let frames = isotp::segment(&payload);
    assert!(frames.len() > 1);
    let mut r = isotp::Reassembler::new();
    let mut done = None;
    for f in &frames {
        if let Some(p) = r.push(0x7e8, f) {
            done = Some(p);
        }
    }
    assert_eq!(done.expect("reassembles"), payload);
}

// ── ELM327 ────────────────────────────────────────────────────────────────

#[test]
fn at_commands_answer_like_the_real_thing() {
    let mut e = elm327::Elm::new();
    assert_eq!(e.command("ATZ"), elm327::Reply::Text("ELM327 v1.5".into()));
    assert_eq!(e.command("AT E0"), elm327::Reply::Text("OK".into()));
    assert!(!e.echo);
    assert_eq!(e.command("ATSP0"), elm327::Reply::Text("OK".into()));
    // ATDPN answers the number, not the name; a leading A means automatic.
    assert_eq!(e.command("ATDPN"), elm327::Reply::Text("6".into()));
    assert_eq!(e.command("ATDP"), elm327::Reply::Text("ISO 15765-4 CAN 11/500".into()));
}

#[test]
fn atrv_reports_the_devices_own_reading() {
    // Opcode 0x20 answers six bytes whose first u16 is millivolts. The
    // capture ranged from 8765 while cranking to 14674 with the alternator on.
    let mut e = elm327::Elm::new();
    e.millivolts = 12348;
    assert_eq!(e.command("ATRV"), elm327::Reply::Text("12.3V".into()));
}

#[test]
fn a_mode_01_request_becomes_a_bus_write() {
    let mut e = elm327::Elm::new();
    match e.command("0100") {
        elm327::Reply::Request { header, payload } => {
            assert_eq!(header, 0x7df);
            assert_eq!(payload, vec![0x01, 0x00]);
        }
        other => panic!("expected a request, got {other:?}"),
    }
}

#[test]
fn atsh_changes_the_transmit_header() {
    let mut e = elm327::Elm::new();
    assert_eq!(e.command("ATSH7E0"), elm327::Reply::Text("OK".into()));
    match e.command("22F190") {
        elm327::Reply::Request { header, .. } => assert_eq!(header, 0x7e0),
        other => panic!("expected a request, got {other:?}"),
    }
}

#[test]
fn the_write_record_matches_what_the_official_tool_sends() {
    // Byte for byte the record inside the 0100 frame the Scanmatik app emitted.
    let record = elm327::write_record(0x7df, &[0x01, 0x00]);
    assert_eq!(record, hex("60800208000000df0700000201000000000000"));
}
