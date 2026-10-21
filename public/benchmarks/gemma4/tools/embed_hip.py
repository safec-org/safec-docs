"""Embeds src/gemma4_rocm_kernels.hip into src/gemma4_rocm_kernels.h as a C
string for hiprtc (gemma4_rocm.sc compiles it at run time).

usage: embed_hip.py   (run from anywhere)
"""
import os

here = os.path.dirname(os.path.abspath(__file__))
src = os.path.join(here, "..", "src", "gemma4_rocm_kernels.hip")
dst = os.path.join(here, "..", "src", "gemma4_rocm_kernels.h")
lines = open(src).read().splitlines()
out = ["#pragma once",
       "// Generated from gemma4_rocm_kernels.hip by tools/embed_hip.py -- edit the .hip file.",
       "namespace std {",
       "static const char* gemma4_rocm_kernel_source_() {",
       "    return"]
for ln in lines:
    out.append('    "' + ln.replace("\\", "\\\\").replace('"', '\\"') + '\\n"')
out[-1] += ";"
out += ["}", "} // namespace std", ""]
open(dst, "w").write("\n".join(out))
print(f"{len(lines)} lines -> {dst}")
