package com.justin.promptbubble

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.hypot

/**
 * The heart of the app. Runs as an accessibility service so it can:
 *  - draw the floating bubble over every app (accessibility overlay),
 *  - read the text on screen (window content),
 *  - take screenshots without a "start recording?" prompt each time (Android 11+).
 */
class BubbleService : AccessibilityService() {

    companion object {
        var instance: BubbleService? = null
            private set
    }

    private enum class Mode { TEXT, SHOT }

    private lateinit var wm: WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var bubble: TextView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var menu: View? = null
    private var overlay: View? = null
    private var menuDismissedAt = 0L
    private val trayListener: () -> Unit = { updateBadge() }

    // ---------------------------------------------------------------- lifecycle

    override fun onServiceConnected() {
        instance = this
        wm = getSystemService(WindowManager::class.java)
        TrayStore.addListener(trayListener)
        if (Prefs.bubbleOn(this)) showBubble()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        if (instance !== this) return
        TrayStore.removeListener(trayListener)
        hideMenu()
        removeOverlay()
        bubble?.let { runCatching { wm.removeView(it) } }
        bubble = null
        instance = null
    }

    // ---------------------------------------------------------------- bubble

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private val bubbleSize get() = dp(52)
    private fun screen(): Rect = wm.currentWindowMetrics.bounds

