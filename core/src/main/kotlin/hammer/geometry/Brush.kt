package hammer.geometry

import hammer.vmf.VmfNode

data class Plane(val normal: Vec3, val dist: Double) {
    fun side(p: Vec3) = (normal dot p) - dist
}

/** "[ux uy uz offset] scale" */
data class TexAxis(val dir: Vec3, val offset: Double, val scale: Double) {
    fun coord(p: Vec3, texSize: Int): Double = ((p dot dir) / scale + offset) / texSize
    companion object {
        fun parse(axis: String?, scale: String?): TexAxis? {
            val nums = axis?.replace("[", " ")?.replace("]", " ")?.trim()?.split(Regex("\\s+"))?.mapNotNull { it.toDoubleOrNull() }
            if (nums == null || nums.size < 4) return null
            return TexAxis(Vec3(nums[0], nums[1], nums[2]), nums[3], scale?.toDoubleOrNull()?.takeIf { it != 0.0 } ?: 0.25)
        }
    }
}

class Face(
    val sideId: Int, val material: String, val plane: Plane, val vertices: List<Vec3>,
    val uAxis: TexAxis?, val vAxis: TexAxis?,
) {
    /** UV for each vertex given the texture size in pixels. */
    fun uvs(w: Int, h: Int): List<Pair<Double, Double>> =
        vertices.map { (uAxis?.coord(it, w) ?: 0.0) to (vAxis?.coord(it, h) ?: 0.0) }
}

class BrushMesh(val solidId: Int, val faces: List<Face>) {
    val min: Vec3 get() = vertsAll().let { v -> Vec3(v.minOf { it.x }, v.minOf { it.y }, v.minOf { it.z }) }
    val max: Vec3 get() = vertsAll().let { v -> Vec3(v.maxOf { it.x }, v.maxOf { it.y }, v.maxOf { it.z }) }
    private fun vertsAll() = faces.flatMap { it.vertices }.ifEmpty { listOf(Vec3.ZERO) }
}

object BrushBuilder {
    private const val BIG = 65536.0
    private const val EPS = 1e-4

    fun parsePlane(s: String): Triple<Vec3, Vec3, Vec3>? {
        val n = Regex("-?[0-9]*\\.?[0-9]+(?:[eE][-+]?[0-9]+)?").findAll(s).map { it.value.toDouble() }.toList()
        if (n.size < 9) return null
        return Triple(Vec3(n[0], n[1], n[2]), Vec3(n[3], n[4], n[5]), Vec3(n[6], n[7], n[8]))
    }

    /** Source plane winding: normal = (p1-p2) x (p3-p2) points out of the solid; solid = { n.x <= d }. */
    fun planeOf(p1: Vec3, p2: Vec3, p3: Vec3): Plane? {
        val n = (p1 - p2) cross (p3 - p2)
        if (n.length() < 1e-9) return null
        val u = n.normalized(); return Plane(u, u dot p1)
    }

    /** Rebuilds polygons by clipping a huge quad on each plane with every other plane. Displacement solids are not handled here. */
    fun build(solid: VmfNode): BrushMesh {
        val sides = solid.all("side")
        val planes = sides.map { s -> parsePlane(s["plane"] ?: "")?.let { (a, b, c) -> planeOf(a, b, c) } }
        val faces = ArrayList<Face>()
        for ((i, s) in sides.withIndex()) {
            val pl = planes[i] ?: continue
            var poly: List<Vec3> = baseQuad(pl)
            for ((j, other) in planes.withIndex()) {
                if (j == i || other == null) continue
                poly = clip(poly, other); if (poly.size < 3) break
            }
            val clean = dedupe(poly)
            if (clean.size < 3) continue
            faces += Face(
                s["id"]?.toIntOrNull() ?: -1, s["material"] ?: "", pl, clean,
                TexAxis.parse(s["uaxis"], null)?.let { a -> a.copy(scale = scaleOf(s["uaxis"])) },
                TexAxis.parse(s["vaxis"], null)?.let { a -> a.copy(scale = scaleOf(s["vaxis"])) },
            )
        }
        return BrushMesh(solid["id"]?.toIntOrNull() ?: -1, faces)
    }

