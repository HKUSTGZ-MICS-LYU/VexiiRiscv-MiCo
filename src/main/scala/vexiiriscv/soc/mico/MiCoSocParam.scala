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
  val vexii = new ParamSimple()
  val socCtrl = new SocCtrlParam()
  var withSpiFlash = false

  var useMiCoVpu = false
  var MiCoVpuLen = 256
  var MiCoVpuWidth = 256
  var MiCoVpuBusWidth = 32

  var useMiCoBitSerial = false

  // Provide some sane default
  vexii.fetchForkAt = 1
  vexii.lsuPmaAt = 1
  vexii.lsuForkAt = 1
  vexii.relaxedBranch = true
  socCtrl.withJtagTap = false
  legalize()

  // This is a command line parser utility, so you can customize the SoC using command line arguments to feed parameters
  def addOptions(parser: scopt.OptionParser[Unit]): Unit = {
    import parser._
    opt[Int]("ram-bytes") action { (v, c) => ramBytes = v }
    opt[String]("ram-elf") action { (v, c) => ramElf = Some(new File(v)) }
    opt[Unit]("ram-blackbox") action { (v, c) => ramBlackBox = true  }
    opt[Boolean]("spi-flash") action { (v, c) => withSpiFlash = v  }
    opt[Unit]("mico-vpu") action { (v, c) => useMiCoVpu = true; vexii.withCfu = true}
    opt[Int]("mico-vpu-len") action { (v, c) => MiCoVpuLen = v }
    opt[Int]("mico-vpu-width") action { (v, c) => MiCoVpuWidth = v }
    opt[Int]("mico-vpu-bus-width") action { (v, c) => MiCoVpuBusWidth = v }
    opt[Unit]("mico-bs") action { (v, c) => useMiCoBitSerial = true }
    socCtrl.addOptions(parser)
    vexii.addOptions(parser)
  }

  // After modifying the attributes of this class, you need to call the legalize function to check / fix it is fine.
  def legalize(): Unit = {
    vexii.privParam.withDebug = socCtrl.withDebug
  }
}