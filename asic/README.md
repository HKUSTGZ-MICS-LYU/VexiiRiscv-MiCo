# MiCoSoc ASIC Flow

This directory provides a SiliconCompiler/OpenROAD flow for the full `MiCoSoc` top. The CPU+CFU option records a hierarchical evaluation scope while keeping the real SoC context and physical top; it does not create a bus stub.

## Environments

- Run the flow and SiliconCompiler/OpenROAD from `silicon`.
- The flow invokes Scala RTL generation through `conda run -n mico_env sbt`.
- `mico_env` must contain the repository SBT build and Java runtime.

## SRAM Policy

`vexiiriscv.asic.Asap7SramBlackboxPolicy` is installed through `SpinalConfig.addStandardMemBlackboxing`. It selects only uninitialized synchronous one-write/one-synchronous-read or single-port read-write memories for the generated `Ram_1wrs` and `Ram_1w_1rs` wrappers. Incompatible memories, including asynchronous-read, true multi-port, and initialized-ROM memories, remain ordinary Spinal memories and are synthesized to flip-flops by Yosys/OpenROAD. ASIC SRAM-enabled BTB presets now select `--btb-single-port-ram` by default so the BTB can use the same single-port SRAM path.

ASIC mode now covers the Fetch L1 data banks and synchronous tag/PLRU memories, the LSU L1 data banks, tags, shared metadata, writeback victim buffer, and the BTB when single-port mode is selected. If `tagsReadAsync` is enabled, the affected tag/metadata memories stay soft. Coherent LSU tag/shared memories stay soft because they have an additional read port. The BTB remains soft only in true dual-port mode; the RAS asynchronous RAM and V2 asynchronous quantBuffer also stay soft.

The supplied ASAP7 SRAM cells have one address pin and one synchronous operation per cycle. The Ram_1w_1rs wrapper therefore requires the Spinal instance to use the same clock and a one-cycle read; it does not claim independent dual-clock or simultaneous independent read/write behavior. The wrapper rejects clock-crossing and other read-latency parameters at elaboration time.

SRAM mapping is classified by write granularity. A byte-enable memory is composed from logical 8-bit lanes; a whole-word memory selects the smallest carrier whose width is at least the full logical word; and a multi-segment mask (such as the 102-bit, two-chunk BTB entry) selects a carrier for each masked segment. The complete physical-view manifest has only 64-bit carriers, so the normal GDS flow uses x64 for full-word/segment units and consumes low bits when the logical unit is narrower. The abstract-only manifest includes all 36 SRAM variants with Liberty, LEF, and Verilog views; it selects x16 for byte lanes, x32 for 22/32-bit whole-word units, and x64 for a 51/52-bit unit. APR loads only the macro variants actually selected from the generated Spinal RAM parameters. MiCoSoc `RamFiber` uses 32-bit words, so `--ram-kbytes N` maps to `N * 1024 / 4` logical words.

Run the checked-in view validation without SiliconCompiler:

```bash
python asic/flow/prepare_views.py --check-only
```

The normal preparation step aliases grouped GDS top cells to the LEF/LIB macro names and checks their dimensions. Abstract-only preparation skips GDS handling, validates all available LEF/LIB/Verilog views, and copies the `coreSite` macro LEFs as `asap7sc7p5t` abstract rows so OpenROAD can place them with the installed ASAP7 technology:

```bash
python asic/flow/prepare_views.py \
  --output /tmp/mico-prepared-views.json \
  --gds-output /tmp/mico-prepared-gds

python asic/flow/prepare_views.py \
  --manifest asic/flow/sram_views_abstract.json \
  --check-only
```

## Minimal SoC Smoke

Use the `minimal` preset to isolate the basic MiCoSoc/VexiiRiscv integration. It passes no VexiiRiscv feature flags: the CPU uses `ParamSimple` defaults (single decoder/lane, no RVC/RVM, no BTB, no L1 cache, and no CFU). The preset defaults to a 1 KiB RAM so synthesis and physical layout remain small; override `--ram-kbytes` when a larger memory is needed. The `minimal-bncfu` preset keeps the same minimal CPU and adds only the original BitNetCfu. Its `256-128-Q64` configuration means `--bitnet-cfu-len 256`, `--bitnet-cfu-width 128`, and `--bitnet-cfu-quant-width 64`; this run also sets the TileLink bus width to 64. It uses the synchronous two-register vector RF default and does not enable Q2T, Q8, caches, or BTB. The example below uses the ASAP7 hard macro backend, which keeps the RAM out of the standard-cell route.

