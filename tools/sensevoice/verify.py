"""SenseVoice ONNX 端侧推理参考实现。

目的不是做产品，而是**给 Kotlin 侧提供可以逐位对齐的参考**：
fbank 参数、LFR 拼帧、CMVN、语言 id、CTC blank —— 这几个只要错一个，
输出就是一堆乱码或者空，而且不报错。所以先在这里跑通并打印中间量。
"""
import json
import sys

import kaldi_native_fbank as knf
import numpy as np
import onnxruntime as ort
import soundfile as sf

D = r"F:\modelcache\sensevoice"


# ---------- 1. fbank（kaldi 风格，参数取自 config.yaml frontend_conf） ----------
def compute_fbank(wav: np.ndarray, snip_edges: bool = True) -> np.ndarray:
    opts = knf.FbankOptions()
    opts.frame_opts.samp_freq = 16000
    opts.frame_opts.dither = 0
    opts.frame_opts.window_type = "hamming"      # config: window: hamming
    opts.frame_opts.frame_length_ms = 25         # frame_length: 25
    opts.frame_opts.frame_shift_ms = 10          # frame_shift: 10
    opts.frame_opts.remove_dc_offset = True
    opts.frame_opts.preemph_coeff = 0.97
    opts.frame_opts.snip_edges = snip_edges
    opts.mel_opts.num_bins = 80                  # n_mels: 80
    opts.mel_opts.low_freq = 20
    opts.mel_opts.high_freq = 0
    opts.use_energy = False
    opts.use_log_fbank = True

    fb = knf.OnlineFbank(opts)
    fb.accept_waveform(16000, (wav * 32768).astype(np.float32))
    fb.input_finished()
    return np.stack([fb.get_frame(i) for i in range(fb.num_frames_ready)])


# ---------- 2. LFR 低帧率拼帧（lfr_m=7, lfr_n=6） ----------
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
            # 最后一帧不足 m 帧，用最后一帧补齐（funasr 的原逻辑）
            num_padding = m - (t - i * n)
            frame = x[i * n:].reshape(-1)
            for _ in range(num_padding):
                frame = np.hstack((frame, x[-1]))
            out.append(frame)
    return np.vstack(out).astype(np.float32)


# ---------- 3. CMVN（Kaldi 文本格式，560 维） ----------
def load_cmvn(path: str):
    lines = open(path, encoding="utf-8").read().splitlines()
    means, vars_ = None, None
    for i, line in enumerate(lines):
        item = line.split()
        if not item:
            continue
        if item[0] == "<AddShift>":
            nxt = lines[i + 1].split()
            means = np.array([float(v) for v in nxt[3:-1]], dtype=np.float64)
        elif item[0] == "<Rescale>":
            nxt = lines[i + 1].split()
            vars_ = np.array([float(v) for v in nxt[3:-1]], dtype=np.float64)
    return means, vars_


def main():
    wav_path = sys.argv[1] if len(sys.argv) > 1 else D + r"\zh.mp3"
    snip = (sys.argv[2] if len(sys.argv) > 2 else "true").lower() != "false"

    wav, sr = sf.read(wav_path, dtype="float32", always_2d=False)
    if wav.ndim > 1:
        wav = wav.mean(axis=1)
    if sr != 16000:
        from math import gcd

        from scipy.signal import resample_poly

        g = gcd(int(sr), 16000)
        wav = resample_poly(wav, 16000 // g, int(sr) // g).astype(np.float32)
        print(f"重采样 {sr} -> 16000")
        sr = 16000
    print(f"音频 {wav_path}: {len(wav)} 采样点 = {len(wav)/16000:.2f}s")

    fbank = compute_fbank(wav, snip_edges=snip)
    print(f"fbank 帧数 = {fbank.shape[0]}, 维度 = {fbank.shape[1]}   (snip_edges={snip})")

    lfr = apply_lfr(fbank)
    print(f"LFR 后 = {lfr.shape}  ← 必须是 (T', 560)")

    means, vars_ = load_cmvn(D + r"\am.mvn")
    print(f"CMVN means/vars 长度 = {len(means)} / {len(vars_)}")
    feats = (lfr.astype(np.float64) + means) * vars_
    feats = feats.astype(np.float32)

    so = ort.SessionOptions()
    so.log_severity_level = 3
    sess = ort.InferenceSession(D + r"\model_quant.onnx", so, providers=["CPUExecutionProvider"])

    tokens = json.load(open(D + r"\tokens.json", encoding="utf-8"))

    print("\n=== 语言/textnorm id 扫描 ===")
    for lang, tnorm in [(0, 0), (0, 14), (0, 15), (3, 14), (3, 15), (3, 0)]:
        feed = {
            "speech": feats[None, :, :],
            "speech_lengths": np.array([feats.shape[0]], dtype=np.int32),
            "language": np.array([lang], dtype=np.int32),
            "textnorm": np.array([tnorm], dtype=np.int32),
        }
        logits, lens = sess.run(["ctc_logits", "encoder_out_lens"], feed)
        ids = logits[0].argmax(axis=1)
        text = decode(ids, tokens)
        print(f"  lang={lang:2} textnorm={tnorm:2} lens={lens[0]:4} → {text}")


def decode(ids: np.ndarray, tokens: list) -> str:
    """贪心 CTC：去重 → 去 blank → 映射词表。

    blank 用 0（词表第 0 项是 <unk>，说明 blank 不在词表里，独占 id 0）。
    """
    out = []
    prev = -1
    for i in ids:
        if i != prev and i != 0:
            if 0 <= i - 0 < len(tokens):
                out.append(tokens[i])
        prev = i
    s = "".join(out)
    return s.replace("▁", " ").strip()


if __name__ == "__main__":
    main()
