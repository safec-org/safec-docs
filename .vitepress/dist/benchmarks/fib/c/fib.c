#include <stdio.h>
long long fib(int n) {
    if (n < 2) return (long long)n;
    return fib(n - 1) + fib(n - 2);
}
int main() {
    long long r = fib(37);
    printf("%lld\n", r);
    return 0;
}
