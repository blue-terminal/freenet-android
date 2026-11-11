//! Web 7.0 pages that run inside Freenet talk to the node over its WebSocket API with the
//! node's own "native" encoding (bincode of the freenet-stdlib types). This module builds
//! the requests and reads the replies with exactly those types, so it cannot drift from
//! what the node expects. The page passes bytes in and gets bytes or JSON back.
//!
//! ABI: every call returns a pointer to [len: u32 little endian][bytes]; free it with `release`.
use freenet_stdlib::client_api::{ClientRequest, ContractRequest, ContractResponse, HostResponse, NodeQuery, QueryResponse};
use freenet_stdlib::prelude::*;
use serde_json::{json, Value};
use std::cell::RefCell;
use std::collections::BTreeMap;
use std::sync::Arc;

type HostResult = Result<HostResponse, freenet_stdlib::client_api::ClientError>;

thread_local! {
    // Replies too large for one WebSocket message arrive in numbered chunks.
    static STREAMS: RefCell<BTreeMap<u32, BTreeMap<u32, Vec<u8>>>> = RefCell::new(BTreeMap::new());
}

#[no_mangle]
pub extern "C" fn reserve(len: usize) -> *mut u8 {
    let mut buffer = Vec::<u8>::with_capacity(len.max(1));
    let pointer = buffer.as_mut_ptr();
    std::mem::forget(buffer);
    pointer
}

#[no_mangle]
pub unsafe extern "C" fn release(pointer: *mut u8, capacity: usize) {
    drop(Vec::from_raw_parts(pointer, 0, capacity));
}

unsafe fn input(pointer: *const u8, len: usize) -> Vec<u8> {
    std::slice::from_raw_parts(pointer, len).to_vec()
}

fn output(bytes: Vec<u8>) -> *mut u8 {
    let mut framed = Vec::with_capacity(bytes.len() + 4);
    framed.extend_from_slice(&(bytes.len() as u32).to_le_bytes());
    framed.extend_from_slice(&bytes);
    let pointer = reserve(framed.len());
    unsafe { std::ptr::copy_nonoverlapping(framed.as_ptr(), pointer, framed.len()) };
    pointer
}

fn failure(message: String) -> Vec<u8> {
    json!({ "type": "error", "message": message }).to_string().into_bytes()
}

fn container(packaged_code: Vec<u8>, parameters: Vec<u8>) -> Result<ContractContainer, String> {
    let (code, _) = ContractCode::load_versioned_from_bytes(packaged_code).map_err(|e| e.to_string())?;
    let wrapped = WrappedContract::new(Arc::new(code), Parameters::from(parameters));
    Ok(ContractContainer::Wasm(ContractWasmAPIVersion::V1(wrapped)))
}

fn instance_id(text: &str) -> Result<ContractInstanceId, String> {
    ContractInstanceId::try_from(text.to_owned()).map_err(|e| format!("{e}"))
}

fn encode(request: ClientRequest<'static>) -> Result<Vec<u8>, String> {
    bincode::serialize(&request).map_err(|e| e.to_string())
}

/// Contract id (base58) of the given packaged code and parameters, or an error JSON.
#[no_mangle]
pub unsafe extern "C" fn contract_id(code: *const u8, code_len: usize, params: *const u8, params_len: usize) -> *mut u8 {
    output(match container(input(code, code_len), input(params, params_len)) {
        Ok(c) => c.key().id().encode().into_bytes(),
        Err(e) => failure(e),
    })
}

#[no_mangle]
pub unsafe extern "C" fn encode_get(id: *const u8, id_len: usize, subscribe: u32) -> *mut u8 {
    let text = String::from_utf8_lossy(&input(id, id_len)).into_owned();
    output(
        instance_id(&text)
            .and_then(|key| {
                encode(ContractRequest::Get { key, return_contract_code: false, subscribe: subscribe != 0, blocking_subscribe: false }.into())
            })
            .unwrap_or_else(failure),
    )
}

/// The node resolves the code hash from the instance id, as fdev does.
#[no_mangle]
pub unsafe extern "C" fn encode_update(id: *const u8, id_len: usize, delta: *const u8, delta_len: usize) -> *mut u8 {
    let text = String::from_utf8_lossy(&input(id, id_len)).into_owned();
    let delta = input(delta, delta_len);
    output(
        instance_id(&text)
            .and_then(|instance| {
                let key = ContractKey::from_id_and_code(instance, CodeHash::new([0u8; 32]));
                encode(ContractRequest::Update { key, data: UpdateData::Delta(StateDelta::from(delta)) }.into())
            })
            .unwrap_or_else(failure),
    )
}

#[no_mangle]
pub unsafe extern "C" fn encode_put(
    code: *const u8,
    code_len: usize,
    params: *const u8,
    params_len: usize,
    state: *const u8,
    state_len: usize,
) -> *mut u8 {
    let state = input(state, state_len);
    output(
        container(input(code, code_len), input(params, params_len))
            .and_then(|contract| {
                encode(
                    ContractRequest::Put {
                        contract,
                        state: WrappedState::new(state),
                        related_contracts: RelatedContracts::default(),
                        subscribe: true,
                        blocking_subscribe: false,
                    }
                    .into(),
                )
            })
            .unwrap_or_else(failure),
    )
}

