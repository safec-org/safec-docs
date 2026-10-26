# Ownership & Drop

SafeC has no implicit destructors: nothing runs at the end of a scope unless the source says so. What it does have is a compile-time check that the code you wrote releases every resource it owns exactly once. A type opts in by defining `drop()`; from then on the compiler tracks every value of that type and rejects a program that leaks one, uses one after dropping or moving it, or drops one twice.

The check runs entirely at compile time. It inserts no calls, no flags and no unwinding tables: the generated code is the code you wrote.

## The Drop Trait {#the-drop-trait}

`Drop` is a builtin [structural trait](/reference/generics#built-in-traits): a struct implements it by having a method `void drop()`.

```c
struct Buf {
    &heap char data;
    unsigned long len;
    void drop();
};

void Buf::drop() { std::dealloc(self.data); }
```

A type is **must-drop** when it implements `Drop`, or when it holds a must-drop value: a struct with a `Buf` field, an array of `Buf`, a `?Buf`, a tuple containing one. Such a type owns that value and is checked the same way, whether or not it has a `drop()` of its own.

Scalars, raw pointers, references and slices are never must-drop: they don't own what they point to.

## The Rule {#the-rule}

A must-drop value has to be **dropped or moved on every path** before the variable holding it goes away. A value is:

- **dropped** by calling `x.drop()`, a [`consume` method](#consume-methods) on it, or the builtin [`drop(x)`](#drop-glue);
- **moved** by passing it by value to a function, returning it, assigning it to another variable or field, storing it into memory it now belongs to (`*p = x`, `v.push(x)`), or sending it to a thread.

Each of these ends the variable's ownership. The compiler follows every path through the function — `if`/`else`, loops, `switch`, `match`, `break`, `continue`, `goto`, early `return`, [`defer`](/reference/control-flow#defer) and `errdefer` — and reports any path on which ownership doesn't end exactly once.

```c
struct Buf mk(int n);

void ok(int c) {
    struct Buf b = mk(1);
    defer b.drop();            // runs at every return below
    if (c) return;
    use(&b);
}

void moved() {
    struct Buf b = mk(1);
    consume_buf(b);            // by value: consume_buf owns it now
}
```

## Errors {#errors}

### Leaks {#leaks}

```c
void never() {
    struct Buf b = mk(1);
}   // error: value of Drop type 'Buf' leaks at the end of its scope: 'b' is never
    //        dropped; call b.drop() or add 'defer b.drop()'
    // note: 'b' declared here
```

The message names the exit that leaks: the end of the scope, a `return` ("leaks when the function returns"), or a `break`, `continue` or `goto` that leaves the scope. When only some paths drop the value:

```c
void some_paths(int c) {
    struct Buf b = mk(1);
    if (c) b.drop();
}   // error: 'b' is dropped or moved on some paths but not on others: it leaks at
    //        the end of its scope on the paths where it is still live
```

A temporary must not be thrown away either:

```c
printf("%d\n", mk(5).len());   // error: this temporary of Drop type 'Buf' is never dropped
```

A by-value parameter belongs to the callee, so it has to be dropped or passed on there:

```c
void param(struct Buf b) {
    printf("%d\n", b.len());
}   // error: ... leaks when the function returns: 'b' is never dropped
```

### Use After Drop or Move {#use-after-drop-or-move}

```c
struct Buf b = mk(1);
b.drop();
b.len();                       // error: use of 'b' after it was dropped

struct Buf c = mk(2);
take(c);
c.len();                       // error: use of 'c' after it was moved
```

### Double Drop {#double-drop}

```c
b.drop();
b.drop();                      // error: double drop: 'b' was dropped already

if (cond) b.drop();
b.drop();                      // error: double drop: 'b' may have been dropped already
```

### Overwriting a Live Value {#overwriting-a-live-value}

Assigning to a variable that still owns a value would lose it:

```c
struct Buf b = mk(1);
b = mk(2);                     // error: assigning to 'b' overwrites a live value of Drop
                               //        type, which would leak; drop it first (or std::replace() it)
```

Once a variable has been dropped or moved it is empty, and assigning to it makes it live again:

```c
take(b);
b = mk(2);                     // OK: b owns the new value
b.drop();
```

### Borrowed Values {#borrowed-values}

A function owns only its locals and its by-value parameters. Through a reference, a pointer, a global or a container element it can look at a value, but not drop it or move it out:

```c
void reset(&Buf b) {
    b.drop();                  // error: cannot drop a value this function only borrows
}

struct Buf steal(&Holder h) {
    return h.b;                // error: cannot move a value of Drop type 'Buf' out of memory
                               //        this function doesn't own
}
```

The same holds for a field or element reached through a reference, and for a reference a call returned — a container's element. Its owner drops it again later, so dropping it here would be a double drop:

```c
void clear_all(&Vec<String> names) {
    names.at(0UL).drop();      // error: cannot drop a value this function only borrows (it is
                               //        reached through a reference, a slice or a call's result, ...)
}
```

To take a value out of borrowed memory, swap something in its place with `std::replace` or `std::swap` (`#include <std/ownership.h>`), and drop or keep what comes out:

```c
struct Buf take_out(&Holder h) {
    return std::replace(&h.b, mk(0));   // h.b now owns the new value
}

void rename(&Widget w, const &char name) {
    std::replace(&w.name, String::from(name)).drop();   // the old name
}
```

Containers drop their own elements: `v.clear()`, `v.drop()` and `v.remove(i)` (which returns the element) are the way to get rid of them.

## Consume Methods {#consume-methods}

`drop()` destroys its receiver. Other methods can do the same by declaring `consume` after their parameter list — `close()` returning a status code, `into_inner()` handing back the contents, `join()` returning a thread's result:

```c
struct File {
    int fd;
    void drop();
    int  close() consume;      // like drop(), but reports the error
};

int File::close() { int r = sys_close(self.fd); self.fd = -1; return r; }
void File::drop() { self.close(); }

int save(struct File f) {
    write_all(&f);
    return f.close();          // f is dropped here
}
```

Calling a `consume` method ends the receiver's ownership like `drop()` does. A method that is not `drop()` or `consume` only borrows `self` and may not drop it.

### Destructors Must Take Everything Apart {#destructors}

Inside `drop()` and `consume` methods, `self` is owned: every must-drop field has to be dropped or moved out before the method returns.

```c
struct Owner { struct Buf a; struct Buf b; void drop(); };
void Owner::drop() {           // error: 'Owner::drop()' never drops field 'b' (of Drop type 'Buf')
    self.a.drop();
}
```

Which fields hold a value may depend on run-time state (an enum tag, a length), so a field counts as handled if it is dropped or moved on at least one path through the destructor.

### Fields of a Drop Type {#fields}

A type with its own `drop()` must be dropped as a whole: you can't drop or move one field out of it and then forget about the rest. A field that is split off has to be put back before the whole value is used or dropped:

```c
struct Two t = Two::new();
t.a.drop();
t.a = mk(2);                   // refilled: t is whole again
t.drop();

t.a.drop();
t.drop();                      // error: 't' is dropped while its field 't.a' is dropped
```

An aggregate *without* its own `drop()` — a plain struct or tuple of owned values — is owned field by field: drop each field, or the whole thing with `drop(x)`.

## Drop Glue: `drop(x)` {#drop-glue}

The builtin `drop(x)` drops any value: it calls `T::drop()` for a Drop type, drops each owning member of an aggregate (struct fields, array elements, a present optional's value), and does nothing for a type that owns nothing. It is the generic way to drop a `T`:

```c
generic<T>
void discard(T value) { drop(value); }   // a Buf is dropped, an int is not
```

`needs_drop(T)` is a compile-time constant: `1` if `T` is must-drop. Generic containers use it to skip per-element work for plain data:

```c
generic<T>
void Vec::clear() {
    if const (needs_drop(T)) {   // compiled out for plain data
        for (unsigned long i = 0UL; i < self.len; i = i + 1UL) { unsafe { drop(self.data[i]); } }
    }
    self.len = 0UL;
}
```

### Conditional Drop {#conditional-drop}

A generic type can implement `Drop` only for some type arguments by putting a compile-time condition after `drop()`:

```c
generic<T, E>
struct Result {
    T value; E error; int is_ok;
    void drop() if (needs_drop(T) || needs_drop(E));
};
```

`Result<int, int>` is then plain data (not must-drop), while `Result<File, Error>` must be dropped.

## Generics {#generics}

Generic code is checked for each instantiation. In `ignore<Buf>` the parameter is a `Buf` and leaking it is an error; in `ignore<int>` there is nothing to drop:

```c
generic<T>
int ignore(T value) { return 1; }   // error (for T = Buf): 'value' is never dropped
```

Write generic code that may receive owned values with `drop(x)` and moves, and it is correct for every `T`.

## Match, Optionals and Loops {#match}

Matching an optional moves the payload into the binding: the binding owns it inside that arm, and the matched variable is consumed.

```c
match (maybe) {
    case some(b): { b.drop(); }
    case none:    { }
}
```

A loop body is checked like any other scope; a value created in one iteration must be gone before the next starts:

```c
for (int i = 0; i < 3; i = i + 1) {
    struct Buf b = mk(i);
    if (i == 1) break;         // error: ... leaks when 'break' leaves its scope
    b.drop();
}
```

## Arrays {#arrays}

Each element of a fixed-size array of a Drop type is tracked separately (for arrays of up to 16 elements), as long as it is selected by a constant index or the array is handled as a whole (`drop(arr)` drops every element). An element selected at run time can't be tracked:

```c
struct Buf a[2];
a[0] = mk(1); a[1] = mk(2);
a[i].drop();   // error: ownership of an array element selected at run time can't be
               //        tracked; use constant indices, or a Vec, for values of Drop type
```

Use a `Vec<T>` for collections of owned values: `Vec` owns its elements, drops them in `drop()`/`clear()`, and drops the old one when `set()` overwrites it.

## References into an Owned Value {#references}

A reference a method returns into its receiver borrows from it, and may not be used once the receiver is dropped or moved:

```c
struct Vec<int> v = make();
&int first = v.at(0);
v.drop();
*first;                        // error: use of a reference borrowed from 'v' after 'v' was dropped
```

The same rule makes [`MutexGuard`](/reference/threads#mutex) safe: the reference `guard.get()` returns is unusable after the guard unlocks.

## Escape Hatch: `forget` {#forget}

`forget(x)` gives up ownership without dropping anything. It is allowed only inside `unsafe { }`, because it leaks:

```c
unsafe { forget(handle); }     // handed to C code that frees it
forget(handle);                // error: forget() leaks its argument without dropping it:
                               //        write it inside 'unsafe { }'
```

Ownership is not tracked inside `unsafe` blocks or through raw pointers: code there is responsible for releasing what it takes.

## What Is Not Checked {#limitations}

- **Panics.** A call that never returns (`panic`, `abort`, `exit`) ends the path: values still live there are not reported. SafeC has no unwinding, so they are not dropped either — the process is going away.
- **Raw memory and `unsafe`.** Values reached through raw pointers or `&heap` references, or handled inside `unsafe { }`, are the programmer's responsibility (the [heap analysis](/reference/memory#compile-time-use-after-free-and-double-free-checking) still catches a use after free there). A value a reference variable names directly — a handle like the `?&File` that `File::open` returns — may be dropped through it.
- **Run-time-indexed arrays** of Drop types (see [Arrays](#arrays)).
- **Shared ownership.** Nothing tracks a value that two places think they own through raw pointers (reference counting is library code that must be written correctly in `unsafe`).
- **Process lifetime.** Globals holding owned values are not required to be dropped.
- **Out-parameters.** A function that fills `&T out` is treated as modifying a live value; for a must-drop `T`, initialize the variable first (or `forget` the empty one inside `unsafe` when the call reports it stored nothing).

## Cost {#cost}

Zero at run time. `drop()` and `consume` methods are ordinary functions, called only where the source calls them; `drop(x)` expands to the same calls you would write by hand; `needs_drop` and conditional drops are resolved at compile time; `forget` emits nothing.
