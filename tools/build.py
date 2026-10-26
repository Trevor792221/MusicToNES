#!/usr/bin/env python3
"""Build a 512 KB / 1 MB UxROM (mapper 2) NES ROM that streams audio through $4011.

Audio is stored as 4-bit delta codes (two samples per byte), chosen by a
Viterbi search so the decoded 7-bit output tracks the source as closely as
possible.

Usage: build.py input_audio output.nes [prg_kb]   (512 or 1024, default 1024)
"""
import subprocess, sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from vit import viterbi
import numpy as np

SRC, OUT = sys.argv[1], sys.argv[2]
PRG_KB = int(sys.argv[3]) if len(sys.argv) > 3 else 1024
CPU_HZ = 1789773
PRG_BANKS = PRG_KB // 16             # last bank fixed at $C000, last bank fixed at $C000
AUDIO_BANKS = PRG_BANKS - 1
CAPACITY = AUDIO_BANKS * 16384

# ---------------------------------------------------------------- audio
def decode(rate):
    raw = subprocess.run(
        ["ffmpeg", "-v", "error", "-i", SRC, "-vn", "-ac", "1",
         "-af", f"highpass=f=40,aresample={rate}:resampler=soxr:precision=28",
         "-f", "f32le", "-"], check=True, capture_output=True).stdout
    return np.frombuffer(raw, dtype=np.float32).astype(np.float64)

# trim near-silence (below -50 dB) from both ends
probe = decode(48000)
thr = 10 ** (-50 / 20) * np.max(np.abs(probe))
nz = np.nonzero(np.abs(probe) > thr)[0]
t0, t1 = nz[0] / 48000, (nz[-1] + 1) / 48000
dur = t1 - t0

# delta step for each 4-bit code
DELTAS = [-42, -28, -18, -11, -6, -3, -1, 0, 1, 3, 6, 11, 18, 28, 42, 60]
ZERO = DELTAS.index(0)
ACC0 = 64

# cycles per sample: C = 5N+29 (+2 with optional NOP)
best = None
for n in range(1, 200):
    for extra in (0, 2):
        c = 5 * n + 29 + extra
        if dur * CPU_HZ / c + 512 <= 2 * CAPACITY:
            best = (n, extra, c)
            break
    if best:
        break
N, NOP, C = best
RATE = CPU_HZ / C
print(f"duration {dur:.2f}s, {C} cycles/sample -> {RATE:.1f} Hz")

pcm = decode(round(RATE))
pcm = pcm[int(t0 * round(RATE)):int(t1 * round(RATE)) + 1]
peak = np.percentile(np.abs(pcm), 99.98)
pcm = np.clip(pcm / peak, -1, 1)

# invert the APU's nonlinear DMC DAC so the output is linear
def tnd(d):
    return 159.79 / (22638.0 / np.maximum(d, 1e-9) + 100)
top = tnd(127.0)
want = (pcm + 1) / 2 * top
want = np.clip(want, 1e-6, top)
d = 22638.0 / (159.79 / want - 100)
codes, _ = viterbi(np.clip(d, 0, 127), DELTAS, ACC0)

# pad with "no change" codes to whole pages, high nibble plays first
pages = (len(codes) + 511) // 512
assert pages <= AUDIO_BANKS * 64
padded = np.full(pages * 512, ZERO, dtype=np.uint8)
padded[:len(codes)] = codes
audio = (padded[0::2] << 4) | padded[1::2]
END_BANK, END_PAGE = pages // 64, 0x80 + pages % 64
audio_bytes = audio.tobytes() + bytes(CAPACITY - len(audio))

# ---------------------------------------------------------------- font
GLYPHS = {
    'A': ["01110", "10001", "10001", "11111", "10001", "10001", "10001"],
    'E': ["11111", "10000", "10000", "11110", "10000", "10000", "11111"],
    'H': ["10001", "10001", "10001", "11111", "10001", "10001", "10001"],
    'K': ["10001", "10010", "10100", "11000", "10100", "10010", "10001"],
    'L': ["10000", "10000", "10000", "10000", "10000", "10000", "11111"],
    'M': ["10001", "11011", "10101", "10101", "10001", "10001", "10001"],
    'N': ["10001", "11001", "10101", "10011", "10001", "10001", "10001"],
    'O': ["01110", "10001", "10001", "10001", "10001", "10001", "01110"],
    'P': ["11110", "10001", "10001", "11110", "10000", "10000", "10000"],
    'S': ["01111", "10000", "10000", "01110", "00001", "00001", "11110"],
    'T': ["11111", "00100", "00100", "00100", "00100", "00100", "00100"],
    'U': ["10001", "10001", "10001", "10001", "10001", "10001", "01110"],
    'Y': ["10001", "10001", "01010", "00100", "00100", "00100", "00100"],
    '-': ["00000", "00000", "00000", "11111", "00000", "00000", "00000"],
    ':': ["00000", "00100", "00100", "00000", "00100", "00100", "00000"],
    '/': ["00001", "00001", "00010", "00100", "01000", "10000", "10000"],
}
chars = [' '] + sorted(GLYPHS)
tile_of = {ch: i for i, ch in enumerate(chars)}
chr_data = bytearray()
for ch in chars:
    rows = GLYPHS.get(ch, ["00000"] * 7)
    plane0 = [int(r, 2) << 2 for r in rows] + [0]
    chr_data += bytes(plane0) + bytes(8)
