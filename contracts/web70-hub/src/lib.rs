//! Shared message board: comments, likes, reports, channel directory, chess moves.
//! The parameters are a topic name, so every topic is its own contract instance.
//! The network itself refuses forged messages: every message must carry a valid ECDSA P-256
//! signature of its author, proof of work, and fit in the author's quota.
use base64ct::{Base64UrlUnpadded, Encoding};
use freenet_stdlib::prelude::*;
use p256::ecdsa::{signature::Verifier, Signature, VerifyingKey};
use serde_json::Value;
use sha2::{Digest, Sha256};
use std::collections::{BTreeMap, BTreeSet};

const MAX_MESSAGES: usize = 250;
const MAX_MESSAGE_BYTES: usize = 16 * 1024;
const MAX_PER_AUTHOR: usize = 50;
#[cfg(not(test))]
const WORK_BITS: u32 = 14;
#[cfg(test)]
const WORK_BITS: u32 = 4;

struct Message {
    time: u64,
    author: String,
    raw: String,
}

fn base64url_prefix(bytes: &[u8]) -> String {
    const TABLE: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    let mut out = String::with_capacity(32);
    for chunk in bytes[..24].chunks(3) {
        let n = (u32::from(chunk[0]) << 16) | (u32::from(chunk[1]) << 8) | u32::from(chunk[2]);
        for shift in [18, 12, 6, 0] {
            out.push(TABLE[((n >> shift) & 63) as usize] as char);
        }
    }
    out
}

fn leading_zero_bits(hash: &[u8]) -> u32 {
    let mut bits = 0;
    for byte in hash {
        if *byte == 0 {
            bits += 8;
        } else {
            return bits + byte.leading_zeros();
        }
    }
    bits
}

/// The author is the public key as JWK "x.y" (base64url); the signature is r||s from WebCrypto.
fn signature_ok(author: &str, text: &str, signature: &str) -> bool {
    let Some((x, y)) = author.split_once('.') else {
        return false;
    };
    let mut point = [0u8; 65];
    point[0] = 4;
    let coordinates_ok = matches!(Base64UrlUnpadded::decode(x, &mut point[1..33]), Ok(d) if d.len() == 32)
        && matches!(Base64UrlUnpadded::decode(y, &mut point[33..65]), Ok(d) if d.len() == 32);
    let mut raw = [0u8; 64];
    let signature_len = Base64UrlUnpadded::decode(signature, &mut raw).map(|d| d.len());
    if !coordinates_ok || signature_len != Ok(64) {
        return false;
    }
    match (VerifyingKey::from_sec1_bytes(&point), Signature::from_slice(&raw)) {
        (Ok(key), Ok(sig)) => key.verify(text.as_bytes(), &sig).is_ok(),
        _ => false,
    }
}

type Board = BTreeMap<String, Message>;

struct Parsed {
    id: String,
    message: Message,
    text: String,
    signature: String,
}

fn parse_message(value: &Value) -> Option<Parsed> {
    let id = value.get("i")?.as_str()?;
    let time = value.get("t")?.as_u64()?;
    let author = value.get("a")?.as_str()?;
    let name = value.get("n")?.as_str()?;
    let payload = value.get("p")?.as_str()?;
    let work = value.get("w")?.as_str()?;
    let signature = value.get("s")?.as_str()?;
    let raw = value.to_string();
    if raw.len() > MAX_MESSAGE_BYTES {
        return None;
    }
    let text = format!("{time}|{author}|{name}|{payload}");
    if base64url_prefix(&Sha256::digest(text.as_bytes())) != id {
        return None;
    }
    if leading_zero_bits(&Sha256::digest(format!("{id}|{work}").as_bytes())) < WORK_BITS {
        return None;
    }
    Some(Parsed {
        id: id.to_owned(),
        message: Message { time, author: author.to_owned(), raw },
        text,
        signature: signature.to_owned(),
    })
}

fn add(board: &mut Board, id: String, message: Message) {
    // Same id with different content: the larger text wins, so every node picks the same one.
    match board.get(&id) {
        Some(old) if old.raw >= message.raw => {}
        _ => {
            board.insert(id, message);
        }
    }
}

