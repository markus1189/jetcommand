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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import link.thesymbolic.jetcommand.protocol.Button as Btn
import link.thesymbolic.jetcommand.protocol.LinkState
import link.thesymbolic.jetcommand.protocol.Mode
import link.thesymbolic.jetcommand.protocol.Program
import link.thesymbolic.jetcommand.protocol.Status
import link.thesymbolic.jetcommand.protocol.fanPercent
import link.thesymbolic.jetcommand.protocol.formatTemp
import link.thesymbolic.jetcommand.protocol.tempRange

@Composable
fun NowScreen(
    vm: BedJetViewModel,
    programs: ProgramsSource,
    snackbar: SnackbarHostState,
    openDiagnostics: () -> Unit,
) {
    val stack by vm.stack.collectAsState()
    val link by vm.linkState.collectAsState()
    val update by vm.status.collectAsState()
    val remembered by vm.remembered.collectAsState()
    val scope = rememberCoroutineScope()
    val st = update?.status?.takeIf { link == LinkState.Ready }
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
    val age = ((now - lastStatusAt) / 1_000).takeIf { st != null }

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                (stack?.label?.let { "$it · " } ?: "") +
                    linkText(link) +
                    (age?.let { " · $it s ago" } ?: ""),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Up here, away from the steppers and Stop; also while (re)connecting, so the user can
            // always quit.
            if (stack != null) TextButton(vm::disconnect) { Text("Disconnect", fontSize = 13.sp) }
        }
        NotificationsPrompt()
        if (st == null) {
            Spacer(Modifier.weight(1f))
            if (stack == null) {
                remembered
                    ?.takeIf { vm.hasConnectPermission() }
                    ?.let { d ->
                        Button(vm::connectRemembered, Modifier.fillMaxWidth().height(56.dp)) {
                            Text("Connect ${d.label}")
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                OutlinedButton(openDiagnostics, Modifier.fillMaxWidth().height(56.dp)) {
                    Text("Diagnostics")
                }
            }
            Spacer(Modifier.weight(1f))
            return@Column
        }

        Spacer(Modifier.height(8.dp))
        Connected(vm, st, programs)
        Spacer(Modifier.height(16.dp))

        // Off sits apart from the fields so a sleepy thumb aiming for − doesn't land on it.
        if (st.mode != Mode.STANDBY) {
            OutlinedButton(
                onClick = {
                    val previous = st.mode
                    vm.setMode(Mode.STANDBY)
                    scope.launch {
                        // The snackbar's Short is ~4 s and Long ~10 s; time it out ourselves for ~5
                        // s.
                        val result =
                            withTimeoutOrNull(5_000) {
                                snackbar.showSnackbar(
                                    "Turned off",
                                    actionLabel = "Undo",
                                    duration = SnackbarDuration.Indefinite,
                                )
                            }
                        if (result == null) snackbar.currentSnackbarData?.dismiss()
                        // WAIT can't be set by a button; programs aren't restored either.
                        if (
                            result == SnackbarResult.ActionPerformed &&
                                Btn.forMode(previous) != null
                        )
                            vm.setMode(previous)
                    }
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors =
                    ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
            ) {
                Text("Turn BedJet off", fontSize = 16.sp)
            }
        } else Spacer(Modifier.height(52.dp))
    }
}

private val MODES = listOf(Mode.COOL, Mode.DRY, Mode.EXT_HEAT, Mode.HEAT, Mode.TURBO)

private fun shortLabel(mode: Mode) = if (mode == Mode.EXT_HEAT) "Ext" else mode.label

/**
 * A value sent to the unit and not yet seen in a status; given up after [PENDING_MS], showing the
 * unit's again.
 */
private class Pending {
    var preview by mutableStateOf<Int?>(null)
    var sent by mutableStateOf<Int?>(null)

    fun unconfirmed(confirmed: Int) = sent?.takeIf { it != confirmed }
}

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
private fun ColumnScope.Connected(vm: BedJetViewModel, st: Status, source: ProgramsSource) {
    LaunchedEffect(Unit) { vm.loadPrograms() }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        MODES.forEach { m ->
            val c = modeColor(m)
            val on = m == st.mode
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
                    .clickable(enabled = !on) { vm.setMode(m) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    shortLabel(m),
                    color = if (on) Color(0xFF0E1116) else c,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }

    Spacer(Modifier.height(12.dp))
    val r = st.remainingSeconds
    val step = st.extra?.programStep ?: 0
    val running = runningProgram(source, step)
    Text(
        listOfNotNull(
                "auto off %d:%02d".format(r / 3600, r / 60 % 60).takeIf { st.mode != Mode.STANDBY },
                "program step $step".takeIf { step > 0 && running == null },
            )
            .joinToString(" · "),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 14.sp,
    )
    running?.let {
        Spacer(Modifier.height(12.dp))
        RunningStrip(it, step, r)
    }
    Spacer(Modifier.height(12.dp))

    val temp = rememberPending()
    val fan = rememberPending()
    val (range, _) = tempRange(st.mode, st)
    val tempOn = st.mode.usesTemperature
    val fanOn = st.mode != Mode.STANDBY && st.mode != Mode.WAIT
    val tempShown =
        temp.preview?.let { range.min + it } ?: temp.unconfirmed(st.targetTemp) ?: st.targetTemp
    val fanShown = fan.preview ?: fan.unconfirmed(st.fanStep) ?: st.fanStep

    Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ValueField(
            label = "Temp",
            text = if (tempOn || st.mode == Mode.TURBO) formatTemp(tempShown) else "--",
            sub =
                when {
                    st.mode == Mode.TURBO -> "fixed in Turbo"
                    temp.unconfirmed(st.targetTemp) != null ->
                        "sending · now ${formatTemp(st.targetTemp)}"
                    else -> "actual ${formatTemp(st.actualTemp)}"
                },
            index = tempShown - range.min,
            count = range.max - range.min + 1,
            color = modeColor(st.mode),
            enabled = tempOn,
            hollow = temp.unconfirmed(st.targetTemp) != null,
            onPreview = { temp.preview = it },
            onCommit = {
                temp.sent = range.min + it
                vm.setTemperature(range.min + it)
            },
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
        ValueField(
            label = "Fan",
            text = if (fanOn) "${fanPercent(fanShown)}%" else "--",
            sub =
                if (fan.unconfirmed(st.fanStep) != null) "sending · now ${st.fanPercent}%" else "",
            index = fanShown,
            count = 20,
            color = modeColor(st.mode),
            enabled = fanOn,
            hollow = fan.unconfirmed(st.fanStep) != null,
            onPreview = { fan.preview = it },
            onCommit = {
                fan.sent = it
                vm.setFan(it)
            },
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
    }

    val programs = vm.programs.withIndex().filter { it.value != null }
    if (programs.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            programs.forEach { (slot, name) ->
                Column(
                    Modifier.weight(1f)
                        .height(60.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { vm.startProgram(slot) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        prettyName(name!!),
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
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
}

/**
 * The program the unit is running, from the slot cache. The status carries only the step number:
 * the slot this app started, else the one cached program long enough for the step; null when none
 * or several fit.
 */
@Composable
private fun runningProgram(source: ProgramsSource, step: Int): Program? {
    if (step <= 0) return null
    val device by source.device.collectAsState()
    val started by source.started.collectAsState()
    val slots by
        remember(source, device) { device?.let(source::slots) ?: flowOf(emptyList()) }
            .collectAsState(emptyList())
    val fits = { p: Program? -> p?.takeIf { it.steps.size >= step } }
    started
        ?.takeIf { it.device == device }
        ?.let { s ->
            fits(slots.getOrNull(s.slot)?.state?.program)?.let {
                return it
            }
        }
    return slots.mapNotNull { fits(it.state.program) }.singleOrNull()
}

/** "Twostep · step 2/5", the next step and when it starts, and the strip with a now-line. */
@Composable
private fun RunningStrip(p: Program, step: Int, remainingSeconds: Int) {
    val now = LocalTime.now()
    val i = (step - 1).coerceIn(0, p.steps.lastIndex)
    // The step's remaining time puts us inside it; clock-time steps depend on the start, so place
    // it twice.
    fun place(start: LocalTime) =
        timeline(p.steps, start).let { sp -> sp to sp[i].to - remainingSeconds / 60.0 }
    val (first, nowMin0) = place(now)
    val start = now.minusMinutes(nowMin0.toLong().coerceAtLeast(0))
    val (spans, nowMin) = place(start).takeIf { first.isNotEmpty() } ?: (first to nowMin0)
    val next = spans.getOrNull(i + 1)
    val at =
        now.plusSeconds(remainingSeconds.toLong()).let { "%02d:%02d".format(it.hour, it.minute) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(
                "${prettyName(p.name)} · step $step/${p.steps.size}",
                fontSize = 14.sp,
                modifier = Modifier.weight(1f),
            )
            Text(
                next?.let { "next $at · ${it.step.mode.label.lowercase()} ${duration(it.minutes)}" }
                    ?: "ends $at",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        StepStrip(spans, height = 36.dp, nowMinute = nowMin)
    }
}

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
private fun NotificationsPrompt() {
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
