// Records what the web app's codec and BedJetSim do, so the Kotlin port can be replayed against them.
// Run: nix develop -c node scripts/gen-fixtures.mjs [path/to/bedjet-bluetooth-control.html]
import { readFileSync, writeFileSync } from 'node:fs';
import vm from 'node:vm';

const htmlPath = process.argv[2] ?? new URL('../../tools/bedjet-bluetooth-control.html', import.meta.url).pathname;
const out = new URL('../protocol/src/test/resources/fixtures.json', import.meta.url).pathname;
const html = readFileSync(htmlPath, 'utf8');

const context = { console };
vm.createContext(context);
for (const id of ['bedjet-codec', 'bedjet-sim']) {
    const match = html.match(new RegExp(`<script id="${id}">([\\s\\S]*?)</script>`));
    if (!match) throw new Error(`script #${id} not found in ${htmlPath}`);
    vm.runInContext(match[1], context, { filename: id });
}
const { BedJetCodec: C, BedJetSim } = context;
const { Mode } = C;
const hex = C.hex;
const plain = value => JSON.parse(JSON.stringify(value));

// mulberry32: seeded, so regenerating without a source change yields an identical file.
function rng(seed) {
    return () => {
        seed = (seed + 0x6d2b79f5) | 0;
        let t = Math.imul(seed ^ (seed >>> 15), 1 | seed);
        t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
        return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
    };
}
const rand = rng(0xbed);
const int = (lo, hi) => lo + Math.floor(rand() * (hi - lo + 1));
const pick = list => list[int(0, list.length - 1)];
const chance = p => rand() < p;

function attempt(fn) {
    try { return { out: fn() }; } catch (e) { return { error: e.code ?? e.name }; }
}

// A step that is valid more often than not, so validation and the sim's program runs both get exercised.
function randomStep() {
    const mode = pick([Mode.HEAT, Mode.TURBO, Mode.EXT_HEAT, Mode.COOL, Mode.DRY, Mode.WAIT, Mode.WAIT, Mode.STANDBY]);
    const range = C.TEMP_RANGES[mode];
    const temp = mode === Mode.WAIT ? 80 : mode === Mode.TURBO ? 86 : chance(0.9) ? int(range.min, range.max) : int(0, 255);
    const clock = C.clockTimeAllowed(mode) && chance(0.3);
    const hours = clock ? int(0, chance(0.95) ? 23 : 30) : mode === Mode.TURBO ? 0 : int(0, chance(0.9) ? 3 : 13);
    const minutes = mode === Mode.TURBO ? int(1, 12) : int(0, chance(0.95) ? 59 : 70);
    return { mode, fan: int(0, chance(0.95) ? 19 : 21), temp, hours, minutes, clock };
}
const randomProgram = () => Array.from({ length: int(1, chance(0.9) ? 6 : 34) }, randomStep);

// ---- Codec ----

const ALLOWED = [...new Set([...Object.values(C.Button), 0x81, 0x82, 0x84, 0x85])];
const NAMES = ['', 'Night', 'Schläfchen 😀 long name here', 'tab\there', '~!@#$%^&*()_+{}|', 'exactly15chars!', 'sixteen chars!!!'];

const encoders = [];
const enc = (fn, args, run) => encoders.push({ fn, args, ...attempt(() => [run()].flat().map(hex)) });
for (let code = 0; code < 256; code++) enc('button', [code], () => C.button(code));
for (let mode = 0; mode <= 6; mode++) enc('setMode', [mode], () => C.setMode(mode));
for (const m of [0, 1, 59, 60, 61, 719, 15359, 15360, 99999]) enc('setRuntime', [m], () => C.setRuntime(m));
for (const t of [0, 44, 255, 256]) enc('setTemperature', [t], () => C.setTemperature(t));
for (const f of [0, 19, 20]) enc('setFan', [f], () => C.setFan(f));
for (const [h, m] of [[0, 0], [23, 59], [24, 60], [7, 30]]) enc('setClock', [h, m], () => C.setClock(h, m));
for (const n of [0, 1, 3, 4]) {
    enc('recallMemory', [n], () => C.recallMemory(n));
    enc('saveMemory', [n], () => C.saveMemory(n));
}
for (const slot of [-1, 0, 2, 3]) {
    enc('startProgram', [slot], () => C.startProgram(slot));
    enc('deleteProgram', [slot], () => C.deleteProgram(slot));
}
for (const [id, tag] of [[0, 1], [0x20, 255], [18, 7]]) enc('query', [id, tag], () => C.query(id, tag));
for (const param of [-1, 0, 4, 6, 7]) for (const name of NAMES) enc('nameFrame', [param, name], () => C.nameFrame(param, name));
for (let i = 0; i < 40; i++) {
    const slot = i < 36 ? i % 3 : pick([-1, 3]);
    const steps = randomProgram();
    enc('writeProgram', [slot, steps], () => C.writeProgram(slot, steps));
}

