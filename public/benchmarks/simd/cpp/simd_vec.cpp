#include <cstdio>
#define N 20000000
typedef double v4d __attribute__((vector_size(32)));
double a[N];
int main() {
    for (int i = 0; i < N; i++) a[i] = (double)(i % 1000) * 0.001;
    v4d acc = {0.0, 0.0, 0.0, 0.0};
    int limit = (N / 4) * 4;
    int i = 0;
    for (; i < limit; i += 4) {
        v4d v = { a[i], a[i+1], a[i+2], a[i+3] };
        acc += v * v;
    }
    double sum = acc[0] + acc[1] + acc[2] + acc[3];
    for (; i < N; i++) sum += a[i] * a[i];
    std::printf("%.6f\n", sum);
    return 0;
}
