// SafeC Standard Library — Gemma 4 tokenizer implementation (see
// gemma4_tokenizer.h for the format this reads and the rules it applies).
#pragma once
#include "gemma4_tokenizer.h"
#include <std/io_file.h>
// Declaration-only (.h, not .sc) for all of these -- this module is meant
// to be linked alongside other large modules (gemma4_model.sc in
// particular, which transitively pulls in its own full copies of these
// same implementations via json.sc) that would otherwise collide with a
// second full copy of vec/mem/str/map/string's implementations at link
// time (see DESIGN_NOTES.md's "duplicate-symbol link conflict" note).
// Whatever top-level binary links this file in must pass
// mem.sc/str.sc/map.sc/vec.sc/string.sc explicitly itself, exactly once.
#include <std/mem.h>
#include <std/str.h>
#include <std/collections/map.h>
#include <std/collections/vec.h>
#include <std/collections/string.h>

namespace std {

extern int printf(const char* fmt, ...);
extern void* malloc(unsigned long size);
extern void  free(void* ptr);
extern unsigned long strlen(const char* s);

static unsigned int __g4t_read_u32_le(&File f) {
    unsigned char buf[4];
    unsigned long got;
    unsafe { got = f.read(&buf[0], 4UL); }
    if (got != 4UL) { return 0xFFFFFFFFU; }
    unsigned int v;
    unsafe {
        v = (unsigned int)buf[0]
          | ((unsigned int)buf[1] << 8)
          | ((unsigned int)buf[2] << 16)
          | ((unsigned int)buf[3] << 24);
    }
    return v;
}

// Reads a length-prefixed byte string into a fresh heap NUL-terminated
// buffer (for use as a str_map key / String source) and reports its length.
static char* __g4t_read_lenstr(&File f, unsigned long* outLen) {
    unsigned int len = __g4t_read_u32_le(f);
    char* buf;
    unsigned long got;
    unsafe {
        buf = (char*)malloc((unsigned long)len + 1UL);
        got = f.read((unsigned char*)buf, (unsigned long)len);
        buf[len] = (char)0;
    }
    if (got != (unsigned long)len) {
        printf("gemma4_tokenizer: short read in length-prefixed string\n");
    }
    unsafe { *outLen = (unsigned long)len; }
    return buf;
}

static unsigned long long __g4t_pair_key(int leftId, int rightId) {
    unsigned long long l = (unsigned long long)(unsigned int)leftId;
    unsigned long long r = (unsigned long long)(unsigned int)rightId;
    return (l << 32) | r;
}

struct Gemma4Tokenizer gemma4_tok_load(const char* vocabBinPath, const char* mergesBinPath) {
    struct Gemma4Tokenizer tok;
    tok.vocabToId = StrMap::new();
    unsafe { tok.idToVocab = (struct String*)0; }
    tok.vocabSize = 0UL;
    tok.mergeRank = HashMap::new();
    tok.mergeCount = 0UL;
    tok.padId = 0; tok.eosId = 1; tok.bosId = 2; tok.unkId = 3;

    ?&File vfq;
    unsafe { vfq = File::open(vocabBinPath, "rb"); }
    if (vfq.is_null()) {
        printf("gemma4_tokenizer: could not open vocab file %s\n", vocabBinPath);
        return tok;
    }
    &File vf = expect_ref(vfq, "gemma4_tokenizer: vocab file");
    unsigned int vocabCount = __g4t_read_u32_le(vf);
    tok.vocabSize = (unsigned long)vocabCount;
    unsafe {
        tok.idToVocab = (struct String*)malloc(sizeof(struct String) * tok.vocabSize);
    }

    unsigned long i = 0UL;
    while (i < tok.vocabSize) {
        unsigned long len;
        char* s = __g4t_read_lenstr(vf, &len);
        unsafe { tok.idToVocab[i] = String::from(s); }
        int idVal = (int)i;
        unsafe { tok.vocabToId.insert(s, idVal); }
        unsafe { free((void*)s); }
        i = i + 1UL;
    }
    vf.close();

    ?&File mfq;
    unsafe { mfq = File::open(mergesBinPath, "rb"); }
    if (mfq.is_null()) {
        printf("gemma4_tokenizer: could not open merges file %s\n", mergesBinPath);
        return tok;
    }
    &File mf = expect_ref(mfq, "gemma4_tokenizer: merges file");
    unsigned int mergeCountRaw = __g4t_read_u32_le(mf);
    tok.mergeCount = (unsigned long)mergeCountRaw;

