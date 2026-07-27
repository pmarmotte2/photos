const test = require("node:test");
const assert = require("node:assert/strict");
const zipApi = require("../zip.js");

function uint16(value) {
  return [value & 255, (value >>> 8) & 255];
}

function uint32(value) {
  return [
    value & 255,
    (value >>> 8) & 255,
    (value >>> 16) & 255,
    (value >>> 24) & 255
  ];
}

function storedZip(name, content) {
  const encoder = new TextEncoder();
  const nameBytes = [...encoder.encode(name)];
  const data = [...encoder.encode(content)];
  const local = [
    ...uint32(0x04034b50),
    ...uint16(20), ...uint16(0), ...uint16(0),
    ...uint16(0), ...uint16(0),
    ...uint32(0), ...uint32(data.length), ...uint32(data.length),
    ...uint16(nameBytes.length), ...uint16(0),
    ...nameBytes, ...data
  ];
  const central = [
    ...uint32(0x02014b50),
    ...uint16(20), ...uint16(20), ...uint16(0), ...uint16(0),
    ...uint16(0), ...uint16(0),
    ...uint32(0), ...uint32(data.length), ...uint32(data.length),
    ...uint16(nameBytes.length), ...uint16(0), ...uint16(0),
    ...uint16(0), ...uint16(0), ...uint32(0), ...uint32(0),
    ...nameBytes
  ];
  const end = [
    ...uint32(0x06054b50),
    ...uint16(0), ...uint16(0), ...uint16(1), ...uint16(1),
    ...uint32(central.length), ...uint32(local.length), ...uint16(0)
  ];
  return new Uint8Array([...local, ...central, ...end]);
}

test("une entrée ZIP stockée est extraite", async () => {
  const bytes = storedZip("recapitulatif.csv", "Date;Code");
  const entries = await zipApi.readZip({
    arrayBuffer: async () =>
      bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength)
  });

  const entry = zipApi.findEntry(entries, "recapitulatif.csv");
  assert.equal(new TextDecoder().decode(entry.bytes), "Date;Code");
});
