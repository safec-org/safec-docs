# serial -- 직렬화 (JSON / XML / HTML / CSV / YAML)

`std/serial/`은 형식에 구애받지 않는 `Value` 트리와, 이 트리를 읽고 쓸 수 있는 다섯 가지 형식 백엔드(JSON, XML, HTML, CSV, YAML)를 제공합니다. SafeC에는 필드 이름 리플렉션이 없으므로(오직 개수만 알려주는 `fieldcount(T)`만 있고 이름은 알 수 없습니다), 구조체를 직렬화 가능하게 만들려면 자동 `#[derive]` 스타일 매크로 대신 `to_value()` 메서드를 직접 작성해야 합니다.

```c
#include "serial/value.h"
#include "serial/json.h"
#include "serial/xml.h"
#include "serial/html.h"
```

## `Value` 트리 {#the-value-tree}

```c
#define VAL_NULL   0
#define VAL_BOOL   1
#define VAL_INT    2
#define VAL_FLOAT  3
#define VAL_STRING 4
#define VAL_ARRAY  5
#define VAL_OBJECT 6

struct Value {
    int kind;   // 위의 VAL_* 상수 중 하나

    int kind_of() const;
    int is_null() const;
    int as_bool() const;
    long long as_int() const;
    double as_float() const;
    const char* as_string() const;

    // Array/Object 요소 접근 -- 읽기 전용 뷰 (반환되는 참조는 이 Value가
    // 소유한 저장소를 가리키므로 별도로 해제하지 마세요). 'array_at'은
    // 'idx'가 범위 내에 있다고 신뢰합니다 (array_len() 참고); 'object_get'은
    // 유일하게 널이 될 수 있는 조회입니다 -- 'key'에 해당하는 항목이 없는
    // 것은 실제로 예상되는 결과이지, 호출자의 실수가 아닙니다.
    unsigned long   array_len() const;
    &Value          array_at(unsigned long idx) const;
    unsigned long   object_len() const;
    ?&Value         object_get(const char* key) const;   // 키가 없으면 empty(null)
}

struct Value value_null();
struct Value value_bool(int v);
struct Value value_int(long long v);
struct Value value_float(double v);
struct Value value_string(const char* s);   // s를 복사함
struct Value value_array();                 // 비어 있음; value_array_push로 확장
struct Value value_object();                // 비어 있음; value_object_set으로 확장

void value_array_push(&Value arr, struct Value v);
void value_object_set(&Value obj, const char* key, struct Value v);

struct Value value_clone(const &Value v);
void value_free(&Value v);
```

::: warning 소유권
`value_array_push`/`value_object_set`은 전달받은 `Value`의 **소유권을 가져갑니다** — 그 이후에는 직접 해제하지 마세요. 자신만의 복사본이 여전히 필요하다면 먼저 `value_clone()`을 호출하세요. `value_free()`는 트리 전체(문자열, 배열 요소, 객체 항목)를 재귀적으로 해제합니다.
:::

