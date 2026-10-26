---
layout: playground
title: 플레이그라운드
summary: 브라우저에서 SafeC 프로그램을 편집하고 공유하세요. 북의 예제도 클릭 한 번이면 됩니다.
code: |
  extern int printf(const char* fmt, ...);

  region Frame { capacity: 4096 }

  struct Particle { float x; float v; };

  // 리전은 타입의 일부: p는 아레나보다 오래 살 수 없습니다.
  void step(&arena<Frame> Particle p, float dt) {
      p.x = p.x + p.v * dt;
  }

  int main() {
      &arena<Frame> Particle p = new<Frame> Particle;
      p.x = 0.0;
      p.v = 2.5;
      for (int i = 0; i < 3; i = i + 1) {
          step(p, 0.5);
          printf("t=%d x=%.2f\n", i, p.x);
      }
      arena_reset<Frame>();   // O(1): 객체별 해제도, GC도 없음
      return 0;
  }
---

## 플레이그라운드 소개 {#about}

- **예제**에서 [SafeC 북](/ko/book/)의 실행 가능한 프로그램을 불러올 수 있습니다. 이 사이트의 다른 모든 SafeC 프로그램에도 이곳에서 여는 *플레이그라운드에서 실행* 링크가 있습니다.
- **공유**는 프로그램을 페이지 주소(`#code=…`, base64url)에 담고 링크를 복사합니다 — 어디에도 업로드되지 않습니다.
- **실행**은 WebAssembly로 빌드한 셀프 호스팅 SafeC 컴파일러로, 백그라운드 워커에서 브라우저 안에서만 프로그램을 컴파일·실행합니다. 프로그램은 `printf`/stdout을 쓸 수 있지만 파일, 네트워크, 스레드는 없습니다. 컴파일러는 SafeC로 작성한 SafeC 컴파일러 scc([selfhost/](https://github.com/safec-org/SafeC/tree/main/selfhost))입니다. `SUBSET.md`에 설명된 언어 부분집합을 받아들이며, 소유권·스레드 안전성 검사는 실행하지 않습니다. 이 검사와 전체 std가 필요하면 로컬에서 실행하세요:

```bash norun
safeguard new hello && cd hello
# 프로그램을 src/main.sc에 붙여 넣기
safeguard run
```
