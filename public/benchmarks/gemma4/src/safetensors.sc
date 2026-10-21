// SafeC Standard Library — safetensors weight file reader implementation
// (see safetensors.h).
#pragma once
#include "safetensors.h"
#include <std/ml/float16.h>
#include <std/serial/json.h>
#include <std/io_file.h>
#include <std/mem.h>
#include <std/str.h>

namespace std {

extern int printf(const char* fmt, ...);

static unsigned long long __st_read_u64_le(&File f) {
    unsigned char buf[8];
    unsigned long got;
    unsafe { got = f.read(&buf[0], 8UL); }
    if (got != 8UL) { return 0xFFFFFFFFFFFFFFFFULL; }
    unsigned long long v = 0ULL;
    int i = 7;
    while (i >= 0) {
        unsafe { v = (v << 8) | (unsigned long long)buf[i]; }
        i = i - 1;
    }
    return v;
}

long SafetensorsFile::find(const char* name) const {
    unsigned long i = 0UL;
    while (i < self.entries.length()) {
        &SafetensorsEntry e = self.entries.at(i);
        unsafe {
            if (str_eq(e.name.as_ptr(), name)) { return (long)i; }
        }
        i = i + 1UL;
    }
    return -1L;
}

FloatTensor* SafetensorsFile::load(const char* name) {
    long idx = self.find(name);
    if (idx < 0L) {
        printf("safetensors: tensor not found: %s\n", name);
        return (FloatTensor*)0;
    }
    &File f = expect_ref(self.handle, "safetensors: load on a closed file");
    struct SafetensorsEntry* e;
    unsigned long byteLen;
    unsafe {
        e = (struct SafetensorsEntry*)self.entries.at((unsigned long)idx);
        byteLen = e->dataOffsetEnd - e->dataOffsetStart;
        f.seek((long long)(self.dataBaseOffset + e->dataOffsetStart), 0);
    }
    unsigned char* raw;
    unsafe { raw = (unsigned char*)malloc(byteLen); }
    unsigned long got;
    unsafe { got = f.read(raw, byteLen); }
    if (got != byteLen) {
        printf("safetensors: short read for %s (wanted %lu, got %lu)\n", name, byteLen, got);
        unsafe { free((void*)raw); }
        return (FloatTensor*)0;
    }

    FloatTensor* out;
    int isF32; int isBF16;
    unsafe {
        out = __tensor_alloc((const unsigned long*)&e->shape[0], e->ndim, 0);
        isF32 = str_eq(e->dtype.as_ptr(), "F32");
        isBF16 = str_eq(e->dtype.as_ptr(), "BF16");
    }

    if (isF32) {
        unsafe {
            if (byteLen != out->size * 4UL) {
                printf("safetensors: F32 size mismatch for %s\n", name);
            }
            unsigned long b = 0UL;
            while (b < out->size) {
                ((float*)out->data)[b] = ((float*)raw)[b];
                b = b + 1UL;
            }
        }
    } else if (isBF16) {
        unsafe {
            bf16_to_f32_bulk((const unsigned short*)raw, (float*)out->data, out->size);
        }
    } else {
        unsafe {
            printf("safetensors: unsupported dtype '%s' for %s (only F32/BF16 handled)\n",
                   e->dtype.as_ptr(), name);
            free((void*)raw);
            out->free();
        }
        return (FloatTensor*)0;
    }

    unsafe { free((void*)raw); }
    return out;
}

static struct Gemma4Bf16Tensor __st_empty_bf16() {
    struct Gemma4Bf16Tensor empty;
    unsafe { empty.data = (unsigned short*)0; }
    empty.shape0 = 0UL; empty.shape1 = 0UL; empty.ndim = 0UL; empty.size = 0UL;
    return empty;
}

struct Gemma4Bf16Tensor SafetensorsFile::load_bf16(const char* name) {
    long idx = self.find(name);
    if (idx < 0L) {
        printf("safetensors: tensor not found: %s\n", name);
        return __st_empty_bf16();
    }
    &File f = expect_ref(self.handle, "safetensors: load_bf16 on a closed file");
    struct SafetensorsEntry* e;
    unsigned long byteLen;
    unsigned long ndim; unsigned long s0; unsigned long s1;
    unsafe {
        e = (struct SafetensorsEntry*)self.entries.at((unsigned long)idx);
        byteLen = e->dataOffsetEnd - e->dataOffsetStart;
        ndim = e->ndim;
        s0 = e->shape[0];
        if (ndim > 1UL) { s1 = e->shape[1]; } else { s1 = 0UL; }
        f.seek((long long)(self.dataBaseOffset + e->dataOffsetStart), 0);
    }
    unsigned char* raw;
    unsafe { raw = (unsigned char*)malloc(byteLen); }
    unsigned long got;
    unsafe { got = f.read(raw, byteLen); }
    if (got != byteLen) {
        printf("safetensors: short read for %s (wanted %lu, got %lu)\n", name, byteLen, got);
        unsafe { free((void*)raw); }
        return __st_empty_bf16();
    }

