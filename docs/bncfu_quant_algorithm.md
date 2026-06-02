# BNCFU Quantization Algorithm

This note describes the current quantization datapath in
`src/main/scala/vexiiriscv/soc/mico/BitQuant.scala`, its integration in
`src/main/scala/vexiiriscv/soc/mico/BitNetCfu.scala`, and its software entry
points in `sw/MiCo-Lib/targets/vexii_soc/bncfu/bitnet_quant.c`.

The quant unit currently accelerates two FP32-to-integer conversions:

- Q2T: ternary quantization for KIVI-style KV cache compression.
- Q8: signed INT8 quantization.

Both paths keep the BitNet CFU principle of avoiding floating-point arithmetic
and multipliers inside the CFU datapath.  The CPU still computes `absmax` once
per tensor/vector region.  The CFU receives the raw FP32 bit pattern of that
`absmax`, reads FP32 vector data from the BNCFU vector register file, and
returns packed integer codes.

## Software Semantics

The scalar fallback in `sw/MiCo-Lib/src/mico/quant.c` defines the reference
behavior.

For Q8:

```text
scale = 127.0 / absmax(x)
q[i] = round(x[i] * scale)
return_scale = 1.0 / scale = absmax(x) / 127.0
```

The result is stored as signed INT8.

For Q2T:

```text
scale = 1.0 / absmax(x)
q[i] = clamp(round(x[i] * scale), -1, 1)
return_scale = 1.0 / scale = absmax(x)
```

The result is packed as 2-bit signed codes:

```text
00 = 0
01 = +1
11 = -1
10 = unused by Q2T
```

Four Q2T values are packed into one byte.  Lane 0 uses bits `[1:0]`, lane 1
uses bits `[3:2]`, and so on.

## Software BNCFU Path

The BNCFU target overrides the weak scalar functions when `BNCFU_Q2T` or
`BNCFU_Q8` is enabled.

For Q8, `__FP32toQ8` does:

1. Compute `scale = 127.0 / MiCo_absmax(x, n)` on the CPU.
2. Recover `absmax = 127.0 / scale`, then pass `absmax` as raw FP32 bits.
3. For each loaded VLEN-sized FP32 block:
   - `bncfu_load(0, x + i)` loads FP32 lanes into vector bank 0.
   - `bncfu_q8(absmax_bits, 0, chunk)` converts one `quantWidth` chunk.
   - The 32-bit CFU result is unpacked into output bytes.
4. Remaining scalar tail elements use the fallback `roundf` expression.

For Q2T, `__FP32toQ2T` does:

1. Compute `scale = 1.0 / MiCo_absmax(x, n)` on the CPU.
2. Recover `absmax = 1.0 / scale`, then pass `absmax` as raw FP32 bits.
3. For each VLEN-sized FP32 block:
   - `bncfu_load(0, x + i)` loads FP32 lanes into vector bank 0.
   - `bncfu_q2t(absmax_bits, 0)` converts the whole VLEN block.
   - The packed 2-bit result is copied into the output byte stream.
4. Remaining scalar tail elements use the fallback Q2T expression.

The CPU performs one fence before the large kernel through `bncfu_dma_fence`.
The inner row/vector kernels do not require extra cache flush instructions.

## Q2T Hardware Algorithm

Q2T only needs to distinguish three cases:

```text
q = +1, if x >= +0.5 * absmax
q = -1, if x <= -0.5 * absmax
q =  0, otherwise
```

This is equivalent to `clamp(round(x / absmax), -1, 1)`.

The hardware fast path implements this using only FP32 bit comparison on
magnitudes:

1. Split `absmax` into signless magnitude fields:
   - exponent: `absmax[30:23]`
   - fraction: `absmax[22:0]`
2. Reject invalid scales:
   - `absmax == 0`
   - exponent is `255`, meaning Inf or NaN.
3. Build the FP32 magnitude of `absmax / 2`.
   - For normal values with exponent greater than 1, decrement the exponent.
   - For the exponent-1 boundary, shift the hidden leading bit into the
     subnormal fraction.
   - For subnormal values, shift the fraction right by one.
4. For each FP32 input lane:
   - Ignore the sign bit and compare `abs(x)` with `absmax / 2`.
   - If `abs(x)` is zero or below threshold, emit `00`.
   - If it reaches threshold, use the original sign bit:
     - positive: `01`
     - negative: `11`

This avoids FP division, FP multiply, and integer multiply.  The datapath is a
threshold builder plus magnitude comparators and sign muxes.

## Preferred Q8 Hardware Algorithm: Normalized Lane

Q8 needs to match:

```text
q = round(127 * x / absmax)
```

For a positive magnitude `m = abs(x)`, an output level `t` should be selected
when:

```text
m / absmax >= (t - 0.5) / 127
```

After rearranging:

```text
m * 254 >= absmax * (2 * t - 1)
```

