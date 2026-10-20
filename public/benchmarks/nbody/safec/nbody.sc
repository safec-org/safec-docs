extern int printf(const char* fmt, ...);
extern double sqrt(double x);

#define BODIES 5
#define PI 3.141592653589793
#define SOLAR_MASS (4.0 * PI * PI)
#define DAYS_PER_YEAR 365.24

double x[BODIES]; double y[BODIES]; double z[BODIES];
double vx[BODIES]; double vy[BODIES]; double vz[BODIES];
double mass[BODIES];

// The initial state, including the offset that zeroes the system's
// momentum, is computed by the compiler: init_bodies only copies constants.
struct Bodies { double v[7 * BODIES]; };   // x y z vx vy vz mass, per body

consteval struct Bodies initial_bodies() {
    struct Bodies b;
    double s[35] = {
        // Sun
        0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0,
        // Jupiter
        4.84143144246472090e+00, -1.16032004402742839e+00, -1.03622044471123109e-01,
        1.66007664274403694e-03, 7.69901118419740425e-03, -6.90460016972063023e-05, 9.54791938424326609e-04,
        // Saturn
        8.34336671824457987e+00, 4.12479856412430479e+00, -4.03523417114321381e-01,
        -2.76742510726862411e-03, 4.99852801234917238e-03, 2.30417297573763929e-05, 2.85885980666130812e-04,
        // Uranus
        1.28943695621391310e+01, -1.51111514016986312e+01, -2.23307578892655734e-01,
        2.96460137564761618e-03, 2.37847173959480950e-03, -2.96589568540237556e-05, 4.36624404335156298e-05,
        // Neptune
        1.53796971148509165e+01, -2.59193146099879641e+01, 1.79258772950371181e-01,
        2.68067772490389322e-03, 1.62824170038242295e-03, -9.51592254519715870e-05, 5.15138902046611451e-05
    };
    int i = 0;
    while (i < BODIES) {
        b.v[7 * i] = s[7 * i];
        b.v[7 * i + 1] = s[7 * i + 1];
        b.v[7 * i + 2] = s[7 * i + 2];
        b.v[7 * i + 3] = s[7 * i + 3] * DAYS_PER_YEAR;
        b.v[7 * i + 4] = s[7 * i + 4] * DAYS_PER_YEAR;
        b.v[7 * i + 5] = s[7 * i + 5] * DAYS_PER_YEAR;
        b.v[7 * i + 6] = s[7 * i + 6] * SOLAR_MASS;
        i = i + 1;
    }
    double px = 0.0; double py = 0.0; double pz = 0.0;
    i = 0;
    while (i < BODIES) {
        px = px + b.v[7 * i + 3] * b.v[7 * i + 6];
        py = py + b.v[7 * i + 4] * b.v[7 * i + 6];
        pz = pz + b.v[7 * i + 5] * b.v[7 * i + 6];
        i = i + 1;
    }
    b.v[3] = 0.0 - px / SOLAR_MASS;
    b.v[4] = 0.0 - py / SOLAR_MASS;
    b.v[5] = 0.0 - pz / SOLAR_MASS;
    return b;
}

static const struct Bodies g_initial = initial_bodies();

void init_bodies() {
    int i = 0;
    while (i < BODIES) {
        x[i] = g_initial.v[7 * i];      y[i] = g_initial.v[7 * i + 1];  z[i] = g_initial.v[7 * i + 2];
        vx[i] = g_initial.v[7 * i + 3]; vy[i] = g_initial.v[7 * i + 4]; vz[i] = g_initial.v[7 * i + 5];
        mass[i] = g_initial.v[7 * i + 6];
        i = i + 1;
    }
}

void advance(double dt) {
    int i = 0;
    while (i < BODIES) {
        int j = i + 1;
        while (j < BODIES) {
            double dx = x[i] - x[j];
            double dy = y[i] - y[j];
            double dz = z[i] - z[j];
            double d2 = dx*dx + dy*dy + dz*dz;
            double mag = dt / (d2 * sqrt(d2));
            vx[i] = vx[i] - dx * mass[j] * mag;
            vy[i] = vy[i] - dy * mass[j] * mag;
            vz[i] = vz[i] - dz * mass[j] * mag;
            vx[j] = vx[j] + dx * mass[i] * mag;
            vy[j] = vy[j] + dy * mass[i] * mag;
            vz[j] = vz[j] + dz * mass[i] * mag;
            j = j + 1;
        }
        i = i + 1;
    }
    i = 0;
    while (i < BODIES) {
        x[i] = x[i] + dt * vx[i];
        y[i] = y[i] + dt * vy[i];
        z[i] = z[i] + dt * vz[i];
        i = i + 1;
    }
}

double energy() {
    double e = 0.0;
    int i = 0;
    while (i < BODIES) {
        e = e + 0.5 * mass[i] * (vx[i]*vx[i] + vy[i]*vy[i] + vz[i]*vz[i]);
        int j = i + 1;
        while (j < BODIES) {
            double dx = x[i] - x[j];
            double dy = y[i] - y[j];
            double dz = z[i] - z[j];
            double distance = sqrt(dx*dx + dy*dy + dz*dz);
            e = e - (mass[i] * mass[j]) / distance;
            j = j + 1;
        }
        i = i + 1;
    }
    return e;
}

int main() {
    init_bodies();
    printf("%.9f\n", energy());
    int n = 0;
    while (n < 2000000) {
        advance(0.01);
        n = n + 1;
    }
    printf("%.9f\n", energy());
    return 0;
}
