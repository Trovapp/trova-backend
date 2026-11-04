//! 사용법: rust_parts_pilot <manifest.json> | --serve [--timing]
//! python/parts_control.py 와 같은 입력·출력·종료 코드 규칙을 쓴다.

use rust_parts_pilot::{handle, system_db};
use std::io::{self, BufRead, Write};
use std::time::Instant;

fn timing_line(elapsed_ns: u128, out_len: usize, kind: Option<&str>) {
    let kind_json = match kind {
        Some(k) => format!("\"{k}\""),
        None => "null".to_string(),
    };
    eprintln!("PARTS_TIMING {{\"impl\": \"rust\", \"prep_ns\": {elapsed_ns}, \"out_bytes\": {out_len}, \"error\": {kind_json}}}");
}

fn serve(timing: bool) -> i32 {
    let stdin = io::stdin();
    let mut reader = stdin.lock();
    let stdout = io::stdout();
    let mut out = stdout.lock();
    let mut line: Vec<u8> = Vec::new();
    loop {
        line.clear();
        match reader.read_until(b'\n', &mut line) {
            Ok(0) => return 0,
            Ok(_) => {}
            Err(e) => {
                eprintln!("rust_parts_pilot: stdin read failed: {e}");
                return 1;
            }
        }
        if line.last() == Some(&b'\n') {
            line.pop();
        }
        let start = Instant::now();
        let (bytes, err) = handle(&line, system_db());
        let elapsed = start.elapsed().as_nanos();
        if out.write_all(&bytes).and_then(|_| out.flush()).is_err() {
            return 1;
        }
        if let Some(e) = &err {
            eprintln!("rust_parts_pilot: {}: {}", e.kind, e.detail);
        }
        if timing {
            timing_line(elapsed, bytes.len(), err.as_ref().map(|e| e.kind));
        }
    }
}

fn main() {
    let argv: Vec<String> = std::env::args().skip(1).collect();
    let timing = argv.iter().any(|a| a == "--timing");
    let args: Vec<&String> = argv.iter().filter(|a| *a != "--timing").collect();
    if args.len() == 1 && args[0] == "--serve" {
        std::process::exit(serve(timing));
    }
    if args.len() != 1 || args[0].starts_with("--") {
        eprintln!("usage: rust_parts_pilot <manifest.json> | --serve [--timing]");
        std::process::exit(2);
    }
    let raw = match std::fs::read(args[0]) {
        Ok(raw) => raw,
        Err(e) => {
            let detail = serde_json::to_string(&e.to_string()).unwrap_or_default();
            eprintln!("{{\"error\": {{\"kind\": \"manifest_unreadable\", \"detail\": {detail}}}}}");
            std::process::exit(1);
        }
    };
    let start = Instant::now();
    let (bytes, err) = handle(&raw, system_db());
    let elapsed = start.elapsed().as_nanos();
    if timing {
        timing_line(elapsed, bytes.len(), err.as_ref().map(|e| e.kind));
    }
    if let Some(e) = err {
        // 실패하면 stdout 에 아무것도 쓰지 않는다(부분 출력·성공한 척 금지).
        let detail = serde_json::to_string(&e.detail).unwrap_or_default();
        eprintln!("{{\"error\": {{\"kind\": \"{}\", \"detail\": {detail}}}}}", e.kind);
        std::process::exit(1);
    }
    let stdout = io::stdout();
    let mut out = stdout.lock();
    if out.write_all(&bytes).and_then(|_| out.flush()).is_err() {
        std::process::exit(1);
    }
}
