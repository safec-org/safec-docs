#pragma once
// SafeC Standard Library — Gemma 4 tokenizer (BPE, byte-fallback).
//
// NOT a general-purpose BPE tokenizer -- written against the actual
// google/gemma-4-E2B tokenizer.json (fetched and inspected directly, not
// assumed from earlier Gemma generations, which used SentencePiece-Unigram
// instead). Rules this implements:
//   normalizer:    every literal ' ' (space) -> '\xe2\x96\x81' (U+2581 "▁")
//   pre_tokenizer: effectively a no-op given the normalizer above already
//                  removed every literal space it would have split on
//   model:         BPE, byte_fallback=true (256 '<0xXX>' vocab entries cover
//                  every possible byte, so encoding never truly fails),
//                  fuse_unk=true, no continuing_subword_prefix/end_of_word_suffix
//   decoder:       Sequence[Replace('▁'->' '), ByteFallback, Fuse]
//
// Loads from a preprocessed binary format (see
// safec-docs/public/benchmarks/gemma4/{vocab,merges}.bin and the Python
// script that generated them from the real tokenizer.json) rather than
// parsing the 32MB source JSON directly -- vocab/merge entries can contain
// literal tabs/newlines (real entries in this vocab: runs of repeated
// whitespace characters), so this uses a length-prefixed binary format, not
// a delimited text one:
//   vocab.bin:  [u32 count] { [u32 byteLen][byteLen bytes UTF-8] }*count, in id order
//   merges.bin: [u32 count] { [u32 leftLen][left bytes][u32 rightLen][right bytes] }*count,
//               in descending merge-priority order (earlier = higher priority)
#include <std/collections/map.h>
#include <std/collections/vec.h>
#include <std/collections/string.h>

namespace std {

struct Gemma4Tokenizer {
    struct StrMap<int> vocabToId; // key=token bytes (NUL-terminated copy), val=int id
    struct String* idToVocab;    // heap array[vocabSize], indexed by id
    unsigned long  vocabSize;
    struct HashMap<unsigned long long, int> mergeRank;
                                 // key = (leftId:u32 << 32 | rightId:u32) packed
                                 // into unsigned long long, val = int rank
                                 // (lower = higher priority)
    unsigned long  mergeCount;

    int unkId;
    int bosId;
    int eosId;
    int padId;
};

// Loads vocab.bin + merges.bin (see file header for the format) and fills in
// the special-token ids (hardcoded to this checkpoint's real values,
// confirmed against config.json: pad=0, eos=1, bos=2, unk=3).
struct Gemma4Tokenizer gemma4_tok_load(const char* vocabBinPath, const char* mergesBinPath);

// Encodes UTF-8 'text' into a sequence of token ids (Vec<int>, caller frees
// with .free()). Applies the space->▁ normalization internally -- pass raw
// text, not pre-normalized.
struct Vec<int> gemma4_tok_encode(&Gemma4Tokenizer tok, const char* text);

// Decodes 'count' token ids back into UTF-8 text (▁->space, byte-fallback
// tokens reassembled into their original bytes, adjacent pieces fused).
struct String gemma4_tok_decode(&Gemma4Tokenizer tok, const int* ids, unsigned long count);

void gemma4_tok_free(&Gemma4Tokenizer tok);

} // namespace std
