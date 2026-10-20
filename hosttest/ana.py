import numpy as np
S=2**23
for r in (96000,192000):
    src=np.load(f'src{r}.npy')/S
    for ext in (['wav','flac','aiff'] if r==96000 else ['wav']):
        d=np.fromfile(f'src{r}.{ext}.dec.f64').reshape(-1,2)
        print(f'{ext}{r}: bit-exact={np.array_equal(d,src)}')
def spec_db(x,rate):
    w=np.kaiser(len(x),20); X=np.abs(np.fft.rfft(x*w)); X/=X.max(); f=np.fft.rfftfreq(len(x),1/rate); return f,20*np.log10(X+1e-30)
for name,inR,outR,f0 in [('rs_44100_192000_1000',44100,192000,1000),('rs_44100_192000_19000',44100,192000,19000),('rs_96000_192000_1000',96000,192000,1000),('rs_176400_192000_1000',176400,192000,1000),('rs_352800_192000_1000',352800,192000,1000),('rs_48000_44100_1000',48000,44100,1000)]:
    y=np.fromfile(name+'.f64').reshape(-1,2)[:,0]
    seg=y[outR//4:outR//4+65536*2]
    n=np.arange(len(seg))
    # 最小二乘拟合正弦 -> 残差 (THD+N)
    A=np.stack([np.sin(2*np.pi*f0*n/outR),np.cos(2*np.pi*f0*n/outR),np.ones_like(n,dtype=float)],1)
    c,*_=np.linalg.lstsq(A,seg,rcond=None); res=seg-A@c
    amp=np.hypot(c[0],c[1])
    f,X=spec_db(seg,outR)
    mask=np.abs(f-f0)>200
    print(f'{name}: amp={amp:.6f} (0.5) THD+N={20*np.log10(np.std(res)/(amp/np.sqrt(2))):.1f} dB  worst spur={X[mask].max():.1f} dB @ {f[mask][X[mask].argmax()]:.0f} Hz')
# DSD
d=np.fromfile('sine64.dsf.dec.f64').reshape(-1,2)[:,0]; r=176400
seg=d[r//2:r//2+131072]; n=np.arange(len(seg))
A=np.stack([np.sin(2*np.pi*1000*n/r),np.cos(2*np.pi*1000*n/r)],1); c,*_=np.linalg.lstsq(A,seg,rcond=None)
f,X=spec_db(seg,r); res=seg-A@c
R=np.fft.rfft(res*np.kaiser(len(res),20)); fr=np.fft.rfftfreq(len(res),1/r); P=np.abs(R)**2
inband=np.sqrt(P[fr<20000].sum()/P.sum())*np.std(res)
print(f'DSD64 1kHz: amp={np.hypot(*c):.4f} (expect ~0.5)  in-band(0-20k) noise rel. sine={20*np.log10(inband/(np.hypot(*c)/np.sqrt(2))):.1f} dB (2nd-order modulator test signal)')
# Deck
src=np.load('src192000.npy')/S
a=np.fromfile('deck_onlyA.f64').reshape(-1,2); m=np.fromfile('deck_mixed.f64').reshape(-1,2); fd=np.fromfile('deck_fade.f64').reshape(-1,2)
F=1536
print('deck single 0dB bit-exact after 8ms fade-in:', np.array_equal(a[F:],src[F:len(a)]), ' fade-in monotonic:', bool(np.all(np.diff(np.abs(a[:F,0]/np.where(src[:F,0]==0,1,src[:F,0])))>=-1e-12)))
L=len(a); exp=src[L:L+len(m)]+src[:len(m)]
print('deck mix A+B exact sum after B fade-in:', np.array_equal(m[F:],exp[F:]))
nz=np.nonzero(np.abs(fd[:,0])>0)[0]
print('pause fade-out frames:', nz.max()+1 if len(nz) else 0, '(expect ~1536), then silence')
