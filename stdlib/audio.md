# Audio & MIDI (`std/audio/`)

Real-time audio and MIDI I/O over every platform's native system, in the spirit of RtAudio and RtMidi: enumerate devices and ports, open an output, input or duplex stream with a callback that runs on the audio thread, send and receive MIDI. Errors come back as values (`AudioError`, `std::Result<T, AudioError>`), never as exceptions, and the real-time path allocates nothing and takes no locks.

```c
#include <std/audio/audio.h>          // devices, streams, the audio callback
#include <std/audio/midi.h>           // MIDI ports, messages, parser
#include <std/audio/audio_convert.h>  // sample-format / layout conversion, std/dsp interop
```

`std/prelude.h` does not include them — include them directly. Everything is in the `audio` std section (`lib/<triple>/std/audio.a`, `install.sh --std=+audio`).

---

## Backends {#backends}

| Audio API | `AUDIO_API_*` | Platform | File | Verified |
|---|---|---|---|---|
| ALSA PCM | `ALSA` | Linux | `audio_alsa_linux.sc` | Run against the `null` and `file` PCMs (playback into a file, capture from a file through an ALSA config), zero-device probe |
| JACK | `JACK` | Linux, macOS | `audio_jack_posix.sc` | Run against `jackd -d dummy`: a stream plays into a second stream through the JACK graph |
| Core Audio (HAL) | `COREAUDIO` | macOS | `audio_coreaudio_macos.sc` | Compiled for `x86_64-apple-macos` / `arm64-apple-macos` (IR and objects, `static_assert`-checked struct layouts); not run on a Mac yet |
| WASAPI | `WASAPI` | Windows | `audio_wasapi_win32.sc` | Run under Wine 9 (`tests/stdlib/win32/run.sh`): enumeration, shared-mode output, input and duplex streams; exclusive mode compiled, not run (Wine refuses it) |
| Web Audio | `WEBAUDIO` | wasm32 | `audio_webaudio_wasm.sc` + `audio_web.js` | Run under Node with stand-ins for `AudioContext`, `AudioWorklet` and `ScriptProcessorNode` (`tests/stdlib/audio_web/run.sh`) |
| Dummy | `DUMMY` | everywhere | `audio_dummy.sc` | Unit-tested; the CI smoke test plays through it |

| MIDI API | `MIDI_API_*` | Platform | File | Virtual ports | Verified |
|---|---|---|---|---|---|
| ALSA sequencer | `ALSA` | Linux | `midi_alsa_linux.sc` | yes | Compiled; reports `AUDIO_ERR_UNAVAILABLE` without `/dev/snd/seq` (the test loops back through a virtual port where a sequencer exists) |
| JACK MIDI | `JACK` | Linux, macOS | `midi_jack_posix.sc` | yes | Run against `jackd -d dummy`: notes, CC, pitch bend and sysex through a virtual port |
| Core MIDI | `COREMIDI` | macOS | `midi_coremidi_macos.sc` | yes | Compiled for macOS; not run yet |
| WinMM | `WINMM` | Windows | `midi_winmm_win32.sc` | no | Run under Wine (client/port enumeration; Wine has no MIDI ports here) |
| Web MIDI | `WEBMIDI` | wasm32 | `midi_webmidi_wasm.sc` + `audio_web.js` | no | Run under Node with a stand-in `navigator.requestMIDIAccess` |
| Dummy | `DUMMY` | everywhere | `midi_dummy.sc` | yes | Unit-tested (an in-process loopback) |

`safeguard` builds the backends for the target and only those: a platform's files carry a suffix (`_linux`, `_posix`, `_macos`, `_win32`, `_wasm`) that selects them, and each brings its system libraries into the link — nothing to list in `Package.toml`:

| Target | Linked | Loaded at run time |
|---|---|---|
| Linux | `-ldl -lpthread` | `libasound.so.2` (ALSA PCM and sequencer), `libjack.so.0` |
| macOS | `-framework CoreAudio -framework CoreMIDI -framework CoreFoundation` | `libjack.0.dylib` (Homebrew's or `/usr/local`'s) |
| Windows | `-lole32 -lwinmm` | `avrt.dll` (MMCSS) |
| wasm32 | nothing: imports from `audio_web.js` | |