fn parse_all(bytes: &[u8]) -> Result<Vec<Parsed>, ContractError> {
    if bytes.is_empty() {
        return Ok(Vec::new());
    }
    let root: Value = serde_json::from_slice(bytes).map_err(|_| ContractError::InvalidState)?;
    let list = root
        .get("m")
        .and_then(Value::as_array)
        .ok_or(ContractError::InvalidState)?;
    list.iter()
        .map(|value| parse_message(value).ok_or(ContractError::InvalidState))
        .collect()
}

/// Every message checked, signature included: for anything that comes from outside.
fn read_messages(bytes: &[u8]) -> Result<Vec<(String, Message)>, ContractError> {
    parse_all(bytes)?
        .into_iter()
        .map(|p| {
            if signature_ok(&p.message.author, &p.text, &p.signature) {
                Ok((p.id, p.message))
            } else {
                Err(ContractError::InvalidState)
            }
        })
        .collect()
}

/// The stored state was validated when it arrived: only messages not already in it need a signature check.
fn merge_into(board: &mut Board, bytes: &[u8], trusted: bool) -> Result<(), ContractError> {
    for p in parse_all(bytes)? {
        let known = board.get(&p.id).is_some_and(|old| old.raw == p.message.raw);
        if !trusted && !known && !signature_ok(&p.message.author, &p.text, &p.signature) {
            return Err(ContractError::InvalidUpdate);
        }
        add(board, p.id, p.message);
    }
    Ok(())
}

/// Newest messages only, per author and overall, in one fixed order:
/// the result cannot depend on arrival order.
fn write_board(board: &Board) -> Vec<u8> {
    let mut sorted: Vec<(&String, &Message)> = board.iter().collect();
    sorted.sort_by(|a, b| (b.1.time, b.0).cmp(&(a.1.time, a.0)));
    let mut per_author: BTreeMap<&str, usize> = BTreeMap::new();
    sorted.retain(|(_, m)| {
        let count = per_author.entry(m.author.as_str()).or_insert(0);
        *count += 1;
        *count <= MAX_PER_AUTHOR
    });
    sorted.truncate(MAX_MESSAGES);
    sorted.reverse();
    let items: Vec<&str> = sorted.iter().map(|(_, m)| m.raw.as_str()).collect();
    format!("{{\"m\":[{}]}}", items.join(",")).into_bytes()
}

fn merge_all(state: &[u8], updates: &[&[u8]]) -> Result<Vec<u8>, ContractError> {
    let mut board = Board::new();
    merge_into(&mut board, state, true)?;
    for update in updates {
        merge_into(&mut board, update, false)?;
    }
    Ok(write_board(&board))
}

fn ids_of(state: &[u8]) -> Result<Vec<u8>, ContractError> {
    let ids: Vec<Value> = parse_all(state)?
        .into_iter()
        .map(|p| Value::String(p.id))
        .collect();
    Ok(Value::Array(ids).to_string().into_bytes())
}

fn missing_from(state: &[u8], summary: &[u8]) -> Result<Vec<u8>, ContractError> {
    let known: BTreeSet<String> = if summary.is_empty() {
        BTreeSet::new()
    } else {
        serde_json::from_slice(summary).map_err(|_| ContractError::InvalidUpdate)?
    };
    let mut board = Board::new();
    for p in parse_all(state)? {
        if !known.contains(&p.id) {
            add(&mut board, p.id, p.message);
        }
    }
    Ok(write_board(&board))
}

struct Contract;

#[contract]
impl ContractInterface for Contract {
    fn validate_state(
        _parameters: Parameters<'static>,
        state: State<'static>,
        _related: RelatedContracts<'static>,
    ) -> Result<ValidateResult, ContractError> {
        match read_messages(state.as_ref()) {
            Ok(list) if list.len() <= MAX_MESSAGES => Ok(ValidateResult::Valid),
            _ => Ok(ValidateResult::Invalid),
        }
    }

