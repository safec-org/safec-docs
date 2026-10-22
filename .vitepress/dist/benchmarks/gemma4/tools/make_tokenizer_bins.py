"""Converts the checkpoint's tokenizer.json into the vocab.bin / merges.bin
files gemma4_tokenizer.sc loads (format in src/gemma4_tokenizer.h):

  vocab.bin:  [u32 count] { [u32 byteLen][byteLen bytes UTF-8] }*count, in id order
  merges.bin: [u32 count] { [u32 leftLen][left][u32 rightLen][right] }*count,
              in merge-priority order (earlier = higher priority)

usage: make_tokenizer_bins.py <tokenizer.json> <out-dir>
"""
import json
import os
import struct
import sys


def main():
    src, out = sys.argv[1], sys.argv[2]
    model = json.load(open(src, encoding="utf-8"))["model"]
    vocab = model["vocab"]                                # token -> id
    ids = sorted(vocab.items(), key=lambda kv: kv[1])
    assert [i for _, i in ids] == list(range(len(ids))), "vocab ids are not dense"
    os.makedirs(out, exist_ok=True)
    with open(os.path.join(out, "vocab.bin"), "wb") as f:
        f.write(struct.pack("<I", len(ids)))
        for tok, _ in ids:
            b = tok.encode("utf-8")
            f.write(struct.pack("<I", len(b)) + b)
    merges = model["merges"]
    with open(os.path.join(out, "merges.bin"), "wb") as f:
        f.write(struct.pack("<I", len(merges)))
        for m in merges:
            left, right = m if isinstance(m, list) else m.split(" ", 1)
            lb, rb = left.encode("utf-8"), right.encode("utf-8")
            f.write(struct.pack("<I", len(lb)) + lb + struct.pack("<I", len(rb)) + rb)
    print(f"vocab {len(ids)}, merges {len(merges)} -> {out}")


if __name__ == "__main__":
    main()
