//! Rust 비교 프로그램(#312) — pipeline-test/extract_places.py 의 build_parts 와 같은 parts 를 만든다.
//!
//! 입력 계약과 출력 형식은 python/parts_control.py 와 같다. 키·HTTP·DB·대기열 코드는 없다.
//! MIME 판별은 Python mimetypes 의 동작(기본 표 + 시스템 mime.types 파일 + URL 처럼 보이는 이름 처리)을 옮겼다.

mod mime_defaults;

use base64::Engine;
use serde_json::{Map, Value};
use std::collections::HashMap;
use std::io;
use std::sync::OnceLock;

pub const MAX_INPUT_BYTES: u64 = 16 * 1024 * 1024;

/// extract_places.PHOTO_POST_TEXT 와 같은 문장. 다르면 호환성 검사가 실패한다.
pub const PHOTO_POST_TEXT: &str = concat!(
    "위 이미지들은 인스타그램 사진 게시물에 올라온 사진들입니다(올린 순서). ",
    "사진에 적힌 상호·코스표·위치 태그·주소를 모두 읽고 장소를 찾으세요. ",
    "코스표에 시간·순서와 함께 적힌 항목은 드라이브 코스(예: 북한강변길)라도 모두 장소로 남기세요. ",
    "다만 지도 위 경로 설명에 지나가는 지점으로만 적힌 다리·역·마을 이름(예: '○○교 → △△역 → □□리')은 빼세요. ",
    "사진에 적힌 꿀팁·주의사항(주차, 웨이팅, 예약, 추천 메뉴, 꼭 알아둘 점)은 그 장소의 videoNotes에 담고, ",
    "가격·영업시간보다 먼저 넣으세요."
);
const FRAMES_TEXT: &str = concat!(
    "위 이미지들은 영상에서 시간순으로 뽑은 프레임입니다. ",
    "화면에 적힌 자막/캡션/위치 태그 등 온스크린 텍스트에서 장소를 찾으세요."
);
const DESCRIPTION_PREFIX: &str = "게시물 설명(작성자가 직접 쓴 글, 장소 태그와 주소가 있을 수 있음):\n";
const VIDEO_TEXT: &str = "위 유튜브 영상의 음성, 화면 자막, 위치 태그를 모두 보고 장소를 찾으세요.";
const AUDIO_TEXT: &str = "위 오디오를 듣고 화자가 언급한 장소를 찾으세요.";

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RequestError {
    pub kind: &'static str,
    pub detail: String,
}

impl RequestError {
    fn new(kind: &'static str, detail: impl Into<String>) -> Self {
        RequestError { kind, detail: detail.into() }
    }
}

#[derive(Debug, Default, Clone, PartialEq)]
pub struct Manifest {
    pub transcript: Option<String>,
    pub audio_path: Option<String>,
    pub frame_paths: Option<Vec<String>>,
    pub prompt: String,
    pub candidates: Option<Vec<String>>,
    pub video_uri: Option<String>,
    pub post_description: Option<String>,
    pub photo_post: bool,
}

const ALL_FIELDS: &[&str] = &[
    "transcript",
    "audio_path",
    "frame_paths",
    "prompt",
    "candidates",
    "video_uri",
    "post_description",
    "photo_post",
];

fn opt_string(obj: &Map<String, Value>, key: &str) -> Result<Option<String>, RequestError> {
    match obj.get(key) {
        None | Some(Value::Null) => Ok(None),
        Some(Value::String(s)) => Ok(Some(s.clone())),
        Some(_) => Err(RequestError::new("invalid_manifest", format!("{key} must be a string or null"))),
    }
}

fn opt_string_list(obj: &Map<String, Value>, key: &str) -> Result<Option<Vec<String>>, RequestError> {
    match obj.get(key) {
        None | Some(Value::Null) => Ok(None),
        Some(Value::Array(items)) => items
            .iter()
            .map(|item| match item {
                Value::String(s) => Ok(s.clone()),
                _ => Err(RequestError::new("invalid_manifest", format!("{key} must be an array of strings or null"))),
            })
            .collect::<Result<Vec<String>, RequestError>>()
            .map(Some),
        Some(_) => Err(RequestError::new("invalid_manifest", format!("{key} must be an array of strings or null"))),
    }
}

