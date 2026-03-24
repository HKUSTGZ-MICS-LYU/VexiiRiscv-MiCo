
## Generate MiCo SoC with BNRV support

You will need to add BNRV related parameters:
+ `--bitnet`: Enable BNRV Plugin
+ `--bitnet-version <4,8,16,32>`: Select BNRV Version/Buffer Widths (4 for no buffer). 
+ `--bitnet-qtype <1b,1.5b,2b>`: Select BNRV Datatype (1-bit, 1.58-bit, 2-bit).


## Compile Workloads on BNRV Extended SoC

> [!NOTE] Modify `MiCo-Lib/matmul_test.h` for MatMul shape.

Running BitNet MatMul test:
```shell
make -C sw recompile MAIN=tests/bnmatmul_test TARGET=vexii_soc MARCH=rv32imafc OPT=bnrv SPRAM=1 BITNET_QUANT=3 USE_SIMD=32
```

```shell
make -C sw recompile MAIN=tests/bnmatmul_test TARGET=vexii_soc MARCH=rv32imafc OPT=bnrv SPRAM=1 BITNET_QUANT=2 USE_SIMD=32
```

Running BitNet LLaMa2 Benchmarking:
```shell
make -C sw recompile MAIN=llama2_benchmark LLAMA2_BIN=llama2/llama_3M_W1A8_bench.bin TARGET=vexii_soc MARCH=rv32imafc OPT=bnrv SPRAM=1 BITNET_QUANT=2 USE_SIMD=32
```

> [!NOTE] Make sure the `BITNET_QUANT` and `USE_SIMD` match your BitNet parameters for SoC hardware. `BITNET_QUANT=2` for `--bitnet-qtype 1b`, `3` for `1.5b`, `4` for `2b`.

## Simulate Workloads on SoC

Refer to `sim_soc.sh`