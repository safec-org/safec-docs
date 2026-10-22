# 오디오 & MIDI (`std/audio/`)

RtAudio와 RtMidi의 정신을 따라, 각 플랫폼의 네이티브 시스템 위에서 동작하는 실시간 오디오 및 MIDI 입출력입니다: 장치와 포트를 열거하고, 오디오 스레드에서 실행되는 콜백으로 출력·입력·듀플렉스 스트림을 열고, MIDI를 보내고 받습니다. 오류는 예외가 아니라 값(`AudioError`, `std::Result<T, AudioError>`)으로 돌아오며, 실시간 경로는 아무것도 할당하지 않고 락도 잡지 않습니다.

```c
#include <std/audio/audio.h>          // devices, streams, the audio callback
#include <std/audio/midi.h>           // MIDI ports, messages, parser
#include <std/audio/audio_convert.h>  // sample-format / layout conversion, std/dsp interop
```

`std/prelude.h`는 이 헤더들을 포함하지 않습니다 — 직접 include합니다. 모두 `audio` std 섹션(`lib/<triple>/std/audio.a`, `install.sh --std=+audio`)에 들어 있습니다.

---

## 백엔드 {#backends}

| 오디오 API | `AUDIO_API_*` | 플랫폼 | 파일 | 검증 |
|---|---|---|---|---|
| ALSA PCM | `ALSA` | Linux | `audio_alsa_linux.sc` | `null`·`file` PCM에서 실행됨(파일로 재생, ALSA 설정을 통한 파일에서의 캡처), 장치 0개 프로브 |
| JACK | `JACK` | Linux, macOS | `audio_jack_posix.sc` | `jackd -d dummy`에서 실행됨: 한 스트림이 JACK 그래프를 거쳐 두 번째 스트림으로 재생 |
| Core Audio (HAL) | `COREAUDIO` | macOS | `audio_coreaudio_macos.sc` | `x86_64-apple-macos` / `arm64-apple-macos`용으로 컴파일됨(IR과 오브젝트, `static_assert`로 검사한 구조체 레이아웃); 아직 Mac에서 실행하지 않음 |
| WASAPI | `WASAPI` | Windows | `audio_wasapi_win32.sc` | Wine 9에서 실행됨(`tests/stdlib/win32/run.sh`): 열거, 공유 모드 출력·입력·듀플렉스 스트림; 배타 모드는 컴파일만 됨(Wine이 거부) |
| Web Audio | `WEBAUDIO` | wasm32 | `audio_webaudio_wasm.sc` + `audio_web.js` | `AudioContext`, `AudioWorklet`, `ScriptProcessorNode` 대역을 둔 Node에서 실행됨(`tests/stdlib/audio_web/run.sh`) |
| 더미 | `DUMMY` | 모든 곳 | `audio_dummy.sc` | 단위 테스트됨; CI 스모크 테스트가 이것으로 재생 |

| MIDI API | `MIDI_API_*` | 플랫폼 | 파일 | 가상 포트 | 검증 |
|---|---|---|---|---|---|
| ALSA 시퀀서 | `ALSA` | Linux | `midi_alsa_linux.sc` | 예 | 컴파일됨; `/dev/snd/seq`가 없으면 `AUDIO_ERR_UNAVAILABLE`을 보고(시퀀서가 있는 곳에서는 테스트가 가상 포트로 루프백) |
| JACK MIDI | `JACK` | Linux, macOS | `midi_jack_posix.sc` | 예 | `jackd -d dummy`에서 실행됨: 가상 포트를 통한 노트, CC, 피치 벤드, sysex |
| Core MIDI | `COREMIDI` | macOS | `midi_coremidi_macos.sc` | 예 | macOS용으로 컴파일됨; 아직 실행하지 않음 |
| WinMM | `WINMM` | Windows | `midi_winmm_win32.sc` | 아니오 | Wine에서 실행됨(클라이언트/포트 열거; 이 환경의 Wine에는 MIDI 포트가 없음) |
| Web MIDI | `WEBMIDI` | wasm32 | `midi_webmidi_wasm.sc` + `audio_web.js` | 아니오 | `navigator.requestMIDIAccess` 대역을 둔 Node에서 실행됨 |
| 더미 | `DUMMY` | 모든 곳 | `midi_dummy.sc` | 예 | 단위 테스트됨(프로세스 내 루프백) |

