"""Checks a Music2NES ROM in a 6502 emulator (needs py65):

    python3 emu.py song.nes

Verifies sample timing, the decoded audio stream, that every PPU write lands
in vblank with the scroll restored, the on-screen timer/progress bar, A-button
pause/resume and the rewind at the end of the song.
"""
import sys
import numpy as np
from py65.devices.mpu6502 import MPU

DELTAS = np.array([-42, -28, -18, -11, -6, -3, -1, 0, 1, 3, 6, 11, 18, 28, 42, 60])
PAGES_PER_BANK, TABLES = 62, 0x3E00
FRAME, VBLANK = 29780.67, 2273.0          # NTSC CPU cycles per frame / in vblank
CHARSET = " ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-:/.,'!?&()"

data = open(sys.argv[1], 'rb').read()
NB = data[4]
rom = data[16:]
banks = [rom[i * 16384:(i + 1) * 16384] for i in range(NB)]
stream = b''.join(b[:PAGES_PER_BANK * 256] for b in banks[:-1])


class Machine:
    def __init__(self):
        self.ram = bytearray(0x800)
        self.bank = 0
        self.writes = []                  # (cycle, $4011 value)
        self.pad = 0
        self.shift = 0
        self.nt = bytearray(0x800)
        self.t = self.v = 0
        self.w = 0
        self.rendering = False
        self.vbl_seen = -1                # last frame whose flag was consumed
        self.bad_writes = 0
        self.ppu_writes = 0
        self.bad_scroll = 0
        self.checked_frame = -1
        self.t0 = None

    def now(self):
        return mpu.processorCycles

    def frame_at(self, c):
        return int((c - self.t0) // FRAME) if self.t0 is not None else -1

    def in_vblank(self, c):
        return self.t0 is not None and (c - self.t0) % FRAME < VBLANK

    def check_scroll(self):
        # once each vblank has ended, the scroll registers must be back at 0
        if not self.rendering:
            return
        c = self.now()
        f = self.frame_at(c)
        if (c - self.t0) % FRAME >= VBLANK and f > self.checked_frame:
            self.checked_frame = f
            if self.t != 0:
                self.bad_scroll += 1

    def __getitem__(self, a):
        if isinstance(a, slice):
            return [self[i] for i in range(*a.indices(0x10000))]
        if a < 0x2000:
            return self.ram[a & 0x7FF]
        if 0x2000 <= a < 0x4000 and (a & 7) == 2:
            c = self.now()
            if self.t0 is None:
                self.t0 = c                   # first poll: call it the start of vblank
            self.w = 0
            f = self.frame_at(c)
            if self.in_vblank(c) and f != self.vbl_seen:
                self.vbl_seen = f
                return 0x80
            return 0
        if a == 0x4016:
            v = (self.pad >> self.shift) & 1
            self.shift += 1
            return v
        if a >= 0xC000:
            return banks[NB - 1][a - 0xC000]
        if a >= 0x8000:
            return banks[self.bank][a - 0x8000]
        return 0

    def __setitem__(self, a, val):
        if a < 0x2000:
            self.ram[a & 0x7FF] = val
        elif a < 0x4000:
            r = a & 7
            if r == 2:
                return                        # read-only; the player's idle no-op
            if self.rendering:
                self.ppu_writes += 1
                if not self.in_vblank(self.now()):
                    self.bad_writes += 1
            if r == 0:
                self.t = (self.t & ~0x0C00) | ((val & 3) << 10)
            elif r == 1:
                self.rendering = bool(val & 0x08)
            elif r == 5:
                if self.w == 0:
                    self.t = (self.t & ~0x1F) | (val >> 3)
                else:
                    self.t = (self.t & ~0x73E0) | ((val & 7) << 12) | ((val >> 3) << 5)
                self.w ^= 1
            elif r == 6:
                if self.w == 0:
                    self.t = (self.t & 0x00FF) | ((val & 0x3F) << 8)
                else:
                    self.t = (self.t & 0xFF00) | val
                    self.v = self.t
                self.w ^= 1
            elif r == 7:
                if 0x2000 <= self.v < 0x2800:
                    self.nt[self.v - 0x2000] = val
                self.v = (self.v + 1) & 0x3FFF
        elif a == 0x4011:
            self.writes.append((mpu.processorCycles, val))
        elif a == 0x4016:
            if val & 1:
                self.shift = 0
        elif a >= 0x8000:
            assert val == self[a], "bus conflict"
            self.bank = val


m = Machine()
mpu = MPU(memory=m)
mpu.pc = m[0xFFFC] | m[0xFFFD] << 8


def step(count):
    for _ in range(count):
        mpu.step()
        m.check_scroll()


def run_until(nwrites):
    while len(m.writes) < nwrites:
        mpu.step()
        m.check_scroll()


def row_text(row):
    out = ''
    for col in range(32):
        tid = m.nt[row * 32 + col]
        out += CHARSET[tid] if tid < len(CHARSET) else bar_char(tid)
    return out


def bar_char(tid):
    n = len(CHARSET)
    if tid == n:
        return '.'
    if n < tid < n + 8:
        return str(tid - n)
    return {n + 8: '#', n + 9: '[', n + 10: ']'}.get(tid, '?')


def expected_samples(count):
    b = np.frombuffer(stream[:count // 2 + 1], np.uint8)
    c = np.empty(2 * len(b), int)
    c[0::2] = b >> 4
    c[1::2] = b & 15
    return (64 + np.cumsum(DELTAS[c]))[:count]


ok = True
def check(name, cond, detail=''):
    global ok
    ok &= bool(cond)
    print(f"{'PASS' if cond else 'FAIL'}  {name}{'  ' + detail if detail else ''}")


# 1. play across a bank switch
run_until(PAGES_PER_BANK * 512 + 2000)
cyc = np.array([w[0] for w in m.writes[100:]])
iv = np.diff(cyc)
vals = np.array([w[1] for w in m.writes])
common = np.bincount(iv).argmax()
odd = iv[iv != common]
check("sample spacing", len(odd) <= 1 and (len(odd) == 0 or abs(odd[0] - common) < 40),
      f"{common} cycles ({1789773 / common:.1f} Hz), other gaps: {sorted(set(odd.tolist()))}")
check("decoded audio matches", np.array_equal(vals, expected_samples(len(vals))), f"bank now {m.bank}")
check("PPU writes inside vblank", m.ppu_writes > 0 and m.bad_writes == 0,
      f"{m.ppu_writes} writes, {m.bad_writes} outside")
check("scroll restored every frame", m.bad_scroll == 0, f"{m.checked_frame + 1} frames checked")

# 2. on-screen display for this position
page = len(vals) // 512
print("screen:")
for r in range(8, 25):
    line = row_text(r)
    if line.strip():
        print(f"  {r:2d} |{line}|")

# 3. pause and resume
m.pad = 1
n0 = len(m.writes)
step(30000)
m.pad = 0
step(30000)
paused_writes = len(m.writes) - n0
n1 = len(m.writes)
step(30000)
check("A pauses", len(m.writes) == n1, f"{paused_writes} samples before the pause took effect")
m.pad = 1
step(30000)
exp = expected_samples(len(m.writes))
check("A resumes at the same spot", len(m.writes) > n1 and m.writes[n1][1] == exp[n1])
m.pad = 0
step(5000)

# 4. jump near the end and let it finish
f = banks[NB - 1]
i = f.find(bytes([0xA5, 2, 0xC9]))
end_bank = f[i + 3]
j = f.find(bytes([0xA5, 1, 0xC9]), i)
end_page = f[j + 3]
m.ram[1] = end_page - 1
m.ram[2] = end_bank
m.bank = end_bank
mpu.y = 250
step(60000)
check("rewinds and pauses at the end", m.ram[1] == 0x80 and m.ram[2] == 0 and m.bank == 0)
check("timer/bar reset", '0:00' in row_text(21) and set(row_text(23).strip()) <= set('[.]'),
      row_text(21).strip() + "  " + row_text(23).strip())
print("ALL PASS" if ok else "SOME CHECKS FAILED")
