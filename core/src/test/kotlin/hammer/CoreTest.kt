package hammer

import hammer.edit.*
import hammer.geometry.*
import hammer.vmf.*
import kotlin.test.*

class CoreTest {
    @Test fun roundTripKeepsUnknownAndDuplicateProps() {
        val src = "entity\n{\n\t\"id\" \"5\"\n\t\"classname\" \"logic_relay\"\n\t\"custom_x\" \"1\"\n\tconnections\n\t{\n\t\t\"OnTrigger\" \"a,b,0,0,-1\"\n\t\t\"OnTrigger\" \"c,d,0,0,-1\"\n\t}\n}\n"
        val doc = VmfDocument.parse(src)
        assertEquals(src, doc.save())
        assertEquals(2, doc.entities[0].child("connections")!!.props.size)
    }

    @Test fun boxBuildsSixQuadsWithOutwardNormals() {
        val doc = VmfDocument.empty(); val s = doc.addBox(Vec3(0.0, 0.0, 0.0), Vec3(64.0, 128.0, 32.0))
        val m = BrushBuilder.build(s)
        assertEquals(6, m.faces.size)
        assertTrue(m.faces.all { it.vertices.size == 4 })
        val c = Vec3(32.0, 64.0, 16.0)
        assertTrue(m.faces.all { f -> (f.vertices[0] - c) dot f.plane.normal > 0 })
        assertEquals(Vec3(64.0, 128.0, 32.0), m.max)
    }

    @Test fun saveAndReloadPreservesGeometry() {
        val doc = VmfDocument.empty(); doc.addBox(Vec3(-64.0, -64.0, 0.0), Vec3(64.0, 64.0, 16.0))
        val again = VmfDocument.parse(doc.save())
        assertEquals(1, again.solids().size); assertEquals(6, again.meshes()[0].faces.size)
        assertTrue(again.nextId() > again.maxId() - 1)
    }

    @Test fun undoRedo() {
        val doc = VmfDocument.empty(); val st = CommandStack(); var solid: VmfNode? = null
        st.run(object : Command {
            override val label = "box"
            override fun apply() { solid = solid ?: doc.addBox(Vec3.ZERO, Vec3(8.0, 8.0, 8.0)); if (solid !in doc.world.children) doc.world.children.add(solid!!) }
            override fun revert() { doc.world.children.remove(solid) }
        })
        assertEquals(1, doc.solids().size); st.undo(); assertEquals(0, doc.solids().size); st.redo(); assertEquals(1, doc.solids().size)
    }

    @Test fun malformedFileThrows() { assertFailsWith<VmfParseException> { VmfParser.parse("world\n{\n\"id\" \"1\"\n") } }
}
