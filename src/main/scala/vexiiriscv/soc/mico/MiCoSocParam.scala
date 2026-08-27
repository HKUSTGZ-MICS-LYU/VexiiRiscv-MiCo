package vexiiriscv.soc.mico

import spinal.core._
import vexiiriscv.ParamMiCo
import vexiiriscv.soc.micro.SocCtrlParam

import java.io.File

// This class will carry all the parameter of the SoC
class MiCoSocParam {
  var ramBytes = 512 KiB
  var ramElf = Option.empty[File]
  var ramBlackBox = false
  var blackBoxASIC = false
  val vexii = new ParamMiCo()
  val socCtrl = new SocCtrlParam()
  var withSpiFlash = false

  var useMiCoVpu = false
  var MiCoVpuLen = 256
  var MiCoVpuWidth = 256
  var MiCoVpuBusWidth = 64
  var MiCoVpuStress = false
  var MiCoVpuPipe = false
  var useBitNetCfu = false
  var BitNetCfuLen = 256
  var BitNetCfuWidth = 256
  var BitNetCfuBusWidth = 32
  var BitNetCfuRegDepth = 2
  var BitNetCfuQType = "1.5b"
  var BitNetCfuWithQ2 = false
  var BitNetCfuWithQ2T = true
  var BitNetCfuWithQ8 = false
  var BitNetCfuQuantWidth = 0
  var BitNetCfuStress = false
  var BitNetCfuPipe = false
  var BitNetCfuQ8ComparePipe = false
  var BitNetCfuQuantStandard = false
  var BitNetCfuRfSync = true
  var BitNetCfuBurstLoad = false
  var useBitNetCfuV2 = false
  var BitNetCfuV2DotPipeStages = 1
  var BitNetCfuV2QuantPipeStages = 1
  var BitNetCfuV2LoadBufferDepth = 0
  var withL2Cache = false
  var l2Ways = 8
  var l2Bytes = 4096
  var withHub = false
  var withDma = false

  var sparseMem = false
  var sparseMemLat = 4
  var sparseMemDelay = 0.0f

  // Provide some sane default
  // vexii.fetchForkAt = 1
  // vexii.lsuPmaAt = 1
  // vexii.lsuForkAt = 1
  // vexii.relaxedBranch = true
  socCtrl.withJtagTap = false
  legalize()

  // This is a command line parser utility, so you can customize the SoC using command line arguments to feed parameters
  def addOptions(parser: scopt.OptionParser[Unit]): Unit = {
    import parser._
    opt[Int]("ram-bytes") action { (v, c) => ramBytes = v }
    opt[Int]("ram-kbytes") action { (v, c) => ramBytes = v * 1024 }
    opt[String]("ram-elf") action { (v, c) => ramElf = Some(new File(v)) }
    opt[Unit]("ram-blackbox") action { (v, c) => ramBlackBox = true  }
    opt[Unit]("blackbox-all") action { (v, c) => blackBoxASIC = true }
    opt[Boolean]("spi-flash") action { (v, c) => withSpiFlash = v  }
    opt[Unit]("mico-vpu") action { (v, c) => useMiCoVpu = true; vexii.withCfu = true}
    opt[Int]("mico-vpu-len") action { (v, c) => MiCoVpuLen = v }
    opt[Int]("mico-vpu-width") action { (v, c) => MiCoVpuWidth = v }
    opt[Int]("mico-vpu-bus-width") action { (v, c) => MiCoVpuBusWidth = v }
    opt[Unit]("mico-vpu-stress") action { (v, c) => MiCoVpuStress = true }
    opt[Unit]("mico-vpu-pipe") action { (v, c) => MiCoVpuPipe = true }
    opt[Unit]("mico-bitnet-cfu") action { (v, c) => useBitNetCfu = true; vexii.withCfu = true }
    opt[Unit]("mico-bitnet-cfu-v2") action { (v, c) => useBitNetCfuV2 = true; vexii.withCfu = true }
    opt[Int]("bitnet-cfu-len") action { (v, c) => BitNetCfuLen = v }
    opt[Int]("bitnet-cfu-width") action { (v, c) => BitNetCfuWidth = v }
    opt[Int]("bitnet-cfu-bus-width") action { (v, c) => BitNetCfuBusWidth = v }
    opt[Int]("bitnet-cfu-reg-depth") action { (v, c) => BitNetCfuRegDepth = v }
    opt[Int]("bitnet-cfu-weight-banks") action { (v, c) => BitNetCfuRegDepth = v + 1 }
    opt[String]("bitnet-cfu-qtype") action { (v, c) => BitNetCfuQType = v }
    opt[Unit]("bitnet-cfu-with-q2") action { (v, c) => BitNetCfuWithQ2 = true }
    opt[Unit]("bitnet-cfu-with-q2t") action { (v, c) => BitNetCfuWithQ2T = true }
    opt[Unit]("bitnet-cfu-without-q2t") action { (v, c) => BitNetCfuWithQ2T = false }
    opt[Int]("bitnet-cfu-quant-width") action { (v, c) => BitNetCfuQuantWidth = v }
    opt[Unit]("bitnet-cfu-with-q8") action { (v, c) => BitNetCfuWithQ8 = true }
    opt[Unit]("bitnet-cfu-stress") action { (v, c) => BitNetCfuStress = true }
    opt[Unit]("bitnet-cfu-pipe") action { (v, c) => BitNetCfuPipe = true }
    opt[Unit]("bitnet-cfu-q8-compare-pipe") action { (v, c) => BitNetCfuQ8ComparePipe = true }
    opt[Unit]("bitnet-cfu-quant-standard") action { (v, c) => BitNetCfuQuantStandard = true }
    opt[Unit]("bitnet-cfu-rf-sync") action { (v, c) => BitNetCfuRfSync = true }
    opt[Unit]("bitnet-cfu-rf-async") action { (v, c) => BitNetCfuRfSync = false }
    opt[Unit]("bitnet-cfu-burst-load") action { (v, c) => BitNetCfuBurstLoad = true }
    opt[Int]("bitnet-cfu-v2-dot-pipe-stages") action { (v, c) => BitNetCfuV2DotPipeStages = v }
    opt[Int]("bitnet-cfu-v2-quant-pipe-stages") action { (v, c) => BitNetCfuV2QuantPipeStages = v }
    opt[Int]("bitnet-cfu-v2-load-buffer-depth") action { (v, c) => BitNetCfuV2LoadBufferDepth = v }
    opt[Unit]("l2-cache") action { (v, c) => withL2Cache = true; vexii.lsuL1Coherency = true}
    opt[Unit]("with-hub") action { (v, c) => withHub = true; vexii.lsuL1Coherency = true}
    opt[Unit]("with-dma") action { (v, c) => withDma = true }
    opt[Int]("l2-ways") action { (v, c) => l2Ways = v }
    opt[Int]("l2-bytes") action { (v, c) => l2Bytes = v }
    opt[Unit]("sparse-mem") action { (v, c) => sparseMem = true }
    opt[Int]("sparse-mem-lat") action { (v, c) => sparseMemLat = v}
    opt[Double]("sparse-mem-delay") action { (v, c) => sparseMemDelay = v.toFloat }
    socCtrl.addOptions(parser)
    vexii.addOptions(parser)
  }