chr_pages = (len(chr_data) + 255) // 256
chr_data += bytes(chr_pages * 256 - len(chr_data))

LINES = [(11, "A-HA"), (13, "TAKE ON ME"), (20, "A: PLAY/PAUSE")]

# ---------------------------------------------------------------- assembler
class Asm:
    def __init__(self, org):
        self.org, self.buf, self.labels, self.fix = org, bytearray(), {}, []
    pc = property(lambda s: s.org + len(s.buf))
    def label(self, name): self.labels[name] = self.pc
    def b(self, *v): self.buf += bytes(v)
    def w(self, op, target):
        self.b(op); self.fix.append(('abs', len(self.buf), target)); self.b(0, 0)
    def br(self, op, target):
        self.b(op); self.fix.append(('rel', len(self.buf), target)); self.b(0)
    def resolve(self):
        for kind, at, t in self.fix:
            v = self.labels[t] if isinstance(t, str) else t
            if kind == 'abs':
                self.buf[at:at + 2] = bytes((v & 0xFF, v >> 8))
            else:
                off = v - (self.org + at + 1)
                assert -128 <= off <= 127, t
                self.buf[at] = off & 0xFF

PTRLO, PTRHI, BANK, PREV, ACC = 0, 1, 2, 3, 4
a = Asm(0xC000)
# bank-select table (bus-conflict safe: write the value back onto itself)
a.label('banktable'); a.b(*range(PRG_BANKS))
while a.pc & 0xFF: a.b(0)
# delta lookup indexed by the whole byte: high nibble, then low nibble
a.label('hitab'); a.b(*[DELTAS[v >> 4] & 0xFF for v in range(256)])
a.label('lotab'); a.b(*[DELTAS[v & 15] & 0xFF for v in range(256)])
a.label('font'); a.buf += chr_data

a.label('reset')
a.b(0x78, 0xD8)                         # sei, cld
a.b(0xA2, 0x40); a.w(0x8E, 0x4017)      # ldx #$40, stx $4017
a.b(0xA2, 0xFF, 0x9A, 0xE8)             # ldx #$ff, txs, inx
a.w(0x8E, 0x2000); a.w(0x8E, 0x2001)    # stx $2000/$2001
a.w(0x8E, 0x4010); a.w(0x8E, 0x4015)    # stx $4010/$4015
a.w(0x2C, 0x2002)                       # bit $2002
for i in range(2):                      # wait two vblanks
    a.label(f'vw{i}'); a.w(0x2C, 0x2002); a.br(0x10, f'vw{i}')

# upload font to CHR-RAM $0000
a.b(0xA9, 0x00); a.w(0x8D, 0x2006); a.w(0x8D, 0x2006)
a.b(0xA9, a.labels['font'] & 0xFF, 0x85, PTRLO)
a.b(0xA9, a.labels['font'] >> 8, 0x85, PTRHI)
a.b(0xA2, chr_pages, 0xA0, 0x00)
a.label('chr'); a.b(0xB1, PTRLO); a.w(0x8D, 0x2007); a.b(0xC8); a.br(0xD0, 'chr')
a.b(0xE6, PTRHI, 0xCA); a.br(0xD0, 'chr')

# palette: black background, white text
a.b(0xA9, 0x3F); a.w(0x8D, 0x2006); a.b(0xA9, 0x00); a.w(0x8D, 0x2006)
for i in range(32):
    a.b(0xA9, 0x30 if i % 4 else 0x0F); a.w(0x8D, 0x2007)

# clear nametable 0 + attributes
a.b(0xA9, 0x20); a.w(0x8D, 0x2006); a.b(0xA9, 0x00); a.w(0x8D, 0x2006)
a.b(0xA0, 0x04, 0xA2, 0x00)
a.label('clr'); a.w(0x8D, 0x2007); a.b(0xCA); a.br(0xD0, 'clr'); a.b(0x88); a.br(0xD0, 'clr')