    unsigned long m = 0UL;
    while (m < tok.mergeCount) {
        unsigned long ll; unsigned long rl;
        char* ls = __g4t_read_lenstr(mf, &ll);
        char* rs = __g4t_read_lenstr(mf, &rl);

        unsafe {
            int* leftIdP = (int*)tok.vocabToId.get(ls);
            int* rightIdP = (int*)tok.vocabToId.get(rs);
            if (leftIdP != (int*)0 && rightIdP != (int*)0) {
                unsigned long long key = __g4t_pair_key(*leftIdP, *rightIdP);
                int rankVal = (int)m;
                // Only the FIRST (highest-priority) occurrence of a pair
                // should ever be recorded -- real merge lists shouldn't
                // repeat a pair, but a plain insert() would silently
                // overwrite with a later (lower-priority) rank if one did.
                if (!tok.mergeRank.contains(key)) {
                    tok.mergeRank.insert(key, rankVal);
                }
            }
            free((void*)ls);
            free((void*)rs);
        }
        m = m + 1UL;
    }
    mf.close();

    return tok;
}

void gemma4_tok_free(&Gemma4Tokenizer tok) {
    unsigned long i = 0UL;
    while (i < tok.vocabSize) {
        unsafe { tok.idToVocab[i].free(); }
        i = i + 1UL;
    }
    unsafe { free((void*)tok.idToVocab); }
    tok.vocabToId.free();
    tok.mergeRank.free();
}

static unsigned long __g4t_utf8_char_len(unsigned char c) {
    if ((c & 0x80U) == 0x00U) { return 1UL; }
    if ((c & 0xE0U) == 0xC0U) { return 2UL; }
    if ((c & 0xF0U) == 0xE0U) { return 3UL; }
    if ((c & 0xF8U) == 0xF0U) { return 4UL; }
    return 1UL; // invalid leading byte -- treat as one raw byte, byte-fallback will catch it
}

static struct String __g4t_byte_fallback_token(unsigned char b) {
    struct String s = String::from("<0x");
    char hex[3];
    const char* digits = "0123456789ABCDEF";
    unsafe {
        hex[0] = digits[(b >> 4) & 0x0FU];
        hex[1] = digits[b & 0x0FU];
        hex[2] = (char)0;
    }
    s.push(hex);
    s.push(">");
    return s;
}

// Looks up 'bytes' (length 'len', NOT necessarily NUL-terminated as given,
// so a temp NUL-terminated copy is made) in the vocab; returns -1 if absent.
static int __g4t_vocab_lookup(&Gemma4Tokenizer tok, const char* bytes, unsigned long len) {
    int result;
    unsafe {
        char* tmp = (char*)malloc(len + 1UL);
        unsigned long i = 0UL;
        while (i < len) { tmp[i] = bytes[i]; i = i + 1UL; }
        tmp[len] = (char)0;
        int* idP = (int*)tok.vocabToId.get(tmp);
        result = (idP != (int*)0) ? *idP : -1;
        free((void*)tmp);
    }
    return result;
}

struct Vec<int> gemma4_tok_encode(&Gemma4Tokenizer tok, const char* text) {
    // 1. Normalize: literal ' ' -> the 3-byte UTF-8 encoding of ▁ (U+2581:
    // 0xE2 0x96 0x81). Pre-tokenizer step is a no-op given this (see header
    // comment), so no further splitting happens here.
    struct String normalized = String::new();
    unsigned long tlen;
    unsafe { tlen = (unsigned long)strlen(text); }
    unsigned long ti = 0UL;
    while (ti < tlen) {
        char c;
        unsafe { c = text[ti]; }
        if (c == ' ') {
            unsafe { normalized.push_n("\xe2\x96\x81", 3UL); }
        } else {
            normalized.push_char(c);
        }
        ti = ti + 1UL;
    }

    // 2. Segment into initial symbols: one per UTF-8 character if that
    // character is a direct vocab entry, else per-byte via <0xXX> fallback.
    struct Vec<int> symbols = Vec::new();
    const char* nb = normalized.as_ptr();
    unsigned long nlen = normalized.length();
    unsigned long pos = 0UL;
    while (pos < nlen) {
        unsigned char lead;
        unsafe { lead = (unsigned char)nb[pos]; }
        unsigned long clen = __g4t_utf8_char_len(lead);
        if (pos + clen > nlen) { clen = nlen - pos; } // truncated tail, be safe
        const char* chStart;
        unsafe { chStart = nb + pos; }
        int id = __g4t_vocab_lookup(tok, chStart, clen);
        if (id >= 0) {
            symbols.push(id);
        } else {
            unsigned long bi = 0UL;
            while (bi < clen) {
                unsigned char byteVal;
                unsafe { byteVal = (unsigned char)nb[pos + bi]; }
                struct String fbTok = __g4t_byte_fallback_token(byteVal);
                int fbId = __g4t_vocab_lookup(tok, fbTok.as_ptr(), fbTok.length());
                fbTok.free();
                if (fbId < 0) { fbId = tok.unkId; }
                symbols.push(fbId);
                bi = bi + 1UL;
            }
        }
        pos = pos + clen;
    }
    normalized.free();