  // After modifying the attributes of this class, you need to call the legalize function to check / fix it is fine.
  def legalize(): Unit = {
    vexii.privParam.withDebug = socCtrl.withDebug
    if(withL2Cache || withHub){
      // Coherent interconnect front-ends require LSU L1 to emit acquire traffic.
      vexii.lsuL1Coherency = true
      vexii.lsuL1Enable = true
      // In coherent mode, Tilelink C-source IDs are shared with writeback/probe paths.
      // Keep enough source bits by ensuring refill IDs cover writeback IDs.
      vexii.lsuL1RefillCount = vexii.lsuL1RefillCount max vexii.lsuL1WritebackCount
    }
    if(withDma){
      // DMA uses the memory bus and can operate with or without LSU L1 enabled.
      vexii.lsuMemDataWidthMin = vexii.lsuMemDataWidthMin max 32
    }
    if(sparseMem){
      // Sparse memory bus is wired as 64-bit in MiCoSoc; keep LSU memory width aligned.
      vexii.lsuMemDataWidthMin = vexii.lsuMemDataWidthMin max 64
    }
    if(useMiCoVpu){
      vexii.lsuMemDataWidthMin = vexii.lsuMemDataWidthMin max MiCoVpuBusWidth
    }
    if(useBitNetCfu){
      require(!useMiCoVpu && !useBitNetCfuV2, "MiCo VPU, BitNet CFU and BitNet CFU V2 share the single CPU CFU bus; enable only one of them")
      vexii.lsuMemDataWidthMin = vexii.lsuMemDataWidthMin max BitNetCfuBusWidth
    }
    if(useBitNetCfuV2){
      require(!useMiCoVpu && !useBitNetCfu, "MiCo VPU, BitNet CFU and BitNet CFU V2 share the single CPU CFU bus; enable only one of them")
      require(BitNetCfuV2DotPipeStages >= 0, "bitnet-cfu-v2-dot-pipe-stages must be non-negative")
      require(BitNetCfuV2QuantPipeStages >= 0, "bitnet-cfu-v2-quant-pipe-stages must be non-negative")
      require(BitNetCfuV2LoadBufferDepth >= 0, "bitnet-cfu-v2-load-buffer-depth must be non-negative")
      vexii.lsuMemDataWidthMin = vexii.lsuMemDataWidthMin max BitNetCfuBusWidth
    }
  }
}
