// Multiplayer run: drives the dedicated server over RCON while two dev clients (client/MultiplayerObserver) play.
// Usage (each in its own terminal, or in the background), from the project root:
//   ./gradlew runServer -PmpServer
//   ./gradlew runClient -PmpUser=PlayerA
//   ./gradlew runClient -PmpUser=PlayerB
//   node tools/mp_test.mjs [zoneX zoneY zoneZ]
// Prints every command and reply plus PASS/FAIL checks; screenshots land in run_mp/<player>/screenshots.
import fs from 'node:fs';
import net from 'node:net';

const props = Object.fromEntries(fs.readFileSync('run_mp/server/server.properties', 'utf8')
  .split(/\r?\n/).filter(l => l.includes('=')).map(l => [l.slice(0, l.indexOf('=')), l.slice(l.indexOf('=') + 1)]));
const [zx, zy, zz] = process.argv.slice(2).map(Number).concat([-27, -60, -9]).slice(0, 3);
const A = 'PlayerA', B = 'PlayerB';
const sleep = ms => new Promise(r => setTimeout(r, ms));

// ---- minimal RCON client (id, type, payload, two NULs) ----
let sock, buffer = Buffer.alloc(0), nextId = 1;
const waiting = new Map();
function packet(id, type, body) {
  const b = Buffer.from(body, 'utf8');
  const p = Buffer.alloc(14 + b.length);
  p.writeInt32LE(10 + b.length, 0); p.writeInt32LE(id, 4); p.writeInt32LE(type, 8); b.copy(p, 12);
  return p;
}
function onData(d) {
  buffer = Buffer.concat([buffer, d]);
  while (buffer.length >= 4 && buffer.length >= buffer.readInt32LE(0) + 4) {
    const len = buffer.readInt32LE(0), id = buffer.readInt32LE(4);
    const body = buffer.toString('utf8', 12, 4 + len - 2);
    buffer = buffer.subarray(4 + len);
    const w = waiting.get(id);
    if (w) { waiting.delete(id); w(body); }
  }
}
function send(type, body) {
  const id = nextId++;
  return new Promise((resolve, reject) => {
    waiting.set(id, resolve);
    sock.write(packet(id, type, body));
    setTimeout(() => { if (waiting.delete(id)) reject(new Error('RCON timeout: ' + body)); }, 10000);
  });
}
async function connect() {
  for (let i = 0; i < 120; i++) {
    try {
      sock = await new Promise((res, rej) => {
        const s = net.connect(+props['rcon.port'], '127.0.0.1', () => res(s));
        s.once('error', rej);
      });
      sock.on('data', onData);
      await send(3, props['rcon.password']);
      return;
    } catch { await sleep(2000); }
  }
  throw new Error('server never opened RCON');
}
async function cmd(c) {
  const reply = (await send(2, c)).replace(/§./g, '');
  console.log(`> ${c}\n  ${reply.trim() || '(no reply)'}`);
  return reply;
}

// ---- checks ----
const results = [];
function check(what, ok, detail = '') {
  results.push([ok, what]);
  console.log(`${ok ? 'PASS' : 'FAIL'} ${what}${detail ? '  (' + detail + ')' : ''}`);
}
/** Flashlights a player carries anywhere, counted from their saved NBT. */
async function flashlights(p) {
  const all = await cmd(`data get entity ${p} Inventory`);
  return (all.match(/halloweenevent:flashlight/g) || []).length;
}
async function flashlightOn(p) {
  const off = await cmd(`data get entity ${p} Inventory[{Slot:-106b}]`);
  return /On: ?1b/.test(off);
}

// ---- the run ----
await connect();
console.log('RCON connected');
let online = '';
for (let i = 0; i < 150; i++) {
  online = await cmd('list');
  if (online.includes(A) && online.includes(B)) break;
  await sleep(2000);
}
check('both clients joined', online.includes(A) && online.includes(B), online.trim());

for (const c of ['time set 18000', 'gamerule doDaylightCycle false', 'weather clear 100000',
  `gamemode survival ${A}`, `gamemode survival ${B}`,
  `effect give ${A} minecraft:resistance 1000000 4 true`, `effect give ${B} minecraft:resistance 1000000 4 true`]) {
  await cmd(c);
}
const status = await cmd('halloween status');
if (!/กำลังดำเนินอยู่|running/i.test(status)) await cmd('halloween start');

// Outside first: nobody holds a flashlight.
await cmd(`tp ${A} ${zx - 40} ${zy} ${zz - 3}`);
await cmd(`tp ${B} ${zx - 40} ${zy} ${zz + 4}`);
await sleep(4000);
check('outside the zone: no flashlight (A)', await flashlights(A) === 0);

// Into the zone, 7 blocks apart: each gets one in the off hand; the observers switch them on and face each other.
await cmd(`tp ${A} ${zx} ${zy} ${zz - 3}`);
await cmd(`tp ${B} ${zx} ${zy} ${zz + 4}`);
await sleep(12000);
check('in the zone: exactly one flashlight (A)', await flashlights(A) === 1);
check('in the zone: exactly one flashlight (B)', await flashlights(B) === 1);
check('A switched it on (right-click from the client)', await flashlightOn(A));
check('B switched it on (right-click from the client)', await flashlightOn(B));
await sleep(6000); // screenshots: each sees the other's beam

// The event kills A's flashlight; A's client relights it (struggle) after ~2.5 s.
await cmd(`halloween test flashlight ${A}`);
await sleep(1000);
check('test kill switched A off', !(await flashlightOn(A)));
await sleep(9000);
check('A relit it after the kill (struggle)', await flashlightOn(A));

// Scares, one after another, with time for screenshots. (The dark-figure grab needs a hand-holding couple, which
// takes a key press on both clients: it is covered by the game tests and runClient -PgrabTest, not here.)
for (const [c, wait] of [
  [`halloween test figure 3 ${B}`, 8000],
  [`execute as ${A} run halloween test eyes`, 7000],
  [`execute as ${B} run halloween test eyes wall`, 7000],
  [`execute as ${A} run halloween test hypno`, 8000],
  [`halloween test chase ${A}`, 13000],
]) {
  const r = await cmd(c);
  check(`scare sent: ${c}`, /ทดสอบ|test/i.test(r) && !/Unknown|Incorrect|ไม่ได้|ต้อง/i.test(r), r.trim());
  await sleep(wait);
}
const grab = await cmd(`halloween test grab ${B}`);
console.log(`SKIP dark-figure grab (needs two players holding hands): ${grab.trim()}`);

// Leaving takes the flashlight; coming back gives a fresh one.
await cmd(`tp ${B} ${zx - 40} ${zy} ${zz}`);
await sleep(3000);
check('B left the zone: flashlight taken', await flashlights(B) === 0);
await cmd(`tp ${B} ${zx} ${zy} ${zz + 4}`);
await sleep(3000);
check('B came back: one flashlight again', await flashlights(B) === 1);

// Stopping the event takes every flashlight away.
await cmd('halloween stop');
await sleep(3000);
check('event stopped: no flashlight (A)', await flashlights(A) === 0);
check('event stopped: no flashlight (B)', await flashlights(B) === 0);
await cmd('halloween status');

const failed = results.filter(r => !r[0]);
console.log(`\n${results.length - failed.length}/${results.length} checks passed`);
await cmd('stop').catch(() => {});
sock.destroy();
process.exit(failed.length ? 1 : 0);