    private fun scaleOf(axis: String?): Double = axis?.substringAfter(']', "")?.trim()?.toDoubleOrNull()?.takeIf { it != 0.0 } ?: 0.25

    private fun baseQuad(pl: Plane): List<Vec3> {
        val n = pl.normal
        val ref = if (kotlin.math.abs(n.x) <= kotlin.math.abs(n.y) && kotlin.math.abs(n.x) <= kotlin.math.abs(n.z)) Vec3(1.0, 0.0, 0.0)
            else if (kotlin.math.abs(n.y) <= kotlin.math.abs(n.z)) Vec3(0.0, 1.0, 0.0) else Vec3(0.0, 0.0, 1.0)
        val u = (n cross ref).normalized(); val v = n cross u; val c = n * pl.dist
        return listOf(c - u * BIG - v * BIG, c + u * BIG - v * BIG, c + u * BIG + v * BIG, c - u * BIG + v * BIG)
    }

    private fun clip(poly: List<Vec3>, pl: Plane): List<Vec3> {
        val out = ArrayList<Vec3>()
        for (i in poly.indices) {
            val p = poly[i]; val q = poly[(i + 1) % poly.size]
            val dp = pl.side(p); val dq = pl.side(q)
            if (dp <= EPS) out += p
            if ((dp < -EPS && dq > EPS) || (dp > EPS && dq < -EPS)) out += p + (q - p) * (dp / (dp - dq))
        }
        return out
    }

    private fun dedupe(poly: List<Vec3>): List<Vec3> {
        val out = ArrayList<Vec3>()
        for (p in poly) if (out.isEmpty() || (p - out.last()).length() > 1e-3) out += p
        if (out.size > 1 && (out.first() - out.last()).length() <= 1e-3) out.removeAt(out.size - 1)
        return out
    }

    /** Axis-aligned box in Hammer's plane convention, ready to append to world/entity. */
    fun boxSides(mn: Vec3, mx: Vec3, material: String, nextId: () -> Int): List<VmfNode> {
        val (a, b, c) = Triple(mn.x, mn.y, mn.z); val (x, y, z) = Triple(mx.x, mx.y, mx.z)
        fun p(vararg v: Double) = "(${Vec3.fmt(v[0])} ${Vec3.fmt(v[1])} ${Vec3.fmt(v[2])})"
        val planes = listOf(
            listOf(p(a, y, z), p(x, y, z), p(x, b, z)), listOf(p(a, b, c), p(x, b, c), p(x, y, c)),
            listOf(p(a, y, z), p(a, b, z), p(a, b, c)), listOf(p(x, y, c), p(x, b, c), p(x, b, z)),
            listOf(p(x, b, c), p(a, b, c), p(a, b, z)), listOf(p(x, y, z), p(a, y, z), p(a, y, c)),
        )
        val axes = listOf(
            "[1 0 0 0]" to "[0 -1 0 0]", "[1 0 0 0]" to "[0 -1 0 0]", "[0 1 0 0]" to "[0 0 -1 0]",
            "[0 1 0 0]" to "[0 0 -1 0]", "[1 0 0 0]" to "[0 0 -1 0]", "[1 0 0 0]" to "[0 0 -1 0]",
        )
        return planes.mapIndexed { i, pts ->
            VmfNode("side").apply {
                this["id"] = nextId().toString(); this["plane"] = pts.joinToString(" ")
                this["material"] = material; this["uaxis"] = "${axes[i].first} 0.25"; this["vaxis"] = "${axes[i].second} 0.25"
                this["rotation"] = "0"; this["lightmapscale"] = "16"; this["smoothing_groups"] = "0"
            }
        }
    }
}
