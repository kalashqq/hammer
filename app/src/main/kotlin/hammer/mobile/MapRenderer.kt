package hammer.mobile

import android.graphics.Color
import android.opengl.GLES30 as G
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import hammer.geometry.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs

fun isSky(material: String) = material.uppercase().startsWith("TOOLS/TOOLSSKYBOX")

private class FloatList {
    var a = FloatArray(4096); var n = 0
    fun add(v: Float) { if (n == a.size) a = a.copyOf(n * 2); a[n++] = v }
    fun add3(x: Float, y: Float, z: Float) { add(x); add(y); add(z) }
}

private class Range(val triStart: Int, val triCount: Int, val lineStart: Int, val lineCount: Int)

class MapRenderer : GLSurfaceView.Renderer {
    val cam = OrbitCamera()
    @Volatile var selectedId = -1
    @Volatile private var pending: List<BrushMesh>? = null
    private var meshes: List<BrushMesh> = emptyList()
    private val ranges = HashMap<Int, Range>()
    private var triVbo = 0; private var lineVbo = 0; private var gridVbo = 0
    private var gridCount = 0
    private var triProg = 0; private var lineProg = 0
    private var uTriVP = 0; private var uTint = 0; private var uLineVP = 0; private var uColor = 0
    private var w = 1; private var h = 1
    private val vp = FloatArray(16); private val proj = FloatArray(16); private val view = FloatArray(16)

