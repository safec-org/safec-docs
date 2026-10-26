# 문서 주석

[`safeguard doc`](/ko/advanced/safeguard#safeguard-doc)은 라이브러리의 선언과 거기 붙은 주석으로 API 레퍼런스를 만듭니다. 이 페이지는 그 규칙입니다: 어떤 주석이 무엇을 문서화하는지, 어떻게 쓰는지, 어떤 선언이 나타나는지. [std API 레퍼런스](/api/)도 std 소스에서 같은 방식으로 만들어집니다.

## 문서 주석의 위치 {#placement}

문서 주석은 선언 **바로 위**(사이에 빈 줄 없이)에 있는 주석 줄들이거나, **선언과 같은 줄**에서 선언 뒤에 오는 주석입니다:

```c
/// A point with integer coordinates.
///
/// Created with [Point::new]; compare with [Point::eq].
struct Point {
    int x;   ///< horizontal coordinate
    int y;   ///< vertical coordinate

    /// A point at (`x`, `y`).
    static struct Point new(int x, int y);

    /// Squared distance from the origin.
    long long norm2() const;
};

/** Kinds of shape [shape_area] knows. */
enum ShapeKind {
    SHAPE_SQUARE = 1,   ///< four equal sides
    SHAPE_CIRCLE,       ///< `s` is the radius
};
```

| 형식 | 문서화 대상 |
|------|-----------|
| `/// text`(여러 줄) | 아래의 선언 |
| `/** text */` | 아래의 선언 |
| `int x; ///< text` 또는 `int x; // text` | 같은 줄의 선언 — 필드, 열거자, 짧은 메서드에 쓰는 형식. 그 아래에서 같은 열에 맞춰 이어지는 주석만 있는 줄은 이어지는 내용입니다 |
| `//! text` | 파일 자체(모듈 페이지) |
| `// text`(여러 줄) | 아래의 선언 — **std의 관례**(아래 참고) |

블록에 `///`나 `/**` 줄이 하나라도 있으면 그 줄들만 쓰이므로, 일반 `//` 메모는 문서 주석 옆에 있어도 게시되지 않습니다. 같은 선언에 앞쪽 주석과 같은 줄 주석이 모두 있으면 둘 다 보여 주며 앞쪽 주석이 먼저 옵니다.

선언할 수 있는 모든 것이 문서화됩니다: 네임스페이스 안의 내용(경로와 함께, `geo::Point`), 필드와 메서드를 가진 구조체·공용체, 열거자를 가진 열거형, 필수 메서드를 가진 트레이트, 함수(제네릭 함수는 `generic<T: Bound>` 매개변수와 함께), 매크로, 상수와 전역 변수, 타입 별칭과 newtype.

**헤더에서 선언하고 다른 곳에서 정의한 경우.** 구조체 안에서 선언하고 바깥에서 정의한 메서드(`long long Point::norm2() const { … }`), 또는 헤더에서 선언하고 `.sc` 파일에서 정의한 함수는 하나의 항목입니다: 헤더의 주석이 우선하고, 헤더에 주석이 없으면 정의의 주석이 쓰이며, 페이지는 두 위치 모두로 링크합니다.

## 모듈 문서 {#module-docs}

헤더 하나가 하나의 모듈이며 디렉터리에 따라 중첩됩니다: `include/geo/shapes.h`는 `geo::shapes`입니다. 모듈 문서는 그 파일의 `//!` 줄들입니다:

```c
#pragma once
//! Points and vectors in the plane.
//!
//! ```c
//! struct Point p = Point::new(3, 4);
//! ```
```

`//!` 줄이 없으면, 파일의 첫 주석 블록 뒤에 선언이 아닌 것(빈 줄, `#include`, `#pragma`)이 오는 경우 그 블록이 모듈 문서가 됩니다 — 모든 std 헤더가 쓰는 배치입니다. 라이선스 헤더(`Copyright …`, `SPDX-License-Identifier`)는 건너뜁니다. 패키지의 `README.md`(또는 `[package] readme`)가 첫 페이지가 됩니다.

## Markdown {#markdown}

문서 주석은 Markdown입니다 — GitHub의 표, 취소선, 자동 링크가 더해진 CommonMark. 원시 HTML은 삽입되지 않고 텍스트로 표시됩니다. 문서 주석 안의 제목은 페이지 자체의 제목보다 아래 수준이 되므로, `# Examples`는 항목의 하위 제목이 됩니다.

### 코드 예제 {#code-examples}

펜스 코드 블록은 하이라이트되며, 언어를 지정하지 않은 펜스는 SafeC입니다:

````c
/// Area of a shape of `kind` and size `s`, rounded down.
///
/// # Examples
///
/// ```
/// long long a = geo::shape_area(SHAPE_SQUARE, 3);   // 9
/// ```
long long shape_area(enum ShapeKind kind, int s);
````

예제는 보여 주기만 하고 컴파일하지는 않습니다: 계속 동작해야 하는 예제는 `tests/`에 두세요.

### 문서 내 링크 {#intra-doc-links}

대괄호 안의 이름은 그 항목의 페이지로 링크됩니다:

| 작성 | 링크 대상 |
|-------|----------|
| `[Point]`, `` [`Point`] `` | 구조체 `Point` |
| `[Point::norm2]`, `[Point::norm2()]` | `Point` 페이지의 메서드(`#method.norm2`) |
| `[geo::Point]` | 같은 항목, 네임스페이스 경로로 |
| `[shape_area]` | 함수 |
| `[SHAPE_CIRCLE]`, `[ShapeKind::SHAPE_CIRCLE]` | 열거자 |
| `[geo::shapes]` | 모듈 |
| `[String]`, `[std::Vec::push]` | std의 항목 — [std API 레퍼런스](/api/)로 링크 |
| `[the norm](Point::norm2)` | 임의의 링크 텍스트, 대상은 항목 |

해석되지 않는 이름은 일반 텍스트로 남습니다. 시그니처에서도 해석되는 모든 타입·함수 이름이 같은 방식으로 링크됩니다.

## std의 일반 주석 {#std-convention}

std는 선언 바로 위나 옆의 일반 `//` 주석으로 API를 문서화하며, Markdown이 아닌 산문으로 씁니다. 레퍼런스는 이것도 읽되 한 가지를 조정합니다: 빈 주석 줄이나 `:`로 끝나는 줄 다음에 두 칸 이상 들여쓴 줄들이 이어지면 코드 예제로 봅니다:

```c
// Copies 'len' elements starting at 'items'.
generic<T>
struct Vec<T> Vec::from_arr(const &T items, unsigned long len);

// SafeC Standard Library — Vec<T> (dynamic array)
//
//   struct Vec<int> v = std::Vec::new();
//   v.push(3);
```

구분선 — `// ── Section ──`, `// -----`, `// =====` — 은 주석 블록을 끝냅니다: 그 줄과 그 위의 줄들은 선언의 문서에 포함되지 않습니다. 새 코드에서는 `///`를 권장합니다: 모호하지 않은 Markdown이고, `///` 블록은 근처의 `//` 메모를 문서에서 제외합니다.

## 문서화되는 것 {#visibility}

- 헤더가 있는 패키지에서 레퍼런스는 그 **헤더**(`include/`와 `src/`)입니다: 의존하는 쪽이 `#include`할 수 있는 것. `.sc` 파일에만 있는 선언은 구현입니다. 헤더가 없는 패키지는 `.sc` 파일을 문서화합니다.
- `static` 함수와 전역 변수는 파일에 비공개이므로 제외됩니다.
- `_`로 시작하거나 끝나는 이름(std의 타입 소거 `_raw_` 헬퍼)은 숨겨지며, 문서 주석에 `@hidden`(또는 `#[doc(hidden)]`)만 있는 줄이 있는 선언도 숨겨집니다.
- C 함수의 `extern` 선언은 문서 주석이 있을 때만 표시됩니다.

`safeguard doc --private`은 숨겨진 항목도 보여 줍니다.
