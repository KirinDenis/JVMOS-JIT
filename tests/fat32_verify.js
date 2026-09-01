/*
 * An independent FAT32 reader, written from the specification rather than from
 * the C driver, used as the oracle for it.
 *
 * There is no mtools or 7-Zip on a typical Windows machine, so nothing else
 * here can open a FAT image. Two implementations written separately agreeing
 * on the same bytes is weaker evidence than a reference implementation, but it
 * catches the mistakes that actually happen in a filesystem: a field at the
 * wrong offset, a cluster number computed off by two, a FAT that was written
 * once instead of twice.
 *
 *   node tests/fat32_verify.js <image> [expected-name expected-size ...]
 */
const fs = require("fs");

const img = fs.readFileSync(process.argv[2]);
const u16 = (o) => img.readUInt16LE(o);
const u32 = (o) => img.readUInt32LE(o);

let failures = 0;
const check = (label, ok, detail) => {
  if (ok) {
    console.log(`  ok   ${label}${detail ? "  " + detail : ""}`);
  } else {
    failures++;
    console.log(`  FAIL ${label}${detail ? "  " + detail : ""}`);
  }
};

// ------------------------------------------------------------------- MBR
check("mbr signature", img[510] === 0x55 && img[511] === 0xaa);
const partType = img[0x1be + 4];
const partLba = u32(0x1be + 8);
const partSectors = u32(0x1be + 12);
check("partition type is FAT32 LBA", partType === 0x0c, "0x" + partType.toString(16));
check("system area reserved before the partition", partLba >= 2048, `starts at LBA ${partLba}`);
check("partition fits the image", (partLba + partSectors) * 512 <= img.length,
      `${partSectors} sectors`);

// ------------------------------------------------------- boot sector (BPB)
const B = partLba * 512;
check("vbr signature", img[B + 510] === 0x55 && img[B + 511] === 0xaa);
const bytesPerSector = u16(B + 11);
const spc = img[B + 13];
const reserved = u16(B + 14);
const numFats = img[B + 16];
const rootEntries = u16(B + 17);
const fatSz16 = u16(B + 22);
const totalSectors = u32(B + 32);
const fatSz32 = u32(B + 36);
const rootCluster = u32(B + 44);
const fsInfoSector = u16(B + 48);

check("bytes per sector", bytesPerSector === 512, String(bytesPerSector));
check("sectors per cluster is a power of two", spc > 0 && (spc & (spc - 1)) === 0, String(spc));
check("two FATs", numFats === 2, String(numFats));
check("no fixed root directory (FAT32)", rootEntries === 0);
check("FATSz16 is zero (FAT32)", fatSz16 === 0);
check("total sectors matches the partition", totalSectors === partSectors);
check("root directory cluster", rootCluster >= 2, String(rootCluster));
check("filesystem type label", img.slice(B + 82, B + 90).toString("latin1") === "FAT32   ");

const fatLba = partLba + reserved;
const dataLba = fatLba + numFats * fatSz32;
const clusters = Math.floor((partSectors - reserved - numFats * fatSz32) / spc);
check("FAT is large enough for every cluster",
      fatSz32 * 512 >= (clusters + 2) * 4,
      `${fatSz32} sectors for ${clusters} clusters`);
if (clusters < 65525) {
  console.log(`  note ${clusters} clusters is below the conventional FAT32 minimum of 65525.`);
  console.log(`       The volume is structurally FAT32 and our driver reads it, but strict`);
  console.log(`       tools (Windows among them) will refuse to mount it. Raising the image`);
  console.log(`       past ~34MB is the only fix; it is one number in the Makefile.`);
}

// ------------------------------------------------------------ FSInfo
const F = (partLba + fsInfoSector) * 512;
check("fsinfo lead signature", u32(F) === 0x41615252);
check("fsinfo struct signature", u32(F + 484) === 0x61417272);
check("fsinfo trail signature", u32(F + 508) === 0xaa550000);

// backup boot sector
const BK = (partLba + 6) * 512;
check("backup boot sector matches",
      img.slice(BK, BK + 512).equals(img.slice(B, B + 512)));

// ------------------------------------------------------------ the FATs
const fatEntry = (n) => u32(fatLba * 512 + n * 4) & 0x0fffffff;
check("FAT[0] media descriptor", fatEntry(0) === 0x0ffffff8,
      "0x" + fatEntry(0).toString(16));
check("FAT[1] end of chain", fatEntry(1) === 0x0fffffff);
check("root cluster is an end of chain", fatEntry(rootCluster) >= 0x0ffffff8);

const fat1 = img.slice(fatLba * 512, (fatLba + fatSz32) * 512);
const fat2 = img.slice((fatLba + fatSz32) * 512, (fatLba + 2 * fatSz32) * 512);
check("both FATs are identical", fat1.equals(fat2));

// --------------------------------------------------------- directory
const clusterLba = (c) => dataLba + (c - 2) * spc;

function chain(start) {
  const out = [];
  let c = start;
  while (c >= 2 && c < 0x0ffffff8 && out.length < 100000) {
    out.push(c);
    c = fatEntry(c);
  }
  return out;
}

const files = [];
outer: for (const c of chain(rootCluster)) {
  for (let s = 0; s < spc; s++) {
    const base = (clusterLba(c) + s) * 512;
    for (let e = 0; e < 512; e += 32) {
      const o = base + e;
      if (img[o] === 0x00) break outer;
      if (img[o] === 0xe5) continue;
      if ((img[o + 11] & 0x0f) === 0x0f) continue;
      if (img[o + 11] & 0x08) continue;
      files.push({
        name: img.slice(o, o + 11).toString("latin1"),
        size: u32(o + 28),
        cluster: (u16(o + 20) << 16) | u16(o + 26),
      });
    }
  }
}

console.log(`\n  root directory: ${files.length} entries`);
for (const f of files) {
  const used = chain(f.cluster).length * spc * 512;
  const need = Math.ceil(f.size / (spc * 512)) * spc * 512;
  console.log(`    ${f.name}  ${f.size} bytes  cluster ${f.cluster}  chain holds ${used}`);
  check(`chain for ${f.name.trim()} is long enough`, used >= need || f.size === 0);
}

// expectations from the command line, in name/size pairs
for (let i = 3; i + 1 < process.argv.length; i += 2) {
  const want = process.argv[i];
  const size = Number(process.argv[i + 1]);
  const found = files.find((f) => f.name.replace(/\s+/g, "") === want.replace(/[.\s]/g, ""));
  check(`${want} present with ${size} bytes`, !!found && found.size === size,
        found ? `${found.size} bytes` : "missing");
}

console.log(`\n${failures === 0 ? "all checks passed" : failures + " CHECKS FAILED"}` +
            `  (independent reader, ${clusters} clusters of ${spc * 512} bytes)`);
process.exit(failures ? 1 : 0);
