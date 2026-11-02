package link.thesymbolic.jetcommand.protocol.shell

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import link.thesymbolic.jetcommand.protocol.core.Catalog
import link.thesymbolic.jetcommand.protocol.core.DeviceId
import link.thesymbolic.jetcommand.protocol.core.Fingerprint
import link.thesymbolic.jetcommand.protocol.core.LibraryEntry
import link.thesymbolic.jetcommand.protocol.core.LibraryOutcome
import link.thesymbolic.jetcommand.protocol.core.Origin
import link.thesymbolic.jetcommand.protocol.core.Program
import link.thesymbolic.jetcommand.protocol.core.ProgramId
import link.thesymbolic.jetcommand.protocol.core.ProgramRevision
import link.thesymbolic.jetcommand.protocol.core.PushRecord
import link.thesymbolic.jetcommand.protocol.core.Reconciled
import link.thesymbolic.jetcommand.protocol.core.Refusal
import link.thesymbolic.jetcommand.protocol.core.Resolution
import link.thesymbolic.jetcommand.protocol.core.Slot
import link.thesymbolic.jetcommand.protocol.core.SlotStatus
import link.thesymbolic.jetcommand.protocol.core.SlotView
import link.thesymbolic.jetcommand.protocol.core.StartFailure
import link.thesymbolic.jetcommand.protocol.core.StartOutcome
import link.thesymbolic.jetcommand.protocol.core.StartedSlot
import link.thesymbolic.jetcommand.protocol.core.blankSlot
import link.thesymbolic.jetcommand.protocol.core.fingerprint
import link.thesymbolic.jetcommand.protocol.core.linkSlot
import link.thesymbolic.jetcommand.protocol.core.needsLink
import link.thesymbolic.jetcommand.protocol.core.readBack
import link.thesymbolic.jetcommand.protocol.core.slotStatus
import link.thesymbolic.jetcommand.protocol.core.slotViews
import link.thesymbolic.jetcommand.protocol.ports.LibraryStore
import link.thesymbolic.jetcommand.protocol.ports.SlotReader

private val done = LibraryOutcome.Done(Unit)