ALSA and JACK are `dlopen()`ed, so a program built with `std/audio` starts on a machine without them: the API reports `AUDIO_ERR_UNAVAILABLE` (no library, no JACK server) and `Audio::open(AUDIO_API_UNSPECIFIED)` moves on.

---

## Quick Start {#quick-start}

```c
#include <std/audio/audio.h>
#include <std/math.h>
extern int printf(const char* fmt, ...);

struct Tone { double phase; double step; };

// The real-time callback: fill io's output buffer, return AUDIO_CB_CONTINUE.
int render(&AudioIo io, ?&Tone tp) {
    match (tp) {
        case some(t): {
            struct Slice<float> out = io.output_f32();   // frames x channels, interleaved
            for (unsigned int f = 0U; f < io.frames; f = f + 1U) {
                float v = (float)(0.2 * std::sin_d(t.phase));
                out.set((unsigned long)f * 2UL, v);
                out.set((unsigned long)f * 2UL + 1UL, v);
                t.phase = t.phase + t.step;
            }
        }
        case null: {}
    }
    return AUDIO_CB_CONTINUE;
}

int main() {
    struct Audio audio = std::Audio::open(AUDIO_API_UNSPECIFIED).unwrap_or(std::Audio::closed());
    for (unsigned int i = 0U; i < audio.device_count(); i = i + 1U) {
        struct AudioDeviceInfo d = audio.device_info(i).unwrap_or(std::AudioDeviceInfo::none());
        printf("[%u] %s: %d out, %d in\n", d.id, d.name, d.output_channels, d.input_channels);
    }
    struct Tone tone;
    tone.phase = 0.0;
    tone.step = 2.0 * 3.141592653589793 * 440.0 / 48000.0;
    struct AudioStreamParams out = std::AudioStreamParams::of(audio.default_output_device(), 2);
    unsigned int frames = 256U;                           // requested; receives the actual size
    struct Result<AudioStream, AudioError> r =
        audio.open_stream((?&AudioStreamParams)&out, null, AUDIO_FORMAT_F32, 48000U, &frames,
                          render, (?&Tone)&tone, null);
    match (r.get_err()) { case some(e): { printf("%s\n", e.message); return 1; } case null: {} }
    struct AudioStream s = r.unwrap_or(std::AudioStream::closed());
    s.start();
    std::audio_sleep_us_(2000000LL);                      // two seconds
    s.stop();
    s.close();
    audio.close();
    return 0;
}
```

`examples/audio_demo` is the complete version: `audio_demo list` prints every compiled API's devices and MIDI ports, `audio_demo sine` plays a tone, `audio_demo midi` monitors a MIDI input. `--api dummy` runs anywhere.

---

## Real-Time Rules {#real-time-rules}

The callback runs on the backend's real-time thread — a thread std starts (ALSA, WASAPI, dummy), the JACK process thread, the Core Audio IOProc thread, or the browser's audio rendering. On that path std itself:

- allocates nothing: every buffer (format conversion, interleaving, channel offsets, the WASAPI capture FIFO, the MIDI queues) is allocated when the stream or port is opened;
- takes no lock: stream state, the status flags and the stream clock are atomic words, the MIDI input queue is a single-producer/single-consumer ring;
- doesn't block or print.

