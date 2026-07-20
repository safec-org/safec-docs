extern int printf(const char* fmt, ...);
#define N 20000000

double a[N];

int main() {
    int i = 0;
    while (i < N) {
        a[i] = (double)(i % 1000) * 0.001;
        i = i + 1;
    }
    double sum = 0.0;
    i = 0;
    while (i < N) {
        sum = sum + a[i] * a[i];
        i = i + 1;
    }
    printf("%.6f\n", sum);
    return 0;
}
