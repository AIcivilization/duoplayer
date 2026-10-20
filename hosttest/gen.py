import numpy as np, struct, wave
rng=np.random.default_rng(1)
def wav24(path, rate, x):  # x: (n,2) ints in 24-bit range
    b=bytearray()
    a=x.astype('<i4')
    raw=np.zeros((a.size,3),np.uint8); flat=a.reshape(-1)
    raw[:,0]=flat&255; raw[:,1]=(flat>>8)&255; raw[:,2]=(flat>>16)&255
    with wave.open(path,'wb') as w:
        w.setnchannels(2); w.setsampwidth(3); w.setframerate(rate); w.writeframes(raw.tobytes())
for rate in (96000,192000):
    n=rate*3
    t=np.arange(n)/rate
    s=(0.4*np.sin(2*np.pi*1000*t)*8388607).astype(np.int64)
    noise=rng.integers(-2**20,2**20,size=(n,2))
    x=np.stack([s,s],1)+noise
    x=np.clip(x,-8388608,8388607)
    wav24(f'src{rate}.wav',rate,x); np.save(f'src{rate}.npy',x)
# DSD64 2s, 1kHz sine -6dB, 2nd-order sigma-delta, DSF LSB-first
fs=2822400; n=fs*2
t=np.arange(n)/fs; u=0.5*np.sin(2*np.pi*1000*t)
bits=np.zeros(n,np.uint8); i1=i2=0.0; y=0.0
for k in range(n):
    i1+=u[k]-y; i2+=i1-y
    y=1.0 if i2>=0 else -1.0
    bits[k]=1 if y>0 else 0
bs=4096
nbytes=(n+7)//8
pad=np.zeros(((nbytes+bs-1)//bs)*bs*8,np.uint8); pad[:n]=bits
by=np.packbits(pad.reshape(-1,8)[:,::-1],axis=1).reshape(-1)  # LSB first
blocks=by.reshape(-1,bs)
data=b''.join(blocks[i].tobytes()+blocks[i].tobytes() for i in range(len(blocks)))  # 2ch identical
fmt=b'fmt '+struct.pack('<QIIIIIIQII',52,1,0,2,2,fs,1,n,bs,0)
datac=b'data'+struct.pack('<Q',12+len(data))+data
total=28+len(fmt)+len(datac)
hdr=b'DSD '+struct.pack('<QQQ',28,total,0)
open('sine64.dsf','wb').write(hdr+fmt+datac)
print('ok')
