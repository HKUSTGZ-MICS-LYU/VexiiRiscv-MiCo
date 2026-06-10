# End-to-End BNCFU KIVI Benchmark Plan

## Scope

This plan benchmarks CCT2, CCT7, and KWT end to end on VexiiMico with:

- 1.58-bit/ternary-oriented model quantization in the MiCo C model flow.
- `keep-last` quantization policy for the final quantizable layer.
- KIVI-style attention selected in C with `-DKIVI_ATTN`.
- BNCFU acceleration enabled through `TARGET=vexii_soc OPT=bncfu`.

The benchmark target is the BNCFU simulation flow driven by
`hw/VexiiMico/tools/bncfu_perf_explore.py` presets. The new e2e runner is
`hw/VexiiMico/tools/bncfu_e2e_kivi_benchmark.py`.

## Questions to Answer

1. Does the BNCFU KIVI path produce valid model outputs for CCT2, CCT7, and KWT?
2. How much end-to-end forward latency is removed by BNCFU KIVI attention versus generic KIVI attention?
3. How much of the speedup comes from attention versus the rest of the model?
4. Which model and token/head shapes expose BNCFU overheads such as packing, Q2T, Q8, DMA load, or synchronous RF latency?

## Benchmark Matrix

### Models

Use the model zoo names below unless a checkpoint map overrides them:

| Plan name | Model zoo name | Dataset macro from codegen | Default checkpoint |
| --- | --- | --- | --- |
| CCT2 | `cct2_cifar10` | `CIFAR10` | `output/ckpt/cct2_cifar10_bitnet.pth`, fallback `output/ckpt/cct2_cifar10.pth` |
| CCT7 | `cct7_cifar10` | `CIFAR10` | `output/ckpt/cct7_cifar10_bitnet.pth`, fallback `output/ckpt/cct7_cifar10.pth` |
| KWT | `kwt` | `SPEECHCOMMANDS_2D` | `output/ckpt/kwt_bitnet.pth`, fallback `output/ckpt/kwt.pth` |

If the final CCT7 target is CIFAR100, run with `--models cct7_cifar100` and
pass `--ckpt cct7_cifar100=...`.

### Quantization and Codegen

Generate each model with:

```bash
python examples/mpq_gen.py <model> \
  --ckpt <checkpoint> \
  --weight-q 2 \
  --act-q 8 \
  --keep-last \
  --output-dir hw/VexiiMico/sw \
  --output-name model
```

Notes:

- `--weight-q 2` follows the existing BitNet/ternary deployment recipe for
  1.58-bit-oriented weights.
- `--keep-last` keeps the final quantizable layer at 8-bit.
- KIVI attention is selected at build time with `-DKIVI_ATTN`; the generated
  model still calls `MiCo_ViT_attention_f32`, and `vit_ops.c` dispatches to
  `MiCo_ViT_kivi_attention_f32` when `KIVI_ATTN` is defined.
- Use `--benchmark-mode` for the standard preset comparison when operator
  buckets are required. The runner treats `Estimated Execution Time` as the
  primary cycle count in this mode.

### Software Variants

| Variant | `OPT` | Extra CFLAGS | Purpose |
| --- | --- | --- | --- |
| `kivi_generic` | empty | `-DKIVI_ATTN -DKIVI_PROFILE_INTERNAL` | Generic C KIVI attention baseline. |
| `kivi_bncfu` | `bncfu` | `-DKIVI_ATTN -DKIVI_PROFILE_INTERNAL` | BNCFU-accelerated KIVI attention. |
| `kivi_bncfu_verify` | `bncfu` | `-DKIVI_ATTN -DKIVI_PROFILE_INTERNAL -DKIVI_BNCFU_INT8_VERIFY` | Correctness gate for BDOT packing and visibility bugs. |

The default comparison mode is `baseline_vs_bncfu`: run `kivi_generic` only on
`baseline_fpu`, and run `kivi_bncfu` only on BNCFU presets. Do not run generic
KIVI on the BNCFU SoC unless explicitly debugging the software path with
`--compare-mode all_selected`.

### Hardware Presets

Start with the existing `standard` preset from `bncfu_perf_explore.py`:

- `bncfu_256_sync`: `VLEN=256`, width `128`, quant width `64`.
- `bncfu_512_sync`: `VLEN=512`, width `256`, quant width `128`.

Both enable Q2T, Q8, pipe mode, and synchronous RF. If smoke validation is
needed first, use `--hardware-preset smoke --variants kivi_bncfu_verify`.

Ablation presets:

- `ablation_large`: baseline and BNCFU256 on the existing FPU/cache SoC shape.
- `ablation_small`: the same ablation matrix on a no-FPU/no-cache minimal core.

Both ablation presets use four fixed paths in `--compare-mode ablation`:

`FP32 attention` here means the generated quantized model calls the normal
`MiCo_ViT_attention_f32` attention kernel instead of KIVI attention; it does
not mean the full model is regenerated as an fp32 model.

| Path | Hardware | Attention | K per-token | Q2T/Q8 |
| --- | --- | --- | --- | --- |
| `baseline_fp32_attn` | baseline | FP32 attention | no | off |
| `bncfu256_noquant_fp32_attn` | BNCFU256 noquant | FP32 attention | no | off |
| `bncfu256_noquant_kivi_attn` | BNCFU256 noquant | KIVI attention | yes | off |
| `bncfu256_quant64_kivi_attn` | BNCFU256 quant64 | KIVI attention | yes | on |

### Build Rules

Always compile SoC software with:

```bash
make -C hw/VexiiMico/sw -f Makefile clean compile \
  MAIN=main \
  TARGET=vexii_soc \
  MARCH=rv32imafc_zifencei \
  SPRAM=1 \
  TEST_NUM=<N> \
  VLEN=<preset vlen> \
  BITNET_QUANT=3
```

