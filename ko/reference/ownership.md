# 소유권과 Drop

SafeC에는 암묵적인 소멸자가 없습니다. 소스에 적혀 있지 않으면 스코프가 끝날 때 아무것도 실행되지 않습니다. 대신 컴파일러는 작성한 코드가 소유한 모든 자원을 정확히 한 번 해제하는지 컴파일 타임에 검사합니다. 타입은 `drop()`을 정의함으로써 이 검사에 참여하며, 그때부터 컴파일러는 그 타입의 모든 값을 추적하여 값을 누수하거나, drop 또는 move한 뒤에 사용하거나, 두 번 drop하는 프로그램을 거부합니다.

검사는 전적으로 컴파일 타임에 이루어집니다. 호출도, 플래그도, 언와인딩 테이블도 삽입하지 않습니다. 생성되는 코드는 작성한 코드 그대로입니다.

## Drop 트레이트 {#the-drop-trait}

`Drop`은 내장 [구조적 트레이트](/ko/reference/generics#built-in-traits)입니다. 구조체는 `void drop()` 메서드를 가짐으로써 이를 구현합니다.

```c
struct Buf {
    &heap char data;
    unsigned long len;
    void drop();
};

void Buf::drop() { std::dealloc(self.data); }
```

타입이 `Drop`을 구현하거나, drop이 필요한 값을 담고 있으면 그 타입은 **drop 필수(must-drop)** 입니다. `Buf` 필드를 가진 구조체, `Buf` 배열, `?Buf`, 그것을 포함하는 튜플이 그렇습니다. 이런 타입은 그 값을 소유하며, 자체 `drop()`이 있든 없든 같은 방식으로 검사됩니다.

스칼라, raw 포인터, 참조, 슬라이스는 가리키는 대상을 소유하지 않으므로 결코 drop 필수가 아닙니다.

## 규칙 {#the-rule}

drop 필수 값은 그것을 담은 변수가 사라지기 전에 **모든 경로에서 drop되거나 move되어야** 합니다. 값은 다음과 같이

- **drop됩니다**: `x.drop()`, 값에 대한 [`consume` 메서드](#consume-methods), 또는 내장 [`drop(x)`](#drop-glue) 호출;
- **move됩니다**: 함수에 값으로 전달, 반환, 다른 변수나 필드에 대입, 그 값이 속하게 될 메모리에 저장(`*p = x`, `v.push(x)`), 또는 스레드로 전송.

이들 각각은 변수의 소유권을 끝냅니다. 컴파일러는 함수의 모든 경로 — `if`/`else`, 루프, `switch`, `match`, `break`, `continue`, `goto`, 이른 `return`, [`defer`](/ko/reference/control-flow#defer)와 `errdefer` — 를 따라가며 소유권이 정확히 한 번 끝나지 않는 경로를 보고합니다.

```c
struct Buf mk(int n);

void ok(int c) {
    struct Buf b = mk(1);
    defer b.drop();            // 아래의 모든 return에서 실행됨
    if (c) return;
    use(&b);
}

void moved() {
    struct Buf b = mk(1);
    consume_buf(b);            // 값으로 전달: 이제 consume_buf가 소유
}
```

## 오류 {#errors}

### 누수 {#leaks}

```c
void never() {
    struct Buf b = mk(1);
}   // error: value of Drop type 'Buf' leaks at the end of its scope: 'b' is never
    //        dropped; call b.drop() or add 'defer b.drop()'
    // note: 'b' declared here
```

메시지는 누수가 일어나는 출구를 알려 줍니다: 스코프의 끝, `return`("leaks when the function returns"), 또는 스코프를 벗어나는 `break`, `continue`, `goto`. 일부 경로에서만 값을 drop하면:

```c
void some_paths(int c) {
    struct Buf b = mk(1);
    if (c) b.drop();
}   // error: 'b' is dropped or moved on some paths but not on others: it leaks at
    //        the end of its scope on the paths where it is still live
```

임시 값도 버려서는 안 됩니다:

```c
printf("%d\n", mk(5).len());   // error: this temporary of Drop type 'Buf' is never dropped
```

값으로 받은 매개변수는 호출된 함수가 소유하므로, 그 안에서 drop하거나 넘겨야 합니다:

```c
void param(struct Buf b) {
    printf("%d\n", b.len());
}   // error: ... leaks when the function returns: 'b' is never dropped
```

### drop 또는 move 후 사용 {#use-after-drop-or-move}

```c
struct Buf b = mk(1);
b.drop();
b.len();                       // error: use of 'b' after it was dropped

struct Buf c = mk(2);
take(c);
c.len();                       // error: use of 'c' after it was moved
```

### 이중 drop {#double-drop}

```c
b.drop();
b.drop();                      // error: double drop: 'b' was dropped already

if (cond) b.drop();
b.drop();                      // error: double drop: 'b' may have been dropped already
```

### 살아 있는 값 덮어쓰기 {#overwriting-a-live-value}

아직 값을 소유한 변수에 대입하면 그 값을 잃게 됩니다:

```c
struct Buf b = mk(1);
b = mk(2);                     // error: assigning to 'b' overwrites a live value of Drop
                               //        type, which would leak; drop it first (or std::replace() it)
```

drop 또는 move된 변수는 비어 있으며, 여기에 대입하면 다시 살아납니다:

```c
take(b);
b = mk(2);                     // OK: b가 새 값을 소유
b.drop();
```

### 빌린 값 {#borrowed-values}

함수는 자신의 지역 변수와 값으로 받은 매개변수만 소유합니다. 참조, 포인터, 전역 변수, 컨테이너 원소를 통해서는 값을 볼 수는 있지만 drop하거나 밖으로 move할 수는 없습니다:

```c
void reset(&Buf b) {
    b.drop();                  // error: cannot drop a value this function only borrows
}

struct Buf steal(&Holder h) {
    return h.b;                // error: cannot move a value of Drop type 'Buf' out of memory
                               //        this function doesn't own
}
```

참조를 통해 도달한 필드나 원소, 그리고 호출이 반환한 참조 — 컨테이너의 원소 — 도 마찬가지입니다. 나중에 그 소유자가 다시 drop하므로, 여기서 drop하면 이중 drop이 됩니다:

```c
void clear_all(&Vec<String> names) {
    names.at(0UL).drop();      // error: cannot drop a value this function only borrows (it is
                               //        reached through a reference, a slice or a call's result, ...)
}
```

빌린 메모리에서 값을 꺼내려면 `std::replace`나 `std::swap`(`#include <std/ownership.h>`)으로 그 자리에 다른 값을 넣고, 나온 값을 drop하거나 보관하십시오:

```c
struct Buf take_out(&Holder h) {
    return std::replace(&h.b, mk(0));   // 이제 h.b는 새 값을 소유
}

void rename(&Widget w, const &char name) {
    std::replace(&w.name, String::from(name)).drop();   // 이전 이름
}
```

컨테이너는 자기 원소를 스스로 drop합니다: `v.clear()`, `v.drop()`, `v.remove(i)`(원소를 반환)로 원소를 없애십시오.

## consume 메서드 {#consume-methods}

`drop()`은 수신자를 파괴합니다. 다른 메서드도 매개변수 목록 뒤에 `consume`을 선언하여 같은 일을 할 수 있습니다 — 상태 코드를 반환하는 `close()`, 내용을 돌려주는 `into_inner()`, 스레드의 결과를 반환하는 `join()` 등:

```c
struct File {
    int fd;
    void drop();
    int  close() consume;      // drop()과 같지만 오류를 보고함
};

int File::close() { int r = sys_close(self.fd); self.fd = -1; return r; }
void File::drop() { self.close(); }

int save(struct File f) {
    write_all(&f);
    return f.close();          // 여기서 f가 drop됨
}
```

`consume` 메서드를 호출하면 `drop()`처럼 수신자의 소유권이 끝납니다. `drop()`이나 `consume`이 아닌 메서드는 `self`를 빌리기만 하며 drop할 수 없습니다.

### 소멸자는 모든 것을 해체해야 함 {#destructors}

`drop()`과 `consume` 메서드 안에서 `self`는 소유된 값입니다. 메서드가 반환하기 전에 drop 필수인 모든 필드를 drop하거나 밖으로 move해야 합니다.

```c
struct Owner { struct Buf a; struct Buf b; void drop(); };
void Owner::drop() {           // error: 'Owner::drop()' never drops field 'b' (of Drop type 'Buf')
    self.a.drop();
}
```

어떤 필드가 값을 가지는지는 런타임 상태(열거형 태그, 길이)에 따라 달라질 수 있으므로, 소멸자의 경로 중 하나에서라도 drop 또는 move되면 그 필드는 처리된 것으로 봅니다.

### Drop 타입의 필드 {#fields}

자체 `drop()`을 가진 타입은 전체로서 drop되어야 합니다. 필드 하나를 drop하거나 move한 뒤 나머지를 잊어버릴 수 없습니다. 떼어 낸 필드는 값 전체를 사용하거나 drop하기 전에 다시 채워야 합니다:

```c
struct Two t = Two::new();
t.a.drop();
t.a = mk(2);                   // 다시 채움: t는 다시 온전함
t.drop();

t.a.drop();
t.drop();                      // error: 't' is dropped while its field 't.a' is dropped
```

자체 `drop()`이 *없는* 집합체 — 소유된 값들의 평범한 구조체나 튜플 — 는 필드별로 소유됩니다. 각 필드를 drop하거나, `drop(x)`로 전체를 drop하십시오.

## Drop 글루: `drop(x)` {#drop-glue}

내장 `drop(x)`는 어떤 값이든 drop합니다. Drop 타입이면 `T::drop()`을 호출하고, 집합체이면 소유하는 각 멤버(구조체 필드, 배열 원소, 존재하는 옵셔널의 값)를 drop하며, 아무것도 소유하지 않는 타입이면 아무 일도 하지 않습니다. `T`를 drop하는 제네릭한 방법입니다:

```c
generic<T>
void discard(T value) { drop(value); }   // Buf는 drop되고, int는 아무 일도 없음
```

`needs_drop(T)`는 컴파일 타임 상수로, `T`가 drop 필수이면 `1`입니다. 제네릭 컨테이너는 이를 이용해 평범한 데이터에 대한 원소별 작업을 건너뜁니다:

```c
generic<T>
void Vec::clear() {
    if const (needs_drop(T)) {   // 평범한 데이터에서는 컴파일되지 않음
        for (unsigned long i = 0UL; i < self.len; i = i + 1UL) { unsafe { drop(self.data[i]); } }
    }
    self.len = 0UL;
}
```

### 조건부 Drop {#conditional-drop}

제네릭 타입은 `drop()` 뒤에 컴파일 타임 조건을 붙여 일부 타입 인자에 대해서만 `Drop`을 구현할 수 있습니다:

```c
generic<T, E>
struct Result {
    T value; E error; int is_ok;
    void drop() if (needs_drop(T) || needs_drop(E));
};
```

그러면 `Result<int, int>`는 평범한 데이터(drop 필수 아님)이고, `Result<File, Error>`는 drop해야 합니다.

## 제네릭 {#generics}

제네릭 코드는 인스턴스화마다 검사됩니다. `ignore<Buf>`에서 매개변수는 `Buf`이므로 누수는 오류이고, `ignore<int>`에서는 drop할 것이 없습니다:

```c
generic<T>
int ignore(T value) { return 1; }   // error (T = Buf): 'value' is never dropped
```

소유된 값을 받을 수 있는 제네릭 코드는 `drop(x)`와 move로 작성하십시오. 그러면 모든 `T`에 대해 올바릅니다.

## match, 옵셔널, 루프 {#match}

옵셔널을 match하면 페이로드가 바인딩으로 move됩니다. 그 arm 안에서는 바인딩이 값을 소유하고, match된 변수는 소비됩니다.

```c
match (maybe) {
    case some(b): { b.drop(); }
    case none:    { }
}
```

루프 본문은 다른 스코프와 똑같이 검사됩니다. 한 반복에서 만든 값은 다음 반복이 시작되기 전에 사라져야 합니다:

```c
for (int i = 0; i < 3; i = i + 1) {
    struct Buf b = mk(i);
    if (i == 1) break;         // error: ... leaks when 'break' leaves its scope
    b.drop();
}
```

## 배열 {#arrays}

Drop 타입의 고정 크기 배열(원소 16개 이하)은 원소마다 따로 추적됩니다. 단, 원소를 상수 인덱스로 선택하거나 배열 전체를 다룰 때(`drop(arr)`는 모든 원소를 drop)에 한합니다. 런타임에 선택되는 원소는 추적할 수 없습니다:

```c
struct Buf a[2];
a[0] = mk(1); a[1] = mk(2);
a[i].drop();   // error: ownership of an array element selected at run time can't be
               //        tracked; use constant indices, or a Vec, for values of Drop type
```

소유된 값의 컬렉션에는 `Vec<T>`를 사용하십시오. `Vec`은 원소를 소유하고, `drop()`/`clear()`에서 원소를 drop하며, `set()`이 덮어쓸 때 이전 원소를 drop합니다.

## 소유된 값 안을 가리키는 참조 {#references}

메서드가 수신자 안을 가리키며 반환한 참조는 수신자에게서 빌린 것이므로, 수신자가 drop 또는 move된 뒤에는 사용할 수 없습니다:

```c
struct Vec<int> v = make();
&int first = v.at(0);
v.drop();
*first;                        // error: use of a reference borrowed from 'v' after 'v' was dropped
```

같은 규칙이 [`MutexGuard`](/ko/reference/threads#mutex)를 안전하게 만듭니다. `guard.get()`이 반환한 참조는 가드가 잠금을 푼 뒤에는 쓸 수 없습니다.

## 탈출구: `forget` {#forget}

`forget(x)`는 아무것도 drop하지 않고 소유권을 포기합니다. 누수를 일으키므로 `unsafe { }` 안에서만 허용됩니다:

```c
unsafe { forget(handle); }     // 이를 해제하는 C 코드에 넘김
forget(handle);                // error: forget() leaks its argument without dropping it:
                               //        write it inside 'unsafe { }'
```

`unsafe` 블록 안이나 raw 포인터를 통해서는 소유권을 추적하지 않습니다. 그곳의 코드는 가져간 것을 스스로 해제해야 합니다.

## 검사하지 않는 것 {#limitations}

- **패닉.** 반환하지 않는 호출(`panic`, `abort`, `exit`)은 경로를 끝냅니다. 그 시점에 살아 있는 값은 보고되지 않습니다. SafeC에는 언와인딩이 없으므로 drop되지도 않습니다 — 프로세스가 종료되기 때문입니다.
- **raw 메모리와 `unsafe`.** raw 포인터나 `&heap` 참조로 도달하거나 `unsafe { }` 안에서 다루는 값은 프로그래머의 책임입니다(그래도 [힙 분석](/ko/reference/memory#compile-time-use-after-free-and-double-free-checking)이 해제 후 사용은 잡아냅니다). 참조 변수가 직접 가리키는 값 — `File::open`이 반환하는 `?&File` 같은 핸들 — 은 그 참조를 통해 drop할 수 있습니다.
- Drop 타입의 **런타임 인덱스 배열** ([배열](#arrays) 참조).
- **공유 소유권.** 두 곳이 raw 포인터를 통해 같은 값을 소유한다고 생각하는 경우는 추적하지 않습니다(참조 카운팅은 `unsafe`로 올바르게 작성해야 하는 라이브러리 코드입니다).
- **프로세스 수명.** 소유된 값을 담은 전역 변수는 drop하지 않아도 됩니다.
- **출력 매개변수.** `&T out`을 채우는 함수는 살아 있는 값을 수정하는 것으로 취급됩니다. drop 필수 `T`라면 변수를 먼저 초기화하십시오(또는 호출이 아무것도 저장하지 않았다고 보고할 때 빈 값을 `unsafe` 안에서 `forget`하십시오).

## 비용 {#cost}

런타임 비용은 0입니다. `drop()`과 `consume` 메서드는 소스가 호출하는 곳에서만 호출되는 평범한 함수이고, `drop(x)`는 손으로 작성할 것과 같은 호출로 펼쳐지며, `needs_drop`과 조건부 drop은 컴파일 타임에 결정되고, `forget`은 아무 코드도 생성하지 않습니다.
