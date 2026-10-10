package com.farside.workspace

import org.json.JSONArray

/** Test-only pose representation for asserting Home movement and keyboard layout. */
internal data class SavedWindowPose(val x: Float, val y: Float, val z: Float,
    val qx: Float, val qy: Float, val qz: Float, val qw: Float) {
    fun encode(): String = JSONArray(listOf(x, y, z, qx, qy, qz, qw)).toString()
    fun platformPose(namespace: String): Any {
        val scalar = Float::class.javaPrimitiveType!!
        val vector = Class.forName("$namespace.graphics.Vector3f")
        val rotation = Class.forName("$namespace.graphics.Vector4f")
        val position = vector.getConstructor(scalar, scalar, scalar).newInstance(x, y, z)
        val orientation = rotation.getConstructor(scalar, scalar, scalar, scalar).newInstance(qx, qy, qz, qw)
        return Class.forName("$namespace.graphics.Pose").getConstructor(rotation, vector).newInstance(orientation, position)
    }
    companion object {
        fun read(pose: Any): SavedWindowPose {
            val position = pose.javaClass.getMethod("getPosition").invoke(pose)
            val rotation = pose.javaClass.getMethod("getOrientation").invoke(pose)
            fun component(value: Any, name: String) = (value.javaClass.getMethod(name).invoke(value) as Number).toFloat()
            return SavedWindowPose(component(position, "getX"), component(position, "getY"), component(position, "getZ"),
                component(rotation, "getX"), component(rotation, "getY"), component(rotation, "getZ"), component(rotation, "getW"))
        }
    }
}
