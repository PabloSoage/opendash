//! ELM327 emulation.
//!
//! Phone apps speak ELM327 over a socket. This turns their commands into
//! Scanmatik frames and their answers back into ELM327 text, so an app like
//! ScanMyOpelCAN can drive an SM3 without knowing it.
//!
//! The `AT` set here is the part real apps actually use during a session.
//! Anything unknown answers `?`, which is what an ELM327 does.

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Reply {
    /// Send verbatim, followed by the prompt.
    Text(String),
    /// Put this on the bus and answer with whatever comes back.
    Request {
        header: u32,
        payload: Vec<u8>,
    },
    Unsupported,
}

#[derive(Debug, Clone)]
pub struct Elm {
    pub echo: bool,
    pub headers: bool,
    pub spaces: bool,
    pub linefeed: bool,
    pub protocol: u8,
    /// Transmit header; 0x7DF is the OBD functional address.
    pub tx_header: u32,
    /// Millivolts reported for `ATRV`. Comes from the device's own reading:
    /// opcode 0x20 answers six bytes whose first u16 is the battery voltage.
    pub millivolts: u16,
}

impl Default for Elm {
    fn default() -> Self {
        Self {
            echo: true,
            headers: false,
            spaces: true,
            linefeed: false,
            protocol: 6, // ISO 15765-4 CAN 11-bit 500 kbps
            tx_header: 0x7df,
            millivolts: 0,
        }
    }
}

impl Elm {
    pub fn new() -> Self {
        Self::default()
    }

    /// Handle one line from the app.
    pub fn command(&mut self, line: &str) -> Reply {
        let cmd: String = line.chars().filter(|c| !c.is_whitespace()).collect();
        let upper = cmd.to_ascii_uppercase();

        if let Some(rest) = upper.strip_prefix("AT") {
            return self.at(rest);
        }
        // Anything else is hex: an OBD request.
        match decode_hex(&upper) {
            Some(payload) if !payload.is_empty() => Reply::Request {
                header: self.tx_header,
                payload,
            },
            _ => Reply::Unsupported,
        }
    }

    fn at(&mut self, rest: &str) -> Reply {
        let ok = || Reply::Text("OK".into());
        match rest {
            "Z" | "WS" | "D" => {
                *self = Elm::new();
                Reply::Text("ELM327 v1.5".into())
            }
            "I" => Reply::Text("ELM327 v1.5".into()),
            "E0" => {
                self.echo = false;
                ok()
            }
            "E1" => {
                self.echo = true;
                ok()
            }
            "H0" => {
                self.headers = false;
                ok()
            }
            "H1" => {
                self.headers = true;
                ok()
            }
            "S0" => {
                self.spaces = false;
                ok()
            }
            "S1" => {
                self.spaces = true;
                ok()
            }
            "L0" => {
                self.linefeed = false;
                ok()
            }
            "L1" => {
                self.linefeed = true;
                ok()
            }
            "RV" => Reply::Text(format!("{:.1}V", self.millivolts as f32 / 1000.0)),
            "DP" => Reply::Text(protocol_name(self.protocol).into()),
            "DPN" => Reply::Text(format!("{:X}", self.protocol)),
            _ => {
                if let Some(p) = rest.strip_prefix("SP") {
                    // ATSP0 is "try them all"; we know this bus is CAN 500k.
                    let p = p.trim_start_matches('A');
                    self.protocol = u8::from_str_radix(p, 16).unwrap_or(6);
                    if self.protocol == 0 {
                        self.protocol = 6;
                    }
                    return ok();
                }
                if let Some(h) = rest.strip_prefix("SH") {
                    match u32::from_str_radix(h, 16) {
                        Ok(v) => {
                            self.tx_header = v;
                            return ok();
                        }
                        Err(_) => return Reply::Unsupported,
                    }
                }
                Reply::Unsupported
            }
        }
    }

    /// Format a response the way the app expects it.
    pub fn format(&self, id: u32, payload: &[u8]) -> String {
        let body: Vec<String> = payload.iter().map(|b| format!("{b:02X}")).collect();
        let sep = if self.spaces { " " } else { "" };
        if self.headers {
            format!("{id:03X}{sep}{}", body.join(sep))
        } else {
            body.join(sep)
        }
    }
}

fn protocol_name(p: u8) -> &'static str {
    match p {
        1 => "SAE J1850 PWM",
        2 => "SAE J1850 VPW",
        3 => "ISO 9141-2",
        4 => "ISO 14230-4 KWP",
        5 => "ISO 14230-4 KWP FAST",
        6 => "ISO 15765-4 CAN 11/500",
        7 => "ISO 15765-4 CAN 29/500",
        8 => "ISO 15765-4 CAN 11/250",
        9 => "ISO 15765-4 CAN 29/250",
        _ => "AUTO",
    }
}

fn decode_hex(s: &str) -> Option<Vec<u8>> {
    if !s.len().is_multiple_of(2) || !s.chars().all(|c| c.is_ascii_hexdigit()) {
        return None;
    }
    (0..s.len())
        .step_by(2)
        .map(|i| u8::from_str_radix(&s[i..i + 2], 16).ok())
        .collect()
}

/// The write record the SM3 expects: subcommand, length, id, eight data bytes.
pub fn write_record(id: u32, payload: &[u8]) -> Vec<u8> {
    let mut d = vec![0x60, 0x80, 0x02];
    d.extend_from_slice(&8u32.to_le_bytes());
    d.extend_from_slice(&id.to_le_bytes());
    let mut eight = [0u8; 8];
    eight[0] = payload.len() as u8;
    let n = payload.len().min(7);
    eight[1..1 + n].copy_from_slice(&payload[..n]);
    d.extend_from_slice(&eight);
    d
}
