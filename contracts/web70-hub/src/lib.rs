//! Shared message board: comments, likes, reports, channel directory, chess moves.
//! The parameters are a topic name, so every topic is its own contract instance.
//! Signatures are checked by the readers. The contract keeps the state mergeable and makes
//! flooding expensive: every message carries proof of work, every author has a quota.
use freenet_stdlib::prelude::*;
use serde_json::Value;
use sha2::{Digest, Sha256};
use std::collections::{BTreeMap, BTreeSet};

const MAX_MESSAGES: usize = 1000;
const MAX_MESSAGE_BYTES: usize = 16 * 1024;
const MAX_PER_AUTHOR: usize = 200;
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

type Board = BTreeMap<String, Message>;

fn parse_message(value: &Value) -> Option<(String, Message)> {
    let id = value.get("i")?.as_str()?;
    let time = value.get("t")?.as_u64()?;
    let author = value.get("a")?.as_str()?;
    let name = value.get("n")?.as_str()?;
    let payload = value.get("p")?.as_str()?;
    let work = value.get("w")?.as_str()?;
    value.get("s")?.as_str()?;
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
    Some((id.to_owned(), Message { time, author: author.to_owned(), raw }))
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

fn read_messages(bytes: &[u8]) -> Result<Vec<(String, Message)>, ContractError> {
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

fn merge_into(board: &mut Board, bytes: &[u8]) -> Result<(), ContractError> {
    for (id, message) in read_messages(bytes)? {
        add(board, id, message);
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
    merge_into(&mut board, state)?;
    for update in updates {
        merge_into(&mut board, update)?;
    }
    Ok(write_board(&board))
}

fn ids_of(state: &[u8]) -> Result<Vec<u8>, ContractError> {
    let ids: Vec<Value> = read_messages(state)?
        .into_iter()
        .map(|(id, _)| Value::String(id))
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
    for (id, message) in read_messages(state)? {
        if !known.contains(&id) {
            add(&mut board, id, message);
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

    fn signed(author: &str, time: u64, text: &str) -> (String, String) {
        let body = format!("{time}|{author}|n|{text}");
        let id = base64url_prefix(&Sha256::digest(body.as_bytes()));
        let mut nonce = 0u64;
        while leading_zero_bits(&Sha256::digest(format!("{id}|{nonce}").as_bytes())) < WORK_BITS {
            nonce += 1;
        }
        let raw = format!("{{\"a\":\"{author}\",\"i\":\"{id}\",\"n\":\"n\",\"p\":\"{text}\",\"s\":\"x\",\"t\":{time},\"w\":\"{nonce}\"}}");
        (id, raw)
    }
    fn msg(author: &str, time: u64, text: &str) -> String {
        signed(author, time, text).1
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
        assert_eq!(list.iter().filter(|(_, m)| m.author == "flood").count(), MAX_PER_AUTHOR);
        assert_eq!(list.iter().filter(|(_, m)| m.author == "quiet").count(), 1);
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
        let no_work = format!("{{\"a\":\"a\",\"i\":\"{id}\",\"n\":\"n\",\"p\":\"x\",\"s\":\"x\",\"t\":1,\"w\":\"{bad_nonce}\"}}");
        assert!(read_messages(&state(&[no_work])).is_err());
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
