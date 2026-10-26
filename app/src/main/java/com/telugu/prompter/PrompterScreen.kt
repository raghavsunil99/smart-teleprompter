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
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private enum class Mode { VOICE, STEADY }

private val SpokenColor = Color(0xFF6E6E6E)
private val UpcomingColor = Color.White
private val CurrentColor = Color(0xFFFFC94D)
private val CurrentWordBackground = Color(0x40FFC94D)
private const val FADE_WORDS = 4
private val UpcomingLineColor = Color(0xFFC8C8C8)
private val HeadingColor = Color(0xFF64D2FF)
private val HEADING_LINE = Regex("(?m)^[ \\t]*#.*$")
private val ReadingBand = Color(0x99800000)
private val ToggleBlue = Color(0xFF0A84FF)
private val BarColor = Color(0xFF1C1C1E)
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
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    val densityObj = LocalDensity.current
    val density = densityObj.density

    val matcher = remember(script.body) { ScriptMatcher(script.body) }
    val follower = remember(script.body) { ScrollFollower() }
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
        lastHeard = h
        heardOnLaptop = fromLaptop
        if (matcher.onHeard(h)) {
            position = matcher.position
            follower.confirm(position, System.nanoTime())
        }
    }

    val speech = remember {
        SpeechFollower(
            context = context,
            onHypothesis = { h -> onHeardText(h, fromLaptop = false) },
            onStatus = { status = it },
        )
    }
    speech.muteBeeps = settings.muteBeeps
    speech.continuous = settings.continuousListening
    KeyBus.useVolumeKeys = settings.useVolumeKeys

    fun resync(idx: Int) {
        matcher.reset(idx)
        follower.jumpTo(matcher.position)
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
        window?.let { w ->
            w.attributes = w.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL }
        }
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
            "section" -> value?.toIntOrNull()?.let { jumpToSection(it) }
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
            )
            delay(250)
        }
    }

    // Stop if an error ended the recognizer session.
    LaunchedEffect(status) {
        if (status == SpeechFollower.Status.ERROR_LANGUAGE ||
            status == SpeechFollower.Status.ERROR_PERMISSION ||
            status == SpeechFollower.Status.ERROR_NO_RECOGNIZER
        ) running = false
    }

    // Clean screen: while running, hide our controls and Android's bars unless the user tapped to peek.
    val now by produceNow(peekUntil)
    val controlsVisible = !running || now < peekUntil
    LaunchedEffect(controlsVisible) {
        val w = activity?.window ?: return@LaunchedEffect
        val c = WindowCompat.getInsetsController(w, view)
        if (controlsVisible) {
            c.show(WindowInsetsCompat.Type.systemBars())
        } else {
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.systemBars())
        }
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

    // Voice mode: glide every frame toward the smoothed reading position.
    val isListening by rememberUpdatedState(running && mode == Mode.VOICE)
    LaunchedEffect(mode, wordY) {
        if (mode != Mode.VOICE || wordY.isEmpty()) return@LaunchedEffect
        var last = withFrameNanos { it }
        var lastP = Float.NaN
        while (true) {
            val t = withFrameNanos { it }
            val dt = ((t - last) / 1e9f).coerceAtMost(0.1f)
            last = t
            val p = follower.step(System.nanoTime(), dt, isListening, matcher.words.size)
            // Only drive the scroll when the reading position moves, so a paused prompter can be finger-scrolled.
            if (p == lastP) continue
            lastP = p
            val i = p.toInt().coerceIn(0, wordY.size - 1)
            val j = (i + 1).coerceAtMost(wordY.size - 1)
            val y = wordY[i] + (wordY[j] - wordY[i]) * (p - i)
            val delta = y.roundToInt() - scroll.value
            if (delta != 0) scroll.scrollBy(delta.toFloat())
        }
    }

    // Steady mode: constant-speed scroll.
    val speed by rememberUpdatedState(settings.manualSpeed)
    LaunchedEffect(running, mode) {
        if (!running || mode != Mode.STEADY) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            val t = withFrameNanos { it }
            val dt = (t - last) / 1e9f
            last = t
            scroll.scrollBy(speed * dt * density)
        }
    }

    // Character range of the line being read (for the line-highlight style).
    val readingLine: IntRange? = layout?.takeIf { matcher.words.isNotEmpty() }?.let { l ->
        val li = l.getLineForOffset(matcher.words[position.coerceAtMost(matcher.words.size - 1)].start)
        l.getLineStart(li) until l.getLineEnd(li)
    }

    val text = remember(script.body, position, mode, settings.highlightLine, readingLine) {
        buildAnnotatedString {
            append(script.body)
            if (mode == Mode.VOICE && settings.highlightLine && readingLine != null) {
                // Line focus: the reading line in full white, read text grey, the rest slightly dimmed.
                addStyle(SpanStyle(color = UpcomingLineColor), 0, script.body.length)
                addStyle(SpanStyle(color = SpokenColor), 0, readingLine.first)
                addStyle(SpanStyle(color = UpcomingColor), readingLine.first, readingLine.last + 1)
            } else if (mode == Mode.VOICE && matcher.words.isNotEmpty()) {
                // Only colours change here — never size or weight — so lines never re-wrap while reading.
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
                if (position < matcher.words.size) {
                    val w = matcher.words[position]
                    addStyle(SpanStyle(color = CurrentColor, background = CurrentWordBackground), w.start, w.end)
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
            // Red reading band behind the line being read. The line drifts up by one line height
            // as it is read (see wordY), so the band is centred on that drift.
            Box(
                Modifier
                    .fillMaxWidth()
                    .offset(y = eyeLineDp - lineHeightDp * 0.55f)
                    .height(lineHeightDp * 1.2f)
                    .padding(horizontal = TextSidePadding / 2)
                    .background(ReadingBand),
            )

            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(scroll, enabled = !(running && mode == Mode.VOICE))
                    .padding(horizontal = TextSidePadding),
            ) {
                Box(Modifier.height(eyeLineDp))
                Text(
                    text = text,
                    fontSize = settings.fontSizeSp.sp,
                    lineHeight = (settings.fontSizeSp * 1.5f).sp,
                    onTextLayout = { layout = it },
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
            // Mode toggle + status
            Column(
                Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ModeToggle(mode, onChange = { switchMode(it) })
                Text(
                    when {
                        countdownLeft > 0 -> "Starting in $countdownLeft…"
                        resumed && !running -> "Resumed where you left off · ⤒ to start over"
                        else -> statusText(mode, running, status, remote.laptopMicActive)
                    },
                    color = Color.White, fontSize = 12.sp,
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .background(Color(0xCC222222), RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp, vertical = 3.dp),
                )
            }

            // Bottom bar
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(BarColor)
                    .navigationBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                // Side button: jump to start
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    FilledIconButton(
                        onClick = { toTop() },
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFF2C2C2E), contentColor = Color.White),
                    ) { Icon(Icons.Default.VerticalAlignTop, "Back to start") }
                }
                if (showSettings) SettingsPanel(settings, onSettingsChange, Modifier.heightIn(max = screenHeight * 0.5f))
                if (mode == Mode.VOICE && running && lastHeard.isNotBlank()) {
                    Text((if (heardOnLaptop) "💻 " else "🎙 ") + lastHeard, color = Color.Gray, fontSize = 12.sp, maxLines = 1, modifier = Modifier.padding(horizontal = 8.dp))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { pause(); onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = ToggleBlue) }
                    IconButton(onClick = { toggleTeleprompter() }) {
                        Icon(
                            Icons.Default.Flip, "Teleprompter mode (upside-down + landscape)",
                            tint = if (isTeleprompter) ToggleBlue else Color.White,
                            modifier = Modifier.rotate(90f),
                        )
                    }
                    IconButton(onClick = onRemote) {
                        Icon(Icons.Default.SettingsRemote, "Laptop remote", tint = if (remote.isOn) ToggleBlue else Color.White)
                    }
                    FilledIconButton(
                        onClick = { if (active) pause() else play() },
                        modifier = Modifier.size(56.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = ToggleBlue, contentColor = Color.White),
                    ) { Icon(if (active) Icons.Default.Pause else Icons.Default.PlayArrow, "Play/Pause") }
                    IconButton(onClick = { onSettingsChange(settings.copy(fontSizeSp = (settings.fontSizeSp - 2).coerceAtLeast(18f))) }) {
                        Icon(Icons.Default.TextDecrease, "Smaller", tint = Color.White)
                    }
                    IconButton(onClick = { onSettingsChange(settings.copy(fontSizeSp = (settings.fontSizeSp + 2).coerceAtMost(96f))) }) {
                        Icon(Icons.Default.TextIncrease, "Bigger", tint = Color.White)
                    }
                    IconButton(onClick = { showSettings = !showSettings }) {
                        Icon(Icons.Default.Settings, "Settings", tint = if (showSettings) ToggleBlue else Color.White)
                    }
                }
            }
        }
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
                fontSize = 15.sp,
                modifier = Modifier
                    .background(if (selected) ToggleBlue else Color.Transparent, RoundedCornerShape(50))
                    .clickable { onChange(m) }
                    .padding(horizontal = 18.dp, vertical = 8.dp),
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
private fun SettingsPanel(s: PrompterSettings, onChange: (PrompterSettings) -> Unit, modifier: Modifier = Modifier) {
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
        Text("Highlight", color = Color.White, fontSize = 14.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !s.highlightLine, onClick = { onChange(s.copy(highlightLine = false)) }, label = { Text("Word") })
            FilterChip(selected = s.highlightLine, onClick = { onChange(s.copy(highlightLine = true)) }, label = { Text("Whole line") })
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

private fun statusText(mode: Mode, running: Boolean, status: SpeechFollower.Status, laptopMic: Boolean): String = when {
    mode == Mode.STEADY -> if (running) "Steady scroll" else "Steady · paused"
    laptopMic -> if (running) "💻 Laptop mic" else "💻 Laptop mic ready · tap play"
    status == SpeechFollower.Status.ERROR_LANGUAGE -> "Telugu speech recognition not available"
    status == SpeechFollower.Status.ERROR_NETWORK -> "Network issue, retrying…"
    status == SpeechFollower.Status.ERROR_PERMISSION -> "Allow microphone access"
    status == SpeechFollower.Status.ERROR_NO_RECOGNIZER -> "Speech recognizer missing — install the Google app"
    !running -> "Tap ▶ and start speaking"
    status == SpeechFollower.Status.HEARING -> "Hearing you"
    else -> "Listening"
}