const STATUS = '01 56 1b 01 00 00 00 2e 4f 00 13 00 00 14 50 00 00 2d 00 12 01 9a 01 10 10 00 15 35 00 01 53';
const statuses = [STATUS, STATUS.slice(0, 20 * 3 - 1), '00 56 1e 01 00 00 00 2e 4f 07 13', STATUS.replace(/^01 56 1b 02/, '01 56 1b 01')];
for (let i = 0; i < 30; i++) {
    const length = pick([19, 20, 22, 23, 26, 27, 29, 30, 31]);
    const b = Array.from({ length }, () => int(0, 255));
    if (chance(0.9)) { b[1] = 0x56; b[3] = 0x01; }
    if (b.length > 9 && chance(0.85)) b[9] = int(0, 6);
    statuses.push(hex(b));
}
const parsers = [];
for (const s of statuses) parsers.push({ fn: 'parseStatus', args: [s], out: plain(C.parseStatus(C.fromHex(s))) });
for (let i = 0; i < 30; i++) {
    const steps = randomProgram().slice(0, 32);
    const index = int(0, 3);
    const frame = C.writeProgram(0, steps)[Math.min(index, Math.ceil(steps.length / 8) - 1)] ?? new Uint8Array(43);
    let payload = Array.from(frame.slice(2));
    if (chance(0.2)) payload = payload.slice(0, int(0, payload.length));
    if (chance(0.1)) payload[1 + 5 * int(0, 7)] = int(7, 255);
    if (chance(0.05)) payload[0] = 33;
    parsers.push({ fn: 'parseFragment', args: [hex(payload), index], out: plain(C.parseFragment(payload, index)) });
}
for (const s of ['', '00', '41 42 00 43', '7f 80 ff', '01 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 41 00']) {
    const b = C.fromHex(s);
    parsers.push({ fn: 'parseText', args: [s], out: C.parseText(b) });
    parsers.push({ fn: 'parseNames', args: [s], out: plain(C.parseNames(b)) });
}

const heatHours = [];
for (let mode = 0; mode <= 6; mode++) {
    for (let temp = 0; temp <= 100; temp += mode === Mode.HEAT ? 1 : 25) {
        for (let fan = 0; fan <= 19; fan++) heatHours.push([mode, temp, fan, C.heatHours(mode, temp, fan)]);
    }
}

const validations = [{ steps: [], now: 0, out: plain(C.validateProgram([], 0)) }];
// Clock-step boundaries random programs rarely hit: ending exactly at the previous end, and at 12 h ± 1 min.
const wait = (hours, minutes, clock) => ({ mode: Mode.WAIT, fan: 19, temp: 80, hours, minutes, clock });
for (const [now, steps] of [
    [600, [wait(10, 0, true)]],
    [600, [wait(21, 59, true)]],
    [600, [wait(22, 0, true)]],
    [600, [wait(22, 1, true)]],
    [1430, [wait(0, 20, false), wait(0, 10, true)]],
    [1430, [wait(11, 0, false), wait(23, 50, true)]],
]) {
    validations.push({ steps, now, out: plain(C.validateProgram(steps, now)) });
}
for (let i = 0; i < 300; i++) {
    const steps = randomProgram();
    const now = int(0, 1439);
    validations.push({ steps, now, out: plain(C.validateProgram(steps, now)) });
}

const formatTemp = [];
for (const halfC of [0, 1, 44, 45, 80, 86, 255]) for (const unit of ['C', 'F']) {
    formatTemp.push([halfC, unit, C.formatTemp(halfC, unit)]);
}

// ---- Sim ----

function randomCommand(sim) {
    switch (int(0, 9)) {
        case 0: case 1: return [C.button(pick(ALLOWED))];
        case 2: return [C.setRuntime(int(0, 800))];
        case 3: return [C.setTemperature(int(30, 90))];
        case 4: return [C.setFan(int(0, 19))];
        case 5: return [C.setClock(int(0, 23), int(0, 59))];
        case 6: {
            const slot = int(0, 2);
            const frames = C.writeProgram(slot, randomProgram().slice(0, 32));
            const sent = chance(0.85) ? frames : frames.slice(0, int(0, frames.length));
            return chance(0.7) ? [...sent, C.nameFrame(4 + slot, pick(NAMES))] : sent;
        }
        case 7: return [C.nameFrame(int(0, 6), pick(NAMES))];
        case 8: return [C.startProgram(sim.slots.findIndex(Boolean) >= 0 && chance(0.7) ? sim.slots.findIndex(Boolean) : int(0, 2))];
        default: return [chance(0.5) ? Uint8Array.of(0x09, int(0, 255)) : Uint8Array.of(int(0, 255))];
    }
}

