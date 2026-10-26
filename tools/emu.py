"""Checks a Music2NES ROM in a 6502 emulator: python3 emu.py song.nes (needs py65)"""
import sys, numpy as np
from py65.devices.mpu6502 import MPU
data = open(sys.argv[1],'rb').read(); NB = data[4]; rom = data[16:]
banks = [rom[i*16384:(i+1)*16384] for i in range(NB)]
class Mem:
    def __init__(s): s.ram=bytearray(0x800); s.bank=0; s.writes=[]; s.cyc=0; s.pad=0; s.strobe=0; s.sh=0
    def __getitem__(s,a):
        if isinstance(a,slice): return [s[i] for i in range(*a.indices(0x10000))]
        if a<0x2000: return s.ram[a&0x7ff]
        if a==0x2002: return 0x80
        if a==0x4016:
            v=(s.pad>>s.sh)&1; s.sh+=1; return v
        if a>=0xC000: return banks[NB-1][a-0xC000]
        if a>=0x8000: return banks[s.bank][a-0x8000]
        return 0
    def __setitem__(s,a,v):
        if a<0x2000: s.ram[a&0x7ff]=v
        elif a==0x4011: s.writes.append((mpu.processorCycles,v))
        elif a==0x4016:
            if v&1: s.sh=0
        elif a>=0x8000:
            assert v==s[a], "bus conflict"
            s.bank=v
m=Mem(); mpu=MPU(memory=m); mpu.pc=m[0xFFFC]|m[0xFFFD]<<8
def run_until(nwrites):
    while len(m.writes)<nwrites: mpu.step()
run_until(34000)
c=np.array([w[0] for w in m.writes]); d=np.diff(c)
print("intervals:", sorted(set(d.tolist())), "bank", m.bank)
vals=np.array([w[1] for w in m.writes],dtype=np.uint8)
D=[-42,-28,-18,-11,-6,-3,-1,0,1,3,6,11,18,28,42,60]
b=np.frombuffer(rom[:len(vals)//2+1],np.uint8); cd=np.empty(2*len(b),int); cd[0::2]=b>>4; cd[1::2]=b&15
exp=(64+np.cumsum(np.array(D)[cd]))[:len(vals)]
print("matches decoded data:", np.array_equal(vals, exp), "range", vals.min(), vals.max())
# press A -> pause
m.pad=1; n=len(m.writes)
for _ in range(20000): mpu.step()
m.pad=0
for _ in range(20000): mpu.step()
print("writes during pause:", len(m.writes)-n-0)
p=len(m.writes)
m.pad=1
for _ in range(20000): mpu.step()
b=np.frombuffer(rom[:p],np.uint8); cd=np.empty(2*len(b),int); cd[0::2]=b>>4; cd[1::2]=b&15; exp_all=64+np.cumsum(np.array(D)[cd])
print("resumed, new writes:", len(m.writes)-p, "continues at right place:", m.writes[p][1]==exp_all[p] if len(m.writes)>p else None)
