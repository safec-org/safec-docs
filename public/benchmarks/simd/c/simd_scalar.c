#include <stdio.h>
#define N 20000000
double a[N];
int main() {
    for (int i = 0; i < N; i++) a[i] = (double)(i % 1000) * 0.001;
    double sum = 0.0;
    for (int i = 0; i < N; i++) sum += a[i] * a[i];
    printf("%.6f\n", sum);
    return 0;
}