`safeguard`는 타깃용 백엔드를, 그것만 빌드합니다: 플랫폼별 파일에는 그 파일을 고르는 접미사(`_linux`, `_posix`, `_macos`, `_win32`, `_wasm`)가 붙어 있고, 각 파일은 자신의 시스템 라이브러리를 링크에 가져옵니다 — `Package.toml`에 적을 것이 없습니다:

| 타깃 | 링크됨 | 실행 시점에 로드 |
|---|---|---|
| Linux | `-ldl -lpthread` | `libasound.so.2`(ALSA PCM과 시퀀서), `libjack.so.0` |
| macOS | `-framework CoreAudio -framework CoreMIDI -framework CoreFoundation` | `libjack.0.dylib`(Homebrew 또는 `/usr/local`) |
| Windows | `-lole32 -lwinmm` | `avrt.dll`(MMCSS) |
| wasm32 | 없음: `audio_web.js`에서 가져오는 임포트 | |

ALSA와 JACK은 `dlopen()`으로 열리므로, `std/audio`로 빌드한 프로그램은 그것들이 없는 머신에서도 시작됩니다: API는 `AUDIO_ERR_UNAVAILABLE`(라이브러리 없음, JACK 서버 없음)을 보고하고 `Audio::open(AUDIO_API_UNSPECIFIED)`는 다음 API로 넘어갑니다.

---

## 빠른 시작 {#quick-start}

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

`examples/audio_demo`가 완전한 버전입니다: `audio_demo list`는 컴파일된 모든 API의 장치와 MIDI 포트를 출력하고, `audio_demo sine`은 톤을 재생하며, `audio_demo midi`는 MIDI 입력을 모니터링합니다. `--api dummy`는 어디서나 동작합니다.

---

## 실시간 규칙 {#real-time-rules}

콜백은 백엔드의 실시간 스레드에서 실행됩니다 — std가 시작한 스레드(ALSA, WASAPI, 더미), JACK의 process 스레드, Core Audio의 IOProc 스레드, 또는 브라우저의 오디오 렌더링. 그 경로에서 std 자신은:

- 아무것도 할당하지 않습니다: 모든 버퍼(포맷 변환, 인터리브, 채널 오프셋, WASAPI 캡처 FIFO, MIDI 큐)는 스트림이나 포트를 열 때 할당됩니다;
- 락을 잡지 않습니다: 스트림 상태, 상태 플래그, 스트림 시계는 원자적 워드이고, MIDI 입력 큐는 단일 생산자/단일 소비자 링입니다;
- 블록하거나 출력하지 않습니다.