Write the callback the same way: no `new`/`alloc`, no mutex, no file or console I/O. Hand data to other threads through a lock-free structure (std/dsp's [`AudioBuffer`](/stdlib/dsp#audiobuffer-lock-free-multi-channel-ring-buffer), `audio_buffer_write_f32()` below, std/sync's ring buffers). `start()`, `stop()`, `abort()` and `close()` are control-thread calls — they may join the stream thread or drain the device — so never call them from the callback: return `AUDIO_CB_STOP` (play what's queued, then stop) or `AUDIO_CB_ABORT` (stop now) instead. `AUDIO_FLAG_SCHEDULE_REALTIME` raises a stream thread std started to `SCHED_FIFO` (POSIX) or the MMCSS "Pro Audio" class (Windows; WASAPI streams always use it).

---

## Audio API — `audio.h` {#audio-api}

### APIs {#apis}

```c
#define AUDIO_API_UNSPECIFIED 0      // Audio::open(): the default, then any that works
#define AUDIO_API_ALSA        1
#define AUDIO_API_JACK        2
#define AUDIO_API_COREAUDIO   3
#define AUDIO_API_WASAPI      4
#define AUDIO_API_WEBAUDIO    5
#define AUDIO_API_DUMMY       6

int audio_compiled_apis(&int out, int cap);   // this build's APIs, in preference order; returns the count
int audio_api_compiled(int api);
int audio_default_api();                      // see below
const &char audio_api_name(int api);          // "alsa"
const &char audio_api_display_name(int api);  // "ALSA"
int audio_api_by_name(const &char name);      // AUDIO_API_UNSPECIFIED if unknown
```

The default API is Core Audio on macOS, WASAPI on Windows, Web Audio on wasm32 and ALSA on Linux — or JACK, on Linux and macOS, while a JACK server is running. The `SAFEC_AUDIO_API` environment variable (`alsa`, `jack`, `dummy`, ...) overrides it. `Audio::open(AUDIO_API_UNSPECIFIED)` opens the default; if it has no devices, the first other available API that has some; if none does, the default with zero devices; and the dummy API only when nothing else opened at all.

### `Audio` — one API's device list {#audio}

```c
struct Audio {
    int    api_id;
    ?&void impl;

    static struct Result<Audio, AudioError> open(int api);   // probes the devices
    static struct Audio closed();

    int  is_open() const;
    int  api() const;
    struct AudioError rescan();                              // re-enumerate (hot-plug)
    unsigned int device_count() const;
    struct Result<AudioDeviceInfo, AudioError> device_info(unsigned int index) const;
    struct Result<AudioDeviceInfo, AudioError> device_by_id(unsigned int id) const;
    unsigned int default_output_device() const;              // ids; AUDIO_DEFAULT_DEVICE (0) if none
    unsigned int default_input_device() const;

    generic<T>
    struct Result<AudioStream, AudioError> open_stream(?&AudioStreamParams output, ?&AudioStreamParams input,
                                                       unsigned int format, unsigned int sample_rate,
                                                       &unsigned int frames,
                                                       fn int(&AudioIo, ?&T) callback, ?&T user,
                                                       ?&AudioStreamOptions opts);
    void close();                                            // streams stay valid
};
```

A machine with no sound hardware still opens: `device_count()` is 0. Device ids are stable for the `Audio`'s lifetime — a device that disappears and comes back after a `rescan()` keeps its id — and are what `open_stream` takes; `AUDIO_DEFAULT_DEVICE` (0) means the API's default device (or the first with channels in that direction).

```c
struct AudioDeviceInfo {
    unsigned int id;
    char         name[AUDIO_DEVICE_NAME_MAX];
    int          output_channels, input_channels, duplex_channels;
    int          is_default_output, is_default_input;
    unsigned int native_formats;                          // AUDIO_FORMAT_* bits
    unsigned int sample_rates[AUDIO_MAX_SAMPLE_RATES];    // ascending
    int          sample_rate_count;
    unsigned int preferred_sample_rate;

    int supports_rate(unsigned int rate) const;
    static struct AudioDeviceInfo none();                 // for unwrap_or()
};
```

### Opening a stream {#opening-a-stream}

Give output parameters, input parameters, or both (duplex). `format` is what the callback sees; std converts to whatever the device runs.

```c
struct AudioStreamParams {
    unsigned int device;        // AudioDeviceInfo.id or AUDIO_DEFAULT_DEVICE
    int          channels;
    int          first_channel; // offset into the device's channels
    static struct AudioStreamParams of(unsigned int device, int channels);
};

struct AudioStreamOptions {
    unsigned int flags;          // AUDIO_FLAG_*
    unsigned int buffer_count;   // periods the device queues (0 = the API's default)
    int          priority;       // with AUDIO_FLAG_SCHEDULE_REALTIME: 1..99
    char         stream_name[AUDIO_STREAM_NAME_MAX];   // JACK client name
    char         device_name[AUDIO_DEVICE_NAME_MAX];   // ALSA PCM name / JACK client to connect to
    static struct AudioStreamOptions none();
    static struct AudioStreamOptions with_flags(unsigned int flags);
};
```

| Format | Sample |
|---|---|
| `AUDIO_FORMAT_I8` | `signed char` |
| `AUDIO_FORMAT_I16` | `short` |
| `AUDIO_FORMAT_I24` | 3 bytes, packed, little-endian |
| `AUDIO_FORMAT_I32` | `int` |
| `AUDIO_FORMAT_F32` | `float`, nominal −1..1 |
| `AUDIO_FORMAT_F64` | `double`, nominal −1..1 |

| Flag | Effect |
|---|---|
| `AUDIO_FLAG_NONINTERLEAVED` | Callback buffers are channel-major (`frames` samples per channel) |
| `AUDIO_FLAG_MINIMIZE_LATENCY` | The smallest period the device allows |
| `AUDIO_FLAG_EXCLUSIVE` | WASAPI exclusive mode, Core Audio hog mode, ALSA without the plug layer |
| `AUDIO_FLAG_SCHEDULE_REALTIME` | Real-time priority for a stream thread std starts |
| `AUDIO_FLAG_ALSA_USE_DEFAULT` | ALSA: open the `default` PCM |
| `AUDIO_FLAG_JACK_DONT_CONNECT` | JACK: register the ports, connect nothing |
| `AUDIO_FLAG_DUMMY_MANUAL` | Dummy: no timer thread; drive it with `AudioStream::pump()` |

`frames` is the requested period (0 = the API's default) and receives the one the device uses — JACK and exclusive-mode WASAPI dictate it, ScriptProcessor-based Web Audio rounds it to a power of two. The callback always gets that many frames.

### The callback {#the-callback}

```c
int callback(&AudioIo io, ?&T user);   // AUDIO_CB_CONTINUE, AUDIO_CB_STOP or AUDIO_CB_ABORT

struct AudioIo {
    ?&void       output, input;          // null for the direction not opened
    unsigned int frames;
    double       stream_time;            // seconds processed before this period
    unsigned int status;                 // AUDIO_STATUS_INPUT_OVERFLOW | AUDIO_STATUS_OUTPUT_UNDERFLOW
    int          output_channels, input_channels;
    unsigned int format;
    int          interleaved;
    unsigned int sample_rate;

    struct Slice<float>  output_f32();   // also input_f32, _f64, _i16, _i32, _i8; empty for another format
    struct Slice<unsigned char> output_bytes();          // any format (the only view of I24)
    struct Slice<float>  output_channel_f32(int ch);     // one channel of a non-interleaved f32 stream
    void clear_output();
};
```

The typed views are bounds-checked [`Slice<T>`](/stdlib/collections#slice)s over the buffer: `frames * channels` samples, interleaved (frame-major) unless the stream is non-interleaved. `status` carries the xruns the backend saw since the previous callback: ALSA's `-EPIPE`, JACK's xrun callback, Core Audio's processor overload, WASAPI's data discontinuity and empty render buffer, a dry AudioWorklet queue, the dummy API's late wake-ups.

### `AudioStream` {#audiostream}

```c
struct AudioStream {
    ?&void impl;
    static struct AudioStream closed();

    struct AudioError start();
    struct AudioError stop();             // play out what's queued, then stop
    struct AudioError abort();            // stop now
    void close();                         // abort if running; release everything; idempotent

    int    is_open() const;
    int    is_running() const;            // 0 after the callback returned STOP/ABORT, too
    int    state() const;                 // AUDIO_STREAM_CLOSED / _STOPPED / _RUNNING
    double stream_time() const;           // frames processed / sample rate
    void   set_stream_time(double seconds);
    long   latency() const;               // input + output, frames
    long   output_latency() const;
    long   input_latency() const;
    unsigned int sample_rate() const;
    unsigned int buffer_frames() const;
    int    api() const;
    struct AudioError last_error() const; // what stopped the stream from its own thread
    int    pump();                        // AUDIO_FLAG_DUMMY_MANUAL: run one period now
};
```

A stopped stream can be started again. `start()` on a running stream and `stop()` on a stopped one return `AUDIO_ERR_WARNING` (which `ok()` accepts). When the device fails while running — unplugged, a JACK server shutdown, xrun recovery failing — the stream stops by itself and `last_error()` says why.

### Errors {#errors}

```c
struct AudioError {
    int  code;                                 // AUDIO_OK or AUDIO_ERR_*
    char message[AUDIO_ERROR_MESSAGE_MAX];     // "alsa: snd_pcm_open: Device or resource busy"
    int ok() const;                            // AUDIO_OK or AUDIO_ERR_WARNING
    int failed() const;
    static struct AudioError none();
};
const &char audio_error_name(int code);
```

| Code | Meaning |
|---|---|
| `AUDIO_ERR_WARNING` | Not fatal (already running, realtime priority refused) |
| `AUDIO_ERR_UNSUPPORTED` | The API isn't compiled in, or can't do this (WinMM virtual ports) |
| `AUDIO_ERR_NO_DEVICES` | No device for that direction |
| `AUDIO_ERR_INVALID_DEVICE` | No device/port with that id or index |
| `AUDIO_ERR_INVALID_PARAM` | Channels, rate, format the device or API can't do |
| `AUDIO_ERR_INVALID_USE` | Wrong state (a closed stream, a port already open) |
| `AUDIO_ERR_DEVICE_BUSY` | Device in use (exclusive mode, an ALSA hw device) |
| `AUDIO_ERR_SYSTEM` | The OS or library call failed (its message, HRESULT, OSStatus included) |
| `AUDIO_ERR_THREAD` | The stream thread couldn't start |
| `AUDIO_ERR_NO_MEMORY` | Allocation failed (open time only) |
| `AUDIO_ERR_UNAVAILABLE` | The backend's library or server isn't there |

---

## Backend Notes {#backend-notes}

**ALSA.** Devices are ALSA's `default` PCM (when it opens) and every `hw:CARD,DEV` the control interface lists, probed non-blocking for channels, formats and the standard rates. `AudioStreamOptions.device_name` opens any other PCM by name — `null`, `plughw:1,0`, `file:FILE=out.raw,FORMAT=raw`, a PCM from `~/.asoundrc` — which is how std's tests run without hardware. The stream thread does blocking `snd_pcm_readi`/`writei` (or `readn`/`writen` for non-interleaved-only hardware) of one period; xruns are recovered with `snd_pcm_prepare()`. The device format is the stream's when the hardware takes it, else the best it has (f32, i32, packed i24, i16, f64, i8); a `hw:` device that refuses the rate is reopened through `plughw:` unless `AUDIO_FLAG_EXCLUSIVE`. ALSA's own stderr messages are silenced; they arrive in `AudioError`.

**JACK.** Every JACK client with audio ports is a device — `system` (the hardware, the default) and every application, other SafeC streams included — with the client's input ports as output channels and its output ports as input channels. Each stream is its own client (`stream_name`, "SafeC" by default) with ports `out_1..`/`in_1..`, connected on `start()` to the device's ports from `first_channel` on. JACK runs the whole graph at one rate and buffer size: ask for the server's rate (else `AUDIO_ERR_INVALID_PARAM`); `frames` comes back as the server's buffer size. The server is never auto-started.

**Core Audio.** The HAL's devices (`kAudioHardwarePropertyDevices`), opened with an `AudioDeviceIOProc` that the HAL calls on its own real-time thread. std sets the device's buffer frame size and nominal sample rate to the request, and takes hog mode with `AUDIO_FLAG_EXCLUSIVE`. A duplex stream uses one device for both directions — combine two with an aggregate device (Audio MIDI Setup).

**WASAPI.** Render and capture endpoints are separate devices. Shared mode (default) uses the engine's mix format and lets the engine resample another rate; `AUDIO_FLAG_EXCLUSIVE` opens exclusive mode in the first of the stream's format, f32, i32, i24, i16 the device accepts, re-aligning the buffer when the driver asks. Both are event-driven from one stream thread (MMCSS "Pro Audio"); capture packets go through a FIFO so duplex periods line up. std initializes COM as multithreaded on the threads it uses.

**Web Audio.** A wasm32 module calls into `std/audio/audio_web.js` (see [WebAssembly](#webassembly)): one "Web Audio" device, stereo out, plus the microphone as input when the page called `SafeCAudio.enableInput()`. The glue drives the callback from an AudioWorklet — the worklet asks the main thread for each period through its `MessagePort` and plays from a queue of `buffer_count` (default 3) periods — or, without AudioWorklet, from a `ScriptProcessorNode`. Either way the callback runs on the page's main thread. Browsers only start audio after a user gesture.

**Dummy.** Two devices, "Dummy Duplex" (8 in, 8 out) and "Dummy Output" (2 out), any format and rate. A running stream's timer thread calls the callback once per period, paced by the monotonic clock (late wake-ups are reported as xruns). Its input is deterministic: in a duplex stream each period's input is the previous period's output, channel for channel; in an input-only stream a 440 Hz sine at half scale. With `AUDIO_FLAG_DUMMY_MANUAL`, `AudioStream::pump()` runs one period on the caller's thread — the way to unit-test a callback, and the only mode on targets without threads.

---

## Conversion — `audio_convert.h` {#conversion}

The conversions streams do, for application use; all allocation-free and safe inside a callback.

```c
void audio_f32_to_i16(const &float in, &short out, unsigned long n);   // and back: audio_i16_to_f32
void audio_f32_to_i32(...);  void audio_i32_to_f32(...);
void audio_f32_to_i24(const &float in, &unsigned char out, unsigned long n);   // packed 3-byte
void audio_i24_to_f32(...);
void audio_f32_to_i8(...);   void audio_i8_to_f32(...);
void audio_f32_to_f64(...);  void audio_f64_to_f32(...);
void audio_i16_to_i32(...);  void audio_i32_to_i16(...);
void audio_f32_to_i16_dither(const &float in, &short out, unsigned long n, &unsigned int state);  // TPDF

void audio_interleave_f32(const &float planar, &float out, unsigned long frames, int channels);
void audio_deinterleave_f32(const &float in, &float planar, unsigned long frames, int channels);
void audio_gain_f32(&float buf, unsigned long n, float gain);
void audio_mix_f32(&float dst, const &float src, unsigned long n, float gain);
float audio_peak_f32(const &float buf, unsigned long n);

// std/dsp interop
void audio_f32_to_fixed(const &float in, &Fixed out, unsigned long n);   // Q8.24
void audio_fixed_to_f32(const &Fixed in, &float out, unsigned long n);
unsigned long audio_buffer_write_f32(&AudioBuffer buf, const &float in, unsigned long frames);
unsigned long audio_buffer_read_f32(&AudioBuffer buf, &float out, unsigned long frames);
```

An N-bit integer `s` maps to `s / 2^(N-1)`, so the most negative value is exactly −1.0; float to integer multiplies by `2^(N-1) − 1`, rounds to nearest and saturates. `audio_buffer_write_f32`/`read_f32` move interleaved frames into and out of a [`std::AudioBuffer`](/stdlib/dsp#audiobuffer-lock-free-multi-channel-ring-buffer) — the lock-free SPSC ring std/dsp's filters read from — converting to Q8.24 in fixed-size chunks on the stack: the natural hand-off between a callback and the rest of a program. For sample-rate conversion of recorded material use [`resample_sinc_f32`](/stdlib/dsp#resample-upsampling-downsampling) and friends.

---

## MIDI — `midi.h` {#midi}

```c
struct MidiIn {
    static struct Result<MidiIn, AudioError> open(int api, const &char client_name, unsigned long queue_bytes);
    static struct MidiIn closed();
    int  api() const;
    unsigned int port_count();
    struct Result<MidiPortInfo, AudioError> port_info(unsigned int index);   // .name
    struct AudioError open_port(unsigned int index, const &char port_name);
    struct AudioError open_virtual_port(const &char port_name);
    void close_port();
    int  is_port_open() const;

    generic<T> void set_callback(fn void(&MidiEvent, ?&T) callback, ?&T user);
    void cancel_callback();                                   // back to the queue
    void ignore_types(int sysex, int timing, int active_sensing);   // default: all three ignored
    long get_message(&unsigned char buf, unsigned long cap, &double timestamp);
    unsigned long dropped() const;
    void close();
};

struct MidiOut {
    static struct Result<MidiOut, AudioError> open(int api, const &char client_name);
    // port_count, port_info, open_port, open_virtual_port, close_port, is_port_open, close as above
    struct AudioError send_message(const &unsigned char msg, unsigned long len);   // one whole message
    struct AudioError send(struct MidiMsg msg);
};
```

A `MidiIn`/`MidiOut` is one client of an API. `open_port()` connects to one of the system's ports; `open_virtual_port()` creates a port other programs connect to (ALSA, JACK, Core MIDI and dummy; WinMM and Web MIDI return `AUDIO_ERR_UNSUPPORTED`). The default MIDI API follows the audio one (Core MIDI, WinMM, Web MIDI, ALSA — or JACK while its server runs); `SAFEC_MIDI_API` overrides it, and `MIDI_API_UNSPECIFIED` falls back to the other APIs and finally the dummy loopback.

Input arrives on the backend's thread (the sequencer thread std starts for ALSA, JACK's process thread, Core MIDI's and WinMM's callback threads, the browser's event loop) as complete messages — sysex reassembled, running status expanded, real-time bytes split out. With a callback set, it is called there, under the same rules as an audio callback:

```c
struct MidiEvent {
    double timestamp;                  // seconds since the port opened (monotonic)
    double delta;                      // since the previous message
    struct Slice<unsigned char> bytes; // valid during the callback
    int status() const;
    struct MidiMsg short_msg() const;  // len 0 for sysex
};
```

Without one, messages go into a lock-free queue sized at `open()` (`queue_bytes`, default `MIDI_DEFAULT_QUEUE_BYTES` = 16 KiB): `get_message()` copies the next one out and returns its length, 0 when empty, or `-length` when `buf` is too small (the message stays). A message that doesn't fit is dropped and counted in `dropped()`. Sysex, MIDI clock / time code and active sensing are filtered by default, as RtMidi does — `ignore_types(0, 1, 1)` keeps sysex.

### Messages {#midi-messages}

```c
struct MidiMsg {                      // up to 3 bytes: everything but sysex
    unsigned char data[3];
    int           len;
    static struct MidiMsg note_on(int channel, int note, int velocity);
    static struct MidiMsg note_off(int channel, int note, int velocity);
    static struct MidiMsg poly_pressure(int channel, int note, int pressure);
    static struct MidiMsg control_change(int channel, int controller, int value);
    static struct MidiMsg program_change(int channel, int program);
    static struct MidiMsg channel_pressure(int channel, int pressure);
    static struct MidiMsg pitch_bend(int channel, int value);       // -8192 .. 8191
    static struct MidiMsg song_position(int beats);
    static struct MidiMsg song_select(int song);
    static struct MidiMsg system(int status);                       // MIDI_CLOCK, MIDI_START, ...
    static struct MidiMsg of(const &unsigned char bytes, unsigned long n);
    int status() const;  int type() const;  int channel() const;
    int data1() const;   int data2() const;
    int is_note_on() const;  int is_note_off() const;  int pitch_bend_value() const;
};

int  midi_message_length(int status);       // 1..3, 0 for sysex, -1 for a data byte
int  midi_is_realtime(int status);
long midi_build_sysex(&unsigned char out, unsigned long cap, const &unsigned char payload, unsigned long n);
int  midi_14bit(int lsb, int msb);
```

Channels are 0..15 (masked); data bytes are clamped to 0..127. Status constants: `MIDI_NOTE_OFF` (0x80) … `MIDI_PITCH_BEND` (0xE0), `MIDI_SYSEX`, `MIDI_TIME_CODE`, `MIDI_SONG_POSITION`, `MIDI_SONG_SELECT`, `MIDI_TUNE_REQUEST`, `MIDI_SYSEX_END`, `MIDI_CLOCK`, `MIDI_START`, `MIDI_CONTINUE`, `MIDI_STOP`, `MIDI_ACTIVE_SENSING`, `MIDI_SYSTEM_RESET`.

### Byte streams: `MidiParser`, `MidiEncoder` {#midi-parser}

```c
struct MidiParser {
    static struct MidiParser new(?&unsigned char sysex_buf, unsigned long cap);
    int  feed(int byte);                 // MIDI_PARSE_NONE / _MESSAGE / _SYSEX / _ERROR
    struct MidiMsg message() const;      // after MIDI_PARSE_MESSAGE
    unsigned long sysex_length() const;  // after MIDI_PARSE_SYSEX (in sysex_buf, F0 .. F7)
    void reset();
};

struct MidiEncoder {
    static struct MidiEncoder new(int use_running_status);
    long encode(const &unsigned char msg, unsigned long len, &unsigned char out, unsigned long cap);
    void reset();
};
```

The parser handles running status, real-time bytes anywhere (even inside a sysex), system common messages cancelling running status, an unterminated sysex ending at the next status byte, and sysex longer than its buffer (`MIDI_PARSE_ERROR`, dropped). The backends that hand over raw byte streams (ALSA sequencer decode output, Core MIDI packets, WinMM sysex buffers) go through it. `MidiEncoder` is the reverse for writing a stream (a serial port, a file): it drops a channel message's status byte when the previous one had the same status.

---

## WebAssembly {#webassembly}

On `--target=wasm32` the Web Audio and Web MIDI backends are imports from the JS glue std ships as `std/audio/audio_web.js` (a classic script, an ES module side import, or `require()` under Node — it defines `globalThis.SafeCAudio`):

```js
const env = SafeCAudio.imports({ /* the module's other imports */ });
const { instance } = await WebAssembly.instantiate(bytes, { env });
SafeCAudio.attach(instance);                    // before calling into the module
await SafeCAudio.enableMIDI({ sysex: false });  // optional: Web MIDI ports (a permission prompt)
await SafeCAudio.enableInput();                 // optional: the microphone as an input device
button.onclick = () => { instance.exports.start_audio(); SafeCAudio.resume(); };
```

The module exports what the glue calls back (`std_audio_web_process`, `std_audio_web_buffer`, `std_audio_web_xrun`, `std_midi_web_receive`, `std_midi_web_scratch`); `SafeCAudio.options.worklet = false` forces the ScriptProcessor path, and `options.AudioContext` / `options.navigator` can be replaced (std's tests run the glue under Node with stand-ins). wasm32 has no threads: the dummy audio API runs in manual mode there.

---

## Testing {#testing}

- `tests/stdlib/tests/test_audio.sc` (`safeguard test` in `tests/stdlib`): conversion, the dummy API (manual and threaded), errors, MIDI messages/parser/encoder and the dummy loopback; on Linux, ALSA against the `null`/`file` PCMs, the ALSA sequencer when `/dev/snd/seq` exists, and JACK audio + MIDI loopback against a running server — or one it starts with `jackd -d dummy` if `jackd` is installed. Missing pieces print `SKIP`.
- `tests/stdlib/audio_web/run.sh`: a wasm32 module through `audio_web.js` under Node (AudioWorklet, ScriptProcessor, Web MIDI stand-ins).
- `tests/stdlib/win32/run.sh`: `win32_audio_check` streams through WASAPI and probes WinMM under Wine (give Wine's ALSA driver a null default PCM on a machine without sound hardware).
- `examples/audio_demo` is built and run (`list`, `sine --api dummy`) by the CI smoke test.

## Limitations {#limitations}

- Core Audio and Core MIDI are compiled and layout-checked for macOS but have not yet run on a Mac; WASAPI exclusive mode and WinMM I/O have not run on real Windows hardware.
- Core Audio duplex needs one device for both directions (aggregate devices combine two).
- JACK streams use the server's rate and buffer size; the server is never started for you.
- Web Audio callbacks run on the page's main thread (the AudioWorklet path queues rendered periods); the input device is the microphone stream acquired before probing.
- The dummy MIDI loopback delivers on the sending thread; one sending thread per bus.
- Timestamps are std's monotonic clock at reception (WinMM's driver timestamps on Windows), not the backends' hardware clocks.
