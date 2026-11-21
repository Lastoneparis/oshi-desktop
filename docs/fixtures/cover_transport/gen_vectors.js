#!/usr/bin/env node
'use strict';
// Generates docs/fixtures/cover_transport/vectors.json — the cross-platform
// golden vectors for the V2 cover transport OUTER presentation (2026-11-13).
//
// Deterministic: padding bytes come from a SHA-256 counter stream, so a rerun
// produces byte-identical output. It does NOT import any client code; the
// framing is written out from the wire contract (BE32 length || UTF-8 JSON ||
// padding to a shared bucket, base64, wrapped in one of two closed JSON
// schemas) so the vectors are an independent oracle for Android, iOS and the
// relay. Run: node docs/fixtures/cover_transport/gen_vectors.js
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

const BUCKETS = [4096, 16384, 65536, 262144, 1048576, 4194304];

function stream(seed, n) {
    const out = Buffer.alloc(n);
    let off = 0, ctr = 0;
    while (off < n) {
        const block = crypto.createHash('sha256').update(seed + ':' + ctr++).digest();
        off += block.copy(out, off);
    }
    return out;
}

function frame(record, bucket, seed) {
    const json = Buffer.from(JSON.stringify(record));
    if (!BUCKETS.includes(bucket) || json.length + 4 > bucket) throw Error('bad bucket');
    const out = stream(seed, bucket);
    out.writeUInt32BE(json.length);
    json.copy(out, 4);
    return out;
}

// Outer bodies are spelled out byte for byte: key order and slash escaping
// are exactly what the hardened clients vary, so they must not be left to a
// serializer.
const SHAPES = {
    event_name_first: p => `{"events":[{"name":"page_view","params":{"data":"${p}"}}]}`,
    event_params_first: p => `{"events":[{"params":{"data":"${p}"},"name":"page_view"}]}`,
    batch_event_first: p => `{"batch":[{"event":"screen_view","payload":"${p}"}]}`,
    batch_payload_first: p => `{"batch":[{"payload":"${p}","event":"screen_view"}]}`,
    // What android.jar's org.json emitted on a device before 2026-11-13: every
    // "/" escaped. Valid JSON; all decoders must keep accepting it.
    event_escaped_slashes: p => `{"events":[{"name":"page_view","params":{"data":"${p.replace(/\//g, '\\/')}"}}]}`,
};

const request = {
    v: 1, method: 'POST', path: '/v2/messages',
    headers: { 'content-type': 'application/json; charset=utf-8', 'x-oshi-signature': 'c2lnbmF0dXJl' },
    body: Buffer.from('{"to":"vector","ciphertext":"AAAA"}').toString('base64'),
};
const response = {
    v: 1, status: 200,
    headers: { 'content-type': 'application/json' },
    body: Buffer.from('{"accepted":["m1"]}').toString('base64'),
};

const accept = [];
function add(name, shape, record, bucket, contentType = 'application/json') {
    const data = frame(record, bucket, name).toString('base64');
    accept.push({ name, contentType, bucket, body: SHAPES[shape](data), expect: record });
}
add('request_event_name_first_4k', 'event_name_first', request, 4096);
add('request_event_params_first_4k', 'event_params_first', request, 4096, 'application/json; charset=utf-8');
add('request_batch_event_first_4k', 'batch_event_first', request, 4096, 'application/json;charset=UTF-8');
add('request_batch_payload_first_4k', 'batch_payload_first', request, 4096);
add('request_event_escaped_slashes_4k', 'event_escaped_slashes', request, 4096);
// Bucket spread: a record that fits 4 KiB padded to the next bucket.
add('request_batch_spread_16k', 'batch_payload_first', request, 16384);
add('response_event_name_first_4k', 'event_name_first', response, 4096);
add('response_batch_event_first_4k', 'batch_event_first', response, 4096);

const good = frame(request, 4096, 'reject').toString('base64');
const reject = [
    // Rejected by every strict decoder (Android, iOS, ServerVPS). The lenient
    // relay lineage in ServerPatches/*/v2/cover_transport.js accepts the
    // unknown-key cases; `lenientAccepts` records that so the Node test can
    // assert the actual behaviour of each relay copy rather than assume it.
    { name: 'unknown_outer_key', contentType: 'application/json', lenientAccepts: true,
        body: `{"events":[{"name":"page_view","params":{"data":"${good}"}}],"x":1}` },
    { name: 'unknown_param_key', contentType: 'application/json', lenientAccepts: true,
        body: `{"events":[{"name":"page_view","params":{"data":"${good}","x":1}}]}` },
    { name: 'two_events', contentType: 'application/json', lenientAccepts: false,
        body: `{"events":[{"name":"page_view","params":{"data":"${good}"}},{"name":"page_view","params":{"data":"${good}"}}]}` },
    { name: 'wrong_event_name', contentType: 'application/json', lenientAccepts: false,
        body: `{"batch":[{"event":"click","payload":"${good}"}]}` },
    { name: 'non_bucket_frame', contentType: 'application/json', lenientAccepts: false,
        body: SHAPES.event_name_first(Buffer.concat([frame(request, 4096, 'nb'), Buffer.from([0])]).toString('base64')) },
    { name: 'text_plain_content_type', contentType: 'text/plain;charset=UTF-8', lenientAccepts: false,
        body: SHAPES.event_name_first(good) },
];

const out = {
    version: 1,
    generator: 'docs/fixtures/cover_transport/gen_vectors.js',
    contract: 'docs/COVER_TRANSPORT_STATUS_2026-11-13.md',
    buckets: BUCKETS,
    endpointPaths: ['/v2/cover', '/v2/collect', '/v2/metrics'],
    accept,
    reject,
};
fs.writeFileSync(path.join(__dirname, 'vectors.json'), JSON.stringify(out, null, 1) + '\n');
console.log(`wrote ${accept.length} accept + ${reject.length} reject vectors`);