async function scenario(ops) {
    const sim = new BedJetSim({ autoTick: false });
    await sim.connect();
    const trace = [];
    for (const op of ops(sim)) {
        let result;
        if (op.send) {
            result = attempt(() => null);
            for (const frame of op.send) {
                try { await sim.send(frame); } catch (e) { result = { error: e.code ?? e.name }; }
            }
        } else if (op.query !== undefined) {
            try { result = { out: hex(await sim.query(op.query)) }; } catch (e) { result = { error: e.code ?? e.name }; }
        } else if (op.tick) {
            for (let i = 0; i < op.tick; i++) sim.tick();
        } else if (op.skip !== undefined) {
            sim.skipAhead(op.skip);
        } else if (op.cutOut !== undefined) {
            sim.cutOut(op.cutOut);
        }
        const recorded = { ...op, ...(op.send ? { send: op.send.map(hex) } : {}) };
        trace.push({ op: recorded, ...(result ?? {}), packet: hex(sim.packet()) });
    }
    return trace;
}

const scenarios = [];
const NIGHT = [
    { mode: Mode.COOL, fan: 9, temp: 44, hours: 0, minutes: 30, clock: false },
    { mode: Mode.WAIT, fan: 19, temp: 80, hours: 6, minutes: 30, clock: true },
    { mode: Mode.HEAT, fan: 12, temp: 70, hours: 1, minutes: 15, clock: false },
];
scenarios.push({
    name: 'night program runs through its steps',
    trace: await scenario(() => [
        { send: [C.startProgram(0)] },
        { send: C.writeProgram(0, NIGHT) },
        { send: [C.nameFrame(4, 'Night')] },
        { query: C.Query.PROGRAM_NAMES },
        { query: C.Query.fragment(0, 0) },
        { send: [C.startProgram(0)] },
        { send: [C.ack()] },
        { send: [C.setClock(22, 45)] },
        { send: [C.startProgram(0)] },
        { tick: 90 },
        { skip: 29 },
        { skip: 0.5 },
        { skip: 480 },
        { skip: 75 },
        { skip: 10 },
    ]),
});
// The auto-off timer in a program moves the step's end, and the next step still runs its own length.
scenarios.push({
    name: 'auto off in a program sets the step left',
    trace: await scenario(() => [
        { send: C.writeProgram(1, [
            { mode: Mode.COOL, fan: 9, temp: 44, hours: 0, minutes: 30, clock: false },
            { mode: Mode.HEAT, fan: 12, temp: 70, hours: 0, minutes: 20, clock: false },
            { mode: Mode.DRY, fan: 15, temp: 60, hours: 0, minutes: 10, clock: false },
        ]) },
        { send: [C.nameFrame(5, 'Short')] },
        { send: [C.setClock(22, 0)] },
        { send: [C.startProgram(1)] },
        { send: [C.setRuntime(5)] },
        { tick: 299 },
        { tick: 1 },
        { send: [C.setRuntime(0)] },
        { tick: 1 },
        { send: [C.setRuntime(90)] },
    ]),
});
scenarios.push({
    name: 'manual modes, memories, limits',
    trace: await scenario(() => [
        { send: [C.setMode(Mode.HEAT)] },
        { send: [C.setFan(19)] },
        { send: [C.setTemperature(80)] },
        { send: [C.setRuntime(600)] },
        { send: [C.button(C.Button.TEMP_DOWN_C)] },
        { send: [C.saveMemory(2)] },
        { send: [C.setMode(Mode.DRY)] },
        { send: [C.setTemperature(255)] },
        { send: [C.recallMemory(2)] },
        { send: [C.setMode(Mode.TURBO)] },
        { tick: 30 },
        { skip: 11 },
        { send: [C.recallMemory(1)] },
        { cutOut: 3 },
        { query: C.Query.MEMORY_NAMES },
        { query: C.Query.DEVICE_NAME },
        { query: C.Query.FIRMWARE },
        { query: 2 },
        { send: [Uint8Array.of(0x01)] },
    ]),
});
for (let n = 0; n < 40; n++) {
    scenarios.push({
        name: `random ${n}`,
        trace: await scenario(sim => (function* () {
            for (let i = 0; i < 60; i++) {
                const kind = int(0, 9);
                if (kind < 5) yield { send: randomCommand(sim) };
                else if (kind === 5) yield { query: pick([0, 1, 4, 0x20, 2, ...Array.from({ length: 12 }, (_, k) => 7 + k)]) };
                else if (kind === 6) yield { tick: int(1, 40) };
                else if (kind === 7) yield { skip: pick([int(0, 120), int(0, 900), int(0, 240) + 0.5]) };
                else if (kind === 8) yield { cutOut: chance(0.2) ? int(0, 300) : int(0, 255) };
                else yield { query: C.Query.fragment(int(0, 2), int(0, 3)) };
            }
        })()),
    });
}

writeFileSync(out, JSON.stringify({
    source: htmlPath.replace(/^.*\//, ''), encoders, parsers, heatHours, validations, formatTemp, scenarios,
}) + '\n');
console.log(`wrote ${out}: ${encoders.length} encoder, ${parsers.length} parser, ${validations.length} validation cases, ${scenarios.length} scenarios`);
