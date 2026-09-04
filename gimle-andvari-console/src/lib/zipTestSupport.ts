import { deflateRawSync } from "node:zlib";

/**
 * Builds a real, minimal ZIP archive in memory for `zip.test.ts`/`moduleDescriptor.test.ts` to
 * read back -- exercising `readZipEntry` against an archive shaped exactly like a JDK-produced
 * jar (a proper End Of Central Directory record, central directory, and local file headers)
 * rather than against a hand-faked byte layout that only coincidentally resembles one. Entry data
 * is CRC-unchecked here since `readZipEntry` itself never verifies it -- Andvari and the worker's
 * own `ModuleArtifactReader` are what a genuinely corrupt jar meets further down the pipeline.
 */
export function buildZip(entries: ReadonlyArray<ZipEntryInput>): Uint8Array {
  const localRecords: Uint8Array[] = [];
  const centralRecords: Uint8Array[] = [];
  let offset = 0;

  for (const entry of entries) {
    const method = entry.method ?? 8;
    const nameBytes = new TextEncoder().encode(entry.name);
    const compressed = method === 8 ? new Uint8Array(deflateRawSync(entry.data)) : entry.data;

    const local = new Uint8Array(30 + nameBytes.length);
    const lv = new DataView(local.buffer);
    lv.setUint32(0, 0x04034b50, true);
    lv.setUint16(8, method, true);
    lv.setUint32(18, compressed.length, true);
    lv.setUint32(22, entry.data.length, true);
    lv.setUint16(26, nameBytes.length, true);
    local.set(nameBytes, 30);
    localRecords.push(local, compressed);

    const central = new Uint8Array(46 + nameBytes.length);
    const cv = new DataView(central.buffer);
    cv.setUint32(0, 0x02014b50, true);
    cv.setUint16(10, method, true);
    cv.setUint32(20, compressed.length, true);
    cv.setUint32(24, entry.data.length, true);
    cv.setUint16(28, nameBytes.length, true);
    cv.setUint32(42, offset, true);
    central.set(nameBytes, 46);
    centralRecords.push(central);

    offset += local.length + compressed.length;
  }

  const centralDirOffset = offset;
  const centralDirSize = centralRecords.reduce((sum, r) => sum + r.length, 0);

  const eocd = new Uint8Array(22);
  const ev = new DataView(eocd.buffer);
  ev.setUint32(0, 0x06054b50, true);
  ev.setUint16(8, entries.length, true);
  ev.setUint16(10, entries.length, true);
  ev.setUint32(12, centralDirSize, true);
  ev.setUint32(16, centralDirOffset, true);

  return concat([...localRecords, ...centralRecords, eocd]);
}

export interface ZipEntryInput {
  name: string;
  data: Uint8Array;
  /** 0 = stored, 8 = deflate (default). */
  method?: 0 | 8;
}

function concat(parts: ReadonlyArray<Uint8Array>): Uint8Array {
  const out = new Uint8Array(parts.reduce((sum, p) => sum + p.length, 0));
  let pos = 0;
  for (const part of parts) {
    out.set(part, pos);
    pos += part.length;
  }
  return out;
}