for row, text in LINES:
    addr = 0x2000 + row * 32 + (32 - len(text)) // 2
    a.b(0xA9, addr >> 8); a.w(0x8D, 0x2006); a.b(0xA9, addr & 0xFF); a.w(0x8D, 0x2006)
    for ch in text:
        a.b(0xA9, tile_of[ch]); a.w(0x8D, 0x2007)

a.label('vw2'); a.w(0x2C, 0x2002); a.br(0x10, 'vw2')
a.b(0xA9, 0x00); a.w(0x8D, 0x2005); a.w(0x8D, 0x2005); a.w(0x8D, 0x2000)
a.b(0xA9, 0x0A); a.w(0x8D, 0x2001)      # show background
a.w(0x20, 'rewind')
a.b(0xA9, 0x00, 0x85, PREV)

# ---- playback loop: 5N+29(+2) cycles per sample, also on page change
def decode_sample(tab):
    # lda (ptr),y ; tax ; lda acc ; clc ; adc tab,x ; sta acc ; sta $4011
    a.b(0xB1, PTRLO, 0xAA, 0xA5, ACC, 0x18); a.w(0x7D, tab)
    a.b(0x85, ACC); a.w(0x8D, 0x4011)
a.label('loop_full'); a.b(0xA2, N)
a.label('loop_dx'); a.label('dl'); a.b(0xCA); a.br(0xD0, 'dl')
if NOP: a.b(0xEA)
decode_sample('hitab')
a.b(0xA2, N + 1)
a.label('dl2'); a.b(0xCA); a.br(0xD0, 'dl2')
if NOP: a.b(0xEA)
decode_sample('lotab')
a.b(0xC8); a.br(0xD0, 'loop_full')       # iny ; bne
a.b(0xE6, PTRHI, 0xA5, PTRHI, 0xC9, 0xC0); a.br(0xD0, 'nobank')
a.b(0xA9, 0x80, 0x85, PTRHI, 0xE6, BANK, 0xA6, BANK)
a.w(0xBD, 'banktable'); a.w(0x9D, 'banktable')
a.label('nobank')
a.b(0xA5, BANK, 0xC9, END_BANK); a.br(0xD0, 'noend')
a.b(0xA5, PTRHI, 0xC9, END_PAGE); a.br(0xD0, 'noend')
a.w(0x20, 'rewind'); a.w(0x4C, 'pause')  # song over: rewind and pause
a.label('noend')
# inline A-button poll (kept inline so the cycle count stays fixed)
a.b(0xA9, 0x01); a.w(0x8D, 0x4016); a.b(0xA9, 0x00); a.w(0x8D, 0x4016)
a.w(0xAD, 0x4016); a.b(0x29, 0x01, 0xAA, 0x45, PREV); a.br(0xF0, 'nochg')
a.b(0x86, PREV, 0x8A); a.br(0xD0, 'pause')
a.label('nochg'); a.b(0xA2, N - 10); a.w(0x4C, 'loop_dx')

# ---- paused: poll once per frame, resume on a fresh press of A
a.label('pause')
a.label('pv'); a.w(0x2C, 0x2002); a.br(0x10, 'pv')
a.b(0xA9, 0x01); a.w(0x8D, 0x4016); a.b(0xA9, 0x00); a.w(0x8D, 0x4016)
a.w(0xAD, 0x4016); a.b(0x29, 0x01, 0xAA, 0x45, PREV); a.br(0xF0, 'pause')
a.b(0x86, PREV, 0x8A); a.br(0xF0, 'pause')
a.w(0x4C, 'loop_full')

a.label('rewind')
a.b(0xA9, 0x00, 0x85, PTRLO, 0x85, BANK, 0xAA); a.w(0x9D, 'banktable')
a.b(0xA9, ACC0, 0x85, ACC)
a.b(0xA9, 0x80, 0x85, PTRHI, 0xA0, 0x00, 0x60)

a.label('irq'); a.b(0x40)
a.resolve()
fixed = bytearray(a.buf) + bytes(0x4000 - 6 - len(a.buf))
assert len(fixed) == 0x4000 - 6, "fixed bank overflow"
for v in ('irq', 'reset', 'irq'):
    fixed += bytes((a.labels[v] & 0xFF, a.labels[v] >> 8))

header = b"NES\x1a" + bytes((PRG_BANKS, 0, 0x21, 0x00)) + bytes(8)
with open(OUT, "wb") as f:
    f.write(header + audio_bytes + fixed)
print(f"wrote {OUT}: end bank {END_BANK} page ${END_PAGE:02X}, N={N}, nop={NOP}")
