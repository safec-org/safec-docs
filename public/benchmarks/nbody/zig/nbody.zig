const std = @import("std");
const math = std.math;

const bodies = 5;
const pi = 3.141592653589793;
const solar_mass = 4.0 * pi * pi;
const days_per_year = 365.24;

var x: [bodies]f64 = undefined;
var y: [bodies]f64 = undefined;
var z: [bodies]f64 = undefined;
var vx: [bodies]f64 = undefined;
var vy: [bodies]f64 = undefined;
var vz: [bodies]f64 = undefined;
var mass: [bodies]f64 = undefined;

fn initBodies() void {
    x[0] = 0.0; y[0] = 0.0; z[0] = 0.0; vx[0] = 0.0; vy[0] = 0.0; vz[0] = 0.0; mass[0] = solar_mass;

    x[1] = 4.84143144246472090e+00; y[1] = -1.16032004402742839e+00; z[1] = -1.03622044471123109e-01;
    vx[1] = 1.66007664274403694e-03 * days_per_year; vy[1] = 7.69901118419740425e-03 * days_per_year; vz[1] = -6.90460016972063023e-05 * days_per_year;
    mass[1] = 9.54791938424326609e-04 * solar_mass;

    x[2] = 8.34336671824457987e+00; y[2] = 4.12479856412430479e+00; z[2] = -4.03523417114321381e-01;
    vx[2] = -2.76742510726862411e-03 * days_per_year; vy[2] = 4.99852801234917238e-03 * days_per_year; vz[2] = 2.30417297573763929e-05 * days_per_year;
    mass[2] = 2.85885980666130812e-04 * solar_mass;

    x[3] = 1.28943695621391310e+01; y[3] = -1.51111514016986312e+01; z[3] = -2.23307578892655734e-01;
    vx[3] = 2.96460137564761618e-03 * days_per_year; vy[3] = 2.37847173959480950e-03 * days_per_year; vz[3] = -2.96589568540237556e-05 * days_per_year;
    mass[3] = 4.36624404335156298e-05 * solar_mass;

    x[4] = 1.53796971148509165e+01; y[4] = -2.59193146099879641e+01; z[4] = 1.79258772950371181e-01;
    vx[4] = 2.68067772490389322e-03 * days_per_year; vy[4] = 1.62824170038242295e-03 * days_per_year; vz[4] = -9.51592254519715870e-05 * days_per_year;
    mass[4] = 5.15138902046611451e-05 * solar_mass;

    var px: f64 = 0.0;
    var py: f64 = 0.0;
    var pz: f64 = 0.0;
    var i: usize = 0;
    while (i < bodies) : (i += 1) {
        px += vx[i] * mass[i];
        py += vy[i] * mass[i];
        pz += vz[i] * mass[i];
    }
    vx[0] = -px / solar_mass;
    vy[0] = -py / solar_mass;
    vz[0] = -pz / solar_mass;
}

fn advance(dt: f64) void {
    var i: usize = 0;
    while (i < bodies) : (i += 1) {
        var j: usize = i + 1;
        while (j < bodies) : (j += 1) {
            const dx = x[i] - x[j];
            const dy = y[i] - y[j];
            const dz = z[i] - z[j];
            const d2 = dx * dx + dy * dy + dz * dz;
            const mag = dt / (d2 * math.sqrt(d2));
            vx[i] -= dx * mass[j] * mag;
            vy[i] -= dy * mass[j] * mag;
            vz[i] -= dz * mass[j] * mag;
            vx[j] += dx * mass[i] * mag;
            vy[j] += dy * mass[i] * mag;
            vz[j] += dz * mass[i] * mag;
        }
    }
    i = 0;
    while (i < bodies) : (i += 1) {
        x[i] += dt * vx[i];
        y[i] += dt * vy[i];
        z[i] += dt * vz[i];
    }
}

fn totalEnergy() f64 {
    var e: f64 = 0.0;
    var i: usize = 0;
    while (i < bodies) : (i += 1) {
        e += 0.5 * mass[i] * (vx[i] * vx[i] + vy[i] * vy[i] + vz[i] * vz[i]);
        var j: usize = i + 1;
        while (j < bodies) : (j += 1) {
            const dx = x[i] - x[j];
            const dy = y[i] - y[j];
            const dz = z[i] - z[j];
            const distance = math.sqrt(dx * dx + dy * dy + dz * dz);
            e -= (mass[i] * mass[j]) / distance;
        }
    }
    return e;
}

pub fn main() void {
    initBodies();
    std.debug.print("{d:.9}\n", .{totalEnergy()});
    var n: usize = 0;
    while (n < 2000000) : (n += 1) {
        advance(0.01);
    }
    std.debug.print("{d:.9}\n", .{totalEnergy()});
}
