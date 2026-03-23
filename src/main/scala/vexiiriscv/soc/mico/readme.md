# MiCo SoC 

## MiCo Vector Processing Custom Function Unit

### Overview
`VpuCfu` is a mixed-precision vector dot-product accelerator attached to the CFU bus. It keeps a small vector register file (`vregs` entries, default 2) of width `vlen` bits (default 128) and supports configurable element widths for A/B vectors (8/4/2/1 bits) to trade precision for throughput.

### Operations (function_id)
- `0b100` **Load vector**: reads `vlen/xlen` beats from memory via TileLink into vector register `rd` (rs2), starting at `inputs(0)` base address.
- `0b010` **Configure**: sets element widths `qa` (rs1) and `qb` (rs2) and clears accumulators/offsets.
- `0b001` **Dot product**: multiplies vectors `rs1` and `rs2` in chunks of `maclen` bits (default 32) and accumulates the result. Result is returned on `outputs(0)`; multi-chunk ops iterate until `vlen` is consumed.

### Parameters
- `vlen`: vector register width, must be a multiple of `xlen`.
- `xlen`: bus/data width (CFU + TileLink), defines load stride.
- `maclen`: dot-product slice width; a dot uses `maclen` bits from each vector per step.
- `vregs`: number of vector registers.
- `noWaitCompute`: when true, first dot slice can return in the same cycle as the command.

### Data layout and precision
- Register contents are treated as packed signed elements; element width for A is `qa`, for B is `qb`.
- For 1-bit inputs, B is expanded to signed two-bit, then to the selected slice width. Dot product uses signed multiplication and balanced reduction.
- Partial results accumulate into `reslen = CFU_OUTPUT_DATA_W` bits; response ID mirrors the incoming request.