    // 3. Greedy BPE merge: each round, merge every non-overlapping
    // occurrence of whichever adjacent pair has the single best (lowest)
    // rank anywhere in the sequence; repeat until no pair in the current
    // sequence has a recorded rank at all.
    while (1) {
        unsigned long n = symbols.length();
        if (n < 2UL) { break; }

        int bestRank = -1;
        unsigned long bestPos = 0UL;
        int bestLeft = 0; int bestRight = 0;
        unsigned long i = 0UL;
        while (i < n - 1UL) {
            int leftId = *symbols.at(i);
            int rightId = *symbols.at(i + 1UL);
            unsigned long long key = __g4t_pair_key(leftId, rightId);
            unsafe {
                int* rankP = (int*)tok.mergeRank.get(key);
                if (rankP != (int*)0) {
                    if (bestRank < 0 || *rankP < bestRank) {
                        bestRank = *rankP;
                        bestPos = i;
                        bestLeft = leftId;
                        bestRight = rightId;
                    }
                }
            }
            i = i + 1UL;
        }
        if (bestRank < 0) { break; }

        struct String mergedStr = String::new();
        unsafe {
            mergedStr.push(tok.idToVocab[bestLeft].as_ptr());
            mergedStr.push(tok.idToVocab[bestRight].as_ptr());
        }
        int mergedId = __g4t_vocab_lookup(tok, mergedStr.as_ptr(), mergedStr.length());
        mergedStr.free();
        if (mergedId < 0) {
            // Shouldn't happen for a real, self-consistent merge list (the
            // concatenation of any valid merge pair is guaranteed to be a
            // vocab entry by construction) -- if it does, stop merging
            // rather than loop forever or corrupt the sequence.
            printf("gemma4_tokenizer: merge result not in vocab (left=%d right=%d) -- stopping\n",
                   bestLeft, bestRight);
            break;
        }

        struct Vec<int> next = Vec::new();
        unsigned long j = 0UL;
        while (j < n) {
            if (j == bestPos) {
                next.push(mergedId);
                j = j + 2UL;
            } else {
                next.push(*symbols.at(j));
                j = j + 1UL;
            }
        }
        symbols.free();
        symbols = next;
    }

    return symbols;
}

struct String gemma4_tok_decode(&Gemma4Tokenizer tok, const int* ids, unsigned long count) {
    // Fuse every piece's text first (raw, still containing ▁ markers and
    // any <0xXX> byte-fallback placeholders), then do the two text-level
    // fixups in a single pass over the fused bytes.
    struct String fused = String::new();
    unsigned long i = 0UL;
    while (i < count) {
        int id;
        unsafe { id = ids[i]; }
        if (id >= 0 && (unsigned long)id < tok.vocabSize) {
            unsafe { fused.push(tok.idToVocab[id].as_ptr()); }
        }
        i = i + 1UL;
    }

    struct String out = String::new();
    const char* fb = fused.as_ptr();
    unsigned long flen = fused.length();
    unsigned long p = 0UL;
    while (p < flen) {
        int handled = 0;
        unsafe {
            // ▁ (0xE2 0x96 0x81) -> space
            if (p + 3UL <= flen
                && (unsigned char)fb[p] == 0xE2U
                && (unsigned char)fb[p+1UL] == 0x96U
                && (unsigned char)fb[p+2UL] == 0x81U) {
                out.push_char(' ');
                p = p + 3UL;
                handled = 1;
            }
        }
        if (handled) { continue; }
        unsafe {
            // <0xXX> -> raw byte
            if (p + 5UL <= flen && fb[p] == '<' && fb[p+1UL] == '0' && fb[p+2UL] == 'x'
                && fb[p+5UL - 1UL] == '>') {
                char hi = fb[p+3UL]; char lo = fb[p+4UL];
                int hiV = (hi >= '0' && hi <= '9') ? (int)(hi - '0') : (int)(hi - 'A' + 10);
                int loV = (lo >= '0' && lo <= '9') ? (int)(lo - '0') : (int)(lo - 'A' + 10);
                if (hiV >= 0 && hiV <= 15 && loV >= 0 && loV <= 15) {
                    unsigned char rawByte = (unsigned char)((hiV << 4) | loV);
                    out.push_char((char)rawByte);
                    p = p + 5UL;
                    handled = 1;
                }
            }
        }
        if (handled) { continue; }
        unsafe { out.push_char(fb[p]); }
        p = p + 1UL;
    }
    fused.free();
    return out;
}

} // namespace std