    fn update_state(
        _parameters: Parameters<'static>,
        state: State<'static>,
        data: Vec<UpdateData<'static>>,
    ) -> Result<UpdateModification<'static>, ContractError> {
        let mut updates: Vec<&[u8]> = Vec::new();
        for item in &data {
            match item {
                UpdateData::State(s) => updates.push(s.as_ref()),
                UpdateData::Delta(d) => updates.push(d.as_ref()),
                UpdateData::StateAndDelta { state, delta } => {
                    updates.push(state.as_ref());
                    updates.push(delta.as_ref());
                }
                _ => {}
            }
        }
        let merged = merge_all(state.as_ref(), &updates)?;
        Ok(UpdateModification::valid(State::from(merged)))
    }

    fn summarize_state(
        _parameters: Parameters<'static>,
        state: State<'static>,
    ) -> Result<StateSummary<'static>, ContractError> {
        Ok(StateSummary::from(ids_of(state.as_ref())?))
    }

    fn get_state_delta(
        _parameters: Parameters<'static>,
        state: State<'static>,
        summary: StateSummary<'static>,
    ) -> Result<StateDelta<'static>, ContractError> {
        Ok(StateDelta::from(missing_from(state.as_ref(), summary.as_ref())?))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    use p256::ecdsa::{signature::Signer, SigningKey};

    fn key(label: &str) -> SigningKey {
        SigningKey::from_slice(&Sha256::digest(label.as_bytes())).unwrap()
    }
    fn author(label: &str) -> String {
        let point = key(label).verifying_key().to_encoded_point(false);
        format!("{}.{}", Base64UrlUnpadded::encode_string(&point.as_bytes()[1..33]),
            Base64UrlUnpadded::encode_string(&point.as_bytes()[33..65]))
    }
    fn signed(label: &str, time: u64, text: &str) -> (String, String) {
        let who = author(label);
        let body = format!("{time}|{who}|n|{text}");
        let id = base64url_prefix(&Sha256::digest(body.as_bytes()));
        let mut nonce = 0u64;
        while leading_zero_bits(&Sha256::digest(format!("{id}|{nonce}").as_bytes())) < WORK_BITS {
            nonce += 1;
        }
        let sig: Signature = key(label).sign(body.as_bytes());
        let sig = Base64UrlUnpadded::encode_string(&sig.to_bytes());
        let raw = format!("{{\"a\":\"{who}\",\"i\":\"{id}\",\"n\":\"n\",\"p\":\"{text}\",\"s\":\"{sig}\",\"t\":{time},\"w\":\"{nonce}\"}}");
        (id, raw)
    }
    fn msg(label: &str, time: u64, text: &str) -> String {
        signed(label, time, text).1
    }
    fn state(items: &[String]) -> Vec<u8> {
        format!("{{\"m\":[{}]}}", items.join(",")).into_bytes()
    }
    fn merged(a: &[u8], b: &[u8]) -> Vec<u8> {
        merge_all(a, &[b]).unwrap()
    }

    #[test]
    fn merge_ignores_order_grouping_and_repeats() {
        let a = state(&[msg("a", 1, "x"), msg("d", 5, "low")]);
        let b = state(&[msg("b", 2, "y"), msg("d", 5, "zed")]);
        let c = state(&[msg("c", 3, "z")]);
        assert_eq!(merged(&a, &b), merged(&b, &a));
        assert_eq!(merged(&merged(&a, &b), &c), merged(&a, &merged(&b, &c)));
        let ab = merged(&a, &b);
        assert_eq!(merged(&ab, &b), ab);
        assert_eq!(merged(&ab, &ab), ab);
    }

    #[test]
    fn keeps_only_the_newest_messages_overall() {
        let items: Vec<String> = (0..MAX_MESSAGES as u64 + 5).map(|n| msg(&format!("u{}", n % 50), n, "x")).collect();
        let out = merge_all(&state(&items), &[]).unwrap();
        let list = read_messages(&out).unwrap();
        assert_eq!(list.len(), MAX_MESSAGES);
        assert!(list.iter().all(|(_, m)| m.time >= 5));
    }

    #[test]
    fn one_author_cannot_push_out_the_others() {
        let mut items: Vec<String> = (0..MAX_PER_AUTHOR as u64 + 50).map(|n| msg("flood", 100 + n, "x")).collect();
        items.push(msg("quiet", 1, "hello"));
        let out = merge_all(&state(&items), &[]).unwrap();
        let list = read_messages(&out).unwrap();
        assert_eq!(list.iter().filter(|(_, m)| m.author == author("flood")).count(), MAX_PER_AUTHOR);
        assert_eq!(list.iter().filter(|(_, m)| m.author == author("quiet")).count(), 1);
        let again = merge_all(&out, &[&state(&items)]).unwrap();
        assert_eq!(again, out);
    }

    #[test]
    fn refuses_bad_id_missing_work_and_oversized_messages() {
        assert!(read_messages(b"not json").is_err());
        assert!(read_messages(b"{\"m\":[{\"t\":1}]}").is_err());
        let big = "y".repeat(MAX_MESSAGE_BYTES);
        assert!(read_messages(&state(&[msg("a", 1, &big)])).is_err());
        let (id, good) = signed("a", 1, "x");
        assert!(read_messages(&state(&[good.clone()])).is_ok());
        let wrong_text = good.replace("\"p\":\"x\"", "\"p\":\"changed\"");
        assert!(read_messages(&state(&[wrong_text])).is_err());
        let mut bad_nonce = 0u64;
        while leading_zero_bits(&Sha256::digest(format!("{id}|{bad_nonce}").as_bytes())) >= WORK_BITS {
            bad_nonce += 1;
        }
        let no_work = good.replace(&format!("\"w\":\"{}\"", work_of(&good)), &format!("\"w\":\"{bad_nonce}\""));
        assert!(read_messages(&state(&[no_work])).is_err());
    }

    fn work_of(raw: &str) -> String {
        serde_json::from_str::<Value>(raw).unwrap()["w"].as_str().unwrap().to_owned()
    }

    #[test]
    fn refuses_forged_signatures_everywhere() {
        let good = msg("a", 1, "x");
        let other = msg("b", 1, "x");
        let sig_of = |raw: &str| serde_json::from_str::<Value>(raw).unwrap()["s"].as_str().unwrap().to_owned();
        // Firma di un altro autore messa sul messaggio di "a".
        let stolen = good.replace(&sig_of(&good), &sig_of(&other));
        assert!(read_messages(&state(&[stolen.clone()])).is_err());
        assert!(merge_all(&state(&[msg("c", 1, "y")]), &[&state(&[stolen])]).is_err());
        let garbage = good.replace(&sig_of(&good), "AAAA");
        assert!(read_messages(&state(&[garbage])).is_err());
        let base = state(&[good.clone()]);
        assert!(merge_all(&base, &[&state(&[good])]).is_ok());
    }

    // Stato pieno (1000 messaggi veri, lavoro reale da 14 bit) per misurare il contratto compilato su un nodo:
    // WEB70_BIG_STATE=percorso cargo test -- --ignored emit_full_board
    #[test]
    #[ignore]
    fn emit_full_board() {
        let path = std::env::var("WEB70_BIG_STATE").expect("WEB70_BIG_STATE");
        let mut items = Vec::new();
        for n in 0..MAX_MESSAGES as u64 {
            let label = format!("autore{}", n % 5);
            let who = author(&label);
            let body = format!("{}|{who}|n|messaggio {n}", 1_000 + n);
            let id = base64url_prefix(&Sha256::digest(body.as_bytes()));
            let mut nonce = 0u64;
            while leading_zero_bits(&Sha256::digest(format!("{id}|{nonce}").as_bytes())) < 14 {
                nonce += 1;
            }
            let sig: Signature = key(&label).sign(body.as_bytes());
            let sig = Base64UrlUnpadded::encode_string(&sig.to_bytes());
            items.push(format!("{{\"a\":\"{who}\",\"i\":\"{id}\",\"n\":\"n\",\"p\":\"messaggio {n}\",\"s\":\"{sig}\",\"t\":{},\"w\":\"{nonce}\"}}", 1_000 + n));
        }
        std::fs::write(path, state(&items)).unwrap();
    }

    // Messaggi firmati dalle pagine (WebCrypto, come in fn-net.js): il contratto deve accettarli tutti.
    #[test]
    fn accepts_signatures_made_by_the_pages() {
        let vectors = include_str!("../tests/js_vectors.json");
        let list = read_messages(vectors.as_bytes()).expect("all page-made messages must verify");
        assert!(list.len() >= 40);
    }

    #[test]
    fn delta_carries_only_what_is_missing_and_converges() {
        let full = merge_all(&state(&[msg("a", 1, "x"), msg("b", 2, "y")]), &[]).unwrap();
        let partial = merge_all(&state(&[msg("a", 1, "x")]), &[]).unwrap();
        let summary = ids_of(&partial).unwrap();
        let delta = missing_from(&full, &summary).unwrap();
        assert_eq!(read_messages(&delta).unwrap().len(), 1);
        assert_eq!(merged(&partial, &delta), full);
    }
}
