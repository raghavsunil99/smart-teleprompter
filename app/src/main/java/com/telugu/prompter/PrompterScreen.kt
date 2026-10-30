package com.telugu.prompter

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material.icons.filled.TextDecrease
import androidx.compose.material.icons.filled.TextIncrease
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.delay
import kotlin.math.abs
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private enum class Mode { VOICE, STEADY }

private val SpokenColor = Color(0xFF6E6E6E)
private val UpcomingColor = Color.White
private val CurrentColor = Color(0xFFFFC94D)
private val CurrentWordBackground = Color(0x59FFC94D)
private const val FADE_WORDS = 4
private val UpcomingLineColor = Color(0xFFC8C8C8)
private val HeadingColor = Color(0xFF64D2FF)
private val HEADING_LINE = Regex("(?m)^[ \\t]*#.*$")
private val ReadingBand = Color(0x66B3261E)
private val BandMarker = Color(0xFFE5534B)
private val ToggleBlue = Color(0xFF0A84FF)
private val BarColor = Color(0xFF161618)
private val TextSidePadding = 24.dp
private const val PEEK_MILLIS = 4000L

@Composable
fun PrompterScreen(
    script: Script,
    settings: PrompterSettings,
    onSettingsChange: (PrompterSettings) -> Unit,
    remote: RemoteHub,
    onRemote: () -> Unit,
    savedPosition: Int,
    onSavePosition: (Int) -> Unit,
    /** Set this script's language (a ScriptLanguage code). */
    onLanguageChange: (String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    val densityObj = LocalDensity.current
    val density = densityObj.density

    val language = script.languageFor(ScriptLanguage.fromCode(settings.defaultLanguage) ?: ScriptLanguage.DEFAULT)
    val matcher = remember(script.body, language) { ScriptMatcher(script.body, language) }
    val cruise = remember(script.body) { CruiseFollower() }
    // Hand-off from the speech side to the frame loop: a re-sync jumps instantly, a new heard
    // position is fed to cruise control (the loop knows the pixel y of each word).
    val sync = remember(script.body) { FollowSync() }
    // Fraction of a pixel the text is shifted, so the scroll is never limited to whole pixels.
    val subPixel = remember { mutableFloatStateOf(0f) }
    var position by remember { mutableIntStateOf(0) }
    var mode by remember { mutableStateOf(Mode.VOICE) }
    var running by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf(SpeechFollower.Status.IDLE) }
    var lastHeard by remember { mutableStateOf("") }
    var heardOnLaptop by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var peekUntil by remember { mutableLongStateOf(0L) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var countdownLeft by remember { mutableIntStateOf(0) }
    var resumed by remember { mutableStateOf(false) }
    val currentSettings by rememberUpdatedState(settings)
    val lineHeightPx = with(densityObj) { (settings.fontSizeSp * 1.5f).sp.toPx() }

    /** Words heard by either mic (phone or laptop) move the script the same way. */
    fun onHeardText(h: String, fromLaptop: Boolean) {
        val isNew = h != lastHeard
        lastHeard = h
        heardOnLaptop = fromLaptop
        if (matcher.onHeard(h)) {
            position = matcher.position
            sync.heardAt = System.nanoTime()
        } else if (isNew) {
            sync.speechAt = System.nanoTime() // talking, even if these words don't match
        }
    }

    val speech = remember(language) {
        SpeechFollower(
            context = context,
            language = language.locale,
            onHypothesis = { h -> onHeardText(h, fromLaptop = false) },
            onStatus = { status = it },
        )
    }
    speech.muteBeeps = settings.muteBeeps
    speech.continuous = settings.continuousListening
    KeyBus.useVolumeKeys = settings.useVolumeKeys

    fun resync(idx: Int) {
        matcher.reset(idx)
        sync.jump = true
        position = matcher.position
    }

    /** Word index being read: voice position, or the first word on the top reading line in Steady mode. */
    fun currentWordIndex(): Int {
        val l = layout
        if (mode == Mode.VOICE || l == null || matcher.words.isEmpty()) return position
        val line = l.getLineForVerticalPosition(scroll.value.toFloat())
        return matcher.words.indexOfFirst { it.start >= l.getLineStart(line) }.let { if (it < 0) matcher.words.size else it }
    }

    // Pick up where this script was left last time.
    LaunchedEffect(Unit) {
        if (savedPosition in 1 until matcher.words.size) {
            resync(savedPosition)
            resumed = true
        }
    }

    // Screen awake + full brightness while prompting; restore everything on leave.
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        val window = activity?.window
        val oldBrightness = window?.attributes?.screenBrightness
        onDispose {
            onSavePosition(currentWordIndex())
            speech.stop()
            remote.status = Json.obj("screen" to "list")
            view.keepScreenOn = false
            window?.let { w ->
                w.attributes = w.attributes.apply {
                    screenBrightness = oldBrightness ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                }
                WindowCompat.getInsetsController(w, view).show(WindowInsetsCompat.Type.systemBars())
            }
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // Brightness: by default leave the phone's own setting alone.
    LaunchedEffect(settings.brightnessMode, settings.brightnessLevel) {
        val w = activity?.window ?: return@LaunchedEffect
        w.attributes = w.attributes.apply {
            screenBrightness = when (settings.brightnessMode) {
                PrompterSettings.BRIGHTNESS_MAX -> WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
                PrompterSettings.BRIGHTNESS_CUSTOM -> settings.brightnessLevel.coerceIn(0.05f, 1f)
                else -> WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
        }
    }

    LaunchedEffect(settings.orientation) {
        activity?.requestedOrientation = when (settings.orientation) {
            PrompterSettings.ORIENTATION_LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            PrompterSettings.ORIENTATION_PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // The phone listens only when the laptop isn't: the laptop mic (closer to the speaker) wins,
    // and the phone takes over again a few seconds after the laptop stops sending.
    val phoneMicWanted = running && mode == Mode.VOICE && !remote.laptopMicActive
    LaunchedEffect(phoneMicWanted) {
        if (phoneMicWanted) speech.start() else speech.stop()
    }

    /** Starts after the 3-2-1 countdown (if one is set). */
    fun startWithCountdown() {
        resumed = false
        val n = currentSettings.countdownSeconds
        if (n > 0) countdownLeft = n else running = true
    }

    // Countdown: tick once a second, then start. Pausing sets it to 0, which cancels it.
    LaunchedEffect(countdownLeft) {
        if (countdownLeft <= 0) return@LaunchedEffect
        delay(1000)
        countdownLeft -= 1
        if (countdownLeft == 0) running = true
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startWithCountdown() else status = SpeechFollower.Status.ERROR_PERMISSION
    }

    fun play() {
        showSettings = false
        if (running || countdownLeft > 0) return
        val needsPhoneMic = mode == Mode.VOICE && !remote.laptopMicActive
        val has = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!needsPhoneMic || has) startWithCountdown() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    fun pause() {
        val wasActive = running || countdownLeft > 0
        running = false
        countdownLeft = 0
        if (wasActive) onSavePosition(currentWordIndex())
    }

    val active = running || countdownLeft > 0

    fun switchMode(to: Mode) {
        if (to == mode) return
        val wasRunning = running || countdownLeft > 0
        pause()
        if (to == Mode.VOICE) {
            // Continue voice-following from whatever line Steady mode scrolled to.
            layout?.let { l ->
                val line = l.getLineForVerticalPosition(scroll.value.toFloat())
                resync(matcher.words.indexOfFirst { it.start >= l.getLineStart(line) }.coerceAtLeast(0))
            }
        }
        mode = to
        if (wasRunning) play()
    }

    /** Move one line up (-1) or down (+1). */
    fun nudge(dir: Int) {
        val l = layout ?: return
        if (mode == Mode.VOICE && matcher.words.isNotEmpty()) {
            val cur = l.getLineForOffset(matcher.words[position.coerceAtMost(matcher.words.size - 1)].start)
            val target = (cur + dir).coerceIn(0, l.lineCount - 1)
            val idx = matcher.words.indexOfFirst { it.start >= l.getLineStart(target) }
            resync(if (idx < 0) matcher.words.size else idx)
        } else {
            scope.launch { scroll.animateScrollBy(dir * lineHeightPx) }
        }
    }

    fun toTop() {
        resync(0)
        resumed = false
        scope.launch { scroll.animateScrollTo(0) }
    }

    fun jumpToSection(i: Int) {
        val sec = matcher.sections.getOrNull(i) ?: return
        resync(sec.wordIndex)
        if (mode == Mode.STEADY) {
            val l = layout ?: return
            scope.launch { scroll.animateScrollTo(l.getLineTop(l.getLineForOffset(sec.charStart)).roundToInt()) }
        }
    }

    fun currentSection(): Int = matcher.sectionAt(currentWordIndex())

    /** Continue from the word at (or just after) a character offset, e.g. a line clicked on the laptop. */
    fun seekTo(offset: Int) {
        val idx = matcher.words.indexOfFirst { it.end > offset }.let { if (it < 0) matcher.words.size else it }
        resync(idx)
        resumed = false
        if (mode == Mode.STEADY) {
            val l = layout ?: return
            val line = l.getLineForOffset(offset.coerceIn(0, script.body.length))
            scope.launch { scroll.animateScrollTo(l.getLineTop(line).roundToInt()) }
        }
    }

    val isTeleprompter = settings.flipVertical && settings.orientation == PrompterSettings.ORIENTATION_LANDSCAPE
    fun toggleTeleprompter() {
        onSettingsChange(
            if (isTeleprompter) settings.copy(flipVertical = false, orientation = PrompterSettings.ORIENTATION_AUTO)
            else settings.copy(flipVertical = true, mirror = false, orientation = PrompterSettings.ORIENTATION_LANDSCAPE),
        )
    }

    /** One handler for laptop-remote commands and Bluetooth/keyboard keys. */
    fun handle(cmd: String, value: String?) {
        val s = currentSettings
        when (cmd) {
            "play" -> play()
            "pause" -> pause()
            "toggle" -> if (running || countdownLeft > 0) pause() else play()
            "mode" -> switchMode(if (value == "steady") Mode.STEADY else Mode.VOICE)
            "faster" -> onSettingsChange(s.copy(manualSpeed = (s.manualSpeed + 10f).coerceAtMost(150f)))
            "slower" -> onSettingsChange(s.copy(manualSpeed = (s.manualSpeed - 10f).coerceAtLeast(10f)))
            "bigger" -> onSettingsChange(s.copy(fontSizeSp = (s.fontSizeSp + 2).coerceAtMost(96f)))
            "smaller" -> onSettingsChange(s.copy(fontSizeSp = (s.fontSizeSp - 2).coerceAtLeast(18f)))
            "up" -> nudge(-1)
            "down" -> nudge(1)
            "top" -> toTop()
            "flip" -> onSettingsChange(s.copy(flipVertical = !s.flipVertical))
            "mirror" -> onSettingsChange(s.copy(mirror = !s.mirror))
            "language" -> ScriptLanguage.fromCode(value)?.let { onLanguageChange(it.code) }
            "section" -> value?.toIntOrNull()?.let { jumpToSection(it) }
            "seek" -> value?.toIntOrNull()?.let { offset -> seekTo(offset) }
            "nextSection" -> jumpToSection((currentSection() + 1).coerceAtMost(matcher.sections.size - 1))
            "prevSection" -> {
                // Like a music player: back to this section's start, or the previous one if already there.
                val cur = currentSection()
                val atStart = cur >= 0 && currentWordIndex() <= matcher.sections[cur].wordIndex
                jumpToSection(if (atStart || cur < 0) (cur - 1).coerceAtLeast(0) else cur)
            }
        }
    }

    // Commands from the laptop remote.
    LaunchedEffect(remote) {
        remote.commands.collect { c ->
            if (c is RemoteCommand.Heard) {
                if (running && mode == Mode.VOICE) onHeardText(c.text, fromLaptop = true)
                return@collect
            }
            if (c is RemoteCommand.Prompter) handle(c.cmd, c.value)
        }
    }

    // Bluetooth remotes, pedals and keyboards (routed from the Activity).
    DisposableEffect(Unit) {
        KeyBus.handler = { cmd -> handle(cmd, null); true }
        onDispose { KeyBus.handler = null }
    }

    // Publish live state for the laptop page.
    LaunchedEffect(remote, script) {
        while (true) {
            val l = layout
            val s = currentSettings
            var line = ""
            var next = ""
            var progress = 0f
            if (l != null && l.lineCount > 0) {
                val li = if (mode == Mode.VOICE && matcher.words.isNotEmpty()) {
                    l.getLineForOffset(matcher.words[position.coerceAtMost(matcher.words.size - 1)].start)
                } else {
                    l.getLineForVerticalPosition(scroll.value.toFloat())
                }
                line = script.body.substring(l.getLineStart(li), l.getLineEnd(li)).trim()
                if (li + 1 < l.lineCount) next = script.body.substring(l.getLineStart(li + 1), l.getLineEnd(li + 1)).trim()
                progress = (li + 1f) / l.lineCount
            }
            remote.status = Json.obj(
                "screen" to "prompter",
                "title" to script.title,
                "scriptId" to script.id,
                "rev" to script.body.hashCode(),
                // Character offset of the word being read, for the laptop's live script view.
                "offset" to (matcher.words.getOrNull(currentWordIndex())?.start ?: script.body.length),
                "running" to running,
                "countdown" to countdownLeft,
                "sections" to matcher.sections.map { it.title },
                "section" to currentSection(),
                "mode" to if (mode == Mode.VOICE) "voice" else "steady",
                "hearing" to (phoneMicWanted && status == SpeechFollower.Status.HEARING),
                "laptopMic" to remote.laptopMicActive,
                "heard" to if (mode == Mode.VOICE && running) lastHeard else "",
                "line" to line,
                "next" to next,
                "progress" to progress,
                "fontSize" to s.fontSizeSp,
                "speed" to s.manualSpeed,
                "flip" to s.flipVertical,
                "mirror" to s.mirror,
                "language" to language.code,
                "locale" to language.locale,
                "languageLabel" to language.label,
            )
            delay(100) // the laptop page shows the position at most ~0.2 s behind
        }
    }

    // Stop if an error ended the recognizer session.
    LaunchedEffect(status) {
        if (status == SpeechFollower.Status.ERROR_LANGUAGE ||
            status == SpeechFollower.Status.ERROR_PERMISSION ||
            status == SpeechFollower.Status.ERROR_NO_RECOGNIZER
        ) running = false
    }

    // Clean screen: while running, hide our controls unless the user tapped to peek.
    val now by produceNow(peekUntil)
    val controlsVisible = !running || now < peekUntil
    // Android's status/navigation bars stay hidden on the prompter (swipe from an edge to see them),
    // so script text never runs underneath them. Android re-shows them after rotation, dialogs and
    // permission prompts, so hide them again whenever the screen state changes.
    LaunchedEffect(running, countdownLeft > 0, controlsVisible, showSettings, settings.orientation) {
        val w = activity?.window ?: return@LaunchedEffect
        val c = WindowCompat.getInsetsController(w, view)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.hide(WindowInsetsCompat.Type.systemBars())
    }


    // Y position (px, relative to the text top) of each word, interpolated across its line,
    // so the scroll moves steadily as you read along a line instead of jumping line by line.
    val wordY = remember(layout) {
        val l = layout ?: return@remember FloatArray(0)
        val n = matcher.words.size
        FloatArray(n + 1) { i ->
            if (i == n) {
                l.getLineBottom(l.lineCount - 1)
            } else {
                val w = matcher.words[i]
                val line = l.getLineForOffset(w.start)
                val left = l.getLineLeft(line)
                val right = l.getLineRight(line)
                val x = l.getHorizontalPosition(w.start, true)
                val frac = if (right > left) ((x - left) / (right - left)).coerceIn(0f, 1f) else 0f
                l.getLineTop(line) + frac * (l.getLineBottom(line) - l.getLineTop(line))
            }
        }
    }

    /** Moves the scroll to [y] px: whole pixels via the scroll, the fraction via [subPixel]. */
    suspend fun scrollToFloat(y: Float) {
        val whole = kotlin.math.floor(y).toInt()
        val delta = whole - scroll.value
        if (delta != 0) scroll.scrollBy(delta.toFloat())
        // If the scroll was clamped (start/end of the text), don't shift the fraction either.
        subPixel.floatValue = if (scroll.value == whole) y - whole else 0f
    }

    // Word positions change when the text is re-laid out (font size, rotation): re-sync then.
    // (Not on every highlight change: the text is redrawn for each word, but nothing moves.)
    LaunchedEffect(settings.fontSizeSp, layout?.size) { sync.jump = true }

    // Voice mode: cruise control — scroll at the speaker's pace, every frame, in sub-pixel steps.
    // The loop keeps running across redraws and reads the latest word positions each frame.
    val isListening by rememberUpdatedState(running && mode == Mode.VOICE)
    val currentWordY by rememberUpdatedState(wordY)
    LaunchedEffect(mode) {
        if (mode != Mode.VOICE) return@LaunchedEffect
        sync.jump = true
        var last = withFrameNanos { it }
        var lastY = Float.NaN
        while (true) {
            val t = withFrameNanos { it }
            val dt = ((t - last) / 1e9f).coerceAtMost(0.1f)
            last = t
            val wordY = currentWordY
            if (wordY.isEmpty()) continue
            val p = position.coerceIn(0, wordY.size - 1)
            if (sync.jump) {
                cruise.jumpTo(wordY[p])
                sync.jump = false
                sync.lastPos = p
            } else if (p != sync.lastPos) {
                cruise.confirm(wordY[p], if (sync.heardAt != 0L) sync.heardAt else System.nanoTime())
                sync.lastPos = p
            }
            if (sync.speechAt != 0L) {
                cruise.speechHeard(sync.speechAt)
                sync.speechAt = 0L
            }
            cruise.maxLeadPx = lineHeightPx * 1.2f
            val y = cruise.step(System.nanoTime(), dt, isListening)
            // Only drive the scroll when it moves, so a paused prompter can be finger-scrolled.
            if (y == lastY) continue
            lastY = y
            scrollToFloat(y)
        }
    }

    // Steady mode: constant-speed scroll, also in sub-pixel steps.
    val speed by rememberUpdatedState(settings.manualSpeed)
    LaunchedEffect(running, mode) {
        if (mode != Mode.STEADY) return@LaunchedEffect
        subPixel.floatValue = 0f
        if (!running) return@LaunchedEffect
        var y = scroll.value.toFloat()
        var last = withFrameNanos { it }
        while (true) {
            val t = withFrameNanos { it }
            val dt = (t - last) / 1e9f
            last = t
            if (abs(scroll.value - kotlin.math.floor(y)) > 1f) y = scroll.value.toFloat() // finger-scrolled
            y += speed * dt * density
            scrollToFloat(y)
        }
    }

    // Gliding highlight: a box behind the current word that slides to the next word instead of
    // hopping. Moving to a new line it jumps straight there rather than sweeping back across.
    val highlightTarget: Rect? = layout?.takeIf {
        mode == Mode.VOICE && settings.highlightStyle == PrompterSettings.HIGHLIGHT_WORD && position < matcher.words.size
    }?.let { l ->
        val w = matcher.words[position]
        l.getPathForRange(w.start, w.end).getBounds()
    }
    val highlightBox = remember { Animatable(Rect.Zero, Rect.VectorConverter) }
    LaunchedEffect(highlightTarget) {
        val target = highlightTarget ?: return@LaunchedEffect
        val current = highlightBox.value
        if (current == Rect.Zero || abs(target.top - current.top) > lineHeightPx * 0.5f) {
            highlightBox.snapTo(target)
        } else {
            highlightBox.animateTo(target, spring(dampingRatio = 1f, stiffness = 380f))
        }
    }

    // Character range of the line being read (for the line-highlight style).
    val readingLine: IntRange? = layout?.takeIf { matcher.words.isNotEmpty() }?.let { l ->
        val li = l.getLineForOffset(matcher.words[position.coerceAtMost(matcher.words.size - 1)].start)
        l.getLineStart(li) until l.getLineEnd(li)
    }

    val text = remember(script.body, position, mode, settings.highlightStyle, readingLine) {
        buildAnnotatedString {
            append(script.body)
            if (mode == Mode.VOICE && settings.highlightStyle == PrompterSettings.HIGHLIGHT_LINE && readingLine != null) {
                // Line focus: the reading line in full white, read text grey, the rest slightly dimmed.
                addStyle(SpanStyle(color = UpcomingLineColor), 0, script.body.length)
                addStyle(SpanStyle(color = SpokenColor), 0, readingLine.first)
                addStyle(SpanStyle(color = UpcomingColor), readingLine.first, readingLine.last + 1)
            } else if (mode == Mode.VOICE && settings.highlightStyle == PrompterSettings.HIGHLIGHT_WORD && matcher.words.isNotEmpty()) {
                // Only colours change here — never size or weight — so lines never re-wrap while reading.
                // The current word is marked by the gliding box drawn behind the text, not a colour swap.
                addStyle(SpanStyle(color = UpcomingColor), 0, script.body.length)
                val fadeStart = (position - FADE_WORDS).coerceAtLeast(0)
                val spokenEnd = if (fadeStart > 0) matcher.words[fadeStart - 1].end else 0
                addStyle(SpanStyle(color = SpokenColor), 0, spokenEnd)
                // The last few words read fade gradually from white to grey.
                for (i in fadeStart until position) {
                    val t = (position - i).toFloat() / (FADE_WORDS + 1)
                    val w = matcher.words[i]
                    addStyle(SpanStyle(color = lerp(UpcomingColor, SpokenColor, t)), w.start, w.end)
                }
            } else {
                addStyle(SpanStyle(color = UpcomingColor), 0, script.body.length)
            }
            // "# Section" headings: always styled this way, so they never change size while reading.
            HEADING_LINE.findAll(script.body).forEach {
                addStyle(SpanStyle(color = HeadingColor, fontWeight = FontWeight.Bold), it.range.first, it.range.last + 1)
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val screenHeight = maxHeight
        val eyeLineDp = screenHeight * settings.eyeLine
        val eyeLinePx = eyeLineDp.value * density
        val sidePx = TextSidePadding.value * density
        val lineHeightDp = (lineHeightPx / density).dp

        // Everything that should appear in the teleprompter glass is flipped together.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = if (settings.mirror) -1f else 1f
                    scaleY = if (settings.flipVertical) -1f else 1f
                }
                .pointerInput(matcher, eyeLinePx) {
                    detectTapGestures(
                        // Tap: show controls for a few seconds.
                        onTap = { peekUntil = System.currentTimeMillis() + PEEK_MILLIS },
                        // Long-press a word: continue from there.
                        onLongPress = { pos ->
                            val l = layout ?: return@detectTapGestures
                            val local = Offset(pos.x - sidePx, pos.y + scroll.value - eyeLinePx)
                            val off = l.getOffsetForPosition(local)
                            resync(matcher.words.indexOfLast { it.start <= off }.coerceAtLeast(0))
                        },
                    )
                },
        ) {
            // Reading band. A line starts at the eye-line and drifts up by one line height as it
            // is read (see wordY), so the band spans that drift, with soft top and bottom edges.
            Box(
                Modifier
                    .fillMaxWidth()
                    .offset(y = eyeLineDp - lineHeightDp * 0.4f)
                    .height(lineHeightDp * 1.5f)
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent, 0.18f to ReadingBand, 0.82f to ReadingBand, 1f to Color.Transparent,
                        ),
                    ),
            )
            // Thin marker at the left edge of the band
            Box(
                Modifier
                    .offset(y = eyeLineDp - lineHeightDp * 0.1f)
                    .padding(start = 4.dp)
                    .size(width = 4.dp, height = lineHeightDp * 0.9f)
                    .background(BandMarker, RoundedCornerShape(2.dp)),
            )

            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(scroll, enabled = !(running && mode == Mode.VOICE))
                    .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
                    .padding(horizontal = TextSidePadding),
            ) {
                Box(Modifier.height(eyeLineDp))
                Text(
                    text = text,
                    fontSize = settings.fontSizeSp.sp,
                    lineHeight = (settings.fontSizeSp * 1.5f).sp,
                    onTextLayout = { layout = it },
                    modifier = Modifier
                        .graphicsLayer { translationY = -subPixel.floatValue }
                        .drawBehind {
                            if (highlightTarget != null) {
                                val r = highlightBox.value
                                val padX = 6.dp.toPx()
                                drawRoundRect(
                                    color = CurrentWordBackground,
                                    topLeft = Offset(r.left - padX, r.top + r.height * 0.1f),
                                    size = Size(r.width + 2 * padX, r.height * 0.8f),
                                    cornerRadius = CornerRadius(10.dp.toPx()),
                                )
                            }
                        },
                )
                Box(Modifier.height(screenHeight))
            }

            // 3-2-1 countdown, visible in the glass
            if (countdownLeft > 0) {
                Text(
                    countdownLeft.toString(),
                    color = CurrentColor,
                    fontSize = 120.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .background(Color(0xCC000000), CircleShape)
                        .padding(horizontal = 48.dp, vertical = 8.dp),
                )
            }

            // Small listening dot, visible in the glass while prompting
            if (mode == Mode.VOICE && running) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
                        .size(10.dp)
                        .background(
                            when {
                                remote.laptopMicActive -> ToggleBlue
                                status == SpeechFollower.Status.HEARING -> CurrentColor
                                else -> Color(0xFF444444)
                            },
                            CircleShape,
                        ),
                )
            }
        }

        if (controlsVisible) {
            val wide = maxWidth >= 600.dp
            val statusLine = when {
                countdownLeft > 0 -> "Starting in $countdownLeft…"
                resumed && !running -> "Resumed where you left off · Start to begin again"
                else -> statusText(mode, running, status, remote.laptopMicActive, language)
            }
            val heardLine = if (mode == Mode.VOICE && running && lastHeard.isNotBlank()) {
                (if (heardOnLaptop) "💻 " else "🎙 ") + lastHeard
            } else ""

            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(0f to Color.Transparent, 1f to BarColor))
                    .padding(top = 16.dp)
                    .background(BarColor)
                    .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                if (showSettings) {
                    SettingsPanel(
                        settings, onSettingsChange, language, onLanguageChange,
                        Modifier.heightIn(max = screenHeight * 0.55f),
                    )
                }

                val modeAndStatus: @Composable () -> Unit = {
                    ModeToggle(mode, onChange = { switchMode(it) })
                }
                val buttonsLeft: @Composable () -> Unit = {
                    BarButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = ToggleBlue) { pause(); onBack() }
                    BarButton(Icons.Default.VerticalAlignTop, "Start") { toTop() }
                }
                val playButton: @Composable () -> Unit = {
                    FilledIconButton(
                        onClick = { if (active) pause() else play() },
                        modifier = Modifier.padding(horizontal = 6.dp).size(48.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = ToggleBlue, contentColor = Color.White),
                    ) { Icon(if (active) Icons.Default.Pause else Icons.Default.PlayArrow, if (active) "Pause" else "Play") }
                }
                val buttonsRight: @Composable () -> Unit = {
                    BarButton(Icons.Default.TextDecrease, "Smaller") {
                        onSettingsChange(settings.copy(fontSizeSp = (settings.fontSizeSp - 2).coerceAtLeast(18f)))
                    }
                    BarButton(Icons.Default.TextIncrease, "Bigger") {
                        onSettingsChange(settings.copy(fontSizeSp = (settings.fontSizeSp + 2).coerceAtMost(96f)))
                    }
                    BarButton(Icons.Default.Flip, "Prompter", tint = if (isTeleprompter) ToggleBlue else Color.White, rotate = 90f) {
                        toggleTeleprompter()
                    }
                    BarButton(Icons.Default.SettingsRemote, "Remote", tint = if (remote.isOn) ToggleBlue else Color.White, onClick = onRemote)
                    BarButton(Icons.Default.Settings, "Settings", tint = if (showSettings) ToggleBlue else Color.White) {
                        showSettings = !showSettings
                    }
                }

                // Status (and what the mic hears) in one small line above the buttons.
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(statusLine, color = Color(0xFFB0B0B0), fontSize = 12.sp, maxLines = 1)
                    Text(
                        heardLine, color = Color.Gray, fontSize = 12.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End,
                        modifier = Modifier.weight(1f).padding(start = 12.dp),
                    )
                }
                if (wide) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        buttonsLeft()
                        Box(Modifier.padding(horizontal = 6.dp)) { modeAndStatus() }
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { playButton() }
                        buttonsRight()
                    }
                } else {
                    // Portrait: mode + play on top, the other buttons in one evenly spaced row.
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        BarButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = ToggleBlue) { pause(); onBack() }
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { modeAndStatus() }
                        playButton()
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        BarButton(Icons.Default.VerticalAlignTop, "Start") { toTop() }
                        buttonsRight()
                    }
                }
            }
        }
    }
}

