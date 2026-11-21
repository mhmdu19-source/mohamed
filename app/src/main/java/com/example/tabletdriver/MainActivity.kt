package com.example.tabletdriver

import android.app.PendingIntent
import android.content.*
import android.graphics.*
import android.hardware.usb.*
import android.os.Bundle
import android.view.Surface
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import kotlin.concurrent.thread

/*
 * Manifest needs:
 *   <uses-feature android:name="android.hardware.usb.host"/>
 *
 * Stage 1: run it, plug the tablet, touch the pen on it, and read the hex dump.
 * Stage 2: adjust parseReport() offsets/max values to match your tablet.
 */

// ---- Edit these after looking at the dump ----
const val MAX_X = 32767f      // max X value reported by the tablet
const val MAX_Y = 20479f      // max Y value reported by the tablet
const val MAX_P = 8191f       // max pressure
// -----------------------------------------------

class MainActivity : AppCompatActivity() {
    private lateinit var log: TextView
    private lateinit var canvasView: DrawView
    private val ACTION = "com.example.USB_PERM"
    @Volatile private var running = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        log = TextView(this).apply { textSize = 11f; setTextColor(Color.GREEN); setBackgroundColor(Color.BLACK) }
        canvasView = DrawView(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(log) }, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(canvasView, LinearLayout.LayoutParams(-1, 0, 3f))
        setContentView(root)

        val mgr = getSystemService(USB_SERVICE) as UsbManager
        val dev = mgr.deviceList.values.firstOrNull() ?: run { log.text = "No USB device"; return }
        registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) start(mgr, dev)
            }
        }, IntentFilter(ACTION), RECEIVER_NOT_EXPORTED)
        val pi = PendingIntent.getBroadcast(this, 0, Intent(ACTION).setPackage(packageName), PendingIntent.FLAG_MUTABLE)
        mgr.requestPermission(dev, pi)
    }

    private fun start(mgr: UsbManager, dev: UsbDevice) {
        val conn = mgr.openDevice(dev) ?: return
        running = true
        // Read every HID interface that has an IN interrupt endpoint
        for (n in 0 until dev.interfaceCount) {
            val intf = dev.getInterface(n)
            if (intf.interfaceClass != UsbConstants.USB_CLASS_HID) continue
            conn.claimInterface(intf, true)
            for (e in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(e)
                if (ep.direction != UsbConstants.USB_DIR_IN) continue
                thread { readLoop(conn, ep, n) }
            }
        }
    }

    private fun readLoop(conn: UsbDeviceConnection, ep: UsbEndpoint, intfNo: Int) {
        val buf = ByteArray(ep.maxPacketSize)
        while (running) {
            val len = conn.bulkTransfer(ep, buf, buf.size, 100)
            if (len <= 0) continue
            val data = buf.copyOf(len)
            runOnUiThread {
                log.text = "if$intfNo: " + data.joinToString(" ") { "%02X".format(it) } + "\n" +
                        log.text.toString().take(1500)
                parseReport(data)
            }
        }
    }

    // ASSUMED layout (common for XP-Pen): [id, status, xLo, xHi, yLo, yHi, pLo, pHi]
    // Check against your dump and change offsets if needed.
    private fun parseReport(d: ByteArray) {
        if (d.size < 8) return
        fun u16(i: Int) = (d[i].toInt() and 0xFF) or ((d[i + 1].toInt() and 0xFF) shl 8)
        val x = u16(2) / MAX_X
        val y = u16(4) / MAX_Y
        val p = u16(6) / MAX_P
        val inRange = (d[1].toInt() and 0x20) != 0 || p > 0
        canvasView.onPen(x, y, p, inRange)
    }

    override fun onDestroy() { running = false; super.onDestroy() }
}

class DrawView(c: Context) : View(c) {
    private val bmpPaint = Paint()
    private val pen = Paint().apply { color = Color.WHITE; isAntiAlias = true; strokeCap = Paint.Cap.ROUND }
    private var bmp: Bitmap? = null
    private var cv: Canvas? = null
    private var lx = -1f; private var ly = -1f

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { cv = Canvas(it) }
        cv?.drawColor(Color.DKGRAY)
    }

    /** Rotate normalized tablet coords (0..1) to match current screen rotation. */
    private fun rotate(x: Float, y: Float): Pair<Float, Float> {
        val rot = display?.rotation ?: Surface.ROTATION_0
        return when (rot) {
            Surface.ROTATION_90 -> Pair(y, 1f - x)
            Surface.ROTATION_180 -> Pair(1f - x, 1f - y)
            Surface.ROTATION_270 -> Pair(1f - y, x)
            else -> Pair(x, y)
        }
    }

    fun onPen(nx: Float, ny: Float, pressure: Float, down: Boolean) {
        val (rx, ry) = rotate(nx, ny)
        val px = rx * width; val py = ry * height
        if (down && pressure > 0.01f) {
            pen.strokeWidth = 2f + pressure * 20f
            if (lx >= 0) cv?.drawLine(lx, ly, px, py, pen)
            lx = px; ly = py
        } else { lx = -1f; ly = -1f }
        invalidate()
    }

    override fun onDraw(c: Canvas) { bmp?.let { c.drawBitmap(it, 0f, 0f, bmpPaint) } }
}
