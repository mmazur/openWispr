package com.voicerewriter

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.runBlocking

/**
 * Makes "insert the rewrite into the field I was typing in" possible. An overlay
 * cannot write into other apps — only an IME, the owning app, or an accessibility
 * service can.
 *
 * Insertion is event-driven: RewriteActivity hands us the text and finishes; once
 * the *host* app regains window focus (we ignore our own windows), we paste the
 * clipboard into its focused editable field. Timed retries back this up in case
 * no focus event fires. This avoids the trap of inserting while our own activity
 * is still the active window.
 */
class OpenWisprAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "OpenWisprA11y"

        @Volatile
        private var instance: OpenWisprAccessibilityService? = null

        /**
         * Package of the last non-self app to take window focus. The dictation
         * pipeline reads this to decide whether the target is a code/terminal field
         * (where spoken "dot"/"slash"/"dash" should pass through as words).
         */
        @Volatile
        var lastHostPackage: String? = null
            private set

        /**
         * System surfaces whose window-state changes must NOT be mistaken for the
         * dictation target. The status bar / notification shade / nav bar all report
         * as "com.android.systemui", and the keyboard reports as an input-method
         * package — neither is the app the user is dictating into, so letting them
         * overwrite [lastHostPackage] is what made history entries read "System UI".
         */
        private val NON_HOST_PACKAGES = setOf(
            "com.android.systemui", "android", "com.android.launcher", "com.sec.android.app.launcher",
        )

        private fun isHostPackage(pkg: String, self: String): Boolean =
            pkg != self && pkg !in NON_HOST_PACKAGES &&
                !pkg.contains("inputmethod") && !pkg.contains("honeyboard")

        /** True when the user has enabled the service in Accessibility settings. */
        val isEnabled: Boolean get() = instance != null

        /**
         * Insert [text] into the host app's focused
         * field as soon as that app is back in front. Returns true if the service
         * is running (so the caller knows auto-insert will be attempted).
         * Call from the main thread.
         */
        internal fun enqueueInsert(text: String, capture: ClipboardPasteCapture): Boolean {
            val svc = instance ?: return false
            svc.startInsert(text, capture)
            return true
        }

        /** Apply saved detection settings without restarting accessibility. */
        fun updateBubbleSettings(settings: Settings) {
            val svc = instance ?: return
            svc.main.post {
                val modeChanged = svc.keyboardOnlyDetection != settings.bubbleKeyboardOnly
                svc.keyboardOnlyDetection = settings.bubbleKeyboardOnly
                svc.debounceMs = settings.bubbleDebounceMs.coerceIn(0, BubbleDefaults.MAX_TIMING_MS).toLong()
                if (modeChanged) {
                    svc.main.removeCallbacks(svc.keyboardCheck)
                    svc.evaluateBubbleVisibility()
                }
            }
        }

        /** Re-check input availability when the bubble (re)starts. */
        fun reevaluate() {
            val svc = instance ?: return
            svc.main.post { svc.evaluateBubbleVisibility() }
        }
    }

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var pendingText: String? = null
    private var pendingPasteSession: ClipboardPasteSession? = null
    private val retryDelays = longArrayOf(250, 500, 900, 1400, 2000)
    private var keyboardOnlyDetection = false
    private var debounceMs = BubbleDefaults.DEBOUNCE_MS.toLong()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        val settings = try {
            runBlocking { SettingsRepository(applicationContext).get() }
        } catch (_: Exception) { Settings() }
        keyboardOnlyDetection = settings.bubbleKeyboardOnly
        debounceMs = settings.bubbleDebounceMs.toLong()
        Log.i(TAG, "service connected")
        // Now that focus detection is available, let the bubble switch to its
        // contextual visibility behavior (it starts always-visible without us).
        BubbleService.instance?.refreshGating()
        main.post { evaluateBubbleVisibility() }
    }

    /** Both detection modes use the configured debounce; defaults match v1.6.0. */
    private val keyboardCheck = Runnable { evaluateBubbleVisibility() }

    /**
     * Keyboard-only mode requires an input-method window. Turning it off restores
     * the original keyboard-or-editable-focus detection, including its active-window
     * fallback and preservation of visibility while our own activity is open.
     * Recording and confirmations keep themselves visible separately in BubbleService.
     */
    private fun evaluateBubbleVisibility() {
        val wins = try { windows } catch (_: Exception) { null }
        if (keyboardOnlyDetection) {
            // An IME window need not expose a root node. Never use editable focus
            // or retain a stale positive result when keyboard windows are absent.
            val imeOpen = wins?.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } == true
            Log.d(TAG, "bubbleFocus mode=keyboard ime=$imeOpen host=$lastHostPackage")
            BubbleService.instance?.setInputAvailable(imeOpen)
            return
        }
        if (wins.isNullOrEmpty()) {
            val root = rootInActiveWindow ?: return
            if (root.packageName == packageName) return
            val f = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            val editable = f != null && f.isEditable
            @Suppress("DEPRECATION") f?.recycle()
            Log.d(TAG, "bubbleFocus mode=original(fallback) editable=$editable")
            BubbleService.instance?.setInputAvailable(editable)
            return
        }
        var editable = false
        var imeOpen = false
        var ourModalActive = false
        for (w in wins) {
            if (w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) { imeOpen = true; continue }
            val root = w.root ?: continue
            if (root.packageName == packageName) {
                if (w.type == AccessibilityWindowInfo.TYPE_APPLICATION) ourModalActive = true
                continue
            }
            val f = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (f != null) {
                if (f.isEditable) editable = true
                @Suppress("DEPRECATION") f.recycle()
            }
            if (editable) break
        }
        if (ourModalActive) return
        Log.d(TAG, "bubbleFocus mode=original editable=$editable ime=$imeOpen host=$lastHostPackage")
        BubbleService.instance?.setInputAvailable(editable || imeOpen)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        // Track the foreground host app (cheap: window changes are infrequent) so the
        // dictation pipeline can adapt normalization to code/terminal fields.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString()
            if (pkg != null && isHostPackage(pkg, packageName)) lastHostPackage = pkg
        }
        // Drive the keyboard-gated bubble: re-check on events that can change it
        // (coalesced — content-changed and windows-changed both fire in bursts).
        // TYPE_WINDOWS_CHANGED is what tells us the keyboard came or went: dismissing
        // an IME needn't touch focus or window state, so without it the bubble would
        // stay up after the keyboard slid away.
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                main.removeCallbacks(keyboardCheck)
                main.postDelayed(keyboardCheck, debounceMs)
            }
        }
        if (pendingText == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                val pkg = event.packageName
                if (pkg != null && pkg != packageName) attemptInsert()
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        main.removeCallbacks(keyboardCheck)
        if (instance === this) instance = null
        // Service gone — focus detection is impossible, so let the bubble show always.
        BubbleService.instance?.refreshGating()
    }

    // ---------------- insertion ----------------

    private fun startInsert(text: String, capture: ClipboardPasteCapture) {
        // Note: we deliberately do NOT stage the clipboard here. The common insert path
        // writes the text straight into the field (ACTION_SET_TEXT) without touching the
        // clipboard, so a successful dictation no longer clobbers what the user had copied.
        // The clipboard is only used as a fallback (see attemptInsert / the give-up branch).
        pendingText = text
        pendingPasteSession = capture.session
        main.removeCallbacksAndMessages(null)
        // Backstop retries in case the focus event doesn't arrive.
        for (delay in retryDelays) main.postDelayed({ attemptInsert() }, delay)
        // Give up after the last retry: no field ever focused — fall back to a clipboard copy.
        main.postDelayed({
            val t = pendingText
            if (t != null) {
                pendingText = null
                pendingPasteSession = null
                setClipboard(t)
                // Say what happened. Reaching here means accessibility is running and we still
                // couldn't find an editable node to write into — which is the normal outcome in
                // apps that don't expose their composer (#78), not something the user did.
                //
                // Android 13+ raises its own "Copied" confirmation for the clipboard write, but
                // that reports a success and names no failure, so the dictation reads as having
                // worked while the field stays empty. This is the only place that says otherwise.
                //
                // Deliberately not the same as the no-accessibility path in acceptVoice, which
                // stays silent: a user who declined the permission gets the clipboard by choice
                // and doesn't need telling after every dictation.
                //
                // Said on the bubble rather than in a toast of ours: this service runs in the
                // background, and Android drops background toasts before they reach the screen.
                BubbleService.instance?.showClipboardFallback()
            }
        }, retryDelays.last() + 300)
    }

    /** Try once to insert into the host app's focused editable field. */
    private fun attemptInsert() {
        val text = pendingText ?: return
        val node = findHostFocusedEditable() ?: return
        var viaPaste = false
        val ok = try {
            // Prefer a clipboard-free splice at the cursor; fall back to paste only when
            // we can't determine the cursor (e.g. some WebView fields).
            insertAtCursor(node, text) || run { viaPaste = true; pasteViaClipboard(node, text) }
        } catch (e: Exception) {
            Log.e(TAG, "insert action failed", e); false
        } finally {
            @Suppress("DEPRECATION") node.recycle()
        }
        if (ok) {
            Log.i(TAG, if (viaPaste) "inserted via temporary clipboard paste"
                       else "inserted at cursor (clipboard untouched)")
            pendingText = null
            pendingPasteSession = null
            main.removeCallbacksAndMessages(null)
            // The haptic tick is the confirmation. A toast on top of text visibly appearing in
            // the field is telling the user something they can already see.
            vibrateTick()
            // The bubble says so too, briefly. Not redundant with the text appearing: the field
            // may be scrolled, hidden behind the keyboard, or simply not where the user is
            // looking — and this is the only signal that tells a real insert apart from the
            // clipboard fallback below, which from the user's side looks identical.
            BubbleService.instance?.showInserted()
        }
    }

    /**
     * Clipboard-free insert: splice [insert] in at the cursor (replacing any active
     * selection) via ACTION_SET_TEXT, then place the cursor after it. Returns false when
     * the cursor can't be determined in a non-empty field, so the caller can fall back to
     * paste. This is the path that keeps the clipboard untouched on a normal dictation.
     */
    private fun insertAtCursor(node: AccessibilityNodeInfo, insert: String): Boolean {
        val showingHint = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && node.isShowingHintText
        val edit = buildInputTextEdit(node.text?.toString(), showingHint,
            node.textSelectionStart, node.textSelectionEnd, insert) ?: return false
        val setArgs = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, edit.text)
        }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setArgs)) return false
        val cursor = edit.cursor
        val selArgs = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
        }
        // Text was already accepted. Cursor-placement failure must not cause a
        // later retry to insert it again.
        runCatching { node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selArgs) }
            .onFailure { Log.w(TAG, "cursor placement failed after accepted insertion", it) }
        return true
    }

    /** Fallback insert: borrow the clipboard, then restore its original contents. */
    private fun pasteViaClipboard(node: AccessibilityNodeInfo, text: String): Boolean {
        val session = pendingPasteSession ?: return false
        return session.paste(text) {
            node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        }
    }

    /** Focused editable node in the active window, only if it's NOT our own app. */
    private fun findHostFocusedEditable(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        if (root.packageName == packageName) return null // our sheet is still up
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null && focused.isEditable) return focused
        @Suppress("DEPRECATION") focused?.recycle()
        return null
    }

    private fun setClipboard(text: String) {
        val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cb.setPrimaryClip(ClipData.newPlainText("rewrite", text))
    }

    /** Light confirmation buzz when text lands in the field. */
    private fun vibrateTick() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION") getSystemService(Vibrator::class.java)
        } ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION") vibrator.vibrate(20)
            }
        } catch (_: Exception) {}
    }
}
