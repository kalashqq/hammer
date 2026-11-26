package hammer.vmf

import hammer.geometry.*

/** Editable map: wraps the raw node tree so unknown blocks/properties are never lost. */
class VmfDocument(val root: VmfNode) {
    val world: VmfNode get() = root.child("world") ?: root.add(VmfNode("world").apply { this["id"] = "1"; this["classname"] = "worldspawn" })
    val entities: List<VmfNode> get() = root.all("entity")
    fun solids(): List<VmfNode> = world.all("solid") + entities.flatMap { it.all("solid") }

    fun maxId(): Int {
        var m = 0
        fun walk(n: VmfNode) { if (n.name in setOf("world", "entity", "solid", "side", "group")) n["id"]?.toIntOrNull()?.let { m = maxOf(m, it) }; n.children.forEach(::walk) }
        walk(root); return m
    }
    private var counter = -1
    fun nextId(): Int { if (counter < 0) counter = maxId(); return ++counter }

    fun addBox(mn: Vec3, mx: Vec3, material: String = "DEV/DEV_MEASUREGENERIC01"): VmfNode {
        val solid = VmfNode("solid").apply { this["id"] = nextId().toString() }
        BrushBuilder.boxSides(mn, mx, material, ::nextId).forEach { solid.add(it) }
        world.add(solid); return solid
    }

    fun meshes(): List<BrushMesh> = solids().map { BrushBuilder.build(it) }
    fun save(): String = VmfWriter.write(root)

    companion object {
        fun parse(text: String) = VmfDocument(VmfParser.parse(text))
        fun empty() = VmfDocument(VmfParser.parse("versioninfo\n{\n\t\"editorversion\" \"400\"\n\t\"mapversion\" \"1\"\n\t\"formatversion\" \"100\"\n\t\"prefab\" \"0\"\n}\nworld\n{\n\t\"id\" \"1\"\n\t\"classname\" \"worldspawn\"\n\t\"skyname\" \"sky_day01_01\"\n}\n"))
    }
}