For BNCFU variants add:

```bash
OPT=bncfu \
BNCFU_REG_DEPTH=<preset reg_depth> \
BNCFU_Q2T=1 \
BNCFU_Q8=1 \
BNCFU_QUANT_WIDTH=<preset quant_width>
```

`SPRAM=1` is required for realistic generated models and KIVI buffers.

## Metrics

Primary metrics:

- `primary_cycles`: total model cycles. In benchmark mode this is
  `Estimated Execution Time`; otherwise it is `Execution Time`.
- `Accuracy`: printed by `main.c` over `TEST_NUM`.
- `Correct`: number of correct predictions over `TEST_NUM`.
- `speedup_vs_baseline_fpu_kivi`: `baseline_fpu/kivi_generic` cycles divided
  by the BNCFU row cycles for the same model.

Secondary metrics:

- Operator buckets parsed from benchmark mode:
  - `bitops_cycles`: `bitlinear`, `bitconv`, and qmatmul-style kernels.
  - `attn_cycles`: attention/KIVI kernels such as `MiCo_ViT_attention_f32`.
  - `other_cycles`: all remaining kernels.
- `QMatMul Time`, `Quantization Time`, `Im2Col Time`, and `ATTN_TIMER`.
- `KIVI_INTERNAL_PROFILE total=...` for generic KIVI attention.
- `LLAMA_KIVI_BNCFU_PROFILE ...` is LLaMa-specific and not expected for
  CCT/KWT, but the parser keeps profile lines for future reuse.
- `Estimated Execution Time` and `Benchmark Kernel` rows when
  `--benchmark-mode` is enabled.

Correctness gates:

- `kivi_bncfu_verify` must not print `BDOT_MISMATCH` or fail assertion.
- `Predicted Label`/`Correct Label` lines should remain comparable between
  `kivi_generic` and `kivi_bncfu` for the same samples.
- If verify fails with stale packed-buffer symptoms, inspect fence placement
  before interpreting performance numbers.

## Execution Flow

1. Preflight:
   - Confirm checkpoints exist.
   - Confirm `hw/VexiiMico/sw/MiCo-Lib` is present.
   - Confirm RISC-V toolchain and `sbt` are usable.
2. Smoke correctness:
   - Run one model, one sample, `kivi_bncfu_verify`.
   - Use `--dry-run` first to inspect commands.
3. Full e2e run:
   - Generate each model.
   - Build `kivi_generic` for `baseline_fpu`.
   - Build `kivi_bncfu` for BNCFU presets.
   - Simulate each ELF on selected standard hardware presets.
   - Parse logs into CSV/JSON/Markdown.
4. Analysis:
   - Compare `kivi_bncfu` against `kivi_generic`.
   - Compare CCT2 versus CCT7 versus KWT attention profile share.
   - Check if `bncfu_512_sync` reduces cycles enough to justify the larger CFU.
5. Regression record:
   - Archive `manifest.json`, generated model headers/binaries, ELF files,
     build logs, simulation logs, `results.csv`, and `summary.md`.

## Runner Commands

Dry-run command inspection:

```bash
python hw/VexiiMico/tools/bncfu_e2e_kivi_benchmark.py \
  --models cct2,cct7,kwt \
  --hardware-preset standard \
  --test-num 1 \
  --dry-run
```

Smoke verify:

```bash
python hw/VexiiMico/tools/bncfu_e2e_kivi_benchmark.py \
  --models cct2 \
  --hardware-preset smoke \
  --variants kivi_bncfu_verify \
  --test-num 1 \
  --keep-going
```

Full run:

```bash
python hw/VexiiMico/tools/bncfu_e2e_kivi_benchmark.py \
  --models cct2,cct7,kwt \
  --hardware-preset standard \
  --variants kivi_generic,kivi_bncfu \
  --test-num 5 \
  --out-dir hw/VexiiMico/benchmark_results/e2e_kivi_cct_kwt
```

If CCT7 should use CIFAR100:

```bash
python hw/VexiiMico/tools/bncfu_e2e_kivi_benchmark.py \
  --models cct2_cifar10,cct7_cifar100,kwt \
  --ckpt cct7_cifar100=output/ckpt/cct7_cifar100_bitnet.pth
```

Ablation large:

```bash
python hw/VexiiMico/tools/bncfu_e2e_kivi_benchmark.py \
  --models cct2,kwt,cct7 \
  --hardware-preset ablation_large \
  --compare-mode ablation \
  --test-num 1 \
  --num-workers 0 \
  --keep-going \
  --out-dir hw/VexiiMico/benchmark_results/e2e_kivi_ablation_large_cct_kwt
```

Ablation small:

```bash
python hw/VexiiMico/tools/bncfu_e2e_kivi_benchmark.py \
  --models cct2,kwt,cct7 \
  --hardware-preset ablation_small \
  --compare-mode ablation \
  --test-num 1 \
  --num-workers 0 \
  --keep-going \
  --out-dir hw/VexiiMico/benchmark_results/e2e_kivi_ablation_small_cct_kwt
```

## Acceptance Criteria

- Smoke verify passes for at least CCT2 before running the full matrix.
- Every full-run row has a parsed `execution_cycles` or an explicit failure
  reason in `results.csv`.
- For each BNCFU row, `kivi_bncfu` has a computed
  `speedup_vs_baseline_fpu_kivi`.
- The summary identifies any case where BNCFU is slower and points to profile
  evidence such as `bitops_cycles`, `attn_cycles`, `other_cycles`, Q2T/Q8
  overhead, or small head dimension.