    private fun overlayParams(w: Int, h: Int, extraFlags: Int = 0) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or extraFlags,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }

    fun isBubbleShown() = bubble != null

    fun showBubble() {
        Prefs.setBubbleOn(this, true)
        if (bubble != null) return
        val v = TextView(this).apply {
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF5B5BD6.toInt())
                setStroke(dp(2), 0x66FFFFFF)
            }
            elevation = dp(6).toFloat()
            alpha = 0.92f
        }
        val s = screen()
        val p = overlayParams(bubbleSize, bubbleSize).apply {
            x = s.width() - bubbleSize - dp(4)
            y = s.height() / 3
        }
        v.setOnTouchListener(DragListener(p))
        wm.addView(v, p)
        bubble = v
        bubbleParams = p
        updateBadge()
    }

    fun hideBubble() {
        Prefs.setBubbleOn(this, false)
        hideMenu()
        bubble?.let { runCatching { wm.removeView(it) } }
        bubble = null
    }

    /** The bubble shows ✦ when the tray is empty, otherwise how many items you've collected. */
    private fun updateBadge() {
        val n = TrayStore.count(this)
        bubble?.text = if (n == 0) "✦" else n.toString()
    }

    private fun flashBubble() {
        bubble?.animate()?.scaleX(1.25f)?.scaleY(1.25f)?.setDuration(120)?.withEndAction {
            bubble?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(120)?.start()
        }?.start()
    }

    private inner class DragListener(private val p: WindowManager.LayoutParams) : View.OnTouchListener {
        private var sx = 0f; private var sy = 0f; private var px = 0; private var py = 0
        private var moved = false
        private val slop = ViewConfiguration.get(this@BubbleService).scaledTouchSlop

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    sx = e.rawX; sy = e.rawY; px = p.x; py = p.y; moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - sx; val dy = e.rawY - sy
                    if (!moved && hypot(dx, dy) > slop) { moved = true; hideMenu() }
                    if (moved) {
                        p.x = px + dx.toInt(); p.y = py + dy.toInt()
                        wm.updateViewLayout(v, p)
                    }
                }
                MotionEvent.ACTION_UP -> if (moved) snapToEdge(v) else toggleMenu()
            }
            return true
        }

        private fun snapToEdge(v: View) {
            val s = screen()
            p.x = if (p.x + bubbleSize / 2 < s.width() / 2) dp(4) else s.width() - bubbleSize - dp(4)
            p.y = p.y.coerceIn(0, s.height() - bubbleSize)
            wm.updateViewLayout(v, p)
        }
    }

    // ---------------------------------------------------------------- menu

    private fun toggleMenu() {
        if (menu != null) { hideMenu(); return }
        // A tap on the bubble first counts as an "outside" tap that closes the menu; don't reopen it.
        if (SystemClock.uptimeMillis() - menuDismissedAt < 300) return
        showMenu()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showMenu() {
        val anchor = bubbleParams ?: return
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat(); setColor(0xF2202028.toInt())
            }
            elevation = dp(8).toFloat()
        }
        fun item(label: String, action: () -> Unit) {
            col.addView(TextView(this).apply {
                text = label
                textSize = 15f
                setTextColor(Color.WHITE)
                setPadding(dp(14), dp(11), dp(18), dp(11))
                setOnClickListener { hideMenu(); action() }
            })
        }
        item("📋  Grab all text") { grabText(null) }
        item("🔍  Grab text in area") { startSelection(Mode.TEXT) }
        item("✂️  Snip screenshot") { startSelection(Mode.SHOT) }
        item("🗂  Open tray (${TrayStore.count(this)})") { openTray() }
        item("✕  Hide bubble") { hideBubble() }

        col.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val s = screen()
        val onLeft = anchor.x + bubbleSize / 2 < s.width() / 2
        val p = overlayParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
        ).apply {
            x = if (onLeft) anchor.x + bubbleSize + dp(8) else anchor.x - col.measuredWidth - dp(8)
            y = anchor.y.coerceAtMost(s.height() - col.measuredHeight - dp(24)).coerceAtLeast(0)
        }
        col.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                hideMenu(); menuDismissedAt = SystemClock.uptimeMillis()
            }
            false
        }
        wm.addView(col, p)
        menu = col
    }

    private fun hideMenu() {
        menu?.let { runCatching { wm.removeView(it) } }
        menu = null
    }

    private fun openTray() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // ---------------------------------------------------------------- area selection

    private fun startSelection(mode: Mode) {
        removeOverlay()
        val hint = if (mode == Mode.SHOT) "Drag over the area to capture" else "Drag over the text to grab"
        val view = SelectionOverlay(this, hint) { rect ->
            removeOverlay()
            if (rect == null) { bubble?.visibility = View.VISIBLE; return@SelectionOverlay }
            when (mode) {
                Mode.TEXT -> { bubble?.visibility = View.VISIBLE; grabText(rect) }
                Mode.SHOT -> capture(rect)   // keeps the bubble hidden until the shot is taken
            }
        }
        val p = overlayParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        ).apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        bubble?.visibility = View.INVISIBLE
        wm.addView(view, p)
        overlay = view
    }

    private fun removeOverlay() {
        overlay?.let { runCatching { wm.removeView(it) } }
        overlay = null
    }

    // ---------------------------------------------------------------- text

    /** Root nodes of the app windows on screen (skipping our own), with a readable source name. */
    private fun appRoots(): List<Pair<AccessibilityNodeInfo, String>> {
        val out = mutableListOf<Pair<AccessibilityNodeInfo, String>>()
        for (w in windows) {
            if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val root = w.root ?: continue
            if (root.packageName == packageName) continue
            out += root to (w.title?.toString() ?: root.packageName?.toString() ?: "App")
        }
        if (out.isEmpty()) rootInActiveWindow?.takeIf { it.packageName != packageName }?.let {
            out += it to (it.packageName?.toString() ?: "App")
        }
        return out
    }

    private fun collect(node: AccessibilityNodeInfo, out: LinkedHashSet<String>, area: Rect?, depth: Int = 0) {
        if (depth > 80 || !node.isVisibleToUser) return
        val inArea = area == null || Rect().also { node.getBoundsInScreen(it) }.let {
            area.contains(it.centerX(), it.centerY())
        }
        if (inArea && !node.isPassword) {
            var t = node.text?.toString()?.trim().orEmpty()
            // Use content descriptions only on leaf nodes, so containers don't duplicate their children.
            if (t.isEmpty() && node.childCount == 0) t = node.contentDescription?.toString()?.trim().orEmpty()
            if (t.isNotEmpty()) out += t
        }
        for (i in 0 until node.childCount) node.getChild(i)?.let { collect(it, out, area, depth + 1) }
    }

    private fun grabText(area: Rect?) {
        val roots = appRoots()
        if (roots.isEmpty()) { toast("Couldn't read this screen"); return }
        val lines = LinkedHashSet<String>()
        roots.forEach { collect(it.first, lines, area) }
        if (lines.isEmpty()) {
            toast("No readable text here — try Snip instead")
            return
        }
        TrayStore.addText(this, roots.first().second, lines.joinToString("\n"))
        toast("Grabbed ${lines.size} line${if (lines.size == 1) "" else "s"} from ${roots.first().second}")
        flashBubble()
    }

    // ---------------------------------------------------------------- screenshot

    private fun capture(rect: Rect) {
        val source = appRoots().firstOrNull()?.second ?: "Screen"
        // Give the overlay a moment to disappear so it isn't in the shot.
        handler.postDelayed({
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    bubble?.visibility = View.VISIBLE
                    val buffer = result.hardwareBuffer
                    val hw = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                    if (hw == null) { buffer.close(); toast("Screenshot failed"); return }
                    val full = hw.copy(Bitmap.Config.ARGB_8888, false)
                    hw.recycle(); buffer.close()
                    val r = Rect(rect)
                    if (!r.intersect(0, 0, full.width, full.height)) { toast("Selection was off-screen"); return }
                    Thread {
                        val crop = Bitmap.createBitmap(full, r.left, r.top, r.width(), r.height())
                        val uri = saveImage(crop)
                        handler.post { onSnipSaved(uri, source) }
                    }.start()
                }

                override fun onFailure(errorCode: Int) {
                    bubble?.visibility = View.VISIBLE
                    toast(
                        if (errorCode == ERROR_TAKE_SCREENSHOT_SECURE_WINDOW) "This app blocks screenshots"
                        else "Screenshot failed ($errorCode)"
                    )
                }
            })
        }, 250)
    }

    private fun onSnipSaved(uri: Uri?, source: String) {
        if (uri == null) { toast("Couldn't save the snip"); return }
        TrayStore.addImage(this, source, uri.toString())
        runCatching {
            getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newUri(contentResolver, "Snip", uri))
        }
        toast("Snip added to tray and copied")
        flashBubble()
    }

    /** Saves to Pictures/PromptBubble so the image also shows up in your gallery and can be shared. */
    private fun saveImage(bmp: Bitmap): Uri? = runCatching {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "snip_${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/PromptBubble")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
        contentResolver.openOutputStream(uri)!!.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        contentResolver.update(uri, values, null, null)
        uri
    }.getOrNull()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
