#!/usr/bin/env python3
"""Runs the SafeC benchmark suite on this machine and writes results/<name>.json.

usage: run_bench.py --name <machine-id> [--only fib,nbody,...] [--runs 3]

Every program is built and run here; timings are the best (lowest) wall
time of --runs runs, peak memory is the max RSS of that best run, and
compile time is the release build's wall time. Tools are found on PATH or
through these environment variables:

  SAFEC_HOME   SafeC checkout (std/ and the built compiler)       [required]
  SAFEC SAFEGUARD CLANG CLANGXX RUSTC CARGO GO ZIG PYTHON AB
  MLPY         python with torch / tensorflow / numpy (ML + NumPy rows)
  WEBPY        python with fastapi + uvicorn (web row)
  BLAS_LIBS    linker flags for a CBLAS library on non-macOS hosts
               (e.g. "-L/opt/openblas/lib -lopenblas")
  ROCM_PATH    ROCm install for the AMD GPU rows (default /opt/rocm)
  CUDA_PATH    CUDA toolkit for the NVIDIA GPU rows (default /usr/local/cuda)
"""
import argparse
import glob, json, os, platform, re, shutil, signal, socket, subprocess, sys, time

HERE = os.path.dirname(os.path.abspath(__file__))
IS_MAC = platform.system() == "Darwin"
ARCH = platform.machine().lower()


def tool(var, default):
    return os.environ.get(var) or shutil.which(default) or default


SAFEC_HOME = os.environ.get("SAFEC_HOME") or sys.exit("set SAFEC_HOME to the SafeC checkout")
SAFEC = tool("SAFEC", "safec")
SAFEGUARD = tool("SAFEGUARD", "safeguard")
CLANG, CLANGXX = tool("CLANG", "clang"), tool("CLANGXX", "clang++")
RUSTC, CARGO, GO, ZIG = tool("RUSTC", "rustc"), tool("CARGO", "cargo"), tool("GO", "go"), tool("ZIG", "zig")
PYTHON = tool("PYTHON", "python3")
MLPY = os.environ.get("MLPY") or PYTHON
WEBPY = os.environ.get("WEBPY") or MLPY
AB = tool("AB", "ab")
ROCM = os.environ.get("ROCM_PATH", "/opt/rocm")
CUDA = os.environ.get("CUDA_PATH", "/usr/local/cuda")
BLAS = (os.environ.get("BLAS_LIBS", "-lopenblas").split() if not IS_MAC else ["-framework", "Accelerate"])
BUILD = os.path.join(HERE, "build")


# ── process helpers ───────────────────────────────────────────────────────────

def sh(cmd, cwd=None, env=None, check=True, capture=True):
    r = subprocess.run(cmd, cwd=cwd, env=env, text=True,
                       stdout=subprocess.PIPE if capture else None,
                       stderr=subprocess.STDOUT if capture else None)
    if check and r.returncode != 0:
        raise RuntimeError(f"command failed ({r.returncode}): {' '.join(cmd)}\n{r.stdout}")
    return r.stdout or ""


