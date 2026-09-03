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

SRAM mapping is classified by write granularity. ASAP7 byte-enable memories are composed from logical 8-bit lanes. ICS55 macros expose bit-granular `WEB`, so a byte-enable memory prefers one carrier covering the complete logical word and falls back to logical 8-bit lanes when no suitable bit-write macro is available. A whole-word memory selects the smallest carrier whose width is at least the full logical word; and a multi-segment mask (such as the 102-bit, two-chunk BTB entry) selects a carrier for each masked segment. The complete physical-view manifest has only 64-bit carriers, so the normal GDS flow uses x64 for full-word/segment units and consumes low bits when the logical unit is narrower. The abstract-only manifest includes all 36 SRAM variants with Liberty, LEF, and Verilog views; it selects x16 for byte lanes, x32 for 22/32-bit whole-word units, and x64 for a 51/52-bit unit. APR loads only the macro variants actually selected from the generated Spinal RAM parameters. MiCoSoc `RamFiber` uses 32-bit words, so `--ram-kbytes N` maps to `N * 1024 / 4` logical words.

## External Main RAM

Use `--ram-port` (alias `--external-main-ram`) to omit only the internal main `RamFiber` and expose the same TileLink Get/Put slave at the top level. The SoC decode remains mapped at `0x80000000`; the external port uses a RAM-local byte address of `log2(ram-kbytes * 1024)` bits (19 bits for the default 512 KiB aperture). All cache/tag/metadata/BTB/RF memories remain in the generated SoC and continue through the normal SRAM policy. This mode is intended for SoC-only synthesis and integration with a separately implemented SRAM controller, so `--ram-elf` is rejected.

For the feature-rich Large SoC on ICS55:

```bash
conda run -n silicon python asic/flow/mico_flow.py \
  --pdk ics55 --preset base --step syn --sram-backend ics55 \
  --ram-kbytes 512 --ram-port --clock-period 2.0 \
  --run-name base-ics55-external-ram-syn
```

The generated `MiCoSoc.v` exposes `system_ramPort_node_bus_a_*` request signals and `system_ramPort_node_bus_d_*` response signals. `metrics.json` records `ram_port: true`; the actual SRAM instance list then excludes the large main RAM while retaining the other SoC memories.

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

ICS55 macro placement follows SiliconCompiler's native OpenROAD task: the flow leaves `mpl_constraints` unset, so SC's `sc_macro_placement.tcl` invokes OpenROAD `rtl_macro_placer`, using the generated netlist, macro dimensions, core boundary, halo, and connectivity instead of a fixed instance-order packing script. No ICS55 grid legalization is applied yet; the checked-in `macro_placement_ics55.tcl` remains available for a later legalization pass if the native placer produces invalid core7 coordinates or poor pin access. ASAP7 retains its checked-in track-grid `mpl_constraints` workaround because the local SRAM LEF pin phase currently fails inside `rtl_macro_placer`. Use `--macro-halo H` to set the native OpenROAD macro keepout in microns in both directions; it defaults to `1.0`, so larger values such as `--macro-halo 5.0` can be used for pin-access/congestion experiments. ASAP7 standard-cell Liberty uses `1ps`, so `--clock-period 2000.0` denotes a 2ns/500MHz target; the SDC uncertainty and UART delays are also expressed in ps. For a bounded physical smoke, add `--fast-placement`; it skips the optional timing-repair nodes but still runs placement, CTS, routing, and GDS export. For experiments with the smaller abstract SRAM variants, add `--abstract-only`; this selects all 36 abstract macros, prefers `x16` carriers for byte lanes, and forces SiliconCompiler to stop at `route.global`, producing route/timing reports without GDS preparation or stream-out. Add `--abstract-detailed-route` only when the machine has enough memory for detailed routing.

For a memory-bounded APR smoke, `--no-btb` omits only the BTB while retaining the real MiCoSoc/cache hierarchy and hard SRAM macros. ASIC SRAM-enabled runs use the single-port SRAM-compatible mode by default; `bootMemClear` must remain disabled for BTB blackboxing. `--grt-use-pin-access` enables OpenROAD pin-access preparation; `--grt-overflow-iter N` bounds global-route cleanup, and `--drt-end-iteration N` bounds detailed-route optimization. `--fast-placement` selects `--drt-end-iteration 1` unless overridden. `--grt-allow-congestion` is diagnostic-only and must not be treated as signoff. The flow adds the RVT ASAP7 library to stream export so RVT, LVT, SLVT, and SRAM cells all have physical views. The checked physical smoke produced a KLayout GDS export and an antenna report; the available ASAP7 package does not provide a foundry KLayout DRC deck, and the bounded detailed-route probe retained DRT geometry violations, so those artifacts are routing/export evidence rather than clean signoff. Abstract-only results are routing/timing experiments only: their macro LEFs are not backed by GDS in this checkout and must not be treated as final layout.

