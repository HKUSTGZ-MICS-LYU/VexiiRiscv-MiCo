# LLaMa KIVI BNCFU Attention Operator Optimization Plan

## Summary

Target: make LLaMa KIVI BNCFU attention faster than FP32 KV attention for
`seq=64`, `group_size=32`, and `mid` case.

Acceptance:

- Standalone KV test: 3M shape, `seq=64`, `head_size=32`, `pos=47`.
  KIVI BNCFU `ATTN_TIMER` must be lower than FP32 MHA `ATTN_TIMER`.
- E2E perf-est: `llama3m_w2a8`, `ablation_large`, `--case mid`,
  `--prefill-len 64`, `--decode-context-len 64`. The KIVI BNCFU attention
  bucket should not be slower than the noquant FP32-KV attention bucket.
- Correctness: no `MISMATCH`, `BDOT_MISMATCH`, or assertion failure.

## Baseline Finding

Standalone `seq=128` tests showed KIVI was slower than FP32 attention even on
BNCFU. For 3M shape, BNCFU FP32 MHA attention took about `78.5M` cycles, while
KIVI BNCFU attention took about `91.1M` cycles. The hot path still repacked K
and V into BNCFU-friendly chunks inside every attention call.

For `seq=64` mid, `pos=47`: one completed group is quantized history and 16
tokens remain in the current FP32 group. The first optimization target is the
completed-group path.

## Key Changes

- Add BNCFU-specific packed KV layout buffers for completed KIVI groups.
- Keep the existing q2t KV cache and scales for generic path and verification.
- Move K/V BNCFU layout packing from attention hot loop into group packing.
- Add BNCFU-specific attention entry that consumes the prepacked layout.
- Keep generic host KIVI API behavior unchanged.

## Test Plan

- Build standalone host sanity for existing generic path.
- Build BNCFU standalone KV test with:
  `LLAMA_ATT_SEQ=64`, `LLAMA_ATT_HEADS=8`, `LLAMA_ATT_KV_HEADS=8`,
  `LLAMA_ATT_HEAD_SIZE=32`, `SPRAM=1`, `OPT=bncfu`,
  `MARCH=rv32imafc_zicsr`, `VLEN=256`, `BNCFU_QUANT_WIDTH=64`.
- Run BNCFU standalone test without `KIVI_PROFILE_INTERNAL` for timing.
- Run `kivi_bncfu_verify` only as a correctness gate because per-token profile
  output distorts simulation wall time.
- Run e2e perf-est after standalone speed passes.

## Assumptions

- `group_size=32` remains fixed for this optimization.
- First pass targets `llama3m_w2a8` / `head_size=32`.
- The current FP32 group path remains unchanged in v1.
- Further speedups may require scratch-buffer lifetime changes or current-group
  optimization if prepacking alone is insufficient.