pub fn parse_manifest(raw: &[u8]) -> Result<Manifest, RequestError> {
    let value: Value = serde_json::from_slice(raw).map_err(|e| RequestError::new("invalid_json", e.to_string()))?;
    let obj = match value {
        Value::Object(obj) => obj,
        _ => return Err(RequestError::new("invalid_manifest", "top level must be an object")),
    };
    let mut unknown: Vec<&String> = obj.keys().filter(|k| !ALL_FIELDS.contains(&k.as_str())).collect();
    if !unknown.is_empty() {
        unknown.sort();
        return Err(RequestError::new("invalid_manifest", format!("unknown keys {unknown:?}")));
    }
    let prompt = match obj.get("prompt") {
        Some(Value::String(s)) => s.clone(),
        _ => return Err(RequestError::new("invalid_manifest", "prompt must be a string")),
    };
    let photo_post = match obj.get("photo_post") {
        None => false,
        Some(Value::Bool(b)) => *b,
        Some(_) => return Err(RequestError::new("invalid_manifest", "photo_post must be a boolean")),
    };
    Ok(Manifest {
        transcript: opt_string(&obj, "transcript")?,
        audio_path: opt_string(&obj, "audio_path")?,
        frame_paths: opt_string_list(&obj, "frame_paths")?,
        prompt,
        candidates: opt_string_list(&obj, "candidates")?,
        video_uri: opt_string(&obj, "video_uri")?,
        post_description: opt_string(&obj, "post_description")?,
        photo_post,
    })
}

/// pathlib.PurePosixPath 와 같은 정규화: 빈 조각과 "." 를 없앤다. (열 경로, name) 을 돌려준다.
pub fn pathlib_normalize(raw: &str) -> (String, String) {
    let root = if raw.starts_with("//") && !raw.starts_with("///") {
        "//"
    } else if raw.starts_with('/') {
        "/"
    } else {
        ""
    };
    let parts: Vec<&str> = raw.split('/').filter(|p| !p.is_empty() && *p != ".").collect();
    let name = parts.last().map(|s| s.to_string()).unwrap_or_default();
    let joined = parts.join("/");
    let path = if root.is_empty() && joined.is_empty() { ".".to_string() } else { format!("{root}{joined}") };
    (path, name)
}

fn map_io_error(err: &io::Error) -> &'static str {
    match err.kind() {
        io::ErrorKind::NotFound => "not_found",
        io::ErrorKind::IsADirectory => "is_directory",
        io::ErrorKind::NotADirectory => "not_a_directory",
        io::ErrorKind::PermissionDenied => "permission_denied",
        io::ErrorKind::InvalidInput => "invalid_path",
        _ => "io_error",
    }
}

fn read_media(path: &str) -> Result<Vec<u8>, RequestError> {
    std::fs::read(path).map_err(|e| RequestError::new(map_io_error(&e), format!("{path}: {e}")))
}

fn regular_size(path: &str) -> u64 {
    match std::fs::metadata(path) {
        Ok(meta) if meta.is_file() => meta.len(),
        _ => 0,
    }
}

fn text_part(text: String) -> Value {
    let mut m = Map::new();
    m.insert("text".to_string(), Value::String(text));
    Value::Object(m)
}

fn inline_part(mime: String, data: String) -> Value {
    let mut inner = Map::new();
    inner.insert("mime_type".to_string(), Value::String(mime));
    inner.insert("data".to_string(), Value::String(data));
    let mut m = Map::new();
    m.insert("inline_data".to_string(), Value::Object(inner));
    Value::Object(m)
}

/// Python json.dumps(list_of_str, ensure_ascii=False) 와 같은 표기: 구분자 ", ".
fn python_dumps_str_list(items: &[String]) -> String {
    let encoded: Vec<String> = items.iter().map(|s| serde_json::to_string(s).expect("string encodes")).collect();
    format!("[{}]", encoded.join(", "))
}