`object_get`은 *널이 될 수 있는* 참조를 반환합니다 — 필드나 메서드로 바로 체이닝하지 말고 `match`(또는 `.is_null()`/`.default()`)로 언래핑하세요. 이는 SafeC의 모든 `?&T`에 적용되는 것과 동일한 규칙입니다([안전성](/ko/reference/safety#nullability-enforcement) 참고).

```c
long long age = 0;
match (parsed.object_get("age")) {
    case null:    break;
    case some(a): age = a.as_int();
}
```

`array_at`은 널이 될 수 없으므로(`array_len()`으로 확인된 범위 내 인덱스를 신뢰합니다), `v.array_at(0).as_string()`처럼 바로 체이닝할 수 있습니다.

## 구조체를 직렬화 가능하게 만들기 {#making-a-struct-serializable}

구조체 필드로부터 `Value` 트리를 만드는 `to_value()` 메서드를 작성하세요.

```c
struct Point { double x; double y; };

struct Value Point::to_value() const {
    struct Value v = value_object();
    value_object_set(&v, "x", value_float(self.x));
    value_object_set(&v, "y", value_float(self.y));
    return v;
}

int main() {
    Point p; p.x = 1.5; p.y = 2.5;

    struct Value pv = p.to_value();
    struct String json = value_to_json(&pv);
    printf("%s\n", json.as_ptr());   // {"x":1.5,"y":2.5}

    json.free();
    value_free(&pv);
    return 0;
}
```

`from_value(const &Value)` 메서드는 동일한 방식으로 디코딩 방향을 담당합니다 — `object_get`/`array_at`/`as_int`/등을 사용해 필드를 다시 읽어냅니다.

## JSON {#json}

```c
void json_write(const &Value v, &String out);   // 추가만 함; 'out'을 먼저 비우지 않음
struct String value_to_json(const &Value v);      // 편의 함수: 새 String 반환

struct Value json_parse(const char* text, int* ok);
    // 성공하면 *ok = 1과 파싱된 트리; 실패하면 *ok = 0과 value_null().
    // 첫 완전한 값 이후의 후행 내용은 무시됩니다.
```

JSON은 다섯 가지 형식 중 구조적 모호함이 없는 유일한 형식입니다 — 문법이 타입을 명시적으로 표시하므로, 숫자처럼 보이는 문자열이나 빈 문자열을 포함해 정확히 왕복 변환됩니다.

```c
struct Value v = value_object();
value_object_set(&v, "name", value_string("Ada"));
value_object_set(&v, "age", value_int(36));

struct String json = value_to_json(&v);
printf("%s\n", json.as_ptr());   // {"name":"Ada","age":36}

int ok = 0;
struct Value parsed = json_parse(json.as_ptr(), &ok);
long long age = 0;
match (parsed.object_get("age")) {
    case null:    break;
    case some(a): age = a.as_int();   // 36
}

json.free();
value_free(&v);
value_free(&parsed);
```

## XML {#xml}

```c
void xml_write(const &Value v, const char* tag, &String out);
struct String value_to_xml(const &Value v, const char* root_tag);

struct Value xml_parse(const char* text, int* ok);   // 루트 태그 자체는 반환되지 않고, 그 내용만 반환됨
```

매핑:

| `Value` 종류 | XML 형태 (태그 `T` 아래) |
|---|---|
| `VAL_OBJECT` | `<T><key1>...</key1><key2>...</key2></T>` — 각 키는 이름 있는 자식 엘리먼트가 됨 |
| `VAL_ARRAY` | `<T><item>...</item><item>...</item></T>` — 배열 요소는 자체 이름을 갖지 않음 |
| `VAL_STRING`/`INT`/`FLOAT`/`BOOL` | `<T>텍스트 내용</T>` |
| `VAL_NULL` | `<T/>` |

```c
struct Value v = value_object();
value_object_set(&v, "name", value_string("Ada <Countess>"));
value_object_set(&v, "age", value_int(36));

struct String xml = value_to_xml(&v, "person");
printf("%s\n", xml.as_ptr());
// <person><name>Ada &lt;Countess&gt;</name><age>36</age></person>

int ok = 0;
struct Value parsed = xml_parse(xml.as_ptr(), &ok);
// parsed.object_get("age")를 위 JSON 예제와 같은 방식으로 언래핑

xml.free();
value_free(&v);
value_free(&parsed);
```

`xml_parse`/`json_parse`는 이 모듈 자체가 만들어낸 출력 형태만 이해합니다 — 속성도, CDATA도, `xml_write`가 내보내는 5개(`&amp; &lt; &gt; &quot; &apos;`) 외의 엔터티도 없습니다.

## HTML {#html}

```c
void html_escape(&String out, const char* s);   // 단독 사용 가능, Value 렌더러와 무관
void html_write(const &Value v, &String out);
struct String value_to_html(const &Value v);

struct Value html_parse(const char* text, int* ok);
```

JSON/XML과 달리 HTML은 데이터 교환 형식이 아닙니다 — `html_write`는 `Value` 트리를 사람이 읽을 수 있는 문서 조각으로 렌더링합니다.

| `Value` 종류 | HTML 형태 |
|---|---|
| `VAL_OBJECT` | `<dl><dt>key</dt><dd>...</dd>...</dl>` (정의 목록) |
| `VAL_ARRAY` | `<ul><li>...</li>...</ul>` |
| `VAL_STRING`/`INT`/`FLOAT`/`BOOL` | 이스케이프된 텍스트, 감싸는 태그 없음 |
| `VAL_NULL` | `<em>null</em>` |

```c
struct Value v = value_object();
value_object_set(&v, "comment", value_string("<script>alert(1)</script>"));

struct String html = value_to_html(&v);
printf("%s\n", html.as_ptr());
// <dl><dt>comment</dt><dd>&lt;script&gt;alert(1)&lt;/script&gt;</dd></dl>

html.free();
value_free(&v);
```

`html_parse`는 이와 동일한 `<dl>`/`<ul>`/`<em>null</em>`/맨 텍스트 형태를 다시 `Value` 트리로 읽어들입니다 — 이는 범용 HTML 파서가 아니며, (`xml_parse`와 마찬가지로) 임의의 HTML 문서가 아니라 이 모듈 자체의 출력을 왕복시키기 위한 것입니다.

## CSV {#csv}

```c
#include "serial/csv.h"
```

CSV 문서는 행(row)들의 `VAL_ARRAY`이며, 각 행 자체는 `VAL_STRING` 필드들의 `VAL_ARRAY`입니다 — CSV는 고유한 타입 체계가 없으므로(모든 필드가 텍스트), `json_parse`와 달리 int/float/bool 추론이 없습니다. 어떤 열이 숫자를 담고 있다고 알고 있다면 각 필드를 직접 변환하세요(`String::parse_int()`/`parse_float()`).

```c
void csv_write(const &Value v, &String out);   // 'v'는 반드시 VAL_ARRAY의 VAL_ARRAY여야 함
struct String value_to_csv(const &Value v);

struct Value csv_parse(const char* text, int* ok);
```

콤마, 큰따옴표, 개행을 포함하는 필드는 RFC 4180에 따라 따옴표로 묶이며, 내장된 큰따옴표는 이중으로 표기됩니다(`""`). 파싱 시 `"\n"`과 `"\r\n"` 줄바꿈 모두 허용되며, 후행 개행은 선택 사항입니다. 잘못된 형식의 따옴표 필드(닫는 `"`가 없는 여는 `"`)를 만나면 `*ok`는 0으로 설정되고 반환값은 그때까지 파싱된 내용이 됩니다.

```c
int ok = 0;
struct Value rows = csv_parse("name,age\nAda,36\n", &ok);
&Value row0 = rows.array_at(0);
&Value name = row0.array_at(0);   // VAL_STRING "name"
printf("%s\n", name.as_string());

value_free(&rows);
```

## YAML {#yaml}

```c
#include "serial/yaml.h"
```

YAML 1.1의 블록 스타일 서브셋입니다: 블록 스칼라(평문, 각자의 이스케이프 규칙을 따르는 단일/이중 따옴표), 블록 시퀀스(`- item`, 맵 목록의 축약형인 `- key: value` 포함), 블록 매핑(`key: value`, 들여쓰기로 중첩), `#` 주석, `~`/`null`/`true`/`false`/숫자 코어 스키마 스칼라 형태.

**지원하지 않는 것**(완전한 YAML 1.2 프로세서는 훨씬 더 큰 작업입니다): 플로우 스타일(`[a, b]` / `{k: v}`), 앵커/앨리어스, 태그, 다중 문서 스트림, 블록 스칼라 리터럴(`|`/`>`), 병합 키. 이들 중 하나를 사용하는 문서는 평문 스칼라 텍스트로 파싱되거나, 아래 `yaml_parse`의 `ok` 계약에 따라 실패합니다.

```c
void yaml_write(const &Value v, &String out);
struct String value_to_yaml(const &Value v);

struct Value yaml_parse(const char* text, int* ok);
    // 구조적 오류(들여쓰기 불일치, 종료되지 않은 따옴표 스칼라, 시퀀스/매핑
    // 항목도 아니고 맨 스칼라가 기대되는 위치의 맨 스칼라도 아닌 줄)가
    // 있으면 *ok = 0 -- 반환값은 오류 전까지 파싱된 내용입니다.
```

```c
struct Value v = value_object();
value_object_set(&v, "name", value_string("Ada"));
value_object_set(&v, "age", value_int(36));

struct String yaml = value_to_yaml(&v);
printf("%s", yaml.as_ptr());
// name: Ada
// age: 36

yaml.free();
value_free(&v);
```

## 왕복 변환 충실도 {#round-trip-fidelity}

JSON은 구조적 모호함이 없습니다 — 임의의 문자열 내용을 정확히 왕복시키는 것이 중요하다면 항상 JSON을 우선하세요. XML과 HTML은 모든 `Value` 종류를 평문 태그 텍스트로 매핑하는 데서 비롯된 두 가지 고유한 모호함을 공유합니다.

- **빈 문자열 대 빈 컨테이너.** `<t></t>`(자식도 텍스트도 없음)는 빈 문자열일 수도, 빈 객체/배열일 수도 있습니다. 두 파서 모두 항상 이를 `VAL_STRING ""`으로 해석합니다.
- **숫자/불린처럼 보이는 텍스트.** 숫자나 `true`/`false`처럼 보이는 텍스트 내용은, 실제로 그렇게 직렬화되었을 때와 마찬가지로 `VAL_INT`/`VAL_FLOAT`/`VAL_BOOL`로 다시 디코딩됩니다 — 값이 우연히 `"42"`인 문자열 필드는 XML/HTML을 거쳐 문자열 `"42"`가 아니라 정수 `42`로 왕복됩니다.

CSV는 타입 추론을 아예 하지 않음으로써 이 문제를 피합니다(모든 필드는 예외 없이 `VAL_STRING`으로 다시 파싱됩니다). YAML은 XML/HTML과 마찬가지로 `null`/불린/숫자처럼 보이는 스칼라를 해당 타입의 `Value` 종류로 추론합니다 — 같은 모호함이 형식 하나만큼 더 늘어난 셈입니다.

## 성능 참고 사항 {#performance-note}

각 형식의 문자열 이스케이프 함수(`json_append_escaped_`, `xml_append_escaped_`, `html_escape`)는 이스케이프가 필요 없는 바이트 구간을 바이트 하나당 `push_char()` 호출 하나가 아니라 단일 `String::push_n()` 호출로 일괄 처리합니다 — 실제 문자열 내용 대부분에는 특수 문자가 없으므로, 이렇게 하면 일반적인 경우 문자열당 경계 검사가 포함된 버퍼 증가 한 번과 `memcpy` 한 번만으로 처리되며, 바이트당 함수 호출이 발생하지 않습니다.