```bash
conda run -n silicon python asic/flow/mico_flow.py \
  --preset minimal --step asic --sram-backend asap7 \
  --clock-period 2000.0 --fast-placement \
  --grt-use-pin-access --run-name minimal-hard
```

For the minimal CPU plus BitNetCfu `256-128-Q64` evaluation:

```bash
conda run -n silicon python asic/flow/mico_flow.py \
  --preset minimal-bncfu --step asic --sram-backend asap7 \
  --ram-kbytes 1 --bitnet-cfu-len 256 --bitnet-cfu-width 128 \
  --bitnet-cfu-bus-width 64 --bitnet-cfu-quant-width 64 \
  --bitnet-cfu-qtype 1.5b --clock-period 2000.0 --fast-placement \
  --grt-use-pin-access --run-name minimal-bncfu-256-128-q64
```

These are basic layout smokes, not the full cache/CFU evaluation. The `base`, `bncfu`, and `bncfu-v2` presets retain their existing feature-rich generator arguments.

## Standalone Yosys Cache Check

To inspect SRAM replacement without SiliconCompiler or OpenROAD, run the standalone generator/Yosys driver. It generates the real cache-enabled, no-CFU `MiCoSoc` RTL, installs the `vexiiriscv.asic.Asap7SramBlackboxPolicy`, adds the checked-in SRAM blackbox declarations, and runs only the checked-in Yosys script. The default keeps BTB enabled and passes `--btb-single-port-ram`, so its SRAM replacement is visible in the macro report; add `--no-btb` for an L1-cache-only comparison.

```bash
python asic/flow/run_yosys_cache_only.py \
  --workdir asic/build/yosys-cache-nocfu \
  --ram-kbytes 8
```

The report is written to `reports/cache_memory_report.json`. It lists logical `Ram_1wrs`/`Ram_1w_1rs` instances, grouped SRAM macro cells, memories present before `memory_map`, and the final register count.

## Synthesis

Soft memory synthesis is useful for a quick area/timing baseline:

```bash
conda run -n silicon python asic/flow/mico_flow.py \
  --preset base --step syn --sram-backend soft \
  --ram-kbytes 512 --run-name base-soft
```

Use `--sram-backend asap7` to generate and instantiate physical SRAM wrappers. The wrapper source and prepared views are placed below the run directory:

```bash
conda run -n silicon python asic/flow/mico_flow.py \
  --preset base --step syn --sram-backend asap7 \
  --ram-kbytes 512 --run-name base-hard
```

## Full ASIC Flow

Run the complete ASAP7 OpenROAD flow with `--step asic`:

```bash
conda run -n silicon python asic/flow/mico_flow.py \
  --preset base --step asic --sram-backend asap7 \
  --ram-kbytes 512 --clock-period 2000.0 \
  --run-name base-asap7
```

Outputs are under `asic/build/<preset>/<run-name>/`, including `metrics.json`, `metrics.summary.txt`, prepared GDS aliases, and the SiliconCompiler build directory. Add `--scope cpu-cfu` when comparing the CPU+CFU hierarchy; the physical top remains `MiCoSoc` so bus and memory context stay real. The ASIC flow omits SiliconCompiler 0.38.5's pre-floorplan `cleanup.clean` node because that node reports an invalid `utilization=-100` metric before floorplanning; synthesis output is passed directly to OpenROAD floorplan.

