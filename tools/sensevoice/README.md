# SenseVoice 端侧推理：参考实现与黄金数据

这个目录里的 Python 脚本**不是产品的一部分**，它们的唯一用途是：

> 在没有 Android 库可用的情况下，为 Kotlin 手写的 fbank 提供一份
> **可以逐位对齐的基准**。

## 为什么需要它

`core:ai` 里的 fbank（预加重 / 汉明窗 / FFT / mel 滤波器组 / log）没有任何
可用的 Android 预编译库（sherpa-onnx 的 AAR 在本机网络下拿不到），只能手写。

而 fbank 算错**不会抛异常**：它只会让识别结果变成乱码，或者悄悄变差一点点。
没有基准就只能靠"看着差不多"，而这在 ASR 里是不可接受的。

所以做法是：先在 Python 里用 `kaldi_native_fbank`（与 sherpa-onnx 同源）
跑出一份基准，把每一层的中间量都固化成二进制测试资源，
再让 Kotlin 实现逐层对齐。**哪一层错了立刻能定位**，
而不是对着最终结果猜。

## 脚本说明

| 脚本 | 作用 |
|---|---|
| `verify.py` | 端到端参考实现：fbank → LFR → CMVN → ONNX 推理 → CTC 解码 |
| `gen_golden.py` | 生成 fbank / LFR / CMVN 的黄金数据（含校验和） |
| `gen_golden2.py` | 单独固化窗函数、mel 滤波器组矩阵、mel 刻度公式 |
| `gen_golden3.py` | 确定 Kaldi RFFT 的输出打包方式，固化功率谱基准 |
| `pin_down.py` | **逐变体逼近，敲定实现细节**（见下） |

## 靠这些脚本敲定的关键细节

这些如果靠"凭记忆写"，每一个都会导致识别退化且不报错：

| 细节 | 结论 | 怎么确定的 |
|---|---|---|
| 采样量纲 | **×32768（int16 量纲）** | 两种量纲各跑一遍，看 `(x+means)*vars` 的均值谁接近 0 |
| 去直流 vs 预加重顺序 | **先去直流，后预加重** | `pin_down.py` 四种组合各跑一遍 |
| 预加重首点 | `x[0] -= 0.97·x[0]`（首点取自身） | 同上，误差 4.9 → 7.9e-05 |
| mel 刻度公式 | `1127·ln(1+f/700)`，不是 HTK 的 `2595·log10` | `mel_scale(1000) = 999.9907` |
| mel 滤波器组 | **不做归一化**（行和 0.64~8.29） | 直接 dump `get_matrix()` |
| 窗长 | **400**（不是 512），加窗后才补零 | dump `FeatureWindowFunction.window` |
| log 下限 | `1.19209290e-07`（`FLT_EPSILON`） | kaldi 源码语义 |
| CTC blank | **id 0**，且词表第 0 项是 `<unk>` | 跑真模型看词表与输出 |
| 语言 / textnorm | **auto=0 / withitn=14** | 扫描 6 组组合，比识别质量 |

## 复现步骤

```powershell
pip install onnxruntime numpy soundfile scipy kaldi-native-fbank

# 从 ModelScope 拉模型（GitHub / HuggingFace 本机不可达）
#   iic/SenseVoiceSmall-onnx → model_quant.onnx / am.mvn / tokens.json
python gen_golden.py      # 基础黄金数据
python gen_golden2.py     # 分层系数
python gen_golden3.py     # RFFT 功率谱
python pin_down.py        # 验证实现细节（会回写 golden_fbank_params.json）
python verify.py zh.mp3   # 端到端验证：应输出「开放时间早上9点至下午5点。」
```

产出的测试资源写入 `core/ai/src/test/resources/`（约 130KB），
Kotlin 侧的 `FbankGoldenTest` / `LfrCmvnGoldenTest` 会逐帧比对。