The preferred `BitQuantNormalizedLane` uses this inequality as the keep-test
for candidate level `t`. For finite normalized values, decompose magnitudes
into effective exponent and significand:

```text
abs(x)  = Mx * 2^Ex
absmax  = Ma * 2^Ea
```

Under the common quantization condition `Ea >= Ex`, the comparison becomes:

```text
254 * Mx >= (2 * t - 1) * Ma << (Ea - Ex)
```

This avoids floating-point operations and avoids general multiplication in the
hot datapath:

- `254 * Mx` is hardwired as `(Mx << 8) - (Mx << 1)`.
- The `Ma * level` term is held in a register and updated incrementally as the
  SAR cursor sets bits.
- The exponent difference selects a small left shift before the final integer
  compare.

If `abs(x).exp > absmax.exp`, the lane can keep any nonzero Q8 candidate
directly. If the exponent difference is too large, the result is zero without
entering the multi-cycle Q8 loop.

### Binary Level Construction

The final Q8 magnitude is in `[0, 127]`. The hardware builds it one bit at a
time, from a selected start bit down to bit 0:

```text
level = 0
levelProduct = 0
cursor = startCursor
while cursor != 0:
    trial = level | cursor
    trialProduct = levelProduct + Ma * cursor
    if keep(absmax, x, trialProduct):
        level = trial
        levelProduct = trialProduct
    cursor >>= 1
```

After all bits are processed:

```text
q8_code = sign(x) ? -level : level
```

The negative code is generated as 8-bit two's-complement subtraction
`0 - level`.

## Legacy Generalized Q8 Keep-Test

`BitQuantLane` keeps a generalized symmetric quant path that supports
`qBits=2` and `qBits=8` through the same shift-add keep function:

```text
abs(x) * ((1 << qBits) - 2) >= absmax * (2 * trial - 1)
```

For Q8, this reduces to:

```text
abs(x) * 254 >= absmax * (2 * trial - 1)
```

The legacy path forms both products using shift-add terms controlled by factor
bits. It is still multiplier-free, but it generates more LUT and adder logic
than the normalized lane because the threshold factor is less specialized.

The older explanation is kept below because it is still useful when reading the
legacy lane.

The legacy hardware uses this inequality as the keep-test for candidate level
`t`.
This exactly represents the half-step rounding threshold without floating-point
multiply or divide.

### Legacy Binary Level Construction

The final Q8 magnitude is in `[0, 127]`.  The hardware builds it one bit at a
time, from bit 6 down to bit 0:

```text
level = 0
for bit in 6 downto 0:
    trial = level | (1 << bit)
    if keep(absmax, x, trial):
        level = trial
```

After all bits are processed:

```text
q8_code = sign(x) ? -level : level
```

The negative code is generated as 8-bit two's-complement subtraction
`0 - level`.

### Legacy Keep-Test Datapath

`BitNetQ8Keep(absmax, lane, trial)` performs the comparison:

```text
abs(x) * 254 >= absmax * (2 * trial - 1)
```

The implementation works on FP32 magnitude parts:

- `effectiveExponent`
  - normal FP32: original exponent
  - subnormal FP32: treated with effective exponent 1
- `significand`
  - normal FP32: `1.fraction`
  - subnormal FP32: `0.fraction`

Then it forms two integer products:

```text
scaledMagnitude  = significand(abs(x)) * 254
thresholdProduct = significand(absmax) * (2 * trial - 1)
```

The `* 254` path is implemented as a constant shift-add expression.  The
`*(2 * trial - 1)` path is implemented as shift-add terms controlled by the
bits of the small threshold factor.  No hardware multiplier primitive is
instantiated by this code.

Finally, `fp32ScaledGte` aligns the two products by exponent difference and
compares the aligned integer products.  This gives the result of the scaled
FP32 magnitude comparison without constructing real FP values.

## Shared Quant Lane Integration

Q2T and Q8 share the same `BitQuantLaneIO` array in `BitNetCfu`:

```text
lane count = quantWidth / 32
Q2T: qBits = 2
Q8 : qBits = 8
```

`BitQuantNormalizedLane` is the default implementation. Use
`--bitnet-cfu-quant-standard` only when selecting the legacy `BitQuantLane` for
comparison.

The CFU wrapper keeps per-lane state:

- `absReg`: raw FP32 `absmax` bits.
- `opReg`: current `quantWidth` FP32 chunk from the vector register file.
- `offset`: bit offset inside the VLEN vector.
- `resultReg`: packed 32-bit CFU result.
- `busy/done` state per lane.

Each lane owns its internal Q8 SAR state. The wrapper launches selected lanes
and waits until all selected lanes hold `done=true`. This is important because
Q2T and direct-zero Q8 values can complete immediately, while nonzero Q8 values
need multiple cycles.

The old `BitQuantLane` still optionally uses an internal
`prepare -> compare -> commit` pipeline:

```text
prepare -> compare -> commit
```

With `q8ComparePipe=false`, these stages collapse into one combinational node.
With `q8ComparePipe=true`, `StageLink` registers payloads between stages.

## Q2T Fast Path Versus Shared Q8 Path

There are two possible Q2T implementations:

- `q2tFastPath=true`: direct `abs(x) >= absmax/2` threshold comparison.
- `q2tFastPath=false`: reuse the Q8 keep-test with `trial = 64`.

The direct threshold path is the current preferred implementation. It is cheaper
because Q2T does not need the full Q8 binary search. In the normalized lane,
Q2T completes immediately when the threshold decision is available.

The shared keep-test form is mathematically equivalent:

```text
trial = 64
2 * trial - 1 = 127
abs(x) * 254 >= absmax * 127
abs(x) >= absmax / 2
```

## Legacy Q8 Lane Slicing

`q8LanesPerCycle` controls how many FP32 lanes are processed per Q8 compare
cycle.

For `quantWidth = 128`, there are four FP32 lanes per Q8 chunk:

```text
quantLanes = quantWidth / 32 = 4
```

Possible settings include:

- `0`: interpreted as full width, so all four lanes are processed per cycle.
- `2`: two lanes per cycle, two lane groups per Q8 bit.
- `1`: one lane per cycle, four lane groups per Q8 bit.

This only affects Q8.  With the Q2T fast path enabled, Q2T still processes the
whole `quantWidth` chunk through the threshold comparator path.

The tradeoff is area versus Q8 latency:

- Fewer lanes reduce Q8 compare/pack hardware.
- Fewer lanes multiply the number of cycles needed for each Q8 bit.
- Q2T performance remains unchanged.

Recent measurements for `VLEN=256`, `BDOT_WIDTH=128`, `QUANT_WIDTH=128`, and
`REG_DEPTH=5` showed:

```text
Q8 full:  dut=181685 cycles, 1.81x scalar speedup
Q8 lane2: dut=224693 cycles, 1.46x scalar speedup
Q8 lane1: dut=310709 cycles, 1.06x scalar speedup
Q2T full/lane1: dut=138978 cycles, 2.84x scalar speedup
```

## Instruction Interface

The current custom0 `func3` allocation is:

```text
func3=011: Q2T
func3=101: Q8
func3=100: LOAD
```

Q2T:

```text
rd = bncfu_q2t(absmax_bits=rs1, bank=raw rs2 index)
```

Q8:

```text
rd = bncfu_q8(absmax_bits=rs1, bank=raw rs2 index, chunk=func7)
```

The `chunk` field selects which `quantWidth` segment of the VLEN vector is
converted by Q8.  Q2T returns the packed result for the whole VLEN block by
iterating `quantWidth` chunks internally.

## Current Limits

- `quantWidth` must be a power of two.
- `quantWidth` must divide `vlen`.
- `quantWidth` must be a multiple of 32 bits.
- `quantWidth <= 128`, because Q8 packs up to four INT8 lanes into one 32-bit
  CFU result.
- Q2T requires `2 * (VLEN / 32) <= 32`, so the software header currently
  constrains VLEN for the packed return format.
- Q8 chunk index is encoded in `func7`, and current software macros support a
  fixed range of chunk and bank IDs.

## Accuracy Notes

Q8 compares against exact half-step thresholds in scaled integer form, so it
tracks the scalar `roundf(x * 127 / absmax)` rule for finite nonzero `absmax`.

Q2T fast path compares `abs(x)` to `absmax / 2`, which tracks
`round(x / absmax)` followed by clamp to `[-1, 1]`.

For invalid `absmax` values such as zero, Inf, or NaN, the hardware quantizers
emit zero codes.  The normal software path computes `absmax` from real tensor
data and is expected to pass a positive finite value.

## Latest Normalized-Lane Measurements

Software simulation with `VLEN=256`, `BDOT_WIDTH=128`, `QUANT_WIDTH=128`,
`REG_DEPTH=5`, `SPRAM=1`, `MARCH=rv32imafc_zifencei`, and
the default normalized quant lane:

| Test | Case | Scalar Cycles | BNCFU Cycles | Speedup |
| --- | --- | ---: | ---: | ---: |
| Q2T | vec-8192 | 396,620 | 136,571 | 2.90x |
| Q8 | vec-2048 | 78,331 | 39,629 | 1.97x |

Vivado post-route comparison against the legacy lane at the same SoC point:

| Config | LUT | FF | BRAM tile | DSP | Period | Fmax | WNS@4ns |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| old quant | 16,415 | 8,268 | 141 | 4 | 5.867 ns | 170.44 MHz | -1.867 ns |
| normalized quant | 14,844 | 7,982 | 141 | 4 | 5.506 ns | 181.62 MHz | -1.506 ns |

Detailed PPA notes are in `docs/bncfu_quant_normalized_ppa.md`.