/** The rules: reconciling slot reads, the write pipeline, conflicts and the run picker. */
class ProgramLibrary(
    private val store: LibraryStore,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _started = MutableStateFlow<StartedSlot?>(null)
    /**
     * The slot this app last started, until [clearStarted]: the status names only the step, not the
     * slot.
     */
    val started: StateFlow<StartedSlot?> = _started.asStateFlow()

    // Revisions are immutable, so (program, rev) keys the SHA-256 for good.
    private val fingerprints = ConcurrentHashMap<Pair<ProgramId, Int>, Fingerprint>()

    private fun fingerprintOf(r: ProgramRevision): Fingerprint =
        fingerprints.getOrPut(r.programId to r.rev) { r.program.fingerprint() }

    fun noteStarted(device: DeviceId, slot: Slot) {
        _started.value = StartedSlot(device, slot)
    }

    fun clearStarted() {
        _started.value = null
    }

    fun entries(): Flow<List<LibraryEntry>> = store.entries()

    fun catalog(): Flow<Catalog> =
        combine(store.entries(), store.allRevisions()) { entries, revisions ->
            Catalog(entries, revisions, ::fingerprintOf)
        }

    /** Always three, slot order. */
    fun slots(device: DeviceId): Flow<List<SlotView>> =
        combine(store.slots(device), catalog()) { states, catalog ->
            slotViews(device, states, catalog)
        }

    suspend fun revisions(programId: ProgramId): List<ProgramRevision> = store.revisions(programId)

    /** Reads names and steps of all slots and reconciles them; call on every Ready. */
    suspend fun refresh(device: DeviceId, bedJet: SlotReader) {
        // Each query takes the link's queue on its own, so a user command waits behind one query,
        // not the refresh.
        val programs = bedJet.storedPrograms()
        for ((slot, program) in Slot.all.zip(programs)) reconcile(device, slot, program)
    }

    /**
     * Validate, re-read the slot and compare to the cache, save an orphan it holds, write, verify.
     */
    suspend fun push(
        device: DeviceId,
        bedJet: BedJetDevice,
        slot: Slot,
        programId: ProgramId,
    ): LibraryOutcome<Unit> {
        if ((bedJet.status?.status?.extra?.programStep ?: 0) > 0)
            return LibraryOutcome.Refused(Refusal.PROGRAM_RUNNING)
        val entry = store.entry(programId) ?: return LibraryOutcome.Refused(Refusal.NOT_IN_LIBRARY)
        if (entry.deletedAt != null) return LibraryOutcome.Refused(Refusal.DELETED)

        val cached = store.slot(device, slot)
        val onUnit = readSlot(bedJet, slot)
        if (cached?.readAt == null || onUnit != cached.program) {
            val hadCache = cached?.readAt != null
            reconcile(device, slot, onUnit)
            if (hadCache) return LibraryOutcome.Refused(Refusal.SLOT_CHANGED)
        }
        if (onUnit != null && !snapshot().holds(onUnit.fingerprint())) {
            store.createProgram(onUnit, Origin.KEPT_FROM_SLOT, now())
        }

        val target = entry.current
        val state = store.slot(device, slot) ?: blankSlot(device, slot)
        store.putSlot(
            state.copy(pending = PushRecord(programId, target.rev, fingerprintOf(target), now()))
        )
        val stored = bedJet.store(target.program, slot)
        store.putSlot(
            state.copy(
                program = stored,
                readAt = now(),
                pushed = PushRecord(programId, target.rev, stored.fingerprint(), now()),
                pending = null,
            )
        )
        return done
    }

    /**
     * [push] unless the slot already holds the current revision, then [BedJetDevice.startProgram].
     */
    suspend fun run(
        device: DeviceId,
        bedJet: BedJetDevice,
        slot: Slot,
        programId: ProgramId,
    ): LibraryOutcome<Unit> {
        val status = slotStatus(store.slot(device, slot) ?: blankSlot(device, slot), snapshot())
        if (!status.holdsCurrent(programId)) {
            val pushed = push(device, bedJet, slot, programId)
            if (pushed is LibraryOutcome.Refused) return pushed
        }
        val started = bedJet.startProgram(slot)
        if (started is StartOutcome.Failed)
            return LibraryOutcome.Refused(
                when (started.reason) {
                    StartFailure.CLOCK_NOT_SET -> Refusal.CLOCK_NOT_SET
                    StartFailure.STEP_TOO_LONG -> Refusal.STEP_TOO_LONG
                    StartFailure.UNCONFIRMED -> Refusal.START_UNCONFIRMED
                }
            )
        noteStarted(device, slot)
        return done
    }

    suspend fun resolve(
        device: DeviceId,
        slot: Slot,
        resolution: Resolution,
    ): LibraryOutcome<Unit> {
        val state = store.slot(device, slot) ?: blankSlot(device, slot)
        val status =
            slotStatus(state, snapshot()) as? SlotStatus.ChangedOutside
                ?: return LibraryOutcome.Refused(Refusal.STALE)
        val fp = status.program.fingerprint()
        val id = status.entry.id
        val pushed =
            when (resolution) {
                Resolution.TAKE_THEIRS -> {
                    store.setCurrent(id, status.outsideRev)
                    PushRecord(id, status.outsideRev, fp, now())
                }
                // The outside revision then counts as an older revision of the entry.
                Resolution.KEEP_MINE -> PushRecord(id, status.outsideRev, fp, now())
                Resolution.SPLIT_OFF -> {
                    val split = store.createProgram(status.program, Origin.OUTSIDE, now())
                    PushRecord(split.id, split.current.rev, fp, now())
                }
            }
        store.putSlot(state.copy(pushed = pushed))
        return done
    }

    /** Saves the unknown program a slot holds to the library and links the slot to it. */
    suspend fun keep(device: DeviceId, slot: Slot): LibraryOutcome<LibraryEntry> {
        val state = store.slot(device, slot) ?: blankSlot(device, slot)
        val status =
            slotStatus(state, snapshot()) as? SlotStatus.NotInLibrary
                ?: return LibraryOutcome.Refused(Refusal.STALE)
        val entry = store.createProgram(status.program, Origin.KEPT_FROM_SLOT, now())
        store.putSlot(
            state.copy(
                pushed =
                    PushRecord(entry.id, entry.current.rev, status.program.fingerprint(), now())
            )
        )
        return LibraryOutcome.Done(entry)
    }

    suspend fun create(program: Program, origin: Origin = Origin.BLANK): LibraryEntry =
        store.createProgram(program, origin, now())

    /** [program] as [programId]'s new current revision; the slots keep theirs until pushed. */
    suspend fun saveEdit(programId: ProgramId, program: Program): ProgramRevision =
        store.addRevision(programId, program, Origin.EDIT, now(), makeCurrent = true)

    suspend fun delete(programId: ProgramId) = store.setDeleted(programId, now())

    suspend fun restore(programId: ProgramId) = store.setDeleted(programId, null)

    private suspend fun snapshot(): Catalog = catalog().first()

    private suspend fun readSlot(bedJet: SlotReader, slot: Slot): Program? {
        val name = bedJet.programNames().getOrNull(slot.index)
        val steps = bedJet.programSteps(slot)
        return if (name == null || steps.isEmpty()) null else Program(name, steps)
    }

    private suspend fun reconcile(device: DeviceId, slot: Slot, program: Program?) {
        val old = store.slot(device, slot) ?: blankSlot(device, slot)
        val t = now()
        val read = readBack(old, program, t)
        val (state, revision) =
            if (needsLink(read)) linkSlot(read, snapshot(), t) else Reconciled(read)
        revision?.let {
            store.addRevision(it.programId, it.program, Origin.OUTSIDE, it.at, makeCurrent = false)
        }
        store.putSlot(state)
    }
}
