package com.agentos.shell.tools

import android.content.Context
import android.hardware.camera2.CameraManager
import android.util.Log

/**
 * Flashlight / torch. `setTorchMode` needs NO permission and works on any device with a camera flash. We track
 * the real hardware state via a TorchCallback so "toggle" and the UI always reflect what's actually on, even if
 * another app changed it. Returns human strings for the assistant to speak back.
 */
object Torch {
    private const val TAG = "SlyOS-Torch"
    @Volatile private var on = false
    @Volatile private var flashId: String? = null
    @Volatile private var registered = false

    private fun cm(ctx: Context) = ctx.applicationContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    /** The first camera that has a flash unit (usually the back camera). Null if the device has no flash. */
    private fun flashCameraId(ctx: Context): String? {
        flashId?.let { return it }
        return try {
            val m = cm(ctx)
            val id = m.cameraIdList.firstOrNull { cid ->
                m.getCameraCharacteristics(cid).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
            flashId = id
            if (id != null && !registered) {
                try {
                    m.registerTorchCallback(object : CameraManager.TorchCallback() {
                        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                            if (cameraId == flashId) on = enabled
                        }
                    }, null)
                    registered = true
                } catch (e: Exception) {}
            }
            id
        } catch (e: Exception) { null }
    }

    fun isOn(): Boolean = on

    fun hasFlash(ctx: Context): Boolean = flashCameraId(ctx) != null

    /** on = true/false, or pass null to toggle. Returns a spoken-style confirmation. */
    fun set(ctx: Context, want: Boolean?): String {
        val id = flashCameraId(ctx) ?: return "This device doesn't have a flashlight."
        val target = want ?: !on
        return try {
            cm(ctx).setTorchMode(id, target)
            on = target
            if (target) "Flashlight on." else "Flashlight off."
        } catch (e: Exception) {
            Log.w(TAG, "setTorchMode: ${e.message}")
            "I couldn't reach the flashlight just now."
        }
    }
}