/** Small icon button with a label underneath. */
@Composable
private fun BarButton(
    icon: ImageVector,
    label: String,
    tint: Color = Color.White,
    rotate: Float = 0f,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp).rotate(rotate))
        Text(label, color = Color(0xFFCCCCCC), fontSize = 10.sp, maxLines = 1)
    }
}

@Composable
private fun ModeToggle(mode: Mode, onChange: (Mode) -> Unit) {
    Row(
        Modifier
            .background(Color(0xCC2C2C2E), RoundedCornerShape(50))
            .padding(3.dp),
    ) {
        listOf(Mode.STEADY to "Steady", Mode.VOICE to "Voice Follow").forEach { (m, label) ->
            val selected = m == mode
            Text(
                label,
                color = Color.White,
                fontSize = 13.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (selected) ToggleBlue else Color.Transparent)
                    .clickable { onChange(m) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

/** Current time that re-ticks when a peek window ends, so the controls hide again. */
@Composable
private fun produceNow(peekUntil: Long) = produceState(System.currentTimeMillis(), peekUntil) {
    value = System.currentTimeMillis()
    val remaining = peekUntil - value
    if (remaining > 0) {
        delay(remaining + 50)
        value = System.currentTimeMillis()
    }
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

@Composable
private fun SettingsPanel(
    s: PrompterSettings,
    onChange: (PrompterSettings) -> Unit,
    language: ScriptLanguage,
    onLanguageChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp)) {
        SettingRow("Flip upside-down (teleprompter glass)") {
            Switch(checked = s.flipVertical, onCheckedChange = { onChange(s.copy(flipVertical = it)) })
        }
        SettingRow("Mirror left-right") {
            Switch(checked = s.mirror, onCheckedChange = { onChange(s.copy(mirror = it)) })
        }
        Text("Orientation", color = Color.White, fontSize = 14.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                PrompterSettings.ORIENTATION_AUTO to "Auto",
                PrompterSettings.ORIENTATION_LANDSCAPE to "Landscape",
                PrompterSettings.ORIENTATION_PORTRAIT to "Portrait",
            ).forEach { (value, label) ->
                FilterChip(selected = s.orientation == value, onClick = { onChange(s.copy(orientation = value)) }, label = { Text(label) })
            }
        }
        Text("Language of this script", color = Color.White, fontSize = 14.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ScriptLanguage.entries.forEach { l ->
                FilterChip(selected = language == l, onClick = { onLanguageChange(l.code) }, label = { Text(l.label) })
            }
        }
        Text("Default for scripts typed in English letters", color = Color.White, fontSize = 14.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ScriptLanguage.entries.forEach { l ->
                FilterChip(
                    selected = s.defaultLanguage == l.code,
                    onClick = { onChange(s.copy(defaultLanguage = l.code)) },
                    label = { Text(l.label) },
                )
            }
        }
        Text("Highlight", color = Color.White, fontSize = 14.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                PrompterSettings.HIGHLIGHT_WORD to "Gliding word",
                PrompterSettings.HIGHLIGHT_LINE to "Whole line",
                PrompterSettings.HIGHLIGHT_OFF to "Off",
            ).forEach { (v, label) ->
                FilterChip(selected = s.highlightStyle == v, onClick = { onChange(s.copy(highlightStyle = v)) }, label = { Text(label) })
            }
        }
        Text("Screen brightness", color = Color.White, fontSize = 14.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                PrompterSettings.BRIGHTNESS_PHONE to "Phone's",
                PrompterSettings.BRIGHTNESS_MAX to "Maximum",
                PrompterSettings.BRIGHTNESS_CUSTOM to "Custom",
            ).forEach { (v, label) ->
                FilterChip(selected = s.brightnessMode == v, onClick = { onChange(s.copy(brightnessMode = v)) }, label = { Text(label) })
            }
        }
        if (s.brightnessMode == PrompterSettings.BRIGHTNESS_CUSTOM) {
            Slider(value = s.brightnessLevel, onValueChange = { onChange(s.copy(brightnessLevel = it)) }, valueRange = 0.05f..1f)
        }
        Text("Countdown before start", color = Color.White, fontSize = 14.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0 to "Off", 3 to "3 s", 5 to "5 s").forEach { (n, label) ->
                FilterChip(selected = s.countdownSeconds == n, onClick = { onChange(s.copy(countdownSeconds = n)) }, label = { Text(label) })
            }
        }
        SettingRow("Mute beeps") {
            Switch(checked = s.muteBeeps, onCheckedChange = { onChange(s.copy(muteBeeps = it)) })
        }
        SettingRow("Volume buttons as remote (shutter remotes)") {
            Switch(checked = s.useVolumeKeys, onCheckedChange = { onChange(s.copy(useVolumeKeys = it)) })
        }
        SettingRow("Continuous listening (Android 13+; turn off if following gets worse)") {
            Switch(checked = s.continuousListening, onCheckedChange = { onChange(s.copy(continuousListening = it)) })
        }
        Text("Reading band position: ${(s.eyeLine * 100).toInt()}%", color = Color.White, fontSize = 13.sp)
        Slider(value = s.eyeLine, onValueChange = { onChange(s.copy(eyeLine = it)) }, valueRange = 0.1f..0.6f)
        Text("Steady speed: ${s.manualSpeed.toInt()}", color = Color.White, fontSize = 13.sp)
        Slider(value = s.manualSpeed, onValueChange = { onChange(s.copy(manualSpeed = it)) }, valueRange = 10f..150f)
        Text(
            "Tip: tap = show controls, long-press a word = continue from there. " +
                "Start a line with # to mark a section (jump between sections from the laptop).",
            color = Color.Gray, fontSize = 12.sp,
        )
    }
}

