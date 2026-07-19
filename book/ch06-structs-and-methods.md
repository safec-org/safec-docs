# Chapter 6: Structs and Methods

## Defining a struct

`struct` works the way you'd expect from C, plus the ability to attach
methods:

```c
struct Point {
    double x;
    double y;

    double length() const;
    void scale(double s);
};
```

Inside the struct body, a method is *declared* — name, parameters, return
type, and whether it's `const` — but not defined. The definition lives
outside, qualified with `Type::method`:

```c
double Point::length() const {
    return self.x * self.x + self.y * self.y;
}

void Point::scale(double s) {
    self.x = self.x * s;
    self.y = self.y * s;
}
```

`self` is implicit — you don't declare it as a parameter, but it's
available inside every method body, referring to the instance the method
was called on. A `const` method receives `self` as read-only (it can read
fields but not assign to them, enforced the same way any other `const`
binding is); a non-`const` method like `scale` can mutate through `self`.

Calling a method uses the familiar `.` syntax:

```c
struct Point p = {3.0, 4.0};
double len = p.length();     // 25.0 (this length() returns the squared length)
p.scale(2.0);
// p is now {6.0, 8.0}
```

Under the hood, a method is just an ordinary function with an explicit
`self` parameter prepended — `p.length()` lowers to something shaped like
`Point_length(&p)`, and `p.scale(2.0)` to `Point_scale(&p, 2.0)`. Nothing
magic is happening; it's the same "struct + free functions that take a
pointer to it" pattern you'd hand-write in C, with syntax sugar on top.

## Operator overloading

A struct can define what `+`, `-`, `*`, `/`, `%`, and the comparison
operators mean for its own type, by naming a method `operator+` (etc.):

```c
struct Vec2 {
    double x;
    double y;

    Vec2 operator+(Vec2 other) const;
};

Vec2 Vec2::operator+(Vec2 other) const {
    Vec2 result;
    result.x = self.x + other.x;
    result.y = self.y + other.y;
    return result;
}
```

```c
struct Vec2 a = {1.0, 2.0};
struct Vec2 b = {3.0, 4.0};
struct Vec2 c = a + b;      // {4.0, 6.0} -- calls Vec2::operator+
```

This is exactly the kind of thing that makes small numeric/geometric
types (2D/3D vectors, complex numbers, fixed-point values) pleasant to
work with — write the arithmetic once as a method, then use ordinary
operator syntax everywhere else instead of `vec2_add(a, b)` calls
scattered through the codebase.

## Structs and regions

One thing worth connecting back to the previous chapter: a struct's
fields don't have their own independent regions — the whole struct lives
in whatever region the variable holding it lives in. A `struct Point p`
declared as a local variable is `&stack`-region data as a whole; a
`&arena<Pool> struct Point` allocated via `new<Pool>` lives in the arena,
fields included. There's no scenario where one field of a struct is
secretly heap-allocated while the rest sits on the stack unless you make
that explicit yourself (e.g., a field that's itself a `&heap T`
reference, which then has its own independent lifetime worth tracking
separately from the struct it's embedded in).

Next: [Enums, Unions, and Match](/book/ch07-enums-and-match) — structs
group *related* data together; enums and unions express *alternative*
shapes of data, and `match` is how you handle every alternative safely.