pub fn build_parts(m: &Manifest, db: &MimeDb) -> Result<Vec<Value>, RequestError> {
    let audio = m.audio_path.as_deref().map(pathlib_normalize);
    let frames: Option<Vec<(String, String)>> =
        m.frame_paths.as_ref().map(|v| v.iter().map(|p| pathlib_normalize(p)).collect());
    let mut total: u64 = 0;
    if let Some((path, _)) = &audio {
        total += regular_size(path);
    }
    if let Some(list) = &frames {
        for (path, _) in list {
            total += regular_size(path);
        }
    }
    if total > MAX_INPUT_BYTES {
        return Err(RequestError::new("input_too_large", format!("{total} bytes > {MAX_INPUT_BYTES}")));
    }

    let engine = base64::engine::general_purpose::STANDARD;
    let mut parts: Vec<Value> = Vec::new();
    if let Some(desc) = m.post_description.as_deref().filter(|s| !s.is_empty()) {
        parts.push(text_part(format!("{DESCRIPTION_PREFIX}{desc}")));
    }
    if let Some(uri) = m.video_uri.as_deref().filter(|s| !s.is_empty()) {
        let mut inner = Map::new();
        inner.insert("file_uri".to_string(), Value::String(uri.to_string()));
        let mut part = Map::new();
        part.insert("file_data".to_string(), Value::Object(inner));
        parts.push(Value::Object(part));
        parts.push(text_part(VIDEO_TEXT.to_string()));
    }
    if let Some(t) = m.transcript.as_deref().filter(|s| !s.is_empty()) {
        parts.push(text_part(format!("자막/전사 텍스트:\n{t}")));
    }
    // Python 의 Path 는 빈 문자열이어도 참이라(Path("") == ".") audio_path 가 있으면 항상 읽는다.
    if let Some((path, name)) = &audio {
        let mime = db.guess_type(name)?.unwrap_or_else(|| "audio/mp3".to_string());
        let data = engine.encode(read_media(path)?);
        parts.push(inline_part(mime, data));
        parts.push(text_part(AUDIO_TEXT.to_string()));
    }
    if let Some(list) = frames.as_ref().filter(|l| !l.is_empty()) {
        for (path, name) in list {
            let mime = db.guess_type(name)?.unwrap_or_else(|| "image/jpeg".to_string());
            let data = engine.encode(read_media(path)?);
            parts.push(inline_part(mime, data));
        }
        parts.push(text_part(if m.photo_post { PHOTO_POST_TEXT } else { FRAMES_TEXT }.to_string()));
    }
    if let Some(c) = &m.candidates {
        parts.push(text_part(format!("1차 후보 목록: {}", python_dumps_str_list(c))));
    }
    parts.push(text_part(m.prompt.clone()));
    Ok(parts)
}

/// 출력 한 줄: 키 정렬(serde_json Map 은 BTreeMap)·공백 없음·UTF-8.
pub fn encode_parts(parts: &[Value]) -> Vec<u8> {
    let mut out = serde_json::to_vec(parts).expect("parts encode");
    out.push(b'\n');
    out
}

pub fn encode_error(kind: &str) -> Vec<u8> {
    format!("{{\"error\":{{\"kind\":\"{kind}\"}}}}\n").into_bytes()
}

/// 요청 하나. 성공이면 (parts 줄, None), 실패면 (오류 줄, Some(오류)).
pub fn handle(raw: &[u8], db: &MimeDb) -> (Vec<u8>, Option<RequestError>) {
    match parse_manifest(raw).and_then(|m| build_parts(&m, db)) {
        Ok(parts) => (encode_parts(&parts), None),
        Err(e) => (encode_error(e.kind), Some(e)),
    }
}

// ---------------------------------------------------------------- MIME (Python mimetypes 옮김)

#[derive(Debug, Clone)]
pub struct MimeDb {
    types: HashMap<String, String>,
    encodings: HashMap<String, String>,
    suffix: HashMap<String, String>,
    /// 시스템 mime.types 파일을 읽다 실패하면 Python 처럼 guess_type 이 매번 실패한다.
    init_error: Option<String>,
}

fn py_isspace(c: char) -> bool {
    c.is_whitespace() || ('\u{1c}'..='\u{1f}').contains(&c)
}

impl MimeDb {
    /// 시스템 파일 없이 Python 기본 표만.
    pub fn defaults() -> Self {
        let conv = |t: &[(&str, &str)]| t.iter().map(|(a, b)| (a.to_string(), b.to_string())).collect();
        MimeDb {
            types: conv(mime_defaults::TYPES_MAP),
            encodings: conv(mime_defaults::ENCODINGS_MAP),
            suffix: conv(mime_defaults::SUFFIX_MAP),
            init_error: None,
        }
    }

    /// Python mimetypes.init() 과 같은 순서로 knownfiles 중 있는 파일을 읽는다.
    pub fn system() -> Self {
        let mut db = Self::defaults();
        for file in mime_defaults::KNOWN_FILES {
            let path = std::path::Path::new(file);
            if !path.is_file() {
                continue;
            }
            match std::fs::read(path) {
                Ok(bytes) => match String::from_utf8(bytes) {
                    Ok(text) => db.read_mime_types(&text),
                    Err(e) => {
                        db.init_error = Some(format!("{file}: {e}"));
                        return db;
                    }
                },
                Err(e) => {
                    db.init_error = Some(format!("{file}: {e}"));
                    return db;
                }
            }
        }
        db
    }