## ICS55 Flow

The flow also supports the checked-in ICsprout55 collateral through `--pdk ics55`. The default root is `asic/pdk/icsprout55-pdk`; override it with `--ics55-pdk-root` or `ICSPROUT55_PDK_ROOT`. H7CR/RVT is the main library, with H7CL/LVT and H7CH/HVT available for optimization. The adapter uses the ecosystem LEFs (`*_ecos.lef`), M2 GDS views, `core7` rows, MET2-MET5 routing, and the local H7C support-cell lists.

ICS55 Liberty uses nanoseconds. Therefore `--clock-period 2.0` means a 2ns target, and the generated `micosoc.sdc` uses 0.05ns uncertainty and 0.2ns UART delays:

```bash
conda run -n silicon python asic/flow/mico_flow.py \
  --pdk ics55 --preset minimal --step syn --sram-backend ics55 \
  --clock-period 2.0 --run-name minimal-ics55-syn

conda run -n silicon python asic/flow/mico_flow.py \
  --pdk ics55 --preset minimal --step asic --sram-backend ics55 \
  --clock-period 2.0 --fast-placement --run-name minimal-ics55-global
```

For the first ICS55 backend acceptance boundary, the ASIC flow stops at `route.global` by default. Use `--to-step route.global` explicitly when resuming or scripting a run. With `--abstract-only --abstract-detailed-route`, the flow can continue through `route.detailed` and emits routed DEF/ODB and PNG previews without GDS; this is useful for visual layout inspection, but the preview PDK may report detailed-router DRC/missing-route warnings and must not be treated as signoff. The flow removes `floorplan.power_grid` because this local checkout does not provide a verified ICsprout55 PDN recipe, and removes `cts.fillcell` because the available `FILLCAP*` cells do not form a valid arbitrary-width filler set. The generic tapcell node is retained only for row cutting and reports a missing tapcell configuration. RC values used for OpenROAD estimation are provisional and are not extraction/signoff data.

When `--sram-backend ics55` is selected without `--ics55-sram-manifest`, the flow first generates the Spinal RTL, scans every `Ram_1wrs`/`Ram_1w_1rs` instance, and calls `asic/pdk/download_ics55_sram_pdk.py` for the required carrier widths and depths. Requests with the same carrier width are coalesced to one sufficiently deep package; memories deeper than the downloader maximum are banked by the generated wrapper. For ICS55 byte-mask RAMs, the flow prefers one bit-write macro covering the complete logical word (for example, one `256x32 mux8` for a 32-bit byte-masked RAM) and falls back to per-byte carriers when no such macro is available. The current minimal RTL therefore uses `256x8 mux8` and `256x32 mux8` packages rather than assuming one fixed SRAM. The flow reuses verified cache entries, validates the downloaded LEF/Liberty/Verilog views, and writes `ics55_sram_manifest.json` inside the run directory. Synthesis uses the downloaded macro blackboxes and Liberty timing views; APR uses their LEFs. `--ics55-sram-words` and `--ics55-sram-bits` act as optional minimum carrier requirements, while `--ics55-sram-mux` is a preference that can fall back when the requested width is incompatible. The VT, low-power, redundancy, word-write, bus-format, ring, and corner options are passed to every downloader request.

For a manually prepared package, `--ics55-sram-manifest` remains available:

```bash
conda run -n silicon python asic/flow/mico_flow.py \
  --pdk ics55 --preset minimal --step asic --sram-backend ics55 \
  --ics55-sram-manifest /path/to/ics55_sram_manifest.json \
  --clock-period 2.0 --fast-placement --to-step route.global \
  --run-name minimal-ics55-sram-global
```

The manifest must identify each macro's Verilog, LEF, Liberty, and (for physical preparation) GDS view, dimensions, address/data ports, and GDS source cell. It may set `physical_views: false` for an abstract route experiment. Full `write.gds`/`write.views` is rejected until a verified KLayout technology/layer-map setup is added; no IO/padframe, PDN, DRC/LVS, or signoff RC flow is claimed by this integration.

`--sram-backend ics55` is only valid with `--pdk ics55`, and `--sram-backend asap7` only with `--pdk asap7`. The existing ASAP7 commands and their picosecond timing units remain unchanged.

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
python -m unittest discover -s asic/tests -q
python -m py_compile asic/flow/*.py
```

The behavioral test uses the generated SRAM Verilog model. Synthesis uses the declarations in `asic/rtl/asap7_sram_cells.v`; those behavioral models must not be passed to OpenROAD.
