extern int printf(const char* fmt, ...);
#define N 20000000

double a[N];

int main() {
    int i = 0;
    while (i < N) {
        a[i] = (double)(i % 1000) * 0.001;
        i = i + 1;
    }
    vec<double, 4> acc = {0.0, 0.0, 0.0, 0.0};
    i = 0;
    int limit = (N / 4) * 4;
    while (i < limit) {
        vec<double, 4> v;
        v[0] = a[i]; v[1] = a[i+1]; v[2] = a[i+2]; v[3] = a[i+3];
        acc = acc + v * v;
        i = i + 4;
    }
    double sum = acc[0] + acc[1] + acc[2] + acc[3];
    while (i < N) {
        sum = sum + a[i] * a[i];
        i = i + 1;
    }
    printf("%.6f\n", sum);
    return 0;
}