    /// MimeTypes.readfp: 줄마다 공백으로 나누고 '#' 로 시작하는 낱말부터 버린다. 나중 값이 덮어쓴다.
    pub fn read_mime_types(&mut self, text: &str) {
        let normalized = text.replace("\r\n", "\n").replace('\r', "\n");
        for line in normalized.split('\n') {
            let mut words: Vec<&str> = line.split(py_isspace).filter(|w| !w.is_empty()).collect();
            if let Some(i) = words.iter().position(|w| w.starts_with('#')) {
                words.truncate(i);
            }
            if words.is_empty() {
                continue;
            }
            let mime = words[0];
            for suff in &words[1..] {
                self.types.insert(format!(".{suff}"), mime.to_string());
            }
        }
    }

    /// mimetypes.guess_type(name)[0].
    pub fn guess_type(&self, url: &str) -> Result<Option<String>, RequestError> {
        if let Some(e) = &self.init_error {
            return Err(RequestError::new("invalid_path", e.clone()));
        }
        // urllib.parse.urlparse 중 파일 이름(‘/’ 없음)에 해당하는 부분.
        let mut s: String = url.trim_start_matches(|c: char| c <= '\u{20}').to_string();
        s.retain(|c| c != '\t' && c != '\r' && c != '\n');
        let mut scheme = String::new();
        let mut rest = s.as_str();
        if let Some(i) = s.find(':') {
            let head = &s[..i];
            let first = head.chars().next();
            if i > 0
                && first.is_some_and(|c| c.is_ascii_alphabetic())
                && head.chars().all(|c| c.is_ascii_alphanumeric() || c == '+' || c == '-' || c == '.')
            {
                scheme = head.to_ascii_lowercase();
                rest = &s[i + 1..];
            }
        }
        if scheme.len() <= 1 {
            return Ok(self.guess_file_type(url));
        }
        let mut path = rest;
        if let Some(i) = path.find('#') {
            path = &path[..i];
        }
        if let Some(i) = path.find('?') {
            path = &path[..i];
        }
        const USES_PARAMS: &[&str] = &[
            "", "ftp", "hdl", "prospero", "http", "imap", "https", "shttp", "rtsp", "rtsps", "rtspu", "sip", "sips",
            "mms", "sftp", "tel",
        ];
        if USES_PARAMS.contains(&scheme.as_str()) {
            if let Some(i) = path.find(';') {
                path = &path[..i];
            }
        }
        if scheme == "data" {
            let Some(comma) = path.find(',') else { return Ok(None) };
            let ty = match path[..comma].find(';') {
                Some(semi) => &path[..semi],
                None => &path[..comma],
            };
            if ty.contains('=') || !ty.contains('/') {
                return Ok(Some("text/plain".to_string()));
            }
            return Ok(Some(ty.to_string()));
        }
        Ok(self.guess_file_type(path))
    }

    fn guess_file_type(&self, path: &str) -> Option<String> {
        let (mut base, mut ext) = splitext(path);
        while let Some(rep) = self.suffix.get(&ext.to_lowercase()) {
            let joined = format!("{base}{rep}");
            let (b, e) = splitext(&joined);
            base = b;
            ext = e;
        }
        if self.encodings.contains_key(&ext) {
            let (b, e) = splitext(&base);
            base = b;
            ext = e;
        }
        let _ = base;
        self.types.get(&ext.to_lowercase()).cloned()
    }
}

/// posixpath.splitext.
pub fn splitext(p: &str) -> (String, String) {
    let sep = p.rfind('/').map(|i| i as isize).unwrap_or(-1);
    if let Some(dot) = p.rfind('.') {
        if dot as isize > sep {
            let start = (sep + 1) as usize;
            if p[start..dot].bytes().any(|b| b != b'.') {
                return (p[..dot].to_string(), p[dot..].to_string());
            }
        }
    }
    (p.to_string(), String::new())
}

static SYSTEM_DB: OnceLock<MimeDb> = OnceLock::new();

