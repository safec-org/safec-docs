const BODIES: usize = 5;
const PI: f64 = 3.141592653589793;
const SOLAR_MASS: f64 = 4.0 * PI * PI;
const DAYS_PER_YEAR: f64 = 365.24;

struct Bodies {
    x: [f64; BODIES],
    y: [f64; BODIES],
    z: [f64; BODIES],
    vx: [f64; BODIES],
    vy: [f64; BODIES],
    vz: [f64; BODIES],
    mass: [f64; BODIES],
}

fn init_bodies() -> Bodies {
    let mut b = Bodies {
        x: [0.0; BODIES], y: [0.0; BODIES], z: [0.0; BODIES],
        vx: [0.0; BODIES], vy: [0.0; BODIES], vz: [0.0; BODIES],
        mass: [0.0; BODIES],
    };
    b.mass[0] = SOLAR_MASS;

    b.x[1] = 4.84143144246472090e+00; b.y[1] = -1.16032004402742839e+00; b.z[1] = -1.03622044471123109e-01;
    b.vx[1] = 1.66007664274403694e-03 * DAYS_PER_YEAR; b.vy[1] = 7.69901118419740425e-03 * DAYS_PER_YEAR; b.vz[1] = -6.90460016972063023e-05 * DAYS_PER_YEAR;
    b.mass[1] = 9.54791938424326609e-04 * SOLAR_MASS;

    b.x[2] = 8.34336671824457987e+00; b.y[2] = 4.12479856412430479e+00; b.z[2] = -4.03523417114321381e-01;
    b.vx[2] = -2.76742510726862411e-03 * DAYS_PER_YEAR; b.vy[2] = 4.99852801234917238e-03 * DAYS_PER_YEAR; b.vz[2] = 2.30417297573763929e-05 * DAYS_PER_YEAR;
    b.mass[2] = 2.85885980666130812e-04 * SOLAR_MASS;

    b.x[3] = 1.28943695621391310e+01; b.y[3] = -1.51111514016986312e+01; b.z[3] = -2.23307578892655734e-01;
    b.vx[3] = 2.96460137564761618e-03 * DAYS_PER_YEAR; b.vy[3] = 2.37847173959480950e-03 * DAYS_PER_YEAR; b.vz[3] = -2.96589568540237556e-05 * DAYS_PER_YEAR;
    b.mass[3] = 4.36624404335156298e-05 * SOLAR_MASS;

    b.x[4] = 1.53796971148509165e+01; b.y[4] = -2.59193146099879641e+01; b.z[4] = 1.79258772950371181e-01;
    b.vx[4] = 2.68067772490389322e-03 * DAYS_PER_YEAR; b.vy[4] = 1.62824170038242295e-03 * DAYS_PER_YEAR; b.vz[4] = -9.51592254519715870e-05 * DAYS_PER_YEAR;
    b.mass[4] = 5.15138902046611451e-05 * SOLAR_MASS;

    let mut px = 0.0; let mut py = 0.0; let mut pz = 0.0;
    for i in 0..BODIES {
        px += b.vx[i] * b.mass[i];
        py += b.vy[i] * b.mass[i];
        pz += b.vz[i] * b.mass[i];
    }
    b.vx[0] = -px / SOLAR_MASS;
    b.vy[0] = -py / SOLAR_MASS;
    b.vz[0] = -pz / SOLAR_MASS;
    b
}

fn advance(b: &mut Bodies, dt: f64) {
    for i in 0..BODIES {
        for j in (i + 1)..BODIES {
            let dx = b.x[i] - b.x[j];
            let dy = b.y[i] - b.y[j];
            let dz = b.z[i] - b.z[j];
            let d2 = dx * dx + dy * dy + dz * dz;
            let mag = dt / (d2 * d2.sqrt());
            b.vx[i] -= dx * b.mass[j] * mag;
            b.vy[i] -= dy * b.mass[j] * mag;
            b.vz[i] -= dz * b.mass[j] * mag;
            b.vx[j] += dx * b.mass[i] * mag;
            b.vy[j] += dy * b.mass[i] * mag;
            b.vz[j] += dz * b.mass[i] * mag;
        }
    }
    for i in 0..BODIES {
        b.x[i] += dt * b.vx[i];
        b.y[i] += dt * b.vy[i];
        b.z[i] += dt * b.vz[i];
    }
}

fn energy(b: &Bodies) -> f64 {
    let mut e = 0.0;
    for i in 0..BODIES {
        e += 0.5 * b.mass[i] * (b.vx[i] * b.vx[i] + b.vy[i] * b.vy[i] + b.vz[i] * b.vz[i]);
        for j in (i + 1)..BODIES {
            let dx = b.x[i] - b.x[j];
            let dy = b.y[i] - b.y[j];
            let dz = b.z[i] - b.z[j];
            let distance = (dx * dx + dy * dy + dz * dz).sqrt();
            e -= (b.mass[i] * b.mass[j]) / distance;
        }
    }
    e
}

fn main() {
    let mut b = init_bodies();
    println!("{:.9}", energy(&b));
    for _ in 0..2000000 {
        advance(&mut b, 0.01);
    }
    println!("{:.9}", energy(&b));
}