    int isF32; int isBF16;
    unsafe {
        isF32 = str_eq(e->dtype.as_ptr(), "F32");
        isBF16 = str_eq(e->dtype.as_ptr(), "BF16");
    }

    struct Gemma4Bf16Tensor out;
    if (ndim > 1UL) { out = gemma4_bf16_new_2d(s0, s1); } else { out = gemma4_bf16_new_1d(s0); }

    if (isBF16) {
        // Already bf16 on disk -- raw byte copy, no conversion at all
        // (the whole point: zero precision loss beyond the checkpoint's
        // own, and no transient F32 buffer ever materializes).
        unsafe {
            if (byteLen != out.size * 2UL) {
                printf("safetensors: BF16 size mismatch for %s\n", name);
            }
            unsigned long b = 0UL;
            while (b < out.size) {
                out.data[b] = ((unsigned short*)raw)[b];
                b = b + 1UL;
            }
        }
    } else if (isF32) {
        unsafe {
            if (byteLen != out.size * 4UL) {
                printf("safetensors: F32 size mismatch for %s\n", name);
            }
            f32_to_bf16_bulk((const float*)raw, out.data, out.size);
        }
    } else {
        unsafe {
            printf("safetensors: unsupported dtype '%s' for %s (only F32/BF16 handled)\n",
                   e->dtype.as_ptr(), name);
            free((void*)raw);
        }
        gemma4_bf16_free(&out);
        return __st_empty_bf16();
    }

    unsafe { free((void*)raw); }
    return out;
}

void SafetensorsFile::close_() {
    match (self.handle) {
        case some(f): f.close();
        case null: { }
    }
    self.handle = null;
    unsigned long i = 0UL;
    while (i < self.entries.length()) {
        &SafetensorsEntry e = self.entries.at(i);
        e.name.free();
        e.dtype.free();
        i = i + 1UL;
    }
    self.entries.free();
}

struct SafetensorsFile safetensors_open(const char* path) {
    struct SafetensorsFile st;
    st.handle = null;
    st.dataBaseOffset = 0UL;
    st.entries = Vec::new();

    ?&File fq;
    unsafe { fq = File::open(path, "rb"); }
    if (fq.is_null()) {
        printf("safetensors: could not open %s\n", path);
        return st;
    }
    &File f = expect_ref(fq, "safetensors: file handle");

    unsigned long long headerLen = __st_read_u64_le(f);
    if (headerLen == 0xFFFFFFFFFFFFFFFFULL || headerLen == 0ULL) {
        printf("safetensors: bad header length in %s\n", path);
        f.close();
        return st;
    }

    char* headerBuf;
    unsafe { headerBuf = (char*)malloc((unsigned long)headerLen + 1UL); }
    unsigned long got;
    unsafe { got = f.read((unsigned char*)headerBuf, (unsigned long)headerLen); }
    if (got != (unsigned long)headerLen) {
        printf("safetensors: short header read in %s\n", path);
        unsafe { free((void*)headerBuf); }
        f.close();
        return st;
    }
    unsafe { headerBuf[headerLen] = (char)0; }

    int ok = 0;
    struct Value header;
    unsafe { header = json_parse(headerBuf, &ok); }
    unsafe { free((void*)headerBuf); }
    if (!ok) {
        printf("safetensors: header JSON parse failed in %s\n", path);
        f.close();
        return st;
    }

    unsigned long i = 0UL;
    while (i < header.obj_val.length()) {
        unsafe {
            struct ObjectEntry* oe = (struct ObjectEntry*)header.obj_val.at(i);
            const char* key = (const char*)oe->key;
            if (key != (const char*)0 && str_eq(key, "__metadata__") == 0) {
                struct SafetensorsEntry se;
                se.name = String::from(key);

                struct Value* dtypeV = oe->val.object_get("dtype");
                se.dtype = String::from(dtypeV->as_string());

                struct Value* shapeV = oe->val.object_get("shape");
                unsigned long nd = shapeV->array_len();
                if (nd > 8UL) {
                    printf("safetensors: tensor '%s' has rank %lu > 8, truncating\n", key, nd);
                    nd = 8UL;
                }
                se.ndim = nd;
                unsigned long d = 0UL;
                while (d < nd) {
                    &Value elemV = shapeV->array_at(d);
                    se.shape[d] = (unsigned long)elemV.as_int();
                    d = d + 1UL;
                }

                struct Value* offV = oe->val.object_get("data_offsets");
                &Value off0 = offV->array_at(0UL);
                &Value off1 = offV->array_at(1UL);
                se.dataOffsetStart = (unsigned long)off0.as_int();
                se.dataOffsetEnd   = (unsigned long)off1.as_int();

                st.entries.push(se);
            }
        }
        i = i + 1UL;
    }
    header.free();

    st.handle = fq;
    st.dataBaseOffset = 8UL + (unsigned long)headerLen;
    return st;
}

} // namespace std
