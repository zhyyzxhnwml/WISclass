"""用 numpy 复现 kaldi fbank，把「凭记忆不确定」的细节一次性敲定。

待定项：
  A. 预加重第一个采样点的处理（kaldi 究竟是 frame[0]-=coeff*frame[0] 还是不动）
  B. 预加重与去直流谁先谁后
  C. mel 滤波器组是 256 列还是 257 列
  D. log 的下限值

做法：逐个变体去逼近 kaldi_native_fbank 的真实输出，谁最接近就用谁。
这样 Kotlin 侧照抄确定版本即可，不用靠构建反复试。
"""
import json
import math
import os

import numpy as np

RES = r"f:\上课啦\core\ai\src\test\resources"
SR = 16000
FRAME_LEN = 400
FRAME_SHIFT = 160
PADDED = 512
NUM_MEL = 80
COEFF = 0.97
FLT_EPSILON = np.float64(1.19209290e-07)


def test_signal(n=8320):
    x = np.zeros(n, dtype=np.float64)
    for i in range(n):
        x[i] = (0.5 * math.sin(2 * math.pi * 440.0 * i / SR)
                + 0.3 * math.sin(2 * math.pi * 1150.0 * i / SR)
                + 0.15 * math.sin(2 * math.pi * 3100.0 * i / SR))
    return x


def mel_scale(f):
    return 1127.0 * np.log(1.0 + f / 700.0)


def build_mel(num_cols):
    nyq = 0.5 * SR
    low, high = 20.0, nyq
    fft_bin_width = SR / PADDED
    mel_low, mel_high = mel_scale(low), mel_scale(high)
    delta = (mel_high - mel_low) / (NUM_MEL + 1)
    m = np.zeros((NUM_MEL, num_cols), dtype=np.float64)
    for b in range(NUM_MEL):
        left = mel_low + b * delta
        center = mel_low + (b + 1) * delta
        right = mel_low + (b + 2) * delta
        for i in range(num_cols):
            mel = mel_scale(fft_bin_width * i)
            if left < mel < right:
                m[b, i] = ((mel - left) / (center - left)) if mel <= center \
                    else ((right - mel) / (right - center))
    return m


def hamming():
    a = 2 * math.pi / (FRAME_LEN - 1)
    return np.array([0.54 - 0.46 * math.cos(a * i) for i in range(FRAME_LEN)])


def fbank(sig, preemph_first_self=True, preemph_before_dc=False, num_cols=257, log_floor=FLT_EPSILON):
    win = hamming()
    mel = build_mel(num_cols)
    n_frames = 1 + (len(sig) - FRAME_LEN) // FRAME_SHIFT
    out = np.zeros((n_frames, NUM_MEL), dtype=np.float64)

    for f in range(n_frames):
        start = f * FRAME_SHIFT
        fr = sig[start:start + FRAME_LEN].copy()

        def do_preemph(x):
            if preemph_first_self:
                first = x[0]
                x[1:] -= COEFF * x[:-1]
                x[0] -= COEFF * first
            else:
                x[1:] -= COEFF * x[:-1]
            return x

        if preemph_before_dc:
            fr = do_preemph(fr)
            fr -= fr.mean()
        else:
            fr -= fr.mean()
            fr = do_preemph(fr)

        fr *= win
        buf = np.zeros(PADDED, dtype=np.float64)
        buf[:FRAME_LEN] = fr

        spec = np.fft.rfft(buf)
        power = (spec.real ** 2 + spec.imag ** 2)
        if num_cols == 256:
            power = power[:256]

        energies = mel @ power
        out[f] = np.log(np.maximum(energies, log_floor))
    return out


def main():
    golden = np.fromfile(os.path.join(RES, "golden_fbank.bin"), dtype="<f4").reshape(-1, NUM_MEL)
    # 关键：kaldi 的 fbank 工作在 int16 量纲上。
    # funasr 的 WavFrontend 里有一行 `waveform = waveform * (1 << 15)`，
    # 少了它 CMVN 就工作偏了 ~3.0，识别会悄悄变差（实测均值从 0.08 掉到 -3.0）。
    sig = test_signal() * (1 << 15)
    print(f"golden: {golden.shape}  验证信号帧数应为 {1 + (len(sig)-FRAME_LEN)//FRAME_SHIFT}\n")

    best = None
    for first_self in (True, False):
        for before_dc in (False, True):
            for cols in (257, 256):
                got = fbank(sig, preemph_first_self=first_self,
                            preemph_before_dc=before_dc, num_cols=cols)
                err = np.max(np.abs(got - golden))
                tag = f"preemph_first_self={first_self!s:5} before_dc={before_dc!s:5} cols={cols}"
                print(f"  {tag}  最大误差 = {err:.6e}")
                if best is None or err < best[0]:
                    best = (err, first_self, before_dc, cols)

    err, first_self, before_dc, cols = best
    print(f"\n最接近的组合: preemph_first_self={first_self} before_dc={before_dc} cols={cols}  误差={err:.3e}")

    if err > 1e-3:
        print("!! 误差仍偏大，需要继续排查")
        return

    # 固化结论，供 Kotlin 侧读取
    meta_path = os.path.join(RES, "golden_fbank_params.json")
    meta = json.load(open(meta_path, encoding="utf-8"))
    meta["preemph_first_sample_self"] = first_self
    meta["preemph_before_dc_removal"] = before_dc
    meta["mel_matrix_cols"] = cols
    meta["log_floor"] = float(FLT_EPSILON)
    meta["verified_max_abs_err"] = float(err)
    json.dump(meta, open(meta_path, "w", encoding="utf-8"), indent=2)
    print("已写入 golden_fbank_params.json")

    got = fbank(sig, first_self, before_dc, cols)
    print(f"\n逐帧误差前 5 帧: {np.max(np.abs(got - golden), axis=1)[:5]}")
    print(f"golden[0][:5] = {golden[0][:5]}")
    print(f"got   [0][:5] = {got[0][:5]}")


if __name__ == "__main__":
    main()
