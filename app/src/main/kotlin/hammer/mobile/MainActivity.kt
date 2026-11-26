package hammer.mobile

import android.graphics.Color
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import hammer.edit.*
import hammer.geometry.*
import hammer.vmf.*
import kotlin.math.*

class MainActivity : ComponentActivity() {
    private val renderer = MapRenderer()
    private lateinit var gl: GLSurfaceView
    private lateinit var status: TextView
    private var doc = VmfDocument.empty()
    private var stack = CommandStack()
    private var meshes: List<BrushMesh> = emptyList()
    private var selected = -1
    private var currentUri: Uri? = null
    private var fileName = "новая карта"

    private val openLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) load(uri) }
    private val saveAsLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) { currentUri = uri; fileName = uri.lastPathSegment ?: fileName; writeTo(uri); updateStatus() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        gl = GLSurfaceView(this).apply {
            setEGLContextClientVersion(3); setRenderer(renderer); renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
            setOnTouchListener { _, e -> handleTouch(e); true }
        }
        status = TextView(this).apply { setTextColor(Color.WHITE); setPadding(16, 8, 16, 8); setBackgroundColor(0x88000000.toInt()) }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            listOf<Pair<String, () -> Unit>>(
                "Открыть" to { openLauncher.launch(arrayOf("*/*")) },
                "Новая" to { newMap() },
                "Сохранить" to { save() },
                "Сохранить как" to { saveAsLauncher.launch("map.vmf") },
                "+ Блок" to { addBlock() },
                "Удалить" to { deleteSelected() },
                "↶" to { if (stack.undo()) refresh() },
                "↷" to { if (stack.redo()) refresh() },
                "Фокус" to { focusSelected() },
                "Вся карта" to { showAll() },
            ).forEach { (t, a) -> addView(button(t, a)) }
        }
        val bar = HorizontalScrollView(this).apply { setBackgroundColor(0xCC15171C.toInt()); addView(row) }
        val root = FrameLayout(this)
        root.addView(gl)
        root.addView(status, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START))
        root.addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        setContentView(root)
        updateStatus()
    }

    override fun onPause() { super.onPause(); gl.onPause() }
    override fun onResume() { super.onResume(); gl.onResume() }

    private fun button(text: String, action: () -> Unit) = Button(this).apply {
        this.text = text; isAllCaps = false; setTextColor(Color.WHITE); setBackgroundColor(0xFF3A3F4B.toInt())
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { setMargins(6, 6, 6, 6) }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ---------- files ----------
    private fun load(uri: Uri) {
        toast("Загрузка…")
        Thread {
            try {
                val text = contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
                val d = VmfDocument.parse(text); val m = d.meshes()
                runOnUiThread {
                    doc = d; stack = CommandStack(); meshes = m; currentUri = uri; fileName = uri.lastPathSegment ?: "map.vmf"
                    renderer.setMeshes(m); select(-1); showAll()
                }
            } catch (e: Throwable) { runOnUiThread { toast("Ошибка: ${e.message}") } }
        }.start()
    }

    private fun newMap() { doc = VmfDocument.empty(); stack = CommandStack(); currentUri = null; fileName = "новая карта"; selected = -1; renderer.selectedId = -1; refresh() }

    private fun save() {
        val u = currentUri ?: return saveAsLauncher.launch("map.vmf")
        writeTo(u)
    }

    private fun writeTo(uri: Uri) {
        val text = doc.save()
        Thread {
            try {
                contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                runOnUiThread { toast("Сохранено") }
            } catch (e: Throwable) { runOnUiThread { toast("Не удалось сохранить: ${e.message}"); saveAsLauncher.launch("map.vmf") } }
        }.start()
    }

    // ---------- editing ----------
    private fun exec(c: Command) { stack.run(c); refresh() }

    private fun refresh() {
        meshes = doc.meshes(); renderer.setMeshes(meshes)
        if (meshes.none { it.solidId == selected }) { selected = -1; renderer.selectedId = -1 }
        gl.requestRender(); updateStatus()
    }

    private fun select(id: Int) { selected = id; renderer.selectedId = id; gl.requestRender(); updateStatus() }

    private fun addBlock() {
        val d = doc; val g = 64.0; val c = renderer.cam
        val x = floor(c.tx / g) * g; val y = floor(c.ty / g) * g; val z = floor(c.tz / g) * g
        var node: VmfNode? = null
        exec(object : Command {
            override val label = "Добавить блок"
            override fun apply() { val n = node; if (n == null) node = d.addBox(Vec3(x, y, z), Vec3(x + g, y + g, z + g)) else d.world.children.add(n) }
            override fun revert() { node?.let { d.world.children.remove(it) } }
        })
        node?.get("id")?.toIntOrNull()?.let { select(it) }
    }

    private fun deleteSelected() {
        val id = selected
        if (id < 0) return toast("Ничего не выбрано")
        val d = doc
        val (parent, node) = (listOf(d.world) + d.entities).firstNotNullOfOrNull { p ->
            p.all("solid").firstOrNull { it["id"] == id.toString() }?.let { p to it }
        } ?: return toast("Блок не найден")
        var idx = 0
        exec(object : Command {
            override val label = "Удалить блок"
            override fun apply() { idx = parent.children.indexOf(node); parent.children.remove(node) }
            override fun revert() { parent.children.add(min(idx, parent.children.size), node) }
        })
    }

    // ---------- camera ----------
    private fun bounds(ms: List<BrushMesh>): Pair<Vec3, Vec3>? {
        val vs = ms.flatMap { it.faces }.filter { !isSky(it.material) }.flatMap { it.vertices }
        if (vs.isEmpty()) return null
        return Vec3(vs.minOf { it.x }, vs.minOf { it.y }, vs.minOf { it.z }) to Vec3(vs.maxOf { it.x }, vs.maxOf { it.y }, vs.maxOf { it.z })
    }

    private fun frame(b: Pair<Vec3, Vec3>?) {
        val c = renderer.cam
        if (b == null) { c.tx = 0f; c.ty = 0f; c.tz = 0f; c.dist = 600f }
        else {
            val (mn, mx) = b; val ctr = (mn + mx) * 0.5; val e = mx - mn
            c.tx = ctr.x.toFloat(); c.ty = ctr.y.toFloat(); c.tz = ctr.z.toFloat()
            c.dist = (max(e.x, max(e.y, e.z)) * 1.8).toFloat().coerceAtLeast(96f)
        }
        gl.requestRender()
    }

    private fun showAll() = frame(bounds(meshes))
    private fun focusSelected() {
        val m = meshes.firstOrNull { it.solidId == selected } ?: return toast("Ничего не выбрано")
        frame(bounds(listOf(m)))
    }

    private fun updateStatus() { status.text = "$fileName · брашей: ${meshes.size} · выбран: ${if (selected >= 0) selected.toString() else "—"}" }

    private var lx = 0f; private var ly = 0f; private var lcx = 0f; private var lcy = 0f; private var ldist = 0f
    private var downX = 0f; private var downY = 0f; private var downT = 0L; private var moved = false; private var maxP = 0

    private fun handleTouch(e: MotionEvent) {
        val cam = renderer.cam
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; downT = e.eventTime; moved = false; maxP = 1; lx = e.x; ly = e.y }
            MotionEvent.ACTION_POINTER_DOWN -> {
                maxP = max(maxP, e.pointerCount)
                if (e.pointerCount == 2) { lcx = (e.getX(0) + e.getX(1)) / 2; lcy = (e.getY(0) + e.getY(1)) / 2; ldist = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1)) }
            }
            MotionEvent.ACTION_MOVE -> if (e.pointerCount == 1) {
                cam.orbit(e.x - lx, e.y - ly)
                if (hypot(e.x - downX, e.y - downY) > 20f) moved = true
                lx = e.x; ly = e.y; gl.requestRender()
            } else if (e.pointerCount >= 2) {
                moved = true
                val cx = (e.getX(0) + e.getX(1)) / 2; val cy = (e.getY(0) + e.getY(1)) / 2
                val d = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
                cam.pan(cx - lcx, cy - lcy, 1.155f * cam.dist / gl.height)
                if (ldist > 1f && d > 1f) cam.zoom(ldist / d)
                lcx = cx; lcy = cy; ldist = d; gl.requestRender()
            }
            MotionEvent.ACTION_POINTER_UP -> if (e.pointerCount == 2) { val i = if (e.actionIndex == 0) 1 else 0; lx = e.getX(i); ly = e.getY(i) }
            MotionEvent.ACTION_UP -> if (!moved && maxP == 1 && e.eventTime - downT < 300) {
                val x = e.x; val y = e.y
                gl.queueEvent { val id = renderer.pick(x, y); runOnUiThread { select(id) } }
            }
        }
    }
}