def run_once(cmd, cwd=None, env=None, timeout=600):
    """(wall seconds, peak RSS in MB, stdout) for one run."""
    t0 = time.perf_counter()
    p = subprocess.Popen(cmd, cwd=cwd, env=env, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    out, _ = p.communicate(timeout=timeout)  # reaps via waitpid; rusage below
    wall = time.perf_counter() - t0
    if p.returncode != 0:
        raise RuntimeError(f"{' '.join(cmd)} exited {p.returncode}\n{out[-2000:]}")
    return wall, out


def measure(cmd, runs, cwd=None, env=None, timeout=600):
    """Best wall time over `runs` runs, peak RSS (MB) of that run, its output.

    Peak RSS comes from /usr/bin/time (GNU '%M' KB on Linux, '-l' bytes on
    macOS) rather than this process's wait4(): Linux keeps ru_maxrss across
    exec, so a child forked from Python would report Python's own footprint.
    """
    best = None
    for _ in range(runs):
        timer = ["/usr/bin/time", "-l"] if IS_MAC else ["/usr/bin/time", "-f", "@@RSS %M"]
        t0 = time.perf_counter()
        r = subprocess.run(timer + cmd, cwd=cwd, env=env, text=True, timeout=timeout,
                           stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        wall = time.perf_counter() - t0
        if r.returncode != 0:
            raise RuntimeError(f"{' '.join(cmd)} exited {r.returncode}\n{(r.stdout + r.stderr)[-2000:]}")
        if IS_MAC:
            m = re.search(r"(\d+)\s+maximum resident set size", r.stderr)
            rss_mb = int(m.group(1)) / (1024 * 1024) if m else None
        else:
            m = re.search(r"@@RSS (\d+)", r.stderr)
            rss_mb = int(m.group(1)) / 1024 if m else None
        if best is None or wall < best[0]:
            best = (wall, rss_mb, r.stdout)
    return best


def timed_build(cmds, cwd=None):
    t0 = time.perf_counter()
    for c in cmds:
        sh(c, cwd=cwd)
    return time.perf_counter() - t0


# ── SafeC toolchain ───────────────────────────────────────────────────────────

_std_archive = None

def std_archive():
    """Builds (once) the release std archive through safeguard."""
    global _std_archive
    if _std_archive:
        return _std_archive
    proj = os.path.join(BUILD, "_std")
    os.makedirs(os.path.join(proj, "src"), exist_ok=True)
    with open(os.path.join(proj, "Package.toml"), "w") as f:
        f.write('[package]\nname = "bench_std"\nversion = "0.1.0"\n\n[build]\nedition = "2026"\n')
    with open(os.path.join(proj, "src", "main.sc"), "w") as f:
        f.write("int main() { return 0; }\n")
    env = dict(os.environ, SAFEC_HOME=SAFEC_HOME)
    sh([SAFEGUARD, "build", "--release"], cwd=proj, env=env)
    _std_archive = os.path.join(proj, "build", "deps", "libsafec_std.a")
    return _std_archive


def _shared_lib(d, name):
    """Path to lib<name>.so (or the highest lib<name>.so.N) in d, else None."""
    if not d or not os.path.isdir(d):
        return None
    plain = os.path.join(d, f"lib{name}.so")
    if os.path.exists(plain):
        return plain
    hits = sorted(f for f in os.listdir(d) if f.startswith(f"lib{name}.so."))
    return os.path.join(d, hits[-1]) if hits else None


def vendor_blas():
    """{label: link flags} for the CPU vendor BLAS libraries found through
    AOCL_ROOT / MKLROOT -- the same link lines safeguard's [build] blas uses.
    std's sgemm picks cblas_sgemm up through a weak reference."""
    out = {}
    aocl = os.environ.get("AOCL_ROOT")
    for d in ([aocl, os.path.join(aocl, "lib"), os.path.join(aocl, "lib_LP64")] if aocl else []):
        lib = _shared_lib(d, "blis-mt")
        if lib:
            out["aocl"] = [f"-Wl,-rpath,{d}", "-Wl,--no-as-needed", lib, "-lgomp"]
            break
    mkl = os.environ.get("MKLROOT")
    for d in ([mkl, os.path.join(mkl, "lib"), os.path.join(mkl, "lib", "intel64")] if mkl else []):
        libs = [_shared_lib(d, n) for n in ("mkl_intel_lp64", "mkl_gnu_thread", "mkl_core")]
        if all(libs):
            out["mkl"] = [f"-Wl,-rpath,{d}", "-Wl,--no-as-needed"] + libs + ["-lgomp"]
            break
    return out


def safec_cmds(src, out, release, extra_libs=(), defines=()):
    ll = out + ".bc"
    # --no-incremental: time a real compile, not a hit in safec's bitcode cache
    c1 = [SAFEC, src, "-I", SAFEC_HOME, "--no-incremental", "-o", ll] + (["-O2"] if release else []) + [f"-D{d}" for d in defines]
    c2 = [CLANG, "-w", "-O2" if release else "-O0", ll, std_archive(), "-o", out, "-lm", "-lpthread"] + list(extra_libs)
    if IS_MAC:
        c2 += ["-framework", "Accelerate"]   # std's sgemm uses it (a weak reference, resolved at link time)
    return [c1, c2]


# ── languages for the classic benchmarks ──────────────────────────────────────

def lang_builds(name, src, out, release):
    """Build commands for one source file."""
    if name == "safec":
        return safec_cmds(src, out, release)
    if name == "c":
        return [[CLANG, "-O2" if release else "-O0", src, "-o", out, "-lm", "-lpthread"]]
    if name == "cpp":
        return [[CLANGXX, "-std=c++17", "-O2" if release else "-O0", src, "-o", out, "-lpthread"]]
    if name == "rust":
        return [[RUSTC, src, "-o", out] + (["-O"] if release else [])]
    if name == "zig":
        cmd = [ZIG, "build-exe", "-O", "ReleaseFast" if release else "Debug", src, f"-femit-bin={out}"]
        if "binarytrees" in src:
            cmd.append("-lc")
        return [cmd]
    if name == "go":
        return [[GO, "build", "-o", out] + ([] if release else ["-gcflags=all=-N -l"]) + [src]]
    return []


def run_cmd(name, src, out):
    return [PYTHON, src] if name == "python" else [out]


LANGS = ["safec", "c", "cpp", "rust", "zig", "go", "python"]
EXT = {"safec": "sc", "c": "c", "cpp": "cpp", "rust": "rs", "zig": "zig", "go": "go", "python": "py"}


def classic(bench, runs, langs=LANGS, src_names=None, debug=True):
    """Debug/release time, release RSS and compile time per language."""
    res = {}
    for lang in langs:
        stem = (src_names or {}).get(lang, bench)
        src = os.path.join(HERE, bench, lang if lang != "safec_arena" else "safec", f"{stem}.{EXT.get(lang, 'sc')}")
        if not os.path.exists(src):
            continue
        row = {}
        try:
            for mode in (["debug", "release"] if debug else ["release"]):
                rel = mode == "release"
                out = os.path.join(BUILD, bench, f"{lang}_{stem}_{mode}")
                os.makedirs(os.path.dirname(out), exist_ok=True)
                cmds = lang_builds(lang, src, out, rel)
                ct = timed_build(cmds)
                wall, rss, _ = measure(run_cmd(lang, src, out), runs)
                row[mode] = round(wall, 4)
                if rel:
                    row["rss_mb"] = round(rss, 1)
                    row["compile_s"] = round(ct, 3) if cmds else None
        except Exception as e:  # keep going; report what failed
            row["error"] = str(e).splitlines()[0][:200]
        res[lang] = row
        print(f"  {bench:14s} {lang:6s} {row}", flush=True)
    return res


def bench_binarytrees(runs):
    r = classic("binarytrees", runs, src_names={"safec": "binarytrees_arena"})
    heap = classic("binarytrees", runs, langs=["safec"], src_names={"safec": "binarytrees"})
    r["safec_heap"] = heap.get("safec", {})
    return r


def bench_mt(runs):
    r = classic("binarytrees_mt", runs, debug=False, src_names={"safec": "binarytrees_mt_arena"})
    heap = classic("binarytrees_mt", runs, langs=["safec"], debug=False, src_names={"safec": "binarytrees_mt"})
    r["safec_heap"] = heap.get("safec", {})
    return r


def bench_simd(runs):
    res = {}
    for lang in ["safec", "c", "cpp", "rust", "zig", "go", "python"]:
        row = {}
        for kind in ["scalar", "vec"]:
            stem = f"simd_{kind}"
            if lang == "rust" and kind == "vec" and ARCH in ("x86_64", "amd64"):
                stem = "simd_vec_x86_64"
            if lang == "python" and kind == "vec":
                stem = "simd_numpy"
            src = os.path.join(HERE, "simd", lang, f"{stem}.{EXT[lang]}")
            if not os.path.exists(src):
                continue
            try:
                out = os.path.join(BUILD, "simd", f"{lang}_{stem}")
                os.makedirs(os.path.dirname(out), exist_ok=True)
                timed_build(lang_builds(lang, src, out, True))
                cmd = [MLPY if stem == "simd_numpy" else PYTHON, src] if lang == "python" else [out]
                wall, _, _ = measure(cmd, runs)
                row[kind] = round(wall, 4)
            except Exception as e:
                row[kind + "_error"] = str(e).splitlines()[0][:200]
        res[lang] = row
        print(f"  simd {lang:6s} {row}", flush=True)
    return res


def bench_collections(runs):
    res = {}
    for lang, src in [("safec", "collections/safec/bench_collections.sc"),
                      ("c", "collections/c/bench_collections.c")]:
        out = os.path.join(BUILD, "collections", lang)
        os.makedirs(os.path.dirname(out), exist_ok=True)
        timed_build(lang_builds(lang, os.path.join(HERE, src), out, True))
        best = {}
        for _ in range(runs):
            for line in sh([out]).splitlines():
                m = re.match(r"(.+?)\s+([\d.]+) ms", line)
                if m:
                    k = m.group(1).strip()
                    best[k] = min(best.get(k, 1e18), float(m.group(2)))
        res[lang] = best
        print(f"  collections {lang} {best}", flush=True)
    return res


def bench_memalloc(runs):
    out = os.path.join(BUILD, "memalloc", "alloc_bench")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    timed_build(safec_cmds(os.path.join(HERE, "memalloc/safec/alloc_bench.sc"), out, True))
    best = {}
    for _ in range(runs):
        for k, v in re.findall(r"(\w+)=([\d.]+)", sh([out])):
            best[k] = min(best.get(k, 1e18), float(v))
    print(f"  memalloc {best}", flush=True)
    return best


# ── ML ────────────────────────────────────────────────────────────────────────

def parse_ml(out):
    d = {}
    for m in re.finditer(r"(?:\[(\w+)\]\s*)?(train_ms|last_loss|inference_ms_per_\d+)=(-?[\d.]+)", out):
        d[(m.group(1) or "") + ":" + m.group(2)] = float(m.group(3))
    return d


def bench_ml(runs):
    res = {}
    cases = [  # (key, source, extra link libs, needs)
        ("small/safec_cpu", "ml/safec/train.sc", [], None),
        ("small/safec_blas", "ml/safec/train_blas.sc", BLAS, None),
        ("big/safec_cpu", "ml_big/safec/train_cpu.sc", [], None),
        ("big/safec_blas", "ml_big/safec/train_cpu_blas.sc", BLAS, None),
    ]
    for vendor, flags in vendor_blas().items():   # same sources, sgemm on the vendor's kernels
        cases += [(f"small/safec_cpu_{vendor}", "ml/safec/train.sc", flags, None),
                  (f"big/safec_cpu_{vendor}", "ml_big/safec/train_cpu.sc", flags, None)]
    if os.path.exists(os.path.join(ROCM, "lib", "libamdhip64.so")):
        rocm = [f"-L{ROCM}/lib", f"-Wl,-rpath,{ROCM}/lib", "-lamdhip64", "-lhiprtc", "-lrocblas"]
        cases.append(("rocm/safec", "ml/safec/train_rocm.sc", rocm, None))
    if os.path.exists(os.path.join(CUDA, "lib64", "libnvrtc.so")) and shutil.which("nvidia-smi"):
        cuda = [f"-L{CUDA}/lib64", f"-Wl,-rpath,{CUDA}/lib64", "-lcuda", "-lcublas", "-lcublasLt", "-lnvrtc"]
        cases.append(("cuda/safec", "ml/safec/train_cuda.sc", cuda, None))
    if IS_MAC:
        cases += [("small/safec_mps", "ml/safec/train_gpu_small.sc", ["-framework", "Metal", "-framework", "Foundation"], None),
                  ("big/safec_mps", "ml_big/safec/train_gpu.sc", ["-framework", "Metal", "-framework", "Foundation"], None)]
    for key, src, libs, _ in cases:
        out = os.path.join(BUILD, "ml", key.replace("/", "_"))
        os.makedirs(os.path.dirname(out), exist_ok=True)
        try:
            timed_build(safec_cmds(os.path.join(HERE, src), out, True, extra_libs=libs))
            runs_out = [sh([out]) for _ in range(runs)]
            if key in ("rocm/safec", "cuda/safec"):
                # one line per shape x mode: keep the best train/inference per line label
                best = {}
                for o in runs_out:
                    for line in o.splitlines():
                        m = re.match(r"\[(.+?)\] (.*)", line)
                        if not m or m.group(1).startswith("warmup"):
                            continue
                        vals = dict((k, float(v)) for k, v in re.findall(r"(\w+)=(-?[\d.]+)", m.group(2)))
                        cur = best.setdefault(m.group(1), vals)
                        for k in ("train_ms",) + tuple(k for k in vals if k.startswith("inference_ms")):
                            if k in vals:
                                cur[k] = min(cur.get(k, 1e18), vals[k])
                res[key] = best
            else:
                parsed = [parse_ml(o) for o in runs_out]
                res[key] = {k: min(p[k] for p in parsed) if "ms" in k else parsed[0][k] for k in parsed[0]}
        except Exception as e:
            res[key] = {"error": str(e).splitlines()[0][:200]}
        print(f"  ml {key} {res[key]}", flush=True)
    devices = ["cpu"]
    try:
        info = sh([MLPY, "-c", "import torch;print(torch.cuda.is_available(), torch.backends.mps.is_available())"]).split()
        if info[0] == "True": devices.append("cuda")
        if info[1] == "True": devices.append("mps")
    except Exception:
        devices = []
    for shape, script in [("small", "ml/pytorch/train.py"), ("big", "ml_big/pytorch/train.py")]:
        # fp16/bf16 rows on CUDA (PyTorch-ROCm reports itself as "cuda" too)
        for dev, dt in [(d, "fp32") for d in devices] + [("cuda", t) for t in ("fp16", "bf16") if "cuda" in devices]:
            key = f"{shape}/pytorch_{dev}" + ("" if dt == "fp32" else f"_{dt}")
            try:
                parsed = [parse_ml(sh([MLPY, os.path.join(HERE, script), dev, dt])) for _ in range(runs)]
                res[key] = {k: min(p[k] for p in parsed) if "ms" in k else parsed[0][k] for k in parsed[0]}
            except Exception as e:
                res[key] = {"error": str(e).splitlines()[0][:200]}
            print(f"  ml {key} {res[key]}", flush=True)
    for shape, script in [("small", "ml/mlx/train.py"), ("big", "ml_big/mlx/train.py")]:
        if sh([MLPY, "-c", "import mlx.core"], check=False).strip():
            break   # no MLX on this machine
        try:
            parsed = [parse_ml(sh([MLPY, os.path.join(HERE, script)])) for _ in range(runs)]
            res[f"{shape}/mlx"] = {k: min(p[k] for p in parsed) if "ms" in k else parsed[0][k] for k in parsed[0]}
        except Exception as e:
            res[f"{shape}/mlx"] = {"error": str(e).splitlines()[0][:200]}
        print(f"  ml {shape}/mlx {res[f'{shape}/mlx']}", flush=True)
    for shape, script in [("small", "ml/tensorflow/train.py"), ("big", "ml_big/tensorflow/train.py")]:
        try:
            env = dict(os.environ, TF_CPP_MIN_LOG_LEVEL="3", CUDA_VISIBLE_DEVICES="")
            parsed = [parse_ml(sh([MLPY, os.path.join(HERE, script), "cpu"], env=env)) for _ in range(runs)]
            res[f"{shape}/tensorflow_cpu"] = {k: min(p[k] for p in parsed) if "ms" in k else parsed[0][k] for k in parsed[0]}
        except Exception as e:
            res[f"{shape}/tensorflow_cpu"] = {"error": str(e).splitlines()[0][:200]}
        print(f"  ml {shape}/tensorflow_cpu {res[f'{shape}/tensorflow_cpu']}", flush=True)
    return res


# ── convolution / FFT (CPU vs CUDA) ───────────────────────────────────────────

def best_labeled(outputs):
    """'[label] k=v ...' lines from several runs -> {label: {k: min v}}."""
    best = {}
    for o in outputs:
        for line in o.splitlines():
            m = re.match(r"\[(.+?)\] (.*)", line)
            if not m:
                continue
            vals = dict((k, float(v)) for k, v in re.findall(r"(\w+)=(-?[\d.]+)", m.group(2)))
            cur = best.setdefault(m.group(1), {})
            for k, v in vals.items():
                cur[k] = min(cur.get(k, 1e18), v) if "ms" in k else v
    return best


def torch_devices():
    try:
        info = sh([MLPY, "-c", "import torch;print(torch.cuda.is_available(), torch.backends.mps.is_available())"]).split()
    except Exception:
        return []
    return ["cpu"] + (["cuda"] if info[0] == "True" else []) + (["mps"] if info[1] == "True" else [])


def cpu_vs_cuda(bench, cpu_src, cuda_src, cuda_libs, torch_script, runs, rocm_src=None, rocm_libs=()):
    """SafeC CPU, SafeC CUDA (when an NVIDIA GPU is present), SafeC ROCm (when
    an AMD GPU is present) and PyTorch rows. PyTorch's 'cuda' device is
    PyTorch-ROCm on an AMD machine."""
    res = {}
    cases = [("safec_cpu", cpu_src, [])]
    cases += [(f"safec_cpu_{v}", cpu_src, flags) for v, flags in vendor_blas().items()]
    if os.path.exists(os.path.join(CUDA, "lib64", "libnvrtc.so")) and shutil.which("nvidia-smi"):
        cases.append(("safec_cuda", cuda_src, [f"-L{CUDA}/lib64", f"-Wl,-rpath,{CUDA}/lib64", "-lcuda", "-lcublas",
                                                "-lcublasLt", "-lnvrtc"] + cuda_libs))
    if rocm_src and os.path.exists(os.path.join(ROCM, "lib", "libamdhip64.so")):
        cases.append(("safec_rocm", rocm_src, [f"-L{ROCM}/lib", f"-Wl,-rpath,{ROCM}/lib"] + list(rocm_libs) +
                                               ["-lamdhip64", "-lhiprtc", "-lrocblas"]))
    for key, src, libs in cases:
        out = os.path.join(BUILD, bench, key)
        os.makedirs(os.path.dirname(out), exist_ok=True)
        try:
            timed_build(safec_cmds(os.path.join(HERE, src), out, True, extra_libs=libs))
            res[key] = best_labeled([sh([out]) for _ in range(runs)])
        except Exception as e:
            res[key] = {"error": str(e).splitlines()[0][:200]}
        print(f"  {bench} {key} {res[key]}", flush=True)
    for dev in torch_devices():
        key = f"pytorch_{dev}"
        try:
            res[key] = best_labeled([sh([MLPY, os.path.join(HERE, torch_script), dev]) for _ in range(runs)])
        except Exception as e:
            res[key] = {"error": str(e).splitlines()[0][:200]}
        print(f"  {bench} {key} {res[key]}", flush=True)
    return res


# Training on the GPU through std::ml's device path: the rocm feature links
# device_rocm.sc in (MIOpen for the convolutions)
ROCM_TRAIN_LIBS = [f"-L{ROCM}/lib", f"-Wl,-rpath,{ROCM}/lib", "-lamdhip64", "-lhiprtc", "-lrocblas", "-lMIOpen"]
ROCM_TRAIN_DEFS = ["SAFEC_FEATURE_ROCM=1"]


def bench_ml_nn(runs):
    """The std::ml training stack (nn.h, trainer.h, autoencoder.h, gan.h, rl.h,
    lora.h, convnet.h) on its test-suite workloads, against PyTorch."""
    res = {}
    cases = [("safec_cpu", "ml_nn/safec/train_nn.sc", [], [])]
    cases += [(f"safec_cpu_{v}", "ml_nn/safec/train_nn.sc", flags, []) for v, flags in vendor_blas().items()]
    if os.path.exists(os.path.join(ROCM, "lib", "libamdhip64.so")):
        cases.append(("safec_rocm", "ml_nn/safec/train_nn_gpu.sc", ROCM_TRAIN_LIBS, ROCM_TRAIN_DEFS))
    for key, src, libs, defs in cases:
        out = os.path.join(BUILD, "ml_nn", key)
        os.makedirs(os.path.dirname(out), exist_ok=True)
        try:
            timed_build(safec_cmds(os.path.join(HERE, src), out, True, extra_libs=libs, defines=defs))
            res[key] = best_labeled([sh([out]) for _ in range(runs)])
        except Exception as e:
            res[key] = {"error": str(e).splitlines()[0][:200]}
        print(f"  ml_nn {key} {res[key]}", flush=True)
    for dev in torch_devices():
        key = f"pytorch_{dev}"
        try:
            res[key] = best_labeled([sh([MLPY, os.path.join(HERE, "ml_nn/pytorch/train_nn.py"), dev]) for _ in range(runs)])
        except Exception as e:
            res[key] = {"error": str(e).splitlines()[0][:200]}
        print(f"  ml_nn {key} {res[key]}", flush=True)
    return res


def bench_ml_nn_gpu(runs):
    """Bigger training workloads (an MLP, CNN, ResNet, U-Net, DCGAN and conv
    VAE): SafeC's CPU path (ml_nn/safec/train_big.sc) and its device path in
    fp32 / fp16 / bf16 (train_big_gpu.sc, PRECISION=...), against PyTorch on
    the GPU in fp32 and with autocast (ml_nn/pytorch/train_big.py)."""
    res = {}
    cases = [("safec_cpu", "ml_nn/safec/train_big.sc", [], [], [""])]
    if os.path.exists(os.path.join(ROCM, "lib", "libamdhip64.so")):
        cases.append(("safec_rocm", "ml_nn/safec/train_big_gpu.sc", ROCM_TRAIN_LIBS, ROCM_TRAIN_DEFS,
                      ["fp32", "fp16", "bf16"]))
    for key, src, libs, defs, precs in cases:
        out = os.path.join(BUILD, "ml_nn_gpu", key)
        os.makedirs(os.path.dirname(out), exist_ok=True)
        try:
            timed_build(safec_cmds(os.path.join(HERE, src), out, True, extra_libs=libs, defines=defs))
            runs_out = []
            for p in precs:
                cmd = ["env", f"PRECISION={p}", out] if p else [out]
                runs_out += [sh(cmd) for _ in range(runs)]
            res[key] = best_labeled(runs_out)
        except Exception as e:
            res[key] = {"error": str(e).splitlines()[0][:200]}
        print(f"  ml_nn_gpu {key} {res[key]}", flush=True)
    for dev in [d for d in torch_devices() if d != "cpu"]:
        key = f"pytorch_{dev}"
        try:
            res[key] = best_labeled([sh([MLPY, os.path.join(HERE, "ml_nn/pytorch/train_big.py"), dev]) for _ in range(runs)])
        except Exception as e:
            res[key] = {"error": str(e).splitlines()[0][:200]}
        print(f"  ml_nn_gpu {key} {res[key]}", flush=True)
    return res


def bench_conv(runs):
    return cpu_vs_cuda("conv", "conv/safec/conv_cpu.sc", "conv/safec/conv_cuda.sc", ["-lcudnn"],
                       "conv/pytorch/conv.py", runs, "conv/safec/conv_rocm.sc", ["-lMIOpen"])


def bench_fft(runs):
    res = cpu_vs_cuda("fft", "fft/safec/fft_cpu.sc", "fft/safec/fft_cuda.sc", ["-lcufft"],
                      "fft/pytorch/fft.py", runs, "fft/safec/fft_rocm.sc", ["-lhipfft"])
    res.update(thirdparty_runs("fft", runs))
    return res


def bench_attention(runs):
    """Flash and linear attention on the GPU: SafeC's CUDA kernels
    (attention/safec/attn_cuda.sc) and ROCm kernels (attention/safec/attn_rocm.sc,
    fp32 rows) against PyTorch (attention/pytorch/attn.py:
    scaled_dot_product_attention, a vectorized linear-attention baseline and,
    if installed, flash-linear-attention's Triton kernel); on a Mac, SafeC's
    Metal kernels (attention/safec/attn_mps.sc) against PyTorch's mps device
    and MLX (attention/mlx/attn.py)."""
    res = {}
    if os.path.exists(os.path.join(ROCM, "lib", "libamdhip64.so")):
        out = os.path.join(BUILD, "attention", "safec_rocm")
        os.makedirs(os.path.dirname(out), exist_ok=True)
        libs = [f"-L{ROCM}/lib", f"-Wl,-rpath,{ROCM}/lib", "-lamdhip64", "-lhiprtc", "-lrocblas"]
        try:
            timed_build(safec_cmds(os.path.join(HERE, "attention/safec/attn_rocm.sc"), out, True, extra_libs=libs))
            res["safec_rocm"] = best_labeled([sh([out]) for _ in range(runs)])
        except Exception as e:
            res["safec_rocm"] = {"error": str(e).splitlines()[0][:200]}
        print(f"  attention safec_rocm {res['safec_rocm']}", flush=True)
        try:
            res["pytorch_rocm"] = best_labeled([sh([MLPY, os.path.join(HERE, "attention/pytorch/attn.py"), "rocm"])
                                                for _ in range(runs)])
        except Exception as e:
            res["pytorch_rocm"] = {"error": str(e).splitlines()[0][:200]}
        print(f"  attention pytorch_rocm {res['pytorch_rocm']}", flush=True)
    if IS_MAC:
        out = os.path.join(BUILD, "attention", "safec_mps")
        os.makedirs(os.path.dirname(out), exist_ok=True)
        try:
            timed_build(safec_cmds(os.path.join(HERE, "attention/safec/attn_mps.sc"), out, True,
                                   extra_libs=["-framework", "Metal", "-framework", "Foundation", "-lobjc"]))
            res["safec_mps"] = best_labeled([sh([out]) for _ in range(runs)])
        except Exception as e:
            res["safec_mps"] = {"error": str(e).splitlines()[0][:200]}
        print(f"  attention safec_mps {res['safec_mps']}", flush=True)
        try:
            if sh([MLPY, "-c", "import torch;print(torch.backends.mps.is_available())"]).strip() == "True":
                res["pytorch_mps"] = best_labeled([sh([MLPY, os.path.join(HERE, "attention/pytorch/attn.py"), "mps"])
                                                   for _ in range(runs)])
        except Exception as e:
            res["pytorch_mps"] = {"error": str(e).splitlines()[0][:200]}
        print(f"  attention pytorch_mps {res.get('pytorch_mps')}", flush=True)
        try:
            res["mlx"] = best_labeled([sh([MLPY, os.path.join(HERE, "attention/mlx/attn.py")]) for _ in range(runs)])
        except Exception as e:
            res["mlx"] = {"error": str(e).splitlines()[0][:200]}
        print(f"  attention mlx {res['mlx']}", flush=True)
    if os.path.exists(os.path.join(CUDA, "lib64", "libnvrtc.so")) and shutil.which("nvidia-smi"):
        out = os.path.join(BUILD, "attention", "safec_cuda")
        os.makedirs(os.path.dirname(out), exist_ok=True)
        libs = [f"-L{CUDA}/lib64", f"-Wl,-rpath,{CUDA}/lib64", "-lcuda", "-lcublas", "-lcublasLt", "-lnvrtc"]
        try:
            timed_build(safec_cmds(os.path.join(HERE, "attention/safec/attn_cuda.sc"), out, True, extra_libs=libs))
            res["safec_cuda"] = best_labeled([sh([out]) for _ in range(runs)])
        except Exception as e:
            res["safec_cuda"] = {"error": str(e).splitlines()[0][:200]}
        print(f"  attention safec_cuda {res['safec_cuda']}", flush=True)
    if shutil.which("nvidia-smi") and "cuda" in torch_devices():
        try:
            res["pytorch_cuda"] = best_labeled([sh([MLPY, os.path.join(HERE, "attention/pytorch/attn.py"), "cuda"])
                                                for _ in range(runs)])
        except Exception as e:
            res["pytorch_cuda"] = {"error": str(e).splitlines()[0][:200]}
        print(f"  attention pytorch_cuda {res['pytorch_cuda']}", flush=True)
    return res


# Third-party CPU libraries for the FFT/DSP comparison, each built and linked
# only into its own benchmark program (never into SafeC's std). Install
# prefixes come from the environment; a library that isn't there is skipped.
#   FFTW_ROOT        FFTW 3 (--enable-threads)        fft: 1 and 16 threads
#   KFR_ROOT         KFR 7 install (static libs)      fft and dsp
#   Q_ROOT           cycfi/q source tree             dsp (biquad)
#   MADRONALIB_ROOT  madronalib source tree          dsp (biquad)
#   (RustFFT and dasp come from crates.io through cargo)  fft and dsp
def thirdparty_runs(kind, runs):
    res = {}
    T = os.environ
    out_dir = os.path.join(BUILD, "thirdparty")
    os.makedirs(out_dir, exist_ok=True)

    def run(key, build, cmd):
        try:
            timed_build(build)
            res[key] = best_labeled([sh(cmd) for _ in range(runs)])
        except Exception as e:
            res[key] = {"error": str(e).splitlines()[0][:200]}
        print(f"  {kind} {key} {res[key]}", flush=True)

    if kind == "fft" and T.get("FFTW_ROOT"):
        f = T["FFTW_ROOT"]
        exe = os.path.join(out_dir, "fftw_bench")
        build = [[CLANG, "-O2", os.path.join(HERE, "fft/c/fftw_bench.c"), f"-I{f}/include", f"-L{f}/lib",
                  f"-Wl,-rpath,{f}/lib", "-lfftw3f_threads", "-lfftw3f", "-lfftw3_threads", "-lfftw3", "-lm", "-lpthread", "-o", exe]]
        run("fftw_1t", build, [exe, "1"])
        run("fftw_16t", [], [exe, str(os.cpu_count() or 16)])
    if T.get("KFR_ROOT"):
        k = T["KFR_ROOT"]
        exe = os.path.join(out_dir, "kfr_bench")
        libs = sorted(glob.glob(os.path.join(k, "lib", "*.a")))
        build = [[CLANGXX, "-std=c++20", "-O2", "-march=native", "-w", os.path.join(HERE, "dsp/cpp/kfr_bench.cpp"),
                  f"-I{k}/include"] + (libs if IS_MAC else ["-Wl,--start-group"] + libs + ["-Wl,--end-group"]) +
                 ["-lpthread", "-o", exe]]   # Apple's ld resolves archives in any order
        run("kfr", build, [exe])
    if kind == "dsp" and T.get("Q_ROOT") and T.get("MADRONALIB_ROOT"):
        q, m = T["Q_ROOT"], T["MADRONALIB_ROOT"]
        incs = [f"-I{d}" for d, _, _ in os.walk(os.path.join(m, "source"))]
        exe = os.path.join(out_dir, "audio_libs_bench")
        build = [[CLANGXX, "-std=c++20", "-O2", "-march=native", "-w", os.path.join(HERE, "dsp/cpp/audio_libs_bench.cpp"),
                  f"-I{q}/q_lib/include", f"-I{q}/infra/include"] + incs + [f"-I{m}/include", "-o", exe]]
        run("q_madronalib", build, [exe])
    if shutil.which(CARGO) or os.path.exists(CARGO):
        tgt = os.path.join(out_dir, "cargo")
        build = [[CARGO, "build", "--release", "--manifest-path", os.path.join(HERE, "dsp/rust/Cargo.toml"),
                  "--target-dir", tgt]]
        run("rust", build, [os.path.join(tgt, "release", "dsp_rust_bench")])
    return res


def bench_dsp(runs):
    """SafeC's std/dsp on the CPU (and with a vendor library linked) against
    NumPy/SciPy, same workloads (dsp/safec/dsp_cpu.sc, dsp/python/dsp_scipy.py)."""
    res = {}
    cases = [("safec_cpu", [])] + [(f"safec_cpu_{v}", f) for v, f in vendor_blas().items()]
    for key, libs in cases:
        out = os.path.join(BUILD, "dsp", key)
        os.makedirs(os.path.dirname(out), exist_ok=True)
        try:
            timed_build(safec_cmds(os.path.join(HERE, "dsp/safec/dsp_cpu.sc"), out, True, extra_libs=libs))
            res[key] = best_labeled([sh([out]) for _ in range(runs)])
        except Exception as e:
            res[key] = {"error": str(e).splitlines()[0][:200]}
        print(f"  dsp {key} {res[key]}", flush=True)
    try:
        res["scipy"] = best_labeled([sh([MLPY, os.path.join(HERE, "dsp/python/dsp_scipy.py")]) for _ in range(runs)])
    except Exception as e:
        res["scipy"] = {"error": str(e).splitlines()[0][:200]}
    print(f"  dsp scipy {res['scipy']}", flush=True)
    res.update(thirdparty_runs("dsp", runs))
    return res


# ── web service ───────────────────────────────────────────────────────────────

def wait_port(port, timeout=20.0):
    t = time.time()
    while time.time() - t < timeout:
        with socket.socket() as s:
            if s.connect_ex(("127.0.0.1", port)) == 0:
                return True
        time.sleep(0.1)
    return False


def bench_web(runs):
    W = os.path.join(HERE, "webservice")
    servers = [  # (lang, port, build cmds, run cmd)
        ("safec", 8082, safec_cmds(f"{W}/safec/server_reactor.sc", f"{BUILD}/web/safec", True), [f"{BUILD}/web/safec"]),
        ("c", 8082, [[CLANG, "-O2", f"{W}/c/server.c", "-o", f"{BUILD}/web/c"]], [f"{BUILD}/web/c"]),
        ("cpp", 8083, [[CLANGXX, "-O2", f"{W}/cpp/server.cpp", "-o", f"{BUILD}/web/cpp"]], [f"{BUILD}/web/cpp"]),
        ("zig", 8085, [[ZIG, "build-exe", "-O", "ReleaseFast", f"{W}/zig/server.zig", f"-femit-bin={BUILD}/web/zig", "-lc"]], [f"{BUILD}/web/zig"]),
        ("go", 8084, [[GO, "build", "-o", f"{BUILD}/web/go", f"{W}/go/server.go"]], [f"{BUILD}/web/go"]),
        ("rust", 8086, [[CARGO, "build", "--release", "--manifest-path", f"{W}/rust/Cargo.toml",
                         "--target-dir", f"{BUILD}/web/cargo"]], [f"{BUILD}/web/cargo/release/webservice"]),
        ("python", 8087, [], [WEBPY, f"{W}/python/server.py"]),
    ]
    os.makedirs(f"{BUILD}/web", exist_ok=True)
    # WEB_PORT_BASE moves the servers off 8082-8087 (passed as PORT) when
    # something else holds those, e.g. an editor's port forwarding
    base = os.environ.get("WEB_PORT_BASE")
    res = {}
    for i, (lang, port, builds, cmd) in enumerate(servers):
        env = None
        if base:
            port = int(base) + i
            env = dict(os.environ, PORT=str(port))
        row = {}
        try:
            timed_build(builds)
            best = None
            for _ in range(runs):
                p = subprocess.Popen(cmd, env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                try:
                    if not wait_port(port):
                        raise RuntimeError("server did not start")
                    sh([AB, "-q", "-n", "2000", "-c", "50", f"http://127.0.0.1:{port}/"])  # warm-up
                    out = sh([AB, "-q", "-n", "20000", "-c", "50", f"http://127.0.0.1:{port}/"])
                    # Linux keeps the forking Python's ru_maxrss across exec, so
                    # wait4() would report Python's peak; read the server's own.
                    hwm = None
                    if not IS_MAC:
                        with open(f"/proc/{p.pid}/status") as f:
                            m = re.search(r"VmHWM:\s+(\d+) kB", f.read())
                            hwm = int(m.group(1)) / 1024 if m else None
                finally:
                    p.send_signal(signal.SIGTERM)
                    try:
                        _, _, ru = os.wait4(p.pid, 0)
                    except ChildProcessError:
                        ru = None
                rps = float(re.search(r"Requests per second:\s+([\d.]+)", out).group(1))
                failed = int(re.search(r"Failed requests:\s+(\d+)", out).group(1))
                p50 = int(re.search(r"\n\s+50%\s+(\d+)", out).group(1))
                p99 = int(re.search(r"\n\s+99%\s+(\d+)", out).group(1))
                rss = (ru.ru_maxrss / (1024 * 1024) if ru else None) if IS_MAC else hwm
                if best is None or rps > best["rps"]:
                    best = {"rps": rps, "p50_ms": p50, "p99_ms": p99, "failed": failed, "rss_mb": round(rss, 1) if rss else None}
                time.sleep(1.0)  # let the port leave TIME_WAIT pressure
            row = best
        except Exception as e:
            row = {"error": str(e).splitlines()[0][:200]}
        res[lang] = row
        print(f"  web {lang:6s} {row}", flush=True)
    return res


# ── main ──────────────────────────────────────────────────────────────────────

def versions():
    def first(cmd):
        try:
            return sh(cmd, check=False).strip().splitlines()[0]
        except Exception:
            return None
    v = {
        "safec": first([SAFEC, "--version"]), "clang": first([CLANG, "--version"]),
        "rustc": first([RUSTC, "--version"]), "go": first([GO, "version"]), "zig": first([ZIG, "version"]),
        "python": first([PYTHON, "--version"]),
        "torch": first([MLPY, "-c", "import torch;print(torch.__version__)"]),
        "tensorflow": (sh([MLPY, "-c", "import os;os.environ['TF_CPP_MIN_LOG_LEVEL']='3';import tensorflow as tf;print(tf.__version__)"],
                          check=False).strip().splitlines() or [None])[-1],  # last line: absl may log before the version
        "numpy": first([MLPY, "-c", "import numpy;print(numpy.__version__)"]),
        "mlx": first([MLPY, "-c", "import mlx.core as mx;print(mx.__version__)"]),
    }
    if IS_MAC:
        v["cpu"] = first(["sysctl", "-n", "machdep.cpu.brand_string"])
        v["os"] = "macOS " + platform.mac_ver()[0]
    else:
        v["cpu"] = next((l.split(":", 1)[1].strip() for l in open("/proc/cpuinfo") if l.startswith("model name")), None)
        v["os"] = first(["sh", "-c", ". /etc/os-release; echo $PRETTY_NAME"])
    v["cores"] = os.cpu_count()
    gpu = first(["sh", "-c", f"{ROCM}/bin/rocminfo 2>/dev/null | grep 'Marketing Name' | sed -n 2p | sed 's/.*: *//'"])
    if not gpu:
        gpu = first(["nvidia-smi", "--query-gpu=name,driver_version", "--format=csv,noheader"])
        if gpu:
            v["cuda"] = first([os.path.join(CUDA, "bin", "nvcc"), "--version"]) and \
                sh([os.path.join(CUDA, "bin", "nvcc"), "--version"], check=False).strip().splitlines()[-1]
    if gpu:
        v["gpu"] = gpu
    return v


BENCHES = {
    "fib": lambda r: classic("fib", r),
    "nbody": lambda r: classic("nbody", r),
    "binarytrees": bench_binarytrees,
    "binarytrees_mt": bench_mt,
    "simd": bench_simd,
    "collections": bench_collections,
    "memalloc": bench_memalloc,
    "ml": bench_ml,
    "ml_nn": bench_ml_nn,
    "ml_nn_gpu": bench_ml_nn_gpu,
    "conv": bench_conv,
    "fft": bench_fft,
    "attention": bench_attention,
    "dsp": bench_dsp,
    "web": bench_web,
}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--name", required=True)
    ap.add_argument("--only", default=",".join(BENCHES))
    ap.add_argument("--runs", type=int, default=3)
    a = ap.parse_args()
    os.makedirs(BUILD, exist_ok=True)
    path = os.path.join(HERE, "results", f"{a.name}.json")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    data = json.load(open(path)) if os.path.exists(path) else {}
    data["machine"] = versions()
    print(json.dumps(data["machine"], indent=1), flush=True)
    for b in a.only.split(","):
        print(f"== {b}", flush=True)
        data[b] = BENCHES[b](a.runs)
        json.dump(data, open(path, "w"), indent=1)
    print(f"wrote {path}")


if __name__ == "__main__":
    main()