    fun setMeshes(m: List<BrushMesh>) { pending = m }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        G.glClearColor(0.12f, 0.12f, 0.14f, 1f)
        G.glEnable(G.GL_DEPTH_TEST); G.glDepthFunc(G.GL_LEQUAL)
        triProg = program(TRI_VS, TRI_FS); lineProg = program(LINE_VS, LINE_FS)
        uTriVP = G.glGetUniformLocation(triProg, "uVP"); uTint = G.glGetUniformLocation(triProg, "uTint")
        uLineVP = G.glGetUniformLocation(lineProg, "uVP"); uColor = G.glGetUniformLocation(lineProg, "uColor")
        val ids = IntArray(3); G.glGenBuffers(3, ids, 0); triVbo = ids[0]; lineVbo = ids[1]; gridVbo = ids[2]
        buildGrid()
        if (pending == null) pending = meshes   // re-upload after context loss
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        w = maxOf(width, 1); h = maxOf(height, 1); G.glViewport(0, 0, w, h)
    }

    override fun onDrawFrame(gl: GL10?) {
        pending?.let { pending = null; meshes = it; upload(it) }
        G.glClear(G.GL_COLOR_BUFFER_BIT or G.GL_DEPTH_BUFFER_BIT)
        val e = cam.eye()
        Matrix.setLookAtM(view, 0, e[0], e[1], e[2], cam.tx, cam.ty, cam.tz, 0f, 0f, 1f)
        Matrix.perspectiveM(proj, 0, 60f, w.toFloat() / h, 4f, 60000f)
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0)

        // faces
        G.glUseProgram(triProg); G.glUniformMatrix4fv(uTriVP, 1, false, vp, 0)
        G.glBindBuffer(G.GL_ARRAY_BUFFER, triVbo)
        for (i in 0..2) { G.glEnableVertexAttribArray(i); G.glVertexAttribPointer(i, 3, G.GL_FLOAT, false, 36, i * 12) }
        G.glEnable(G.GL_POLYGON_OFFSET_FILL); G.glPolygonOffset(1f, 1f)
        G.glUniform1f(uTint, 0f)
        G.glDrawArrays(G.GL_TRIANGLES, 0, triCountAll)
        val sel = ranges[selectedId]
        if (sel != null) { G.glUniform1f(uTint, 1f); G.glDrawArrays(G.GL_TRIANGLES, sel.triStart, sel.triCount) }
        G.glDisable(G.GL_POLYGON_OFFSET_FILL)

        // lines
        G.glUseProgram(lineProg); G.glUniformMatrix4fv(uLineVP, 1, false, vp, 0)
        G.glDisableVertexAttribArray(1); G.glDisableVertexAttribArray(2)
        drawLines(gridVbo, 0, gridCount, 0.30f, 0.30f, 0.34f)
        drawLines(gridVbo, gridCount, 2, 0.9f, 0.2f, 0.2f)
        drawLines(gridVbo, gridCount + 2, 2, 0.2f, 0.9f, 0.2f)
        drawLines(gridVbo, gridCount + 4, 2, 0.3f, 0.5f, 1f)
        drawLines(lineVbo, 0, lineCountAll, 0.05f, 0.05f, 0.07f)
        if (sel != null) { G.glLineWidth(3f); drawLines(lineVbo, sel.lineStart, sel.lineCount, 1f, 0.9f, 0.1f); G.glLineWidth(1f) }
    }

    private var triCountAll = 0
    private var lineCountAll = 0

    private fun drawLines(vbo: Int, first: Int, count: Int, r: Float, g: Float, b: Float) {
        if (count <= 0) return
        G.glBindBuffer(G.GL_ARRAY_BUFFER, vbo)
        G.glEnableVertexAttribArray(0); G.glVertexAttribPointer(0, 3, G.GL_FLOAT, false, 12, 0)
        G.glUniform4f(uColor, r, g, b, 1f)
        G.glDrawArrays(G.GL_LINES, first, count)
    }

    private fun upload(list: List<BrushMesh>) {
        val tri = FloatList(); val ln = FloatList(); ranges.clear()
        for (br in list) {
            val ts = tri.n / 9; val ls = ln.n / 3
            for (f in br.faces) {
                if (isSky(f.material)) continue
                val c = colorOf(f.material); val n = f.plane.normal; val v = f.vertices
                for (i in 1 until v.size - 1) for (p in arrayOf(v[0], v[i], v[i + 1])) {
                    tri.add3(p.x.toFloat(), p.y.toFloat(), p.z.toFloat())
                    tri.add3(n.x.toFloat(), n.y.toFloat(), n.z.toFloat())
                    tri.add3(c[0], c[1], c[2])
                }
                for (i in v.indices) {
                    val p = v[i]; val q = v[(i + 1) % v.size]
                    ln.add3(p.x.toFloat(), p.y.toFloat(), p.z.toFloat()); ln.add3(q.x.toFloat(), q.y.toFloat(), q.z.toFloat())
                }
            }
            ranges[br.solidId] = Range(ts, tri.n / 9 - ts, ls, ln.n / 3 - ls)
        }
        triCountAll = tri.n / 9; lineCountAll = ln.n / 3
        bufferData(triVbo, tri.a, tri.n); bufferData(lineVbo, ln.a, ln.n)
    }

    private fun buildGrid() {
        val l = FloatList(); val ext = 2048f; var i = -ext
        while (i <= ext) { l.add3(i, -ext, 0f); l.add3(i, ext, 0f); l.add3(-ext, i, 0f); l.add3(ext, i, 0f); i += 64f }
        gridCount = l.n / 3
        l.add3(0f, 0f, 0f); l.add3(256f, 0f, 0f); l.add3(0f, 0f, 0f); l.add3(0f, 256f, 0f); l.add3(0f, 0f, 0f); l.add3(0f, 0f, 256f)
        bufferData(gridVbo, l.a, l.n)
    }

    private fun bufferData(vbo: Int, data: FloatArray, count: Int) {
        val bb = ByteBuffer.allocateDirect(maxOf(count, 1) * 4).order(ByteOrder.nativeOrder())
        bb.asFloatBuffer().put(data, 0, count)
        G.glBindBuffer(G.GL_ARRAY_BUFFER, vbo)
        G.glBufferData(G.GL_ARRAY_BUFFER, maxOf(count, 1) * 4, bb, G.GL_STATIC_DRAW)
    }

    private fun colorOf(m: String): FloatArray {
        val hue = ((m.uppercase().hashCode() and 0x7fffffff) % 360).toFloat()
        val c = Color.HSVToColor(floatArrayOf(hue, 0.35f, 0.85f))
        return floatArrayOf(Color.red(c) / 255f, Color.green(c) / 255f, Color.blue(c) / 255f)
    }

    /** Must be called on the GL thread. Returns the solid id under the screen point, or -1. */
    fun pick(px: Float, py: Float): Int {
        val inv = FloatArray(16)
        if (!Matrix.invertM(inv, 0, vp, 0)) return -1
        fun unproject(z: Float): Vec3 {
            val o = FloatArray(4)
            Matrix.multiplyMV(o, 0, inv, 0, floatArrayOf(2f * px / w - 1f, 1f - 2f * py / h, z, 1f), 0)
            return Vec3(o[0] / o[3].toDouble(), o[1] / o[3].toDouble(), o[2] / o[3].toDouble())
        }
        val a = unproject(-1f); val b = unproject(1f)
        val d = (b - a).normalized()
        var best = Double.MAX_VALUE; var bestId = -1
        for (br in meshes) for (f in br.faces) {
            if (isSky(f.material)) continue
            val v = f.vertices
            for (i in 1 until v.size - 1) {
                val t = rayTri(a, d, v[0], v[i], v[i + 1])
                if (t != null && t < best) { best = t; bestId = br.solidId }
            }
        }
        return bestId
    }

    private fun rayTri(o: Vec3, d: Vec3, a: Vec3, b: Vec3, c: Vec3): Double? {
        val e1 = b - a; val e2 = c - a; val p = d cross e2; val det = e1 dot p
        if (abs(det) < 1e-12) return null
        val inv = 1.0 / det; val s = o - a; val u = (s dot p) * inv
        if (u < 0 || u > 1) return null
        val q = s cross e1; val v = (d dot q) * inv
        if (v < 0 || u + v > 1) return null
        val t = (e2 dot q) * inv
        return if (t > 1e-3) t else null
    }

    private fun program(vs: String, fs: String): Int {
        fun sh(type: Int, src: String): Int {
            val s = G.glCreateShader(type); G.glShaderSource(s, src); G.glCompileShader(s)
            val ok = IntArray(1); G.glGetShaderiv(s, G.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) throw RuntimeException("Shader error: " + G.glGetShaderInfoLog(s))
            return s
        }
        val p = G.glCreateProgram(); G.glAttachShader(p, sh(G.GL_VERTEX_SHADER, vs)); G.glAttachShader(p, sh(G.GL_FRAGMENT_SHADER, fs))
        G.glLinkProgram(p)
        val ok = IntArray(1); G.glGetProgramiv(p, G.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) throw RuntimeException("Link error: " + G.glGetProgramInfoLog(p))
        return p
    }

    companion object {
        const val TRI_VS = """#version 300 es
uniform mat4 uVP; uniform float uTint;
layout(location=0) in vec3 aPos; layout(location=1) in vec3 aNor; layout(location=2) in vec3 aCol;
out vec3 vCol;
void main() {
  float d = 0.45 + 0.55 * abs(dot(normalize(aNor), normalize(vec3(0.4, 0.3, 0.85))));
  vCol = mix(aCol, vec3(1.0, 0.6, 0.1), uTint * 0.6) * d;
  gl_Position = uVP * vec4(aPos, 1.0);
}"""
        const val TRI_FS = """#version 300 es
precision mediump float; in vec3 vCol; out vec4 o;
void main() { o = vec4(vCol, 1.0); }"""
        const val LINE_VS = """#version 300 es
uniform mat4 uVP; layout(location=0) in vec3 aPos;
void main() { gl_Position = uVP * vec4(aPos, 1.0); }"""
        const val LINE_FS = """#version 300 es
precision mediump float; uniform vec4 uColor; out vec4 o;
void main() { o = uColor; }"""
    }
}
