# 9장: 최종 프로젝트 — 키-값 스토어

지금까지의 모든 장은 한 번에 하나의 아이디어를 독립적으로 소개했습니다.
이 마지막 장은 그 반대입니다: 이 책이 다룬 거의 모든 것 — 구조체와
메서드, `?T`와 `match`, `defer`, 그리고 표준 라이브러리의 `HashMap` —
에 기대어, 작지만 정말로 쓸모 있는 것 하나를 만듭니다: 인메모리
키-값 스토어입니다.

## 타입 설계하기 {#designing-the-type}

키-값 스토어에는 항목을 보관할 곳과 그에 대한 몇 가지 연산이
필요합니다. `std::HashMap`(구체적으로는 문자열 키를 위한 편의 함수인
`str_map_*`)이 무거운 작업을 대신 해 주고, `Store`는 우리가 실제로
노출하고 싶은 연산들로 그것을 감쌉니다.

```c
#include <std/collections/map.h>
#include <std/collections/string.h>

struct Store {
    struct HashMap entries;

    void init();
    void set(const char* key, const char* value);
    ?struct String get(const char* key);
    int remove(const char* key);
    void free();
};
```

`get`은 `?struct String`을 반환합니다 — 키가 존재하면 저장된 값의
복사본을, 존재하지 않으면 `null`을 반환합니다. 이것이 바로
[8장](/ko/book/ch08-error-handling)의 패턴이 정확히 제 역할을 하는
모습입니다: "아무것도 못 찾을 수 있다"는 사실이 반환 타입에 바로
드러나 있고, 모든 호출자는 값을 얻기 전에 두 경우 모두를 처리해야
합니다.

## 구현하기 {#implementing-it}

```c
void Store::init() {
    self.entries = std::str_map_new(sizeof(struct String));
}

void Store::set(const char* key, const char* value) {
    struct String v = std::string_from(value);
    unsafe { std::str_map_insert(&self.entries, key, (const void*)&v); }
}
```

`str_map_insert`는 값 인자를 타입 소거된 `const void*`로 받습니다
(맵 자체는 자신이 `struct String`을 저장하고 있다는 것을 알지도
신경 쓰지도 않습니다 — 그것을 기억하는 것은 `Store`의 몫입니다).
이는 정확히 [5장](/ko/book/ch05-understanding-regions)이 `unsafe`가
필요하다고 알려준 종류의 원시 포인터 캐스트입니다. `set` 자체는
작고 정직한 래퍼로 남습니다 — 실제 원소 타입을 알면서 그것을
단언할 의사도 있는 유일한 곳이기 때문입니다.

```c
?struct String Store::get(const char* key) {
    void* slot;
    unsafe { slot = std::str_map_get(&self.entries, key); }
    if (slot == (void*)0) {
        return null;
    }
    struct String* found;
    unsafe { found = (struct String*)slot; }
    return found->clone();
}
```

`str_map_get`은 존재하지 않는 키에 대해 순수 C 스타일로 `NULL`을
반환합니다 — `get`은 그것을 프로그램의 나머지 부분이 기대하는
`?struct String` 형태로 번역합니다. 타입 소거된 맵 API가 타입이
있는 `Store` API를 만나는 바로 이 경계 지점에서요. `.clone()`이
여기서 중요합니다: `slot`은 맵 자체의 저장 공간 *안을* 가리키고
있으며, 복사본이 아니라 그 포인터를 그대로 반환한다면 맵이 결코
리사이즈되지 않거나 해당 항목이 덮어써지지 않는다는 것에 의존하는
라이프타임을 가진 참조를 호출자에게 넘기게 됩니다 — 정확히
[5장](/ko/book/ch05-understanding-regions)이 암묵적으로 두지 않고
명시적으로 만들려고 설계된 종류의 암묵적 라이프타임 의존성입니다.
복제되어 독립적으로 소유된 `String`은 이 질문 자체를 완전히
비켜갑니다.

```c
int Store::remove(const char* key) {
    int r;
    unsafe { r = std::str_map_remove(&self.entries, key); }
    return r;
}

void Store::free() {
    self.entries.free();
}
```

## 조립하기 {#putting-it-together}

```c
int main() {
    struct Store store;
    store.init();
    defer store.free();

    store.set("name", "SafeC");
    store.set("kind", "language");

    ?struct String name = store.get("name");
    match (name) {
        case none:    printf("name: (not found)\n");
        case some(v): printf("name: %s\n", v.as_ptr());
    }

    int removed = store.remove("kind");
    printf("removed kind: %d\n", removed);

    ?struct String kind = store.get("kind");
    match (kind) {
        case none:    printf("kind: (not found, as expected)\n");
        case some(v): printf("kind: %s\n", v.as_ptr());
    }

    return 0;
}
```

```
name: SafeC
removed kind: 1
kind: (not found, as expected)
```

`store.init();` 바로 뒤의 `defer store.free();`는
[8장](/ko/book/ch08-error-handling)의 "획득 바로 다음에 해제" 관용구를
한 번 더 보여줍니다 — 여러분이 직접 SafeC 프로그램을 작성할 때쯤에는
이 짝짓기가 챙겨야 할 무언가가 아니라 자동으로 몸에 밴 것처럼
느껴져야 합니다.

## 여기서 어디로 갈까 {#where-to-go-from-here}

이 `Store`는 의도적으로 작게 만들어졌습니다 — 인메모리 전용이고,
영속성도 없고, 동시 접근도 없습니다. 이것을 확장하는 것은 이 책이
깊이 다룰 여유가 없었던 나머지 것들을 연습하는 합리적인 방법입니다.

- **영속성**: `free()` 시점에 [`std::csv`](/ko/reference/generics)나
  [`std::json`](/ko/stdlib/serial)으로 `entries`를 디스크에
  직렬화하고, `init()` 시점에 다시 불러오세요.
- **동시성**: [`std::sync`](/ko/stdlib/sync)의 뮤텍스로 `entries`에
  대한 접근을 감싸고, `std::spawn`으로 생성한 스레드들 사이에서
  하나의 `Store`를 공유하세요([동시성](/ko/reference/concurrency)
  참고).
- **진짜 아레나**: 항목들이 짧게 살고 일괄적으로 함께 지워진다면
  (예: 요청 단위 캐시), 힙 기반 `String` 값을 `&arena<R>`로 할당된
  것으로 바꾸고, 항목을 하나씩 해제하는 대신 배치 사이마다 아레나를
  리셋하세요 — [5장](/ko/book/ch05-understanding-regions)이 정확히 이
  트레이드오프를 다뤘습니다.

여기서부터는, 이 책이 소개한 어느 한 기능에 대한 완전한 세부 사항이
필요하다면 [레퍼런스](/ko/reference/types) 섹션으로, 이 장이 기댔던
`HashMap`/`String` 조합을 넘어 `std::`가 제공하는 모든 것 — 컬렉션,
네트워킹, 암호화, 직렬화 등등 — 을 확인하려면 [표준
라이브러리](/ko/stdlib/) 섹션으로 가세요. 모두 지금 여러분이 속속들이
알게 된 것과 정확히 같은 리전 및 안전성 규칙 위에 만들어져 있습니다.

읽어 주셔서 감사합니다 — 즐거운 개발 되세요.
