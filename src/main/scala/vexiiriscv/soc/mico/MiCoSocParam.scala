package vexiiriscv.soc.mico

import spinal.core._
import vexiiriscv.ParamSimple
import vexiiriscv.soc.micro.SocCtrlParam

import java.io.File

// This class will carry all the parameter of the SoC
class MiCoSocParam {
  var ramBytes = 512 KiB
  var ramElf = Option.empty[File]
  var ramBlackBox = false
  var blackBoxASIC = false
  val vexii = new ParamSimple()
  val socCtrl = new SocCtrlParam()
  var withSpiFlash = false

  var useMiCoVpu = false
  var MiCoVpuLen = 256
  var MiCoVpuWidth = 256
  var MiCoVpuBusWidth = 32
  var MiCoVpuStress = false
  var MiCoVpuPipe = false
  var withL2Cache = false
  var l2Ways = 8
  var l2Bytes = 4096
  var withHub = false

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
    opt[Unit]("l2-cache") action { (v, c) => withL2Cache = true; vexii.lsuL1Coherency = true}
    opt[Unit]("with-hub") action { (v, c) => withHub = true; vexii.lsuL1Coherency = true}
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
    if(sparseMem){
      // Sparse memory bus is wired as 64-bit in MiCoSoc; keep LSU memory width aligned.
      vexii.lsuMemDataWidthMin = vexii.lsuMemDataWidthMin max 64
    }
    if(useMiCoVpu){
      vexii.lsuMemDataWidthMin = vexii.lsuMemDataWidthMin max MiCoVpuBusWidth
    }
  }
}
