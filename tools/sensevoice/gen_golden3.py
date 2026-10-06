"""确定 Kaldi RFFT 的输出打包方式，并 dump 功率谱基准。

Kotlin 侧不需要复刻 Kaldi 的打包格式（那是 C++ 的实现细节），
只需要自己的 FFT 算出的**功率谱**与基准一致即可。
所以这里先把打包方式验证清楚，再落成「功率谱」这一层中间量。
"""
import json
import os

import kaldi_native_fbank as knf
import numpy as np

OUT = r"f:\上课啦\core\ai\src\test\resources"
N = 512


def main():
    rfft = knf.Rfft(N)
    n = np.arange(N, dtype=np.float32)
    sig = (0.5 * np.sin(2 * np.pi * 5 * n / N) + 0.25 * np.cos(2 * np.pi * 17 * n / N)).astype(np.float32)

    raw = np.array(rfft.compute(sig.tolist()), dtype=np.float64)
    ref = np.fft.rfft(sig.astype(np.float64))  # 257 复数，作为真值

    print(f"kaldi 原始返回 {len(raw)} 个 float")
    print(f"numpy rfft 得到 {len(ref)} 个复数")

    # 候选 A：标准交错 —— 不可能是这个，因为长度 512 少一个
    # 候选 B：kaldi 打包  [re0(DC), re_{N/2}(Nyquist), re1, im1, re2, im2, ...]
    cand_b = np.empty(len(ref), dtype=np.complex128)
    cand_b[0] = complex(raw[0], 0.0)
    cand_b[N // 2] = complex(raw[1], 0.0)
    for k in range(1, N // 2):
        cand_b[k] = complex(raw[2 * k], raw[2 * k + 1])

    err_b = np.max(np.abs(cand_b - ref))
    # 容差用相对量：峰值 128，float32 往返误差在 1e-5 量级
    ok = err_b < 1e-3
    print(f"候选 B（DC/Nyquist 提前）最大误差 = {err_b:.3e}  ->  {'匹配' if ok else '不匹配'}")

    if ok:
        # dump 功率谱（fbank 实际使用的量）
        power = (cand_b.real ** 2 + cand_b.imag ** 2).astype(np.float32)
        with open(os.path.join(OUT, "golden_rfft_power.bin"), "wb") as f:
            power.tofile(f)
        with open(os.path.join(OUT, "golden_rfft_input.bin"), "wb") as f:
            sig.astype("<f4").tofile(f)
        print(f"  写出 golden_rfft_power.bin  shape={power.shape}")
        print(f"  写出 golden_rfft_input.bin  shape={sig.shape}")
        print(f"  功率谱前 5: {power[:5]}")
        print(f"  DC={power[0]:.4f}  Nyquist={power[-1]:.4f}")

        meta_path = os.path.join(OUT, "golden_fbank_params.json")
        meta = json.load(open(meta_path, encoding="utf-8"))
        meta["rfft_pack"] = "kaldi: raw[0]=DC.real, raw[1]=Nyquist.real, raw[2k],raw[2k+1]=bin k 的 re/im (k=1..N/2-1)"
        json.dump(meta, open(meta_path, "w", encoding="utf-8"), indent=2)
    else:
        print("  前 8 个 raw:", raw[:8])
        print("  ref[0..4]:", ref[:5])


if __name__ == "__main__":
    main()
