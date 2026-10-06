"""dump fbank 各中间环节的真实系数，供 Kotlin 实现分层对齐。

只靠最终 fbank 输出对齐的话，一旦不匹配，无法判断是 FFT 错、窗函数错、
还是 mel 滤波器组错 —— 三方任何一个错都会让结果整体偏移。
把这些系数单独固化成基准，能把定位成本从「猜」降到「看一眼」。
"""
import json
import os

import kaldi_native_fbank as knf
import numpy as np

OUT = r"f:\上课啦\core\ai\src\test\resources"
SR = 16000


def frame_opts() -> knf.FrameExtractionOptions:
    fo = knf.FrameExtractionOptions()
    fo.samp_freq = SR
    fo.dither = 0
    fo.window_type = "hamming"
    fo.frame_length_ms = 25
    fo.frame_shift_ms = 10
    fo.remove_dc_offset = True
    fo.preemph_coeff = 0.97
    fo.snip_edges = True
    return fo


def mel_opts() -> knf.MelBanksOptions:
    mo = knf.MelBanksOptions()
    mo.num_bins = 80
    mo.low_freq = 20
    mo.high_freq = 0
    return mo


def dump(path, arr):
    arr.astype("<f4").tofile(path)
    print(f"  {os.path.basename(path):26} shape={arr.shape}  {os.path.getsize(path)/1024:.1f} KB")


def main():
    os.makedirs(OUT, exist_ok=True)
    fo, mo = frame_opts(), mel_opts()

    # 1) 窗函数（验证 hamming 的具体变体与长度）
    wf = knf.FeatureWindowFunction(fo)
    win = np.array(wf.window, dtype=np.float32)
    print(f"窗函数长度 {len(win)}  首尾 = {win[0]:.8f} / {win[-1]:.8f}")
    dump(os.path.join(OUT, "golden_window.bin"), win)

    # 2) mel 滤波器组矩阵（FFT 点数 → num_bins）
    n_fft = knf.RoundUpToNearestPowerOfTwo(400) if hasattr(knf, "RoundUpToNearestPowerOfTwo") else 512
    banks = knf.MelBanks(mo, fo)
    mat = np.array(banks.get_matrix(), dtype=np.float32)
    print(f"mel 矩阵 {mat.shape}  (n_fft={n_fft})  行和: min={mat.sum(1).min():.6f} max={mat.sum(1).max():.6f}")
    dump(os.path.join(OUT, "golden_mel_matrix.bin"), mat)

    # 3) mel 刻度公式抽样（mel_scale / inverse_mel_scale）
    sweep = [0.0, 20.0, 100.0, 440.0, 1000.0, 2000.0, 4000.0, 8000.0]
    ms = [float(banks.mel_scale(f)) for f in sweep]
    inv = [float(banks.inverse_mel_scale(m)) for m in ms]
    print("mel_scale 抽样:", [f"{v:.4f}" for v in ms])
    with open(os.path.join(OUT, "golden_mel_scale.json"), "w", encoding="utf-8") as f:
        json.dump({"freqs": sweep, "mel": ms, "roundtrip": inv}, f, indent=2)
    print("  golden_mel_scale.json")

    # 4) RFFT 基准（n=512），用一个确定性输入
    rfft = knf.Rfft(n_fft)
    n = np.arange(n_fft, dtype=np.float32)
    sig = (0.5 * np.sin(2 * np.pi * 5 * n / n_fft) + 0.25 * np.cos(2 * np.pi * 17 * n / n_fft)).astype(np.float32)
    res = np.array(rfft.compute(sig.tolist()), dtype=np.float32)
    print(f"RFFT 返回长度 {len(res)}  (期望 514 = 257 复数交错)")
    re = res[0::2]
    im = res[1::2]
    print(f"  拆分后 re/im 各 {len(re)}  (期望 257)")
    dump(os.path.join(OUT, "golden_rfft_real.bin"), re)
    dump(os.path.join(OUT, "golden_rfft_imag.bin"), im)
    dump(os.path.join(OUT, "golden_rfft_input.bin"), sig)

    # 5) 帧切分基准：确认 snip_edges=True 下的帧数与首帧范围
    meta = {
        "samp_freq": SR,
        "frame_length_ms": 25,
        "frame_shift_ms": 10,
        "frame_length_samples": 400,
        "frame_shift_samples": 160,
        "padded_length": n_fft,
        "num_mel_bins": 80,
        "window_len": int(len(win)),
        "mel_matrix_shape": list(mat.shape),
    }
    with open(os.path.join(OUT, "golden_fbank_params.json"), "w", encoding="utf-8") as f:
        json.dump(meta, f, indent=2)
    print("  golden_fbank_params.json")
    print(json.dumps(meta, indent=2))


if __name__ == "__main__":
    main()