pub fn system_db() -> &'static MimeDb {
    SYSTEM_DB.get_or_init(MimeDb::system)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn 기본_표로_확장자를_판별한다() {
        let db = MimeDb::defaults();
        assert_eq!(db.guess_type("a.mp3").unwrap().as_deref(), Some("audio/mpeg"));
        assert_eq!(db.guess_type("a.JPG").unwrap().as_deref(), Some("image/jpeg"));
        assert_eq!(db.guess_type("a.mp3.gz").unwrap().as_deref(), Some("audio/mpeg"));
        assert_eq!(db.guess_type("a.tgz").unwrap().as_deref(), Some("application/x-tar"));
        assert_eq!(db.guess_type(".mp3").unwrap(), None);
        assert_eq!(db.guess_type("audio").unwrap(), None);
        assert_eq!(db.guess_type("프레임.jpeg").unwrap().as_deref(), Some("image/jpeg"));
    }

    #[test]
    fn url_처럼_보이는_이름은_python_처럼_처리한다() {
        let db = MimeDb::defaults();
        assert_eq!(db.guess_type("ab:c.mp3").unwrap().as_deref(), Some("audio/mpeg"));
        assert_eq!(db.guess_type("ab:c.png#x.mp3").unwrap().as_deref(), Some("image/png"));
        assert_eq!(db.guess_type("data:x,y.mp3").unwrap().as_deref(), Some("text/plain"));
        assert_eq!(db.guess_type("data:image/png,y.mp3").unwrap().as_deref(), Some("image/png"));
        assert_eq!(db.guess_type("a:b.png#.mp3").unwrap().as_deref(), Some("audio/mpeg"));
        assert_eq!(db.guess_type("http:a.png;x.mp3").unwrap().as_deref(), Some("image/png"));
        assert_eq!(db.guess_type("zz:a.png;x.mp3").unwrap().as_deref(), Some("audio/mpeg"));
    }

    #[test]
    fn mime_types_파일은_나중_값이_덮어쓰고_주석을_버린다() {
        let mut db = MimeDb::defaults();
        db.read_mime_types("audio/x-test mp3 foo # bar\n# whole comment\naudio/other\tfoo\r\n");
        assert_eq!(db.guess_type("a.mp3").unwrap().as_deref(), Some("audio/x-test"));
        assert_eq!(db.guess_type("a.foo").unwrap().as_deref(), Some("audio/other"));
        assert_eq!(db.guess_type("a.bar").unwrap(), None);
    }

    #[test]
    fn 경로는_pathlib_처럼_정규화한다() {
        assert_eq!(pathlib_normalize(""), (".".to_string(), "".to_string()));
        assert_eq!(pathlib_normalize("a.mp3/"), ("a.mp3".to_string(), "a.mp3".to_string()));
        assert_eq!(pathlib_normalize("./x//y/./z.jpg"), ("x/y/z.jpg".to_string(), "z.jpg".to_string()));
        assert_eq!(pathlib_normalize("/"), ("/".to_string(), "".to_string()));
        assert_eq!(pathlib_normalize("//a"), ("//a".to_string(), "a".to_string()));
        assert_eq!(pathlib_normalize("///a"), ("/a".to_string(), "a".to_string()));
    }

    #[test]
    fn 후보_목록은_python_json_dumps_표기와_같다() {
        let items = vec!["해운대".to_string(), "a\"b\\c".to_string(), "\u{1}".to_string()];
        assert_eq!(python_dumps_str_list(&items), "[\"해운대\", \"a\\\"b\\\\c\", \"\\u0001\"]");
        assert_eq!(python_dumps_str_list(&[]), "[]");
    }

    #[test]
    fn 계약_밖의_입력은_오류_종류로_거절한다() {
        assert_eq!(parse_manifest(b"[]").unwrap_err().kind, "invalid_manifest");
        assert_eq!(parse_manifest(b"{\"prompt\":\"p\",\"x\":1}").unwrap_err().kind, "invalid_manifest");
        assert_eq!(parse_manifest(b"{\"prompt\":\"p\",\"photo_post\":\"yes\"}").unwrap_err().kind, "invalid_manifest");
        assert_eq!(parse_manifest(b"{\"prompt\":").unwrap_err().kind, "invalid_json");
        assert_eq!(parse_manifest(b"{}").unwrap_err().kind, "invalid_manifest");
    }

    #[test]
    fn 텍스트만_있으면_빈_필드는_건너뛴다() {
        let m = Manifest {
            transcript: Some(String::new()),
            post_description: Some(String::new()),
            frame_paths: Some(vec![]),
            candidates: Some(vec![]),
            photo_post: true,
            prompt: "P".to_string(),
            ..Default::default()
        };
        let out = encode_parts(&build_parts(&m, &MimeDb::defaults()).unwrap());
        assert_eq!(String::from_utf8(out).unwrap(), "[{\"text\":\"1차 후보 목록: []\"},{\"text\":\"P\"}]\n");
    }

    #[test]
    fn 없는_파일은_not_found_로_실패한다() {
        let m = Manifest {
            audio_path: Some("/nonexistent/트로바/a.mp3".to_string()),
            prompt: "P".to_string(),
            ..Default::default()
        };
        assert_eq!(build_parts(&m, &MimeDb::defaults()).unwrap_err().kind, "not_found");
    }
}
