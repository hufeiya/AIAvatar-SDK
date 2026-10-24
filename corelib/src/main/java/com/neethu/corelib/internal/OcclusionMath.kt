package com.neethu.corelib.internal

import kotlin.math.abs

/**
 * Pure geometry for the scene occlusion auto-hide (wall cutaway): segment vs
 * axis-aligned box intersection. No Filament/android dependencies — JVM
 * testable（纯数学类不带 Log，与 LocomotionEngine 同惯例）.
 *
 * The occlusion test shoots the segment camera-eye → body point against each
 * occluder's world AABB; a hit means the wall blocks the view of that body
 * point and the wall hides.
 */
internal object OcclusionMath {

    /**
     * Whether the segment [eye]→[target] intersects the AABB [min]..[max].
     * Slab method with per-axis entry/exit overlap; a segment fully inside the
     * box counts as a hit (tEnter ≤ 0 ≤ tExit). Degenerate zero-length
     * segments (eye == target) count as a hit only when the point is inside.
     */
    fun segmentIntersectsAabb(
        eye: FloatArray,
        target: FloatArray,
        min: FloatArray,
        max: FloatArray,
    ): Boolean {
        var tEnter = 0f
        var tExit = 1f
        for (axis in 0..2) {
            val d = target[axis] - eye[axis]
            val lo = min[axis]
            val hi = max[axis]
            if (abs(d) < 1e-8f) {
                // Parallel to this axis' slabs: must start inside the slab.
                if (eye[axis] < lo || eye[axis] > hi) return false
            } else {
                val inv = 1f / d
                var t0 = (lo - eye[axis]) * inv
                var t1 = (hi - eye[axis]) * inv
                if (t0 > t1) {
                    val tmp = t0
                    t0 = t1
                    t1 = tmp
                }
                if (t0 > tEnter) tEnter = t0
                if (t1 < tExit) tExit = t1
                if (tEnter > tExit) return false
            }
        }
        return true
    }

    /**
     * Transform the 8 corners of a local-space AABB by a column-major 4×4
     * matrix and return the world-space bounds.
     */
    fun transformAabb(
        min: FloatArray,
        max: FloatArray,
        matrix: FloatArray,
    ): Pair<FloatArray, FloatArray> {
        var wmin = FloatArray(3) { Float.POSITIVE_INFINITY }
        var wmax = FloatArray(3) { Float.NEGATIVE_INFINITY }
        var i = 0
        for (corner in 0 until 8) {
            val lx = if (corner and 1 == 0) min[0] else max[0]
            val ly = if (corner and 2 == 0) min[1] else max[1]
            val lz = if (corner and 4 == 0) min[2] else max[2]
            for (axis in 0..2) {
                val v = matrix[axis] * lx + matrix[4 + axis] * ly + matrix[8 + axis] * lz + matrix[12 + axis]
                if (v < wmin[axis]) wmin[axis] = v
                if (v > wmax[axis]) wmax[axis] = v
            }
            i++
        }
        return wmin to wmax
    }

    /** Expand [bounds] by [margin] on every axis (flicker hysteresis). */
    fun expand(min: FloatArray, max: FloatArray, margin: Float): Pair<FloatArray, FloatArray> =
        FloatArray(3) { min[it] - margin } to FloatArray(3) { max[it] + margin }
}
