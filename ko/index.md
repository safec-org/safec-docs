---
layout: home
hero:
  name: SafeC
  text: 안전하고 결정론적인 시스템 프로그래밍
  tagline: 컴파일 타임 메모리 안전성, 숨겨진 비용 없음, 완전한 C ABI 호환성을 갖춘, 리전을 인식하는 C의 진화형.
  actions:
    - theme: brand
      text: 시작하기
      link: /ko/guide/getting-started
    - theme: alt
      text: 언어 레퍼런스
      link: /ko/reference/types
    - theme: alt
      text: GitHub
      link: https://github.com/safec-org/SafeC
features:
  - title: 리전 기반 메모리 안전성
    details: 스택, 힙, 아레나, 정적 리전이 컴파일 타임에 강제됩니다. 가비지 컬렉터도, 런타임 오버헤드도 없습니다.
  - title: 숨겨진 비용 없음
    details: 암묵적 할당도, 숨겨진 런타임도, 백그라운드 GC도, 암묵적 예외도 없습니다. 모든 연산의 비용이 눈에 보입니다.
  - title: C ABI 호환
    details: C 구조체 레이아웃, C 호출 규약, 네이티브 #include &lt;stdio.h&gt; 지원. SafeC 객체를 어떤 C 프로젝트에도 링크할 수 있습니다.
  - title: 컴파일 타임 우선
    details: consteval 함수, static_assert, if const, 단형화를 통한 제네릭. 컴파일 타임에 알 수 있는 모든 것은 컴파일 타임에 처리됩니다.
  - title: 베어메탈 대응
    details: --freestanding 모드, naked 함수, 인터럽트 핸들러, 인라인 어셈블리, volatile I/O, 섹션 배치. 커널과 펌웨어를 만들 수 있습니다.
  - title: 현대적인 언어 기능
    details: 제네릭, 구조체 메서드, 연산자 오버로딩, 패턴 매칭, 옵셔널 타입, 슬라이스, defer, 타입이 있는 채널.
---

## Hello, SafeC

```c
extern int printf(const char* fmt, ...);

region AudioPool { capacity: 65536 }

struct Sample {
    float left;
    float right;
};

int main() {
    // 아레나 할당 — 결정론적이며, malloc을 쓰지 않습니다
    &arena<AudioPool> Sample s = new<AudioPool> Sample;
    s.left = 0.5;
    s.right = -0.3;

    printf("L=%.2f R=%.2f\n", s.left, s.right);
    arena_reset<AudioPool>();
    return 0;
}
```

::: tip 번역 안내
이 문서는 SafeC 공식 문서의 한국어 번역입니다. `region`(리전), `trait`(트레이트), `monomorphization`(단형화) 등 고유한 개념어는 확립된 한국어 기술 문서 관행을 따랐으며, `vtable`처럼 흔히 번역하지 않는 용어는 원어 그대로 두었습니다. 코드, 키워드, 식별자는 번역하지 않았습니다. 오역이나 어색한 표현을 발견하시면 [GitHub](https://github.com/safec-org/SafeC)로 알려주세요.
:::
