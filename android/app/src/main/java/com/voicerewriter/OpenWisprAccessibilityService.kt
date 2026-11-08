package com.voicerewriter

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
        internal fun enqueueInsert(text: String, capture: ClipboardPasteCapture, targetPackage: String?, initialFocus: String): Boolean {
            val svc = instance ?: return false
            svc.startInsert(text, capture, targetPackage, initialFocus)
            return true
        }

        data class InputTargetSnapshot(val packageName: String?, val description: String)

        /** Called only when the user starts a dictation, before our activity takes focus. */
        fun captureInputTarget(): InputTargetSnapshot? {
            val svc = instance ?: return null
            if (!InsertionDiagnostics.enabled(svc)) return null
            return try {
                for (window in svc.windows) {
                    if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION || (!window.isFocused && !window.isActive)) continue
                    val root = window.root ?: continue
                    val pkg = root.packageName?.toString() ?: continue
                    if (pkg == svc.packageName || pkg in NON_HOST_PACKAGES) continue
                    val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    val description = "windowActive=${window.isActive} windowFocused=${window.isFocused} " +
                        "input=${focused?.let { svc.nodeDiagnostics(it) } ?: "none"}"
                    @Suppress("DEPRECATION") focused?.recycle()
                    return InputTargetSnapshot(pkg, description)
                }
                InputTargetSnapshot(lastHostPackage, "No focused application window exposed")
            } catch (_: Exception) {
                InputTargetSnapshot(lastHostPackage, "Application focus snapshot unavailable")
            }
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
    private var insertionId = 0
    private var insertionAttempt = 0
    private var insertionStartedAt = 0L
    private var insertionTrace: InsertionTrace? = null
    private var pendingTargetPackage: String? = null
    private val retryGate = InsertionRetryGate()
    private var insertCheckScheduled = false
    private val insertCheck = Runnable {
        insertCheckScheduled = false
        attemptInsert()
    }
    private var lastWindowDiagnosticsAt = 0L
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
                if (pkg != null && pkg != packageName) requestInsertAttempt()
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        pendingText?.let { text ->
            runCatching { setClipboard(text) }
            BubbleService.instance?.showClipboardFallback()
        }
        insertionTrace?.finish("service_disconnected")
        insertionTrace = null
        main.removeCallbacksAndMessages(null)
        insertCheckScheduled = false
        pendingText = null
        pendingPasteSession = null
        if (instance === this) instance = null
        // Service gone — focus detection is impossible, so let the bubble show always.
        BubbleService.instance?.refreshGating()
    }

    // ---------------- insertion ----------------

    private fun startInsert(text: String, capture: ClipboardPasteCapture, targetPackage: String?, initialFocus: String) {
        // Note: we deliberately do NOT stage the clipboard here. The common insert path
        // writes the text straight into the field (ACTION_SET_TEXT) without touching the
        // clipboard, so a successful dictation no longer clobbers what the user had copied.
        // The clipboard is only used as a fallback (see attemptInsert / the give-up branch).
        insertionTrace?.finish("superseded")
        insertionTrace = InsertionDiagnostics.begin(this, targetPackage, initialFocus)
        val requestTrace = insertionTrace
        val pasteSession = capture.session
        pasteSession?.diagnostics = { message, error ->
            requestTrace?.add(message)
            if (error) requestTrace?.problem = "clipboard_restore_failed"
        }
        pendingText = text
        pendingPasteSession = pasteSession
        pendingTargetPackage = targetPackage
        retryGate.reset()
        lastWindowDiagnosticsAt = 0L
        insertionId++
        insertionAttempt = 0
        insertionStartedAt = SystemClock.uptimeMillis()
        trace("request=$insertionId queued chars=${text.length} clipboardCapture=${capture.reason} target=$targetPackage")
        main.removeCallbacksAndMessages(null)
        insertCheckScheduled = false
        // Backstop retries in case the focus event doesn't arrive.
        for (delay in retryDelays) main.postDelayed({ requestInsertAttempt() }, delay)
        // Give up after the last retry: no field ever focused — fall back to a clipboard copy.
        main.postDelayed({
            val t = pendingText
            if (t != null) {
                trace("request=$insertionId gaveUp attempts=$insertionAttempt elapsedMs=${SystemClock.uptimeMillis() - insertionStartedAt}; copying for manual paste")
                insertionTrace?.finish("clipboard_fallback")
                insertionTrace = null
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

    /** Coalesce event storms; accessibility queries must not generate a retry loop. */
    private fun requestInsertAttempt() {
        if (pendingText == null || insertCheckScheduled) return
        val now = SystemClock.uptimeMillis()
        if (now - insertionStartedAt >= retryDelays.last() + 300) return
        insertCheckScheduled = true
        main.postDelayed(insertCheck, retryGate.delayMs(now))
    }

    /** Try once to insert into the host app's focused editable field. */
    private fun attemptInsert() {
        val text = pendingText ?: return
        val now = SystemClock.uptimeMillis()
        if (now - insertionStartedAt >= retryDelays.last() + 300) return
        retryGate.attempted(now)
        insertionAttempt++
        trace("request=$insertionId attempt=$insertionAttempt elapsedMs=${SystemClock.uptimeMillis() - insertionStartedAt}")
        val node = findHostFocusedEditable() ?: return
        val beforeText = node.text?.toString().orEmpty()
        trace("request=$insertionId target ${nodeDiagnostics(node)}")
        var viaPaste = false
        val ok = try {
            // Prefer a clipboard-free splice at the cursor; fall back to paste only when
            // we can't determine the cursor (e.g. some WebView fields).
            val direct = try {
                insertAtCursor(node, text)
            } catch (e: Exception) {
                insertionTrace?.directFailed("set_text_exception_${e.javaClass.simpleName}")
                false
            }
            val accepted = direct || run { viaPaste = true; pasteViaClipboard(node, text) }
            if (accepted) {
                // Diagnostics must never turn an accepted action into an insertion
                // retry, which could duplicate the user's text.
                runCatching { logInsertionReadback(node, beforeText, text, viaPaste, insertionTrace) }
                    .onFailure {
                        trace("Readback scheduling unavailable type=${it.javaClass.simpleName}")
                        insertionTrace?.finish("accepted_unverified")
                    }
            }
            accepted
        } catch (e: Exception) {
            trace("Insertion exception type=${e.javaClass.simpleName}")
            Log.e(TAG, "insert action failed", e); false
        } finally {
            @Suppress("DEPRECATION") node.recycle()
        }
        if (ok) {
            insertionTrace = null // the readback callback owns this request's trace now
            Log.i(TAG, if (viaPaste) "inserted via temporary clipboard paste"
                       else "inserted at cursor (clipboard untouched)")
            pendingText = null
            pendingPasteSession = null
            main.removeCallbacksAndMessages(null)
            insertCheckScheduled = false
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
            node.textSelectionStart, node.textSelectionEnd, insert) ?: run {
            insertionTrace?.directFailed("unknown_cursor_in_nonempty_field")
            return false
        }
        if (showingHint) trace("Treating placeholder as empty input for direct insertion")
        val setArgs = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, edit.text)
        }
        val accepted = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setArgs)
        trace("request=$insertionId action=SET_TEXT accepted=$accepted")
        if (!accepted) {
            insertionTrace?.directFailed("set_text_action_rejected")
            return false
        }
        val selArgs = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, edit.cursor)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, edit.cursor)
        }
        // Text was accepted already. A cursor-placement failure must not cause a
        // second insertion through paste or a subsequent retry.
        runCatching { node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selArgs) }
            .onFailure { trace("Cursor placement unavailable type=${it.javaClass.simpleName}") }
        return true
    }

    /** Fallback insert: borrow the clipboard for the paste, then restore its original contents. */
    private fun pasteViaClipboard(node: AccessibilityNodeInfo, text: String): Boolean {
        val session = pendingPasteSession ?: run {
            trace("request=$insertionId paste skipped: clipboard snapshot unavailable")
            return false
        }
        return session.paste(text) {
            node.performAction(AccessibilityNodeInfo.ACTION_PASTE).also {
                trace("request=$insertionId action=PASTE accepted=$it")
            }
        }
    }

    /** Focused editable node in the active window, only if it's NOT our own app. */
    private fun findHostFocusedEditable(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: run {
            traceState("lookup", "lookup=no-active-root")
            return null
        }
        if (root.packageName == packageName) {
            traceState("lookup", "lookup=self-window")
            return null
        }
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        traceState("lookup", "rootPackage=${root.packageName} rootChildren=${root.childCount} focused=${focused?.let { nodeDiagnostics(it) } ?: "none"}")
        if (focused != null && focused.isEditable) return focused
        @Suppress("DEPRECATION") focused?.recycle()
        // Restrict focus recovery to the intended app. Do not choose an input in
        // a different app that happens to become active during transcription.
        val target = pendingTargetPackage
        if (target != null && target != root.packageName?.toString()) {
            traceState("tree-policy", "tree scan skipped: active package differs from intended target=$target")
            return null
        }
        val candidate = findEditableInTree(root)
        if (candidate == null) logInteractiveWindows()
        return candidate
    }

    /** Breadth-first search with both node-count and wall-time limits. */
    private fun findEditableInTree(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        val editors = mutableListOf<AccessibilityNodeInfo>()
        val samples = mutableListOf<String>()
        var visited = 0
        var truncated = false
        val deadline = SystemClock.uptimeMillis() + 60
        if (!runCatching { root.refresh() }.getOrDefault(false)) {
            traceState("tree-refresh", "tree scan skipped: root refresh failed")
            return null
        }
        @Suppress("DEPRECATION") queue.add(AccessibilityNodeInfo.obtain(root))
        var result: AccessibilityNodeInfo? = null
        try {
            while (queue.isNotEmpty() && visited < 128 && SystemClock.uptimeMillis() < deadline) {
                val node = queue.removeFirst()
                visited++
                try {
                    if (samples.size < 6) samples.add(nodeDiagnostics(node))
                    if (node.isEditable) {
                        @Suppress("DEPRECATION") editors.add(AccessibilityNodeInfo.obtain(node))
                    }
                    val children = minOf(node.childCount, (128 - visited - queue.size).coerceAtLeast(0))
                    if (children < node.childCount) truncated = true
                    for (i in 0 until children) {
                        if (SystemClock.uptimeMillis() >= deadline) { truncated = true; break }
                        node.getChild(i)?.let { queue.addLast(it) }
                    }
                } finally {
                    @Suppress("DEPRECATION") node.recycle()
                }
            }
            val complete = queue.isEmpty() && !truncated
            val choices = editors.map {
                EditableTarget(it.isFocused, it.isVisibleToUser, it.isEnabled,
                    it.actionList.any { action -> action.id == AccessibilityNodeInfo.ACTION_FOCUS }, it.isPassword)
            }
            val choice = chooseEditableTarget(choices)
            traceState("tree-summary", "tree visited=$visited complete=$complete editors=${editors.size} decision=${choice.reason}")
            editors.take(8).forEachIndexed { index, node -> traceState("editor-$index", "editor[$index] ${nodeDiagnostics(node)}") }
            if (editors.isEmpty()) samples.forEachIndexed { index, sample -> traceState("sample-$index", "tree sample[$index] $sample") }
            val index = choice.index ?: return null
            val selected = editors[index]
            if (choice.needsFocus) {
                // A truncated tree cannot establish that this is the only editor.
                if (!complete) {
                    traceState("focus-recovery", "focus recovery skipped: tree scan incomplete")
                    return null
                }
                val accepted = selected.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                val refreshed = selected.refresh()
                traceState("focus-recovery", "focus recovery accepted=$accepted refreshed=$refreshed focused=${selected.isFocused}")
                if (!accepted || !refreshed || !selected.isFocused) return null
            }
            result = selected
            return selected
        } catch (e: Exception) {
            traceState("tree-error", "tree lookup unavailable type=${e.javaClass.simpleName}")
            return null
        } finally {
            queue.forEach { @Suppress("DEPRECATION") it.recycle() }
            editors.filter { it !== result }.forEach { @Suppress("DEPRECATION") it.recycle() }
        }
    }

    /** Structural diagnostics only: never log field contents or the dictated text. */
    private fun nodeDiagnostics(node: AccessibilityNodeInfo): String =
        "package=${node.packageName} class=${node.className} editable=${node.isEditable} " +
            "focused=${node.isFocused} visible=${node.isVisibleToUser} enabled=${node.isEnabled} " +
            "password=${node.isPassword} hint=${Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && node.isShowingHintText} " +
            "sensitive=${Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && node.isAccessibilityDataSensitive} " +
            "chars=${node.text?.length ?: 0} selection=${node.textSelectionStart}:${node.textSelectionEnd} " +
            "actions=${node.actionList.map { it.id }}"

    private fun logInteractiveWindows() {
        if (insertionTrace == null) return
        val now = SystemClock.uptimeMillis()
        if (lastWindowDiagnosticsAt != 0L && now - lastWindowDiagnosticsAt < 500) return
        lastWindowDiagnosticsAt = now
        try {
            for (window in windows) {
                val root = window.root
                val focused = root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                traceState("window-${window.id}", "window type=${window.type} active=${window.isActive} focused=${window.isFocused} rootPackage=${root?.packageName} input=${focused?.let { nodeDiagnostics(it) } ?: "none"}")
                @Suppress("DEPRECATION") focused?.recycle()
            }
        } catch (e: Exception) {
            trace("request=$insertionId window diagnostics unavailable: ${e.javaClass.simpleName}")
        }
    }

    private fun trace(message: String) { insertionTrace?.add(message) }
    private fun traceState(key: String, message: String) { insertionTrace?.state(key, message) }

    private fun logInsertionReadback(node: AccessibilityNodeInfo, beforeText: String, insert: String,
                                     viaPaste: Boolean, requestTrace: InsertionTrace?) {
        requestTrace ?: return
        @Suppress("DEPRECATION") val probe = AccessibilityNodeInfo.obtain(node)
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                val refreshed = probe.refresh()
                val afterText = if (refreshed && !probe.isPassword) probe.text?.toString() else null
                val changed = afterText != null && afterText != beforeText
                val containsInserted = afterText?.contains(insert) == true
                requestTrace.add("readbackAfterMs=500 method=${if (viaPaste) "PASTE" else "SET_TEXT"} refreshed=$refreshed " +
                    "focused=${probe.isFocused} chars=${afterText?.length} changed=$changed containsInserted=$containsInserted")
                if (!viaPaste && (!changed || !containsInserted)) requestTrace.directFailed("set_text_accepted_but_not_observed")
                requestTrace.finish(when {
                    !changed || !containsInserted -> "accepted_not_observed"
                    viaPaste -> "direct_entry_failed_paste_succeeded"
                    else -> null
                })
            } catch (e: Exception) {
                requestTrace.add("Readback unavailable type=${e.javaClass.simpleName}")
                requestTrace.finish("accepted_unverified")
            } finally {
                @Suppress("DEPRECATION") probe.recycle()
            }
        }, 500)
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