@Composable
private fun SettingRow(label: String, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
        content()
    }
}

private fun statusText(
    mode: Mode,
    running: Boolean,
    status: SpeechFollower.Status,
    laptopMic: Boolean,
    language: ScriptLanguage,
): String = when {
    mode == Mode.STEADY -> if (running) "Steady scroll" else "Steady · paused"
    laptopMic -> if (running) "💻 Laptop mic · ${language.label}" else "💻 Laptop mic ready · ${language.label} · tap play"
    status == SpeechFollower.Status.ERROR_LANGUAGE ->
        "${language.label} speech recognition isn't available on this phone (Settings → Google → voice languages)"
    status == SpeechFollower.Status.ERROR_NETWORK -> "Network issue, retrying…"
    status == SpeechFollower.Status.ERROR_PERMISSION -> "Allow microphone access"
    status == SpeechFollower.Status.ERROR_NO_RECOGNIZER -> "Speech recognizer missing — install the Google app"
    !running -> "${language.label} · tap ▶ and start speaking"
    status == SpeechFollower.Status.HEARING -> "Hearing you · ${language.label}"
    else -> "Listening · ${language.label}"
}

/** Shared between the speech callbacks and the frame loop (plain fields: both run on the main thread). */
private class FollowSync {
    var jump = true
    var lastPos = -1
    var heardAt = 0L
    var speechAt = 0L // set whenever the recognizer hears anything, matched or not
}
