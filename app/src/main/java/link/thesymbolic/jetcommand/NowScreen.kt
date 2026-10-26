package link.thesymbolic.jetcommand

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalTime
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import link.thesymbolic.jetcommand.now.FieldView
import link.thesymbolic.jetcommand.now.MODE_BUTTONS
import link.thesymbolic.jetcommand.now.NowBody
import link.thesymbolic.jetcommand.now.NowEvent
import link.thesymbolic.jetcommand.now.NowInputs
import link.thesymbolic.jetcommand.now.NowState
import link.thesymbolic.jetcommand.now.Nudge
import link.thesymbolic.jetcommand.now.ProgramButton
import link.thesymbolic.jetcommand.now.RunningView
import link.thesymbolic.jetcommand.now.countdownView
import link.thesymbolic.jetcommand.now.fanField
import link.thesymbolic.jetcommand.now.nowState
import link.thesymbolic.jetcommand.now.programButtons
import link.thesymbolic.jetcommand.now.runningView
import link.thesymbolic.jetcommand.now.runtimeMinutes
import link.thesymbolic.jetcommand.now.shortLabel
import link.thesymbolic.jetcommand.now.steadyActual
import link.thesymbolic.jetcommand.now.tempField
import link.thesymbolic.jetcommand.now.undoTarget
import link.thesymbolic.jetcommand.protocol.Mode
import link.thesymbolic.jetcommand.protocol.Program
import link.thesymbolic.jetcommand.protocol.SettableMode
import link.thesymbolic.jetcommand.protocol.Slot
import link.thesymbolic.jetcommand.protocol.Status
import link.thesymbolic.jetcommand.protocol.Step
import link.thesymbolic.jetcommand.protocol.Timing

/** Collects the view model and [programs] into a [NowState] for [NowContent]. */
@Composable
fun NowScreen(
    vm: BedJetViewModel,
    programs: ProgramsSource,
    snackbar: SnackbarHostState,
    openDiagnostics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val stack by vm.stack.collectAsState()
    val link by vm.linkState.collectAsState()
    val update by vm.status.collectAsState()
    val remembered by vm.remembered.collectAsState()
    val names by vm.programs.collectAsState()
    val device by programs.device.collectAsState()
    val started by programs.started.collectAsState()
    val slots by
        remember(programs, device) { device?.let(programs::slots) ?: flowOf(emptyList()) }
            .collectAsState(emptyList())
    val age = rememberStatusAge(update)
    // Outlives the Off button, which leaves the composition as soon as it turns the unit off.
    val scope = rememberCoroutineScope()
    val state =
        nowState(
            NowInputs(
                stackLabel = stack?.label,
                link = link,
                update = update,
                statusAgeSeconds = age,
                remembered = remembered?.label,
                canConnect = vm.hasConnectPermission(),
                programNames = names,
                device = device,
                started = started,
                slots = slots,
            ),
            LocalTime.now(),
        )
    val live = state.body as? NowBody.Live
    LaunchedEffect(live != null) { if (live != null) vm.loadPrograms() }

    NowContent(
        state,
        onEvent = { e ->
            when (e) {
                NowEvent.Disconnect -> vm.disconnect()
                NowEvent.ConnectRemembered -> vm.connectRemembered()
                NowEvent.OpenDiagnostics -> openDiagnostics()
                NowEvent.TurnOff ->
                    live?.let { turnOffWithUndo(it.status.mode, vm::setMode, snackbar, scope) }
                is NowEvent.SetMode -> vm.setMode(e.mode)
                is NowEvent.SetTemperature -> vm.setTemperature(e.halfC)
                is NowEvent.SetFan -> vm.setFan(e.step)
                is NowEvent.StartProgram -> vm.startProgram(e.slot)
                is NowEvent.SetRuntime -> vm.setRuntime(e.minutes)
            }
        },
        modifier,
        content = { NotificationsPrompt() },
    )
}

