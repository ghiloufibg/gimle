const EOCD_SIGNATURE = 0x06054b50;
const CENTRAL_DIR_SIGNATURE = 0x02014b50;
const LOCAL_FILE_SIGNATURE = 0x04034b50;
const EOCD_FIXED_SIZE = 22;
// A ZIP comment is at most 65535 bytes, so the End Of Central Directory record can never start
// further than that (plus its own fixed size) from the end of the file.
const EOCD_MAX_COMMENT = 0xffff;

const COMPRESSION_STORED = 0;
const COMPRESSION_DEFLATE = 8;

/**
 * Reads one named entry out of a `.jar` (a plain ZIP archive) directly in the browser, without a
 * general-purpose unzip dependency: the only thing this console ever needs out of an uploaded jar
 * is its bundled `gimle-module.yaml`, so a purpose-built central-directory walk that locates one
 * entry and inflates it (`stored` or `deflate`, the only two methods a JDK-produced jar ever uses)
 * is a small, well-scoped amount of code rather than a reason to add a zip library dependency.
 *
 * Returns `null` when the entry isn't present (a vessel jar with no bundled descriptor, or any
 * archive that isn't a well-formed ZIP at all) -- both are routine, not errors the caller needs to
 * distinguish, since either way the operator falls back to typing the coordinate by hand.
 */
export async function readZipEntry(file: Blob, entryName: string): Promise<Uint8Array | null> {
  const eocd = await findEndOfCentralDirectory(file);
  if (!eocd) return null;

  const centralDirectory = new DataView(
    await file
      .slice(eocd.centralDirOffset, eocd.centralDirOffset + eocd.centralDirSize)
      .arrayBuffer(),
  );
  const record = findCentralDirectoryRecord(centralDirectory, entryName);
  if (!record) return null;

  const localHeader = new DataView(
    await file.slice(record.localHeaderOffset, record.localHeaderOffset + 30).arrayBuffer(),
  );
  if (localHeader.getUint32(0, true) !== LOCAL_FILE_SIGNATURE) return null;
  const nameLen = localHeader.getUint16(26, true);
  const extraLen = localHeader.getUint16(28, true);
  const dataOffset = record.localHeaderOffset + 30 + nameLen + extraLen;

  const compressed = await file.slice(dataOffset, dataOffset + record.compressedSize).arrayBuffer();
  switch (record.compressionMethod) {
    case COMPRESSION_STORED:
      return new Uint8Array(compressed);
    case COMPRESSION_DEFLATE:
      return inflateRaw(compressed);
    default:
      // An unsupported method (e.g. bzip2) is treated the same as "no entry found" -- no
      // JDK-produced jar ever uses one, so this only ever fires on a hand-crafted or foreign file.
      return null;
  }
}

async function inflateRaw(compressed: ArrayBuffer): Promise<Uint8Array> {
  const stream = new Blob([compressed])
    .stream()
    .pipeThrough(new DecompressionStream("deflate-raw"));
  return new Uint8Array(await new Response(stream).arrayBuffer());
}

interface Eocd {
  centralDirOffset: number;
  centralDirSize: number;
}

async function findEndOfCentralDirectory(file: Blob): Promise<Eocd | null> {
  const tailSize = Math.min(file.size, EOCD_FIXED_SIZE + EOCD_MAX_COMMENT);
  if (tailSize < EOCD_FIXED_SIZE) return null;
  const tail = new DataView(await file.slice(file.size - tailSize, file.size).arrayBuffer());

  // The comment (if any) trails the fixed record, so scan backward from the last position a
  // signature could start and take the first match -- the one closest to the end of the file.
  for (let i = tail.byteLength - EOCD_FIXED_SIZE; i >= 0; i--) {
    if (tail.getUint32(i, true) === EOCD_SIGNATURE) {
      return {
        centralDirSize: tail.getUint32(i + 12, true),
        centralDirOffset: tail.getUint32(i + 16, true),
      };
    }
  }
  return null;
}

interface CentralDirectoryRecord {
  compressionMethod: number;
  compressedSize: number;
  localHeaderOffset: number;
}

function findCentralDirectoryRecord(
  view: DataView,
  entryName: string,
): CentralDirectoryRecord | null {
  const decoder = new TextDecoder("utf-8");
  let pos = 0;
  while (pos + 46 <= view.byteLength) {
    if (view.getUint32(pos, true) !== CENTRAL_DIR_SIGNATURE) return null;
    const compressionMethod = view.getUint16(pos + 10, true);
    const compressedSize = view.getUint32(pos + 20, true);
    const nameLen = view.getUint16(pos + 28, true);
    const extraLen = view.getUint16(pos + 30, true);
    const commentLen = view.getUint16(pos + 32, true);
    const localHeaderOffset = view.getUint32(pos + 42, true);
    const nameBytes = new Uint8Array(view.buffer, view.byteOffset + pos + 46, nameLen);
    const name = decoder.decode(nameBytes);
    if (name === entryName) {
      return { compressionMethod, compressedSize, localHeaderOffset };
    }
    pos += 46 + nameLen + extraLen + commentLen;
  }
  return null;
}
