package main

import (
	"fmt"
	"math"
)

const bodies = 5
const pi = 3.141592653589793
const solarMass = 4.0 * pi * pi
const daysPerYear = 365.24

var x, y, z, vx, vy, vz, mass [bodies]float64

func initBodies() {
	x[0], y[0], z[0], vx[0], vy[0], vz[0], mass[0] = 0, 0, 0, 0, 0, 0, solarMass

	x[1], y[1], z[1] = 4.84143144246472090e+00, -1.16032004402742839e+00, -1.03622044471123109e-01
	vx[1], vy[1], vz[1] = 1.66007664274403694e-03*daysPerYear, 7.69901118419740425e-03*daysPerYear, -6.90460016972063023e-05*daysPerYear
	mass[1] = 9.54791938424326609e-04 * solarMass

	x[2], y[2], z[2] = 8.34336671824457987e+00, 4.12479856412430479e+00, -4.03523417114321381e-01
	vx[2], vy[2], vz[2] = -2.76742510726862411e-03*daysPerYear, 4.99852801234917238e-03*daysPerYear, 2.30417297573763929e-05*daysPerYear
	mass[2] = 2.85885980666130812e-04 * solarMass

	x[3], y[3], z[3] = 1.28943695621391310e+01, -1.51111514016986312e+01, -2.23307578892655734e-01
	vx[3], vy[3], vz[3] = 2.96460137564761618e-03*daysPerYear, 2.37847173959480950e-03*daysPerYear, -2.96589568540237556e-05*daysPerYear
	mass[3] = 4.36624404335156298e-05 * solarMass

	x[4], y[4], z[4] = 1.53796971148509165e+01, -2.59193146099879641e+01, 1.79258772950371181e-01
	vx[4], vy[4], vz[4] = 2.68067772490389322e-03*daysPerYear, 1.62824170038242295e-03*daysPerYear, -9.51592254519715870e-05*daysPerYear
	mass[4] = 5.15138902046611451e-05 * solarMass

	var px, py, pz float64
	for i := 0; i < bodies; i++ {
		px += vx[i] * mass[i]
		py += vy[i] * mass[i]
		pz += vz[i] * mass[i]
	}
	vx[0] = -px / solarMass
	vy[0] = -py / solarMass
	vz[0] = -pz / solarMass
}

func advance(dt float64) {
	for i := 0; i < bodies; i++ {
		for j := i + 1; j < bodies; j++ {
			dx := x[i] - x[j]
			dy := y[i] - y[j]
			dz := z[i] - z[j]
			d2 := dx*dx + dy*dy + dz*dz
			mag := dt / (d2 * math.Sqrt(d2))
			vx[i] -= dx * mass[j] * mag
			vy[i] -= dy * mass[j] * mag
			vz[i] -= dz * mass[j] * mag
			vx[j] += dx * mass[i] * mag
			vy[j] += dy * mass[i] * mag
			vz[j] += dz * mass[i] * mag
		}
	}
	for i := 0; i < bodies; i++ {
		x[i] += dt * vx[i]
		y[i] += dt * vy[i]
		z[i] += dt * vz[i]
	}
}

func energy() float64 {
	e := 0.0
	for i := 0; i < bodies; i++ {
		e += 0.5 * mass[i] * (vx[i]*vx[i] + vy[i]*vy[i] + vz[i]*vz[i])
		for j := i + 1; j < bodies; j++ {
			dx := x[i] - x[j]
			dy := y[i] - y[j]
			dz := z[i] - z[j]
			distance := math.Sqrt(dx*dx + dy*dy + dz*dz)
			e -= (mass[i] * mass[j]) / distance
		}
	}
	return e
}

func main() {
	initBodies()
	fmt.Printf("%.9f\n", energy())
	for n := 0; n < 2000000; n++ {
		advance(0.01)
	}
	fmt.Printf("%.9f\n", energy())
}
