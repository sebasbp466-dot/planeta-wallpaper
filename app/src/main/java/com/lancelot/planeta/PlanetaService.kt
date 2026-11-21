package com.lancelot.planeta

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.opengl.GLSurfaceView
import android.os.Build
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder

class PlanetaService : WallpaperService() {

    override fun onCreateEngine(): Engine = PlanetaEngine()

    inner class PlanetaEngine : Engine(), SensorEventListener {
        private val renderer = PlanetaRenderer()
        private var glView: GLView? = null
        private var sm: SensorManager? = null
        private var realW = 0
        private var fx = 0f
        private var fy = 0f

        private val unlock = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                renderer.kick()
            }
        }

        inner class GLView(ctx: Context) : GLSurfaceView(ctx) {
            override fun getHolder(): SurfaceHolder = surfaceHolder
            fun destruir() {
                super.onDetachedFromWindow()
            }
        }

        override fun onCreate(holder: SurfaceHolder) {
            super.onCreate(holder)
            glView = GLView(this@PlanetaService).apply {
                setEGLContextClientVersion(3)
                setRenderer(renderer)
                renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            }
            sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
            val f = IntentFilter(Intent.ACTION_USER_PRESENT)
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(unlock, f, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(unlock, f)
            }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            // Renderiza a media resolucion: el shader es pesado para un Redmi 9.
            if (realW == 0) {
                realW = width
                holder.setFixedSize(width / 2, height / 2)
            }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            val v = glView ?: return
            if (visible) {
                v.onResume()
                val s = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
                if (s != null) sm?.registerListener(this, s, SensorManager.SENSOR_DELAY_UI)
                renderer.kick()
            } else {
                v.onPause()
                sm?.unregisterListener(this)
            }
        }

        override fun onOffsetsChanged(
            xOffset: Float, yOffset: Float, xStep: Float, yStep: Float, xPixels: Int, yPixels: Int
        ) {
            renderer.scroll = xOffset
        }

        override fun onSensorChanged(e: SensorEvent) {
            val tx = (-e.values[0] / 9.8f).coerceIn(-1f, 1f)
            val ty = (e.values[1] / 9.8f - 0.85f).coerceIn(-1f, 1f)
            fx += (tx - fx) * 0.08f
            fy += (ty - fy) * 0.08f
            renderer.tiltX = fx
            renderer.tiltY = fy
        }

        override fun onAccuracyChanged(s: Sensor?, a: Int) {}

        override fun onDestroy() {
            try {
                unregisterReceiver(unlock)
            } catch (e: Exception) {
            }
            sm?.unregisterListener(this)
            glView?.destruir()
            glView = null
            super.onDestroy()
        }
    }
}