콜백도 같은 방식으로 작성합니다: `new`/`alloc` 없이, 뮤텍스 없이, 파일이나 콘솔 I/O 없이. 다른 스레드에는 락 없는 구조(std/dsp의 [`AudioBuffer`](/ko/stdlib/dsp#audiobuffer-lock-free-multi-channel-ring-buffer), 아래의 `audio_buffer_write_f32()`, std/sync의 링 버퍼)로 데이터를 넘깁니다. `start()`, `stop()`, `abort()`, `close()`는 제어 스레드용 호출입니다 — 스트림 스레드를 join하거나 장치를 비울(drain) 수 있습니다 — 그러므로 콜백에서 호출하지 말고, 대신 `AUDIO_CB_STOP`(대기 중인 것을 재생한 뒤 정지) 또는 `AUDIO_CB_ABORT`(즉시 정지)를 반환합니다. `AUDIO_FLAG_SCHEDULE_REALTIME`은 std가 시작한 스트림 스레드를 `SCHED_FIFO`(POSIX) 또는 MMCSS "Pro Audio" 클래스(Windows; WASAPI 스트림은 항상 사용)로 올립니다.

---

## 오디오 API — `audio.h` {#audio-api}

### API {#apis}

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

기본 API는 macOS에서 Core Audio, Windows에서 WASAPI, wasm32에서 Web Audio, Linux에서 ALSA입니다 — 또는 Linux와 macOS에서 JACK 서버가 실행 중일 때는 JACK입니다. 환경 변수 `SAFEC_AUDIO_API`(`alsa`, `jack`, `dummy`, ...)가 이를 덮어씁니다. `Audio::open(AUDIO_API_UNSPECIFIED)`는 기본 API를 엽니다; 장치가 없으면 장치가 있는 다른 사용 가능한 첫 API를; 어느 것에도 없으면 장치 0개인 기본 API를; 그리고 다른 어떤 것도 열리지 않았을 때만 더미 API를 엽니다.

### `Audio` — 한 API의 장치 목록 {#audio}

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

사운드 하드웨어가 없는 머신에서도 열립니다: `device_count()`가 0입니다. 장치 id는 `Audio`가 살아 있는 동안 안정적이며 — 사라졌다가 `rescan()` 뒤에 돌아온 장치는 같은 id를 유지합니다 — `open_stream`이 받는 값입니다; `AUDIO_DEFAULT_DEVICE`(0)는 API의 기본 장치(또는 그 방향으로 채널이 있는 첫 장치)를 뜻합니다.

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

### 스트림 열기 {#opening-a-stream}

출력 파라미터, 입력 파라미터, 또는 둘 다(듀플렉스)를 줍니다. `format`은 콜백이 보는 포맷이며, std가 장치가 실행하는 포맷으로 변환합니다.

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

| 포맷 | 샘플 |
|---|---|
| `AUDIO_FORMAT_I8` | `signed char` |
| `AUDIO_FORMAT_I16` | `short` |
| `AUDIO_FORMAT_I24` | 3바이트, 패킹, 리틀 엔디언 |
| `AUDIO_FORMAT_I32` | `int` |
| `AUDIO_FORMAT_F32` | `float`, 공칭 −1..1 |
| `AUDIO_FORMAT_F64` | `double`, 공칭 −1..1 |

| 플래그 | 효과 |
|---|---|
| `AUDIO_FLAG_NONINTERLEAVED` | 콜백 버퍼가 채널 우선(채널마다 `frames`개 샘플) |
| `AUDIO_FLAG_MINIMIZE_LATENCY` | 장치가 허용하는 가장 작은 주기 |
| `AUDIO_FLAG_EXCLUSIVE` | WASAPI 배타 모드, Core Audio hog 모드, plug 계층 없는 ALSA |
| `AUDIO_FLAG_SCHEDULE_REALTIME` | std가 시작한 스트림 스레드에 실시간 우선순위 |
| `AUDIO_FLAG_ALSA_USE_DEFAULT` | ALSA: `default` PCM을 염 |
| `AUDIO_FLAG_JACK_DONT_CONNECT` | JACK: 포트를 등록하고 아무것도 연결하지 않음 |
| `AUDIO_FLAG_DUMMY_MANUAL` | 더미: 타이머 스레드 없음; `AudioStream::pump()`로 구동 |

`frames`는 요청하는 주기(0 = API 기본값)이며 장치가 쓰는 값을 돌려받습니다 — JACK과 배타 모드 WASAPI는 그것을 지정하고, ScriptProcessor 기반 Web Audio는 2의 거듭제곱으로 올립니다. 콜백은 항상 그만큼의 프레임을 받습니다.

### 콜백 {#the-callback}

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

타입이 있는 뷰는 버퍼 위의 경계 검사 [`Slice<T>`](/ko/stdlib/collections#slice)입니다: `frames * channels`개 샘플이며, 스트림이 non-interleaved가 아니면 인터리브(프레임 우선)입니다. `status`에는 이전 콜백 이후 백엔드가 본 xrun이 담깁니다: ALSA의 `-EPIPE`, JACK의 xrun 콜백, Core Audio의 프로세서 과부하, WASAPI의 데이터 불연속과 빈 렌더 버퍼, 말라 버린 AudioWorklet 큐, 더미 API의 늦은 깨어남.

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

정지된 스트림은 다시 시작할 수 있습니다. 실행 중인 스트림의 `start()`와 정지된 스트림의 `stop()`은 `AUDIO_ERR_WARNING`을 반환합니다(`ok()`가 받아들임). 실행 중 장치가 실패하면 — 분리, JACK 서버 종료, xrun 복구 실패 — 스트림은 스스로 정지하고 `last_error()`가 이유를 알려 줍니다.

### 오류 {#errors}

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

| 코드 | 의미 |
|---|---|
| `AUDIO_ERR_WARNING` | 치명적이지 않음(이미 실행 중, 실시간 우선순위 거부) |
| `AUDIO_ERR_UNSUPPORTED` | API가 컴파일되어 있지 않거나 이것을 할 수 없음(WinMM 가상 포트) |
| `AUDIO_ERR_NO_DEVICES` | 그 방향의 장치가 없음 |
| `AUDIO_ERR_INVALID_DEVICE` | 그 id나 인덱스의 장치/포트가 없음 |
| `AUDIO_ERR_INVALID_PARAM` | 장치나 API가 할 수 없는 채널, 레이트, 포맷 |
| `AUDIO_ERR_INVALID_USE` | 잘못된 상태(닫힌 스트림, 이미 열린 포트) |
| `AUDIO_ERR_DEVICE_BUSY` | 장치 사용 중(배타 모드, ALSA hw 장치) |
| `AUDIO_ERR_SYSTEM` | OS 또는 라이브러리 호출 실패(그 메시지, HRESULT, OSStatus 포함) |
| `AUDIO_ERR_THREAD` | 스트림 스레드를 시작할 수 없음 |
| `AUDIO_ERR_NO_MEMORY` | 할당 실패(열 때만) |
| `AUDIO_ERR_UNAVAILABLE` | 백엔드의 라이브러리나 서버가 없음 |

---

## 백엔드 참고 {#backend-notes}

**ALSA.** 장치는 ALSA의 `default` PCM(열릴 때)과 컨트롤 인터페이스가 나열하는 모든 `hw:CARD,DEV`이며, 채널·포맷·표준 레이트를 논블로킹으로 프로브합니다. `AudioStreamOptions.device_name`은 다른 PCM을 이름으로 엽니다 — `null`, `plughw:1,0`, `file:FILE=out.raw,FORMAT=raw`, `~/.asoundrc`의 PCM — std의 테스트는 이렇게 하드웨어 없이 실행됩니다. 스트림 스레드는 한 주기씩 블로킹 `snd_pcm_readi`/`writei`(non-interleaved만 되는 하드웨어는 `readn`/`writen`)를 하며, xrun은 `snd_pcm_prepare()`로 복구합니다. 장치 포맷은 하드웨어가 받으면 스트림의 포맷이고, 아니면 가진 것 중 최선(f32, i32, 패킹 i24, i16, f64, i8)입니다; 레이트를 거부하는 `hw:` 장치는 `AUDIO_FLAG_EXCLUSIVE`가 아니면 `plughw:`로 다시 엽니다. ALSA 자신의 stderr 메시지는 숨겨지고 `AudioError`로 전달됩니다.

**JACK.** 오디오 포트가 있는 모든 JACK 클라이언트가 장치입니다 — `system`(하드웨어, 기본값)과 다른 SafeC 스트림을 포함한 모든 애플리케이션 — 클라이언트의 입력 포트가 출력 채널, 출력 포트가 입력 채널입니다. 각 스트림은 포트 `out_1..`/`in_1..`을 가진 자신만의 클라이언트(`stream_name`, 기본값 "SafeC")이며, `start()`에서 장치의 포트에 `first_channel`부터 연결됩니다. JACK은 그래프 전체를 하나의 레이트와 버퍼 크기로 실행합니다: 서버의 레이트를 요청해야 하며(아니면 `AUDIO_ERR_INVALID_PARAM`), `frames`는 서버의 버퍼 크기로 돌아옵니다. 서버를 자동으로 시작하지는 않습니다.

**Core Audio.** HAL의 장치(`kAudioHardwarePropertyDevices`)이며, HAL이 자신의 실시간 스레드에서 호출하는 `AudioDeviceIOProc`로 엽니다. std는 장치의 버퍼 프레임 크기와 공칭 샘플 레이트를 요청값으로 설정하고, `AUDIO_FLAG_EXCLUSIVE`면 hog 모드를 잡습니다. 듀플렉스 스트림은 두 방향에 하나의 장치를 씁니다 — 둘을 합치려면 집합 장치(Audio MIDI 설정)를 만듭니다.

**WASAPI.** 렌더와 캡처 엔드포인트는 서로 다른 장치입니다. 공유 모드(기본값)는 엔진의 믹스 포맷을 쓰고 다른 레이트는 엔진이 리샘플합니다; `AUDIO_FLAG_EXCLUSIVE`는 스트림의 포맷, f32, i32, i24, i16 중 장치가 받는 첫 포맷으로 배타 모드를 열며, 드라이버가 요구하면 버퍼를 다시 정렬합니다. 둘 다 하나의 스트림 스레드(MMCSS "Pro Audio")에서 이벤트 기반으로 동작하며, 듀플렉스 주기가 맞도록 캡처 패킷은 FIFO를 거칩니다. std는 자신이 쓰는 스레드에서 COM을 멀티스레드로 초기화합니다.

**Web Audio.** wasm32 모듈은 `std/audio/audio_web.js`를 호출합니다([WebAssembly](#webassembly) 참고): "Web Audio" 장치 하나, 스테레오 출력, 그리고 페이지가 `SafeCAudio.enableInput()`을 호출했다면 마이크를 입력으로 갖습니다. 글루는 AudioWorklet에서 콜백을 구동합니다 — 워크릿이 `MessagePort`로 메인 스레드에 주기마다 요청하고 `buffer_count`(기본값 3)개 주기의 큐에서 재생합니다 — AudioWorklet이 없으면 `ScriptProcessorNode`에서 구동합니다. 어느 쪽이든 콜백은 페이지의 메인 스레드에서 실행됩니다. 브라우저는 사용자 제스처 뒤에만 오디오를 시작합니다.

**더미.** "Dummy Duplex"(입력 8, 출력 8)와 "Dummy Output"(출력 2) 두 장치이며, 어떤 포맷과 레이트든 받습니다. 실행 중인 스트림의 타이머 스레드가 단조 시계에 맞춰 주기마다 콜백을 호출합니다(늦은 깨어남은 xrun으로 보고). 입력은 결정적입니다: 듀플렉스 스트림에서는 각 주기의 입력이 이전 주기의 출력(채널 대 채널)이고, 입력 전용 스트림에서는 절반 크기의 440 Hz 사인파입니다. `AUDIO_FLAG_DUMMY_MANUAL`이면 `AudioStream::pump()`가 호출한 스레드에서 한 주기를 실행합니다 — 콜백을 단위 테스트하는 방법이며, 스레드가 없는 타깃의 유일한 모드입니다.

---

## 변환 — `audio_convert.h` {#conversion}

스트림이 하는 변환을 애플리케이션용으로 제공합니다; 모두 할당이 없고 콜백 안에서 안전합니다.

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

N비트 정수 `s`는 `s / 2^(N-1)`로 대응되므로 가장 작은 음수 값은 정확히 −1.0입니다; float에서 정수로는 `2^(N-1) − 1`을 곱하고 가장 가까운 값으로 반올림하며 포화시킵니다. `audio_buffer_write_f32`/`read_f32`는 인터리브된 프레임을 [`std::AudioBuffer`](/ko/stdlib/dsp#audiobuffer-lock-free-multi-channel-ring-buffer) — std/dsp의 필터가 읽는 락 없는 SPSC 링 — 로 넣고 꺼내며, 스택 위의 고정 크기 청크 단위로 Q8.24로 변환합니다: 콜백과 프로그램의 나머지 사이의 자연스러운 전달 수단입니다. 녹음된 데이터의 샘플 레이트 변환에는 [`resample_sinc_f32`](/ko/stdlib/dsp#resample-upsampling-downsampling) 등을 씁니다.

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

`MidiIn`/`MidiOut`은 API의 클라이언트 하나입니다. `open_port()`는 시스템의 포트 하나에 연결하고, `open_virtual_port()`는 다른 프로그램이 연결하는 포트를 만듭니다(ALSA, JACK, Core MIDI, 더미; WinMM과 Web MIDI는 `AUDIO_ERR_UNSUPPORTED`를 반환). 기본 MIDI API는 오디오 쪽을 따르며(Core MIDI, WinMM, Web MIDI, ALSA — 또는 서버가 실행 중일 때 JACK), `SAFEC_MIDI_API`가 이를 덮어쓰고, `MIDI_API_UNSPECIFIED`는 다른 API들로, 마지막에는 더미 루프백으로 넘어갑니다.

입력은 백엔드의 스레드(ALSA용으로 std가 시작한 시퀀서 스레드, JACK의 process 스레드, Core MIDI와 WinMM의 콜백 스레드, 브라우저의 이벤트 루프)에서 완전한 메시지로 도착합니다 — sysex는 재조립되고, running status는 펼쳐지고, 실시간 바이트는 분리됩니다. 콜백이 설정되어 있으면 그곳에서, 오디오 콜백과 같은 규칙으로 호출됩니다:

```c
struct MidiEvent {
    double timestamp;                  // seconds since the port opened (monotonic)
    double delta;                      // since the previous message
    struct Slice<unsigned char> bytes; // valid during the callback
    int status() const;
    struct MidiMsg short_msg() const;  // len 0 for sysex
};
```

콜백이 없으면 메시지는 `open()`에서 크기를 정한 락 없는 큐(`queue_bytes`, 기본값 `MIDI_DEFAULT_QUEUE_BYTES` = 16 KiB)에 들어갑니다: `get_message()`는 다음 메시지를 복사해 내고 그 길이를, 비어 있으면 0을, `buf`가 너무 작으면 `-길이`를 반환합니다(메시지는 남아 있음). 들어가지 않는 메시지는 버려지고 `dropped()`에 세어집니다. RtMidi처럼 sysex, MIDI 클록/타임 코드, active sensing은 기본적으로 걸러집니다 — `ignore_types(0, 1, 1)`이면 sysex를 남깁니다.

### 메시지 {#midi-messages}

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

채널은 0..15(마스크됨)이고 데이터 바이트는 0..127로 제한됩니다. 상태 상수: `MIDI_NOTE_OFF`(0x80) … `MIDI_PITCH_BEND`(0xE0), `MIDI_SYSEX`, `MIDI_TIME_CODE`, `MIDI_SONG_POSITION`, `MIDI_SONG_SELECT`, `MIDI_TUNE_REQUEST`, `MIDI_SYSEX_END`, `MIDI_CLOCK`, `MIDI_START`, `MIDI_CONTINUE`, `MIDI_STOP`, `MIDI_ACTIVE_SENSING`, `MIDI_SYSTEM_RESET`.

### 바이트 스트림: `MidiParser`, `MidiEncoder` {#midi-parser}

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

파서는 running status, 어디에나(sysex 안에서도) 나타나는 실시간 바이트, running status를 취소하는 시스템 공통 메시지, 다음 상태 바이트에서 끝나는 종결되지 않은 sysex, 버퍼보다 긴 sysex(`MIDI_PARSE_ERROR`, 버려짐)를 처리합니다. 원시 바이트 스트림을 넘기는 백엔드(ALSA 시퀀서 디코드 출력, Core MIDI 패킷, WinMM sysex 버퍼)는 이 파서를 거칩니다. `MidiEncoder`는 스트림(시리얼 포트, 파일)을 쓸 때의 반대 방향입니다: 이전 메시지와 상태가 같은 채널 메시지의 상태 바이트를 생략합니다.

---

## WebAssembly {#webassembly}

`--target=wasm32`에서 Web Audio와 Web MIDI 백엔드는 std가 `std/audio/audio_web.js`로 제공하는 JS 글루의 임포트입니다(클래식 스크립트, ES 모듈 부수 효과 임포트, 또는 Node의 `require()` — `globalThis.SafeCAudio`를 정의합니다):

```js
const env = SafeCAudio.imports({ /* the module's other imports */ });
const { instance } = await WebAssembly.instantiate(bytes, { env });
SafeCAudio.attach(instance);                    // before calling into the module
await SafeCAudio.enableMIDI({ sysex: false });  // optional: Web MIDI ports (a permission prompt)
await SafeCAudio.enableInput();                 // optional: the microphone as an input device
button.onclick = () => { instance.exports.start_audio(); SafeCAudio.resume(); };
```

모듈은 글루가 다시 호출하는 함수(`std_audio_web_process`, `std_audio_web_buffer`, `std_audio_web_xrun`, `std_midi_web_receive`, `std_midi_web_scratch`)를 export합니다; `SafeCAudio.options.worklet = false`는 ScriptProcessor 경로를 강제하고, `options.AudioContext` / `options.navigator`는 바꿀 수 있습니다(std의 테스트는 대역을 둔 Node에서 글루를 실행). wasm32에는 스레드가 없으므로 그곳에서 더미 오디오 API는 수동 모드로 동작합니다.

---

## 테스트 {#testing}

- `tests/stdlib/tests/test_audio.sc`(`tests/stdlib`에서 `safeguard test`): 변환, 더미 API(수동과 스레드), 오류, MIDI 메시지/파서/인코더와 더미 루프백; Linux에서는 `null`/`file` PCM에 대한 ALSA, `/dev/snd/seq`가 있으면 ALSA 시퀀서, 실행 중인 서버 — 또는 `jackd`가 설치되어 있으면 `jackd -d dummy`로 직접 시작한 서버 — 에 대한 JACK 오디오 + MIDI 루프백. 빠진 부분은 `SKIP`을 출력합니다.
- `tests/stdlib/audio_web/run.sh`: Node에서 `audio_web.js`를 거치는 wasm32 모듈(AudioWorklet, ScriptProcessor, Web MIDI 대역).
- `tests/stdlib/win32/run.sh`: `win32_audio_check`가 Wine에서 WASAPI로 스트리밍하고 WinMM을 프로브합니다(사운드 하드웨어가 없는 머신에서는 Wine의 ALSA 드라이버에 null 기본 PCM을 줍니다).
- `examples/audio_demo`는 CI 스모크 테스트가 빌드하고 실행합니다(`list`, `sine --api dummy`).

## 제한 사항 {#limitations}

- Core Audio와 Core MIDI는 macOS용으로 컴파일되고 레이아웃이 검사되었지만 아직 Mac에서 실행하지 않았습니다; WASAPI 배타 모드와 WinMM I/O는 실제 Windows 하드웨어에서 실행하지 않았습니다.
- Core Audio 듀플렉스는 두 방향에 하나의 장치가 필요합니다(집합 장치가 둘을 합칩니다).
- JACK 스트림은 서버의 레이트와 버퍼 크기를 씁니다; 서버를 대신 시작해 주지 않습니다.
- Web Audio 콜백은 페이지의 메인 스레드에서 실행됩니다(AudioWorklet 경로는 렌더된 주기를 큐에 쌓습니다); 입력 장치는 프로브 전에 얻은 마이크 스트림입니다.
- 더미 MIDI 루프백은 보내는 스레드에서 전달합니다; 버스마다 보내는 스레드는 하나입니다.
- 타임스탬프는 수신 시점의 std 단조 시계입니다(Windows에서는 WinMM 드라이버 타임스탬프) — 백엔드의 하드웨어 시계가 아닙니다.
