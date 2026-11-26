package hammer.mobile

import kotlin.math.*

/** Z-up orbit camera around a target point (Source Engine convention). */
class OrbitCamera {
    var yaw = 0.8f; var pitch = 0.6f; var dist = 600f
    var tx = 0f; var ty = 0f; var tz = 0f

    fun eye() = floatArrayOf(tx + dist * cos(pitch) * cos(yaw), ty + dist * cos(pitch) * sin(yaw), tz + dist * sin(pitch))

    fun orbit(dx: Float, dy: Float) { yaw -= dx * 0.005f; pitch = (pitch + dy * 0.005f).coerceIn(-1.5f, 1.5f) }

    /** Pixel drag -> target shift. unit = world units per pixel at the target. */
    fun pan(dx: Float, dy: Float, unit: Float) {
        val rx = -sin(yaw); val ry = cos(yaw)
        val ux = -sin(pitch) * cos(yaw); val uy = -sin(pitch) * sin(yaw); val uz = cos(pitch)
        tx += -rx * dx * unit + ux * dy * unit
        ty += -ry * dx * unit + uy * dy * unit
        tz += uz * dy * unit
    }

    fun zoom(factor: Float) { dist = (dist * factor).coerceIn(16f, 40000f) }
}
