# 1장: 시작하기

## SafeC 설치하기 {#installing-safec}

```bash
curl -fsSL https://raw.githubusercontent.com/safec-org/SafeC/main/install.sh | bash
```

이 명령은 세 개의 바이너리를 설치합니다 — `safec`(컴파일러), `safeguard`
(패키지 매니저이자 빌드 도구로, Rust의 `cargo`와 같은 역할을 합니다),
그리고 `sc-lsp`(에디터 연동을 위한 언어 서버)입니다. 또한 셸을 설정하여 세
바이너리 모두 `PATH`에서 사용할 수 있게 합니다. 스크립트가 끝나면 셸을
재시작하거나(또는 rc 파일을 source하고), 제대로 동작하는지 확인해 보세요.

```bash
safec --version
```

설치 스크립트의 옵션(커스텀 설치 경로, 특정 릴리스 버전, 프리빌드 릴리스
대신 소스에서 빌드하기 등)에 대해서는 가이드 섹션의
[시작하기](/ko/guide/getting-started)를 참고하세요 — 이 장에서는 첫 프로그램을
작성하고 실행하는 데 필요한 내용만 다룹니다.

## `safeguard`: 프로젝트 생성하기 {#safeguard-creating-a-project}

실제 SafeC 프로젝트는 `safec`를 직접 호출하지 않고 `safeguard`를
사용합니다 — 표준 라이브러리, 의존성, 그리고 컴파일 후 링크하는 과정을
대신 처리해 주기 때문입니다. 프로젝트를 하나 만들어 봅시다.

```bash
safeguard new hello
cd hello
```

다음과 같은 구조가 생성됩니다.

```
hello/
├── Package.toml
└── src/
    └── main.sc
```

`Package.toml`은 프로젝트 매니페스트입니다(이름, 버전, 의존성 —
[패키지 매니저](/ko/advanced/safeguard) 문서에서 나중에 전체 구조를 다룹니다).
`src/main.sc`는 진입점이며, 지금 그대로도 컴파일됩니다.

```bash
safeguard run
```

```
Hello from SafeC!
```

## SafeC 프로그램의 구조 {#anatomy-of-a-safec-program}

`src/main.sc`를 열어 보세요.

```c
extern int printf(const char* fmt, ...);

int main() {
    printf("Hello from SafeC!\n");
    return 0;
}
```

C를 써 본 적이 있다면 이 코드의 모든 줄이 이미 익숙할 것입니다 — 의도된
결과입니다. SafeC는 C 구문의 엄격한 상위 집합이며, 짚고 넘어갈 만한 유일한
줄은 `extern` 선언입니다. `printf`는 SafeC 함수가 아니라 C 표준
라이브러리 함수이므로, 호출하기 전에 명시적인 시그니처가 필요합니다 —
일반 C에서 `#include <stdio.h>`가 해 주는 것처럼 SafeC가 `printf`가
무엇인지 암묵적으로 알지는 못합니다. 물론 `#include <stdio.h>`를 그대로
사용할 수도 있습니다.

```c
#include <stdio.h>

int main() {
    printf("Hello from SafeC!\n");
    return 0;
}
```

이렇게 하면 SafeC가 `printf`의 선언(및 헤더 안의 다른 모든 것)을
자동으로 가져옵니다 — 컴파일러가 내부적으로 `clang`을 호출하여 헤더를
읽고 필요한 선언을 추출합니다. 두 형태 모두 동일한 결과로 컴파일됩니다.
이 책은 대부분 각 예제에 필요한 소수의 C 함수에 대해 명시적인 `extern`
선언을 직접 작성합니다. 그래야 예제의 모든 의존성이 헤더 include 뒤에
숨지 않고 스니펫 자체에서 드러나기 때문입니다.

## 편집-컴파일-실행 루프 {#the-edit-compile-run-loop}

`safeguard run`은 실제로는 세 단계입니다 — `safeguard build`(`src/`
아래 모든 것을 컴파일하여 `build/hello`로 링크)를 실행한 다음 결과물을
실행합니다. 반복 작업 중이라면 `safeguard check`가 더 빠릅니다 — 프론트
엔드 전체를 실행하므로(모든 타입 오류와 리전 안전성 위반이 여전히
잡힙니다) 어셈블 및 링크 단계는 건너뛰는데, 이 단계가 보통 대부분의
소요 시간을 차지하기 때문입니다. 컴파일 오류를 고치는 동안에는 `check`를,
실제로 실행되는 모습을 보고 싶을 때는 `run`을 사용하세요.

```bash
safeguard check   # 빠름: 뭔가 깨뜨리지 않았는지 확인
safeguard run     # 느림: 빌드하고 실행
```

## `unsafe`에 대한 짧은 안내 {#a-note-on-unsafe}

`unsafe` 키워드는 5장에서 이것이 정확히 무엇을 막아 주는지 제대로 설명하기
훨씬 전인, 다음 몇 장에서부터 등장하기 시작합니다. 한 문장으로 미리
설명하자면: SafeC는 모든 참조가 어디를 가리키는지, 그리고 여전히
유효한지를 컴파일 타임에 추적합니다 — 원시 포인터를 역참조하거나, 참조를
원시 포인터로 변환하는 것은 컴파일러가 검증할 수 있는 범위를 벗어나는
행위이므로 명시적으로 표시해야 합니다. 5장에서 이 내용을 실제로 제대로
설명합니다. 그때까지는 `unsafe { ... }`를 "날 믿어라"라는 표시로 여기고
넘어가세요 — 전체 그림은 곧 보게 될 것입니다.

다음: [첫 번째 프로그램](/ko/book/ch02-first-program)에서는 "Hello, world"보다
조금 더 본격적인 것을 작성해 봅니다.