/** Seconds since [update] last changed, ticking once a second. */
@Composable
private fun rememberStatusAge(update: Any?): Long {
    // A status arrives about every second; a growing age is the first sign the link went quiet.
    var lastStatusAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(update) { lastStatusAt = SystemClock.elapsedRealtime() }
    val now by
        produceState(SystemClock.elapsedRealtime()) {
            while (true) {
                delay(1_000)
                value = SystemClock.elapsedRealtime()
            }
        }
    return (now - lastStatusAt) / 1_000
}

private fun turnOffWithUndo(
    previous: Mode,
    setMode: (SettableMode) -> Unit,
    snackbar: SnackbarHostState,
    scope: CoroutineScope,
) {
    setMode(SettableMode.STANDBY)
    scope.launch {
        // The snackbar's Short is ~4 s and Long ~10 s; time it out ourselves for ~5 s.
        val result =
            withTimeoutOrNull(5_000) {
                snackbar.showSnackbar(
                    "Turned off",
                    actionLabel = "Undo",
                    duration = SnackbarDuration.Indefinite,
                )
            }
        if (result == null) snackbar.currentSnackbarData?.dismiss()
        if (result == SnackbarResult.ActionPerformed) undoTarget(previous)?.let(setMode)
    }
}

/** The Now screen from [state] alone; [content] goes under the header, for prompts. */
@Composable
fun NowContent(
    state: NowState,
    onEvent: (NowEvent) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    Column(
        modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(state.header, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // Up here, away from the steppers and Stop; also while (re)connecting, so the user can
            // always quit.
            if (state.canDisconnect)
                TextButton({ onEvent(NowEvent.Disconnect) }) {
                    Text("Disconnect", fontSize = 13.sp)
                }
        }
        content()
        when (val body = state.body) {
            is NowBody.Live -> Live(body, onEvent)
            is NowBody.Offline -> {
                Spacer(Modifier.weight(1f))
                body.reconnect?.let { label ->
                    Button(
                        { onEvent(NowEvent.ConnectRemembered) },
                        Modifier.fillMaxWidth().height(56.dp),
                    ) {
                        Text("Connect $label")
                    }
                    Spacer(Modifier.height(12.dp))
                }
                OutlinedButton(
                    { onEvent(NowEvent.OpenDiagnostics) },
                    Modifier.fillMaxWidth().height(56.dp),
                ) {
                    Text("Diagnostics")
                }
                Spacer(Modifier.weight(1f))
            }
            NowBody.Waiting -> Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun ColumnScope.Live(body: NowBody.Live, onEvent: (NowEvent) -> Unit) {
    val st = body.status
    Spacer(Modifier.height(8.dp))
    ModeRow(st.mode) { onEvent(NowEvent.SetMode(it)) }

    Spacer(Modifier.height(12.dp))
    if (body.summary.isNotEmpty()) {
        Text(body.summary, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
    }
    body.running?.let {
        if (body.summary.isNotEmpty()) Spacer(Modifier.height(12.dp))
        RunningStrip(it)
    }
    Countdown(st) { onEvent(NowEvent.SetRuntime(it)) }
    Spacer(Modifier.height(12.dp))

    ValueFields(st, onEvent)
    ProgramButtons(body.programs) { onEvent(NowEvent.StartProgram(it)) }
    Spacer(Modifier.height(16.dp))

    // Off sits apart from the fields so a sleepy thumb aiming for − doesn't land on it.
    if (st.mode != Mode.STANDBY) TurnOffButton { onEvent(NowEvent.TurnOff) }
    else Spacer(Modifier.height(52.dp))
}

@Composable
private fun TurnOffButton(onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        colors =
            ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
    ) {
        Text("Turn BedJet off", fontSize = 16.sp)
    }
}

/**
 * A value sent to the unit and not yet seen in a status; given up after [PENDING_MS], showing the
 * unit's again.
 */
private class Pending {
    var preview by mutableStateOf<Int?>(null)
    var sent by mutableStateOf<Int?>(null)
}

/** Plain field, not snapshot state: it is derived from [Status] during composition. */
private class HeldActual(var value: Int? = null)

private const val PENDING_MS = 5_000L

@Composable
private fun rememberPending(): Pending {
    val p = remember { Pending() }
    LaunchedEffect(p.sent) {
        if (p.sent == null) return@LaunchedEffect
        delay(PENDING_MS)
        p.sent = null
    }
    return p
}

@Composable
private fun ModeRow(mode: Mode, setMode: (SettableMode) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        MODE_BUTTONS.forEach { m ->
            val c = modeColor(m.mode)
            val on = m.mode == mode
            Box(
                Modifier.weight(1f)
                    .height(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (on) c else c.copy(alpha = 0.14f))
                    .border(
                        if (on) 2.dp else 1.dp,
                        if (on) MaterialTheme.colorScheme.onSurface else c,
                        RoundedCornerShape(12.dp),
                    )
                    .clickable(enabled = !on) { setMode(m) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    shortLabel(m.mode),
                    color = if (on) OnModeFill else c,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.ValueFields(st: Status, onEvent: (NowEvent) -> Unit) {
    val temp = rememberPending()
    val fan = rememberPending()
    val held = remember { HeldActual() }
    val actual = steadyActual(held.value, st.actualTemp).also { held.value = it }
    Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Field("Temp", tempField(st, temp.preview, temp.sent, actual), st.mode, temp) {
            onEvent(NowEvent.SetTemperature(it))
        }
        Field("Fan", fanField(st, fan.preview, fan.sent), st.mode, fan) {
            onEvent(NowEvent.SetFan(it))
        }
    }
}

@Composable
private fun RowScope.Field(
    label: String,
    view: FieldView,
    mode: Mode,
    pending: Pending,
    send: (Int) -> Unit,
) {
    ValueField(
        label = label,
        text = view.text,
        sub = view.sub,
        index = view.index,
        count = view.count,
        color = modeColor(mode),
        enabled = view.enabled,
        hollow = view.hollow,
        onPreview = { pending.preview = it?.let(view::valueAt) },
        onCommit = {
            val value = view.valueAt(it)
            pending.sent = value
            send(value)
        },
        modifier = Modifier.weight(1f).fillMaxHeight(),
    )
}

@Composable
private fun ColumnScope.ProgramButtons(programs: List<ProgramButton>, start: (Slot) -> Unit) {
    if (programs.isEmpty()) return
    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        programs.forEach { (slot, label) ->
            Column(
                Modifier.weight(1f)
                    .height(60.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { start(slot) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(label, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    ProgramIcon()
                    Text(
                        "program",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** Taps add up locally and go out as one command once they pause for [SEND_AFTER_MS]. */
@Composable
private fun Countdown(st: Status, send: (Int) -> Unit) {
    var draft by remember { mutableStateOf<Int?>(null) }
    val status by rememberUpdatedState(st)
    LaunchedEffect(draft) {
        val d = draft ?: return@LaunchedEffect
        delay(SEND_AFTER_MS)
        send(d)
        withTimeoutOrNull(PENDING_MS) {
            snapshotFlow { runtimeMinutes(status.remainingSeconds) }.first { abs(it - d) <= 1 }
        }
        draft = null
    }
    val view = countdownView(st, draft, LocalTime.now()) ?: return
    val color = modeColor(st.mode)
    Column(
        Modifier.padding(bottom = 12.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text("Auto off in", fontSize = 14.sp, color = Muted, modifier = Modifier.weight(1f))
            Text(
                view.left,
                fontSize = 26.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (draft != null) color else MaterialTheme.colorScheme.onSurface,
                style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum"),
            )
        }
        Row(Modifier.fillMaxWidth()) {
            Text(view.offAt, fontSize = 13.sp, color = Muted, modifier = Modifier.weight(1f))
            Text(view.ceiling, fontSize = 13.sp, color = Muted)
        }
        Box(
            Modifier.fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(Modifier.fillMaxWidth(view.fraction).height(6.dp).background(color))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            view.nudges.forEachIndexed { i, n ->
                // The ups wear the mode's colour, so a sleepy thumb can tell the halves apart.
                val tint =
                    if (i >= view.nudges.size / 2) color else MaterialTheme.colorScheme.onSurface
                NudgeButton(n, tint) { draft = it }
            }
        }
    }
}

private const val SEND_AFTER_MS = 1_000L

@Composable
private fun RowScope.NudgeButton(n: Nudge, tint: Color, pick: (Int) -> Unit) {
    val target = n.target
    Box(
        Modifier.weight(1f)
            .height(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(
                1.dp,
                if (target != null) tint else MaterialTheme.colorScheme.outline,
                RoundedCornerShape(12.dp),
            )
            .clickable(enabled = target != null) { target?.let(pick) },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            n.label,
            fontSize = if (n.label.length > 3) 12.sp else 15.sp,
            color = if (target != null) tint else Muted.copy(alpha = 0.5f),
            maxLines = 1,
        )
    }
}

@Composable
private fun RunningStrip(r: RunningView) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text(r.title, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Text(
                r.left,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum"),
            )
        }
        Text(
            r.next,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.End),
        )
        StepStrip(r.spans, height = 36.dp, nowMinute = r.nowMinute)
    }
}

@Preview(widthDp = 390, heightDp = 844, backgroundColor = 0xFF07090C, showBackground = true)
@Composable
private fun NowContentPreview() = JetCommandTheme {
    val twostep =
        Program(
            "TWOSTEP",
            listOf(
                Step(Mode.HEAT, 9, 70, Timing.For(0, 30)),
                Step(Mode.COOL, 5, 40, Timing.Until(6, 30)),
            ),
        )
    val status = previewStatus()
    NowContent(
        NowState(
            header = "Demo BedJet · Ready · 1 s ago",
            canDisconnect = true,
            NowBody.Live(
                status,
                summary = "",
                running = runningView(twostep, 1, status.remainingSeconds, LocalTime.of(22, 40)),
                programs = programButtons(listOf("TWOSTEP", null, "Warm up")),
            ),
        ),
        onEvent = {},
    )
}

private fun previewStatus() =
    Status(
        partial = false,
        length = 20,
        remainingSeconds = 1_200,
        actualTemp = 62,
        targetTemp = 70,
        mode = Mode.HEAT,
        fanStep = 9,
        maxRuntimeMinutes = 600,
        minTemp = 0,
        maxTemp = 0,
        turboTime = emptyList(),
        ambientTemp = 44,
        shutdownReason = 0,
        dualZone = null,
        updatePhase = null,
        extra = null,
    )

/** A step curve, the same shape as a program's timeline. */
@Composable
private fun ProgramIcon() {
    val c = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.size(14.dp)) {
        val u = size.width / 14f
        val path =
            Path().apply {
                moveTo(1 * u, 11 * u)
                lineTo(4 * u, 11 * u)
                lineTo(4 * u, 4 * u)
                lineTo(8 * u, 4 * u)
                lineTo(8 * u, 8 * u)
                lineTo(13 * u, 8 * u)
            }
        drawPath(path, c, style = Stroke(width = 1.6f * u))
    }
}

/**
 * The night controls live in the notification; Bluetooth granted via `pm grant` or settings never
 * asked for it.
 */
@Composable
private fun ColumnScope.NotificationsPrompt() {
    if (Build.VERSION.SDK_INT < 33) return
    val context = LocalContext.current
    fun granted() =
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    var ok by remember { mutableStateOf(granted()) }
    var asked by remember { mutableStateOf(false) }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            ok = it
            asked = true
        }
    if (ok) return
    Spacer(Modifier.height(8.dp))
    Text(
        "Notifications are off: no controls in the shade or on the lock screen.",
        color = MaterialTheme.colorScheme.error,
    )
    OutlinedButton({
        // After a denial the system dialog no longer shows; only the app's settings page can turn
        // them on.
        if (asked)
            context.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            )
        else launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }) {
        Text("Turn on notifications")
    }
}