The macro-placement hook places generated SRAM banks as fixed OpenDB instances on the ASAP7 M3 track grid, then OpenROAD places the standard-cell logic around them. This is required because the supplied SRAM pin phase is incompatible with the snapped standard-cell site phase for `rtl_macro_placer`; it does not alter the SRAM LEF or GDS geometry. ASAP7 standard-cell Liberty uses `1ps`, so `--clock-period 2000.0` denotes a 2ns/500MHz target; the SDC uncertainty and UART delays are also expressed in ps. For a bounded physical smoke, add `--fast-placement`; it skips the optional timing-repair nodes but still runs placement, CTS, routing, and GDS export. For experiments with the smaller abstract SRAM variants, add `--abstract-only`; this selects all 36 abstract macros, prefers `x16` carriers for byte lanes, and forces SiliconCompiler to stop at `route.global`, producing route/timing reports without GDS preparation or stream-out. Add `--abstract-detailed-route` only when the machine has enough memory for detailed routing.

For a memory-bounded APR smoke, `--no-btb` omits only the BTB while retaining the real MiCoSoc/cache hierarchy and hard SRAM macros. ASIC SRAM-enabled runs use the single-port SRAM-compatible mode by default; `bootMemClear` must remain disabled for BTB blackboxing. `--grt-use-pin-access` enables OpenROAD pin-access preparation; `--grt-overflow-iter N` bounds global-route cleanup, and `--drt-end-iteration N` bounds detailed-route optimization. `--fast-placement` selects `--drt-end-iteration 1` unless overridden. `--grt-allow-congestion` is diagnostic-only and must not be treated as signoff. The flow adds the RVT ASAP7 library to stream export so RVT, LVT, SLVT, and SRAM cells all have physical views. The checked physical smoke produced a KLayout GDS export and an antenna report; the available ASAP7 package does not provide a foundry KLayout DRC deck, and the bounded detailed-route probe retained DRT geometry violations, so those artifacts are routing/export evidence rather than clean signoff. Abstract-only results are routing/timing experiments only: their macro LEFs are not backed by GDS in this checkout and must not be treated as final layout.

## Abstract-Only Route Check

Use this mode to compare the full 32 KiB cache-enabled SoC with the smaller SRAM macro widths. It runs the real MiCoSoc through placement, CTS, and global route, then stops before detailed route, antenna repair, metal fill, GDS export, and final view writing:

```bash
conda run -n silicon python asic/flow/mico_flow.py \
  --preset base --step asic --sram-backend asap7 --abstract-only \
  --ram-kbytes 32 --clock-period 2000.0 --fast-placement \
  --grt-use-pin-access --run-name base-abstract-32k
```

The resulting `metrics.json` and SiliconCompiler `route.global` reports are valid for comparing placement/routing pressure and estimated Liberty timing. Add `--abstract-detailed-route` to request detailed-route reports; this can exceed the available memory for a 32 KiB design. Neither mode produces GDS or signoff results.

## BNCFU Presets

A synchronous RF is required for hard SRAM mapping. The default BNCFU configuration uses the synchronous RF; do not pass an asynchronous RF option for an ASIC SRAM run.

```bash
conda run -n silicon python asic/flow/mico_flow.py \
  --preset bncfu --step asic --sram-backend asap7 \
  --ram-kbytes 512 --bitnet-cfu-len 256 \
  --bitnet-cfu-width 128 --bitnet-cfu-reg-depth 5 \
  --bitnet-cfu-bus-width 64 --bitnet-cfu-qtype 1.5b \
  --with-q2t --with-q8 --q8-compare-pipe --bitnet-cfu-pipe \
  --clock-period 2000.0 --run-name bncfu-asap7
```

`--preset bncfu-v2` selects the V2 implementation. Its asynchronous `quantBuffer` remains soft by design; compatible synchronous L1/cache and RF memories use SRAM macros. V2 timing/throughput controls are exposed as `--bitnet-cfu-v2-dot-pipe-stages`, `--bitnet-cfu-v2-quant-pipe-stages`, and `--bitnet-cfu-v2-load-buffer-depth`; Q2T/Q8 use the same `--with-q2t` and `--with-q8` switches.

## Tests

```bash
python asic/tests/test_sram_flow.py
python -m py_compile asic/flow/*.py
```

The behavioral test uses the generated SRAM Verilog model. Synthesis uses the declarations in `asic/rtl/asap7_sram_cells.v`; those behavioral models must not be passed to OpenROAD.
