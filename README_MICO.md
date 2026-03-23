# VexiiRiscv + MiCo

VexiiRiscv-MiCo is a mixed-precision computing extension plugin for VexiiRiscv.

You can find the MiCo plugin in scala class `vexiiriscv.execute.MiCoPlugin`.

## MiCo Plugin

The MiCo plugin provides 10 custom insturctions, focusing on signed dot product operations between two 32/64-bit vectors. Each of the packed vectors can contain INT8/INT4/INT2/INT1 data.

### Usage

To add the `MiCoPlugin` into `Param.scala`, you need to find the lines about the `lane0`, and add one more line for `MiCoPlugin`:
```scala
val early0 = new LaneLayer("early0", lane0, priority = 0)
plugins += lane0
plugins += new SrcPlugin(early0, executeAt = 0, relaxedRs = relaxedSrc)
plugins += new MiCoPlugin(early0) // Add MiCoPlugin Here!
plugins += new IntAluPlugin(early0, formatAt = 0)
plugins += shifter(early0, formatAt = relaxedShift.toInt)
plugins += new IntFormatPlugin(lane0)
plugins += new BranchPlugin(layer=early0, aluAt=0, jumpAt=relaxedBranchtoInt, wbAt=0)
```

Then you can generate/simulate VexiiRiscv with MiCo Plugin, check the VexiiRiscv guides below.

Due to the custom instructions added, please turn off RVLS when simulating (`--no-rvls-check`).