fn describe(result: HostResult) -> Value {
    match result {
        Err(e) => json!({ "type": "error", "message": format!("{e}") }),
        Ok(HostResponse::ContractResponse(response)) => match response {
            ContractResponse::GetResponse { key, state, .. } => json!({
                "type": "get",
                "id": key.id().encode(),
                "state": String::from_utf8_lossy(state.as_ref()),
            }),
            ContractResponse::PutResponse { key } => json!({ "type": "put", "id": key.id().encode() }),
            ContractResponse::UpdateResponse { key, .. } => json!({ "type": "update", "id": key.id().encode() }),
            ContractResponse::UpdateNotification { key, .. } => json!({ "type": "notification", "id": key.id().encode() }),
            ContractResponse::SubscribeResponse { key, .. } => json!({ "type": "subscribe", "id": key.id().encode() }),
            ContractResponse::NotFound { instance_id } => json!({ "type": "notfound", "id": instance_id.encode() }),
            _ => json!({ "type": "other" }),
        },
        // Only how many: the addresses of the peers never reach the page.
        Ok(HostResponse::QueryResponse(QueryResponse::ConnectedPeers { peers })) => json!({ "type": "peers", "count": peers.len() }),
        Ok(_) => json!({ "type": "other" }),
    }
}

/// Asks the node how many peers it is connected to.
#[no_mangle]
pub extern "C" fn encode_peers() -> *mut u8 {
    output(encode(ClientRequest::NodeQueries(NodeQuery::ConnectedPeers)).unwrap_or_else(failure))
}

/// Reads one WebSocket message. Chunks are kept until the reply is complete: until then
/// the answer is {"type":"pending"}.
#[no_mangle]
pub unsafe extern "C" fn decode(bytes: *const u8, len: usize) -> *mut u8 {
    let message = input(bytes, len);
    let result: HostResult = match bincode::deserialize(&message) {
        Ok(r) => r,
        Err(e) => return output(failure(format!("risposta illeggibile: {e}"))),
    };
    let value = match result {
        Ok(HostResponse::StreamChunk { stream_id, index, total, data }) => {
            let complete = STREAMS.with(|streams| {
                let mut streams = streams.borrow_mut();
                let parts = streams.entry(stream_id).or_default();
                parts.insert(index, data.to_vec());
                if parts.len() as u32 >= total {
                    let joined: Vec<u8> = parts.values().flatten().copied().collect();
                    streams.remove(&stream_id);
                    Some(joined)
                } else {
                    None
                }
            });
            match complete {
                Some(joined) => match bincode::deserialize::<HostResult>(&joined) {
                    Ok(inner) => describe(inner),
                    Err(e) => json!({ "type": "error", "message": format!("risposta illeggibile: {e}") }),
                },
                None => json!({ "type": "pending" }),
            }
        }
        Ok(HostResponse::StreamHeader { .. }) => json!({ "type": "pending" }),
        other => describe(other),
    };
    output(value.to_string().into_bytes())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn read(pointer: *mut u8) -> Vec<u8> {
        unsafe {
            let len = u32::from_le_bytes(std::slice::from_raw_parts(pointer, 4).try_into().unwrap()) as usize;
            std::slice::from_raw_parts(pointer.add(4), len).to_vec()
        }
    }

    #[test]
    fn requests_round_trip_through_the_node_types() {
        let id = "ABbGNbn5LJrXKkZJXeyV3NvHuTXvvqK6fMp8WzRVZ2pW";
        let get = read(unsafe { encode_get(id.as_ptr(), id.len(), 1) });
        match bincode::deserialize::<ClientRequest>(&get).unwrap() {
            ClientRequest::ContractOp(ContractRequest::Get { key, subscribe, .. }) => {
                assert_eq!(key.encode(), id);
                assert!(subscribe);
            }
            other => panic!("{other:?}"),
        }
        let delta = b"{\"m\":[]}";
        let update = read(unsafe { encode_update(id.as_ptr(), id.len(), delta.as_ptr(), delta.len()) });
        match bincode::deserialize::<ClientRequest>(&update).unwrap() {
            ClientRequest::ContractOp(ContractRequest::Update { key, data: UpdateData::Delta(d) }) => {
                assert_eq!(key.id().encode(), id);
                assert_eq!(d.as_ref(), delta);
            }
            other => panic!("{other:?}"),
        }
    }

    #[test]
    fn replies_and_chunks_are_read() {
        let key = ContractKey::from_id_and_code(
            ContractInstanceId::try_from("ABbGNbn5LJrXKkZJXeyV3NvHuTXvvqK6fMp8WzRVZ2pW".to_owned()).unwrap(),
            CodeHash::new([1u8; 32]),
        );
        let reply: HostResult = Ok(HostResponse::ContractResponse(ContractResponse::GetResponse {
            key,
            contract: None,
            state: WrappedState::new(b"{\"m\":[1]}".to_vec()),
        }));
        let bytes = bincode::serialize(&reply).unwrap();
        let direct: Value = serde_json::from_slice(&read(unsafe { decode(bytes.as_ptr(), bytes.len()) })).unwrap();
        assert_eq!(direct["type"], "get");
        assert_eq!(direct["state"], "{\"m\":[1]}");
        let half = bytes.len() / 2;
        for (index, piece) in [&bytes[..half], &bytes[half..]].iter().enumerate() {
            let chunk: HostResult = Ok(HostResponse::StreamChunk {
                stream_id: 7,
                index: index as u32,
                total: 2,
                data: piece.to_vec().into(),
            });
            let framed = bincode::serialize(&chunk).unwrap();
            let answer: Value = serde_json::from_slice(&read(unsafe { decode(framed.as_ptr(), framed.len()) })).unwrap();
            assert_eq!(answer["type"], if index == 0 { "pending" } else { "get" });
        }
    }
}
