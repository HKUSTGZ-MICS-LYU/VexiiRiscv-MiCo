# BNCFU Normalized Quant PPA Snapshot

This note records the current normalized FP32 quantization lane design and the
latest Vivado/software measurements.

## Design Point

Baseline SoC parameters:

```text
VLEN=256
BDOT_WIDTH=128
BNCFU_REG_DEPTH=5
BNCFU bus width=64
QUANT_WIDTH=128
qtype=1.5b
withQ2T=true
withQ8=true
computePipe=true
q8ComparePipe=true
rfSync=false
```

The compared configurations only differ in the quant lane:

- `q2t_q8_old`: legacy `BitQuantLane`, selected by
  `--bitnet-cfu-quant-standard`.
- `q2t_q8_normalized`: default `BitQuantNormalizedLane`.

Synthesis command:

```bash
python tools/bncfu_space_explore.py \
  --config-json synth_runs/bncfu_quant_normalized_compare/configs.json \
  --out-dir synth_runs/bncfu_quant_normalized_compare \
  --force-gen --force-synth
```

Primary outputs:

- `synth_runs/bncfu_quant_normalized_compare/results.csv`
- `synth_runs/bncfu_quant_normalized_compare/summary.md`
- `synth_runs/bncfu_quant_normalized_compare/rpt/*/timing_summary.rpt`
- `synth_runs/bncfu_quant_normalized_compare/rpt/*/hier_util.rpt`

## Algorithm Summary

For normalized finite FP32 inputs, quantization can avoid FP multiply/divide.
Assume:

```text
absmax.exp >= abs(x).exp
1 <= abs(x).mantissa / absmax.mantissa < 2
```

The Q8 scalar rule is:

```text
q = round(127 * x / absmax)
```

The hardware tests whether a candidate integer level `t` should be kept:

```text
abs(x) / absmax >= (t - 0.5) / 127
abs(x) * 254 >= absmax * (2*t - 1)
```

Under the exponent/mantissa decomposition:

```text
254 * Mx >= (2*t - 1) * Ma << (Ea - Ex)
```

The new lane specializes this:

- `254 * Mx` is a constant expression: `(Mx << 8) - (Mx << 1)`.
- `(2*t - 1) * Ma` is updated incrementally as the SAR level grows.
- The exponent difference selects a small left shift.
- No multiplier primitive is introduced in the BNCFU quant datapath.

Q2T is handled by the same lane interface with `qBits=2`, but it can finish
immediately from the first threshold:

```text
abs(x) >= absmax / 2
```

Q8 uses `qBits=8` and iterates a SAR-style cursor from high bit to low bit.

## Software Validation

Run settings used `SPRAM=1`, `MARCH=rv32imafc_zifencei`,
`BNCFU_Q2T=1`, `BNCFU_Q8=1`, `BNCFU_QUANT_WIDTH=128`, and
the default normalized quant lane. To reproduce the legacy lane, set
`BNCFU_QUANT_STANDARD=1` or pass `--bitnet-cfu-quant-standard`.

Recent normalized-lane simulation results:

| Test | Result | Notable case |
| --- | --- | --- |
| `sw/tests/q2t_quant_test.c` | PASS | `vec-8192`: scalar `396620`, BNCFU `136571`, about `2.90x` |
| `sw/tests/q8_quant_test.c` | PASS | `vec-2048`: scalar `78331`, BNCFU `39629`, about `1.97x` |

The normalized lane is also faster than the previous local snapshot:

| Test | Old BNCFU cycles | Normalized BNCFU cycles | Change |
| --- | ---: | ---: | ---: |
| Q2T `vec-8192` | `138619` | `136571` | about `-1.5%` |
| Q8 `vec-2048` | `45763` | `39629` | about `-13.4%` |

## Vivado PPA

Whole-SoC post-route results:

| Config | LUT | FF | BRAM tile | DSP | Period | Fmax | WNS@4ns | Power |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| old quant | `16415` | `8268` | `141` | `4` | `5.867 ns` | `170.44 MHz` | `-1.867 ns` | `0.695 W` |
| normalized quant | `14844` | `7982` | `141` | `4` | `5.506 ns` | `181.62 MHz` | `-1.506 ns` | `0.705 W` |

Delta:

| Metric | Delta |
| --- | ---: |
| LUT | `-1571`, about `-9.57%` |
| FF | `-286`, about `-3.46%` |
| Period | `-0.361 ns`, about `-6.15%` |
| Fmax | `+11.18 MHz`, about `+6.56%` |
| WNS@4ns | `+0.361 ns` |
| BRAM/DSP | unchanged |

BNCFU hierarchy utilization:

| Config | BNCFU LUT | BNCFU FF | Lane LUT | Lane FF |
| --- | ---: | ---: | ---: | ---: |
| old quant | `5439` | `1318` | about `724-729` | about `182-186` |
| normalized quant | `3887` | `1033` | about `488-509` | `120` |

The new lane reduces BNCFU LUT by `1552`, which accounts for almost all of the
whole-SoC LUT reduction.

## Timing Observations

Old quant worst setup path:

```text
system_bitNetCfu_logic_cfu/compute_stages_1_OPA_reg[37]/C
  -> system_cpu_logic_core/execute_ctrl1_up_early1_SrcPlugin_SRC2_lane1_reg[28]/D
Data Path Delay: 5.767 ns
Logic Levels: 20, including CARRY8=7
```

This path goes through the BNCFU dot partial/result path into CPU execute.

Normalized quant worst setup path:

```text
system_cpu_logic_core/decode_ctrls_1_up_Decode_INSTRUCTION_1_reg[21]/C
  -> system_cpu_logic_core/execute_ctrl1_up_integer_RS2_lane0_reg[12]/D
Data Path Delay: 5.318 ns
Logic Levels: 16
```

After reducing quant area and local pressure, the top critical path moved to a
CPU decode/regfile bypass path. This means further Q8 simplification may still
help area and routing congestion, but the next Fmax bottleneck is no longer the
quant lane itself in this configuration.

Common routing note:

- Both old and normalized runs reported very high fanout on
  `system_bitNetCfu_logic_cfu/vecRegsBank_waddr[2:0]`.
- Vivado did not automatically apply VHFN replication to those nets.
- If RF timing becomes the main target, the vector register write-address fanout
  should be addressed separately from quant arithmetic.

## Current Conclusion

`BitQuantNormalizedLane` is the preferred Q2T/Q8 implementation for the current
BNCFU branch:

- It keeps the no-FP-multiply/no-divider/no-BNCFU-multiplier principle.
- It improves Q8 software latency.
- It substantially reduces BNCFU LUT and FF.
- It improves post-route Fmax, though the 250 MHz target is still not met for
  the full SoC configuration.
