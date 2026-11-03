"""Synthesizes the soft focus 'tick' and select 'pop' used for remote navigation."""
import os
import sys
import wave

import numpy as np

RATE = 44100


def write(path, x):
    x = np.clip(x, -1, 1)
    with wave.open(path, 'wb') as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes((x * 32767).astype('<i2').tobytes())


def tick():
    t = np.arange(int(RATE * 0.028)) / RATE
    body = np.sin(2 * np.pi * 2350 * t) * np.exp(-t / 0.0032)
    click = np.random.default_rng(1).normal(0, 1, t.size) * np.exp(-t / 0.0007) * 0.25
    x = (body + click) * 0.55
    x[:16] *= np.linspace(0, 1, 16)
    return x


def pop():
    t = np.arange(int(RATE * 0.07)) / RATE
    f = 760 - 260 * (t / t[-1])
    x = np.sin(2 * np.pi * np.cumsum(f) / RATE) * np.exp(-t / 0.014) * 0.6
    x[:32] *= np.linspace(0, 1, 32)
    return x


raw = os.path.join(sys.argv[1], 'res', 'raw')
write(os.path.join(raw, 'tick.wav'), tick())
write(os.path.join(raw, 'pop.wav'), pop())
print('sounds ok')
