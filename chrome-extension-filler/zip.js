(function (root, factory) {
  const api = factory();
  root.FoNotesZip = api;
  if (typeof module === "object" && module.exports) module.exports = api;
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
  "use strict";

  const SIGNATURE_EOCD = 0x06054b50;
  const SIGNATURE_CENTRAL = 0x02014b50;
  const SIGNATURE_LOCAL = 0x04034b50;

  function findEndOfCentralDirectory(view) {
    const minimum = Math.max(0, view.byteLength - 65557);
    for (let offset = view.byteLength - 22; offset >= minimum; offset -= 1) {
      if (view.getUint32(offset, true) === SIGNATURE_EOCD) return offset;
    }
    throw new Error("Archive ZIP invalide : répertoire central introuvable.");
  }

  async function inflateRaw(bytes) {
    if (typeof DecompressionStream !== "function") {
      throw new Error("Cette version de Chrome ne permet pas de décompresser le ZIP.");
    }
    const stream = new Blob([bytes])
      .stream()
      .pipeThrough(new DecompressionStream("deflate-raw"));
    return new Uint8Array(await new Response(stream).arrayBuffer());
  }

  async function readZip(file) {
    const buffer = await file.arrayBuffer();
    const view = new DataView(buffer);
    const bytes = new Uint8Array(buffer);
    const eocd = findEndOfCentralDirectory(view);
    const entryCount = view.getUint16(eocd + 10, true);
    let centralOffset = view.getUint32(eocd + 16, true);
    const decoder = new TextDecoder("utf-8");
    const entries = new Map();

    for (let index = 0; index < entryCount; index += 1) {
      if (view.getUint32(centralOffset, true) !== SIGNATURE_CENTRAL) {
        throw new Error("Archive ZIP invalide : entrée centrale incorrecte.");
      }
      const method = view.getUint16(centralOffset + 10, true);
      const compressedSize = view.getUint32(centralOffset + 20, true);
      const uncompressedSize = view.getUint32(centralOffset + 24, true);
      const nameLength = view.getUint16(centralOffset + 28, true);
      const extraLength = view.getUint16(centralOffset + 30, true);
      const commentLength = view.getUint16(centralOffset + 32, true);
      const localOffset = view.getUint32(centralOffset + 42, true);
      const name = decoder.decode(
        bytes.subarray(centralOffset + 46, centralOffset + 46 + nameLength)
      );

      if (!name.endsWith("/")) {
        if (view.getUint32(localOffset, true) !== SIGNATURE_LOCAL) {
          throw new Error(`Archive ZIP invalide pour ${name}.`);
        }
        const localNameLength = view.getUint16(localOffset + 26, true);
        const localExtraLength = view.getUint16(localOffset + 28, true);
        const dataOffset = localOffset + 30 + localNameLength + localExtraLength;
        const compressed = bytes.slice(dataOffset, dataOffset + compressedSize);
        let content;
        if (method === 0) {
          content = compressed;
        } else if (method === 8) {
          content = await inflateRaw(compressed);
        } else {
          throw new Error(`Compression ZIP non prise en charge pour ${name}.`);
        }
        if (uncompressedSize && content.byteLength !== uncompressedSize) {
          throw new Error(`Taille incorrecte après extraction de ${name}.`);
        }
        entries.set(name, content);
      }

      centralOffset += 46 + nameLength + extraLength + commentLength;
    }
    return entries;
  }

  function findEntry(entries, expectedName) {
    const target = String(expectedName).toLowerCase();
    for (const [name, bytes] of entries) {
      if (name.toLowerCase() === target || name.split("/").at(-1).toLowerCase() === target) {
        return { name, bytes };
      }
    }
    return null;
  }

  function findCsvEntry(entries) {
    const csvEntries = [...entries]
      .filter(([name]) => name.toLowerCase().endsWith(".csv"))
      .map(([name, bytes]) => ({ name, bytes }));
    if (!csvEntries.length) return null;
    return csvEntries.find((entry) =>
      entry.name.split("/").at(-1).toLowerCase() === "recapitulatif.csv"
    ) || csvEntries.find((entry) =>
      /^fo[_ -]?notes[_ -]/i.test(entry.name.split("/").at(-1))
    ) || csvEntries[0];
  }

  return { findCsvEntry, findEntry, readZip };
});
