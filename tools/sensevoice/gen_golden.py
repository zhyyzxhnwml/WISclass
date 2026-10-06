"""生成 Kotlin 侧 fbank 实现的黄金参考数据。

fbank 是本次端侧 ASR 里**唯一没有现成 Android 库可用、必须自己实现**的部分，
而且它算错不会报错 —— 只会让识别结果变成乱码。所以先在 Python 里
用 kaldi_native_fbank（与 sherpa-onnx 同源）算出基准，逐帧对齐。

测试信号用纯正弦叠加，Kotlin 侧可以用同样的公式精确复现，
不需要把 wav 塞进仓库。
"""
import json
import math
import os
import re

import kaldi_native_fbank as knf
import numpy as np

OUT = r"f:\上课啦\core\ai\src\test\resources"
SR = 16000
N_SAMPLES = 8320  # 0.52s
FREQS = [(440.0, 0.5), (1150.0, 0.3), (3100.0, 0.15)]


def test_signal() -> np.ndarray:
    """确定性测试信号：Kotlin 侧用同一公式生成，不依赖随机数。"""
    x = np.zeros(N_SAMPLES, dtype=np.float64)
    for i in range(N_SAMPLES):
        v = 0.0
        for f, a in FREQS:
            v += a * math.sin(2.0 * math.pi * f * i / SR)
        x[i] = v
    return x


def compute_fbank(wav: np.ndarray) -> np.ndarray:
    opts = knf.FbankOptions()
    opts.frame_opts.samp_freq = SR
    opts.frame_opts.dither = 0
    opts.frame_opts.window_type = "hamming"
    opts.frame_opts.frame_length_ms = 25
    opts.frame_opts.frame_shift_ms = 10
    opts.frame_opts.remove_dc_offset = True
    opts.frame_opts.preemph_coeff = 0.97
    opts.frame_opts.snip_edges = True
    opts.mel_opts.num_bins = 80
    opts.mel_opts.low_freq = 20
    opts.mel_opts.high_freq = 0
    opts.use_energy = False
    opts.use_log_fbank = True

    fb = knf.OnlineFbank(opts)
    fb.accept_waveform(SR, (wav * 32768).astype(np.float32))
    fb.input_finished()
    return np.stack([fb.get_frame(i) for i in range(fb.num_frames_ready)]).astype(np.float32)


def apply_lfr(x: np.ndarray, m: int = 7, n: int = 6) -> np.ndarray:
    t = x.shape[0]
    t_lfr = int(np.ceil(t / n))
    left = np.tile(x[0], ((m - 1) // 2, 1))
    x = np.vstack((left, x))
    t = t + (m - 1) // 2
    out = []
    for i in range(t_lfr):
        if m <= t - i * n:
            out.append(x[i * n: i * n + m].reshape(1, -1))
        else:
            num_padding = m - (t - i * n)
            frame = x[i * n:].reshape(-1)
            for _ in range(num_padding):
                frame = np.hstack((frame, x[-1]))
            out.append(frame)
    return np.vstack(out).astype(np.float32)


def load_cmvn(path: str):
    lines = open(path, encoding="utf-8").read().splitlines()
    means = vars_ = None
    for i, line in enumerate(lines):
        item = line.split()
        if not item:
            continue
        if item[0] == "<AddShift>":
            means = np.array([float(v) for v in lines[i + 1].split()[3:-1]], dtype=np.float64)
        elif item[0] == "<Rescale>":
            vars_ = np.array([float(v) for v in lines[i + 1].split()[3:-1]], dtype=np.float64)
    return means, vars_


def dump(path: str, arr: np.ndarray):
    arr.astype("<f4").tofile(path)
    print(f"  写出 {os.path.basename(path):24} shape={arr.shape}  {os.path.getsize(path)/1024:.1f} KB")


def main():
    os.makedirs(OUT, exist_ok=True)
    wav = test_signal()
    print(f"测试信号: {N_SAMPLES} 采样点, 频率 {FREQS}")

    fbank = compute_fbank(wav)
    print(f"fbank: {fbank.shape}")
    dump(os.path.join(OUT, "golden_fbank.bin"), fbank)

    # 只存前 6 帧的 LFR / CMVN：560 维太宽，全存会让测试资源过大
    lfr = apply_lfr(fbank)
    means, vars_ = load_cmvn(r"F:\modelcache\sensevoice\am.mvn")
    cmvn = ((lfr.astype(np.float64) + means) * vars_).astype(np.float32)
    print(f"LFR: {lfr.shape}   CMVN: {cmvn.shape}")
    dump(os.path.join(OUT, "golden_lfr_head.bin"), lfr[:6])
    dump(os.path.join(OUT, "golden_cmvn_head.bin"), cmvn[:6])

    # 完整 LFR 的校验和，用来验证整段拼接逻辑（不占体积）
    checks = {
        "samples": N_SAMPLES,
        "sr": SR,
        "freqs": FREQS,
        "fbank_frames": int(fbank.shape[0]),
        "fbank_dim": int(fbank.shape[1]),
        "lfr_frames": int(lfr.shape[0]),
        "lfr_dim": int(lfr.shape[1]),
        "fbank_sum": float(np.sum(fbank, dtype=np.float64)),
        "fbank_abs_sum": float(np.sum(np.abs(fbank), dtype=np.float64)),
        "lfr_sum": float(np.sum(lfr, dtype=np.float64)),
        "cmvn_sum": float(np.sum(cmvn, dtype=np.float64)),
        "cmvn_head6_sum": float(np.sum(cmvn[:6], dtype=np.float64)),
        "fbank_frame0_sum": float(np.sum(fbank[0], dtype=np.float64)),
        "fbank_frame0_mean": float(np.mean(fbank[0], dtype=np.float64)),
    }
    with open(os.path.join(OUT, "golden_meta.json"), "w", encoding="utf-8") as f:
        json.dump(checks, f, indent=2)
    print("  写出 golden_meta.json")
    for k, v in checks.items():
        print(f"    {k} = {v}")

    # am.mvn 也要进测试资源：CMVN 解析器要能被单测覆盖
    src = r"F:\modelcache\sensevoice\am.mvn"
    dst = os.path.join(OUT, "am.mvn")
    with open(src, encoding="utf-8") as fi, open(dst, "w", encoding="utf-8") as fo:
        fo.write(fi.read())
    print(f"  写出 am.mvn ({os.path.getsize(dst)} bytes)")

    # 顺带看看 kaldi 有没有暴露 mel 滤波器组，方便调试
    print("\nknf 暴露的类:", [n for n in dir(knf) if not n.startswith("_")])


if __name__ == "__main__":
    main()
