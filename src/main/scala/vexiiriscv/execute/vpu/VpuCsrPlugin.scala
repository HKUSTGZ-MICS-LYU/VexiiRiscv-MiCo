package vexiiriscv.execute.vpu

import spinal.core._
import spinal.lib.misc.plugin.FiberPlugin
import vexiiriscv.execute.CsrService
import vexiiriscv.execute._
import vexiiriscv.riscv._

trait VpuConfigService {
  def getRS1Width(): UInt
  def getRS2Width(): UInt
  def getINC(): UInt
}

class VpuCsrPlugin extends FiberPlugin with VpuConfigService {
  val api = during build new Area {
    val vpuConfig = Reg(UInt(11 bits)) init(0)
  }

  override def getRS1Width(): UInt = api.vpuConfig(1 downto 0)  // Width of RS1 is defined using CSR[1:0]
  override def getRS2Width(): UInt = api.vpuConfig(3 downto 2)  // Width of RS2 is defined using CSR[3:2]
  override def getINC(): UInt = api.vpuConfig(10 downto 4)      // Value of Increment is defined using CSR[10:4]
  
  val logic = during setup new Area {
    val cp = host[CsrService]
    val buildBefore = retains(cp.csrLock)
    awaitBuild()
    
    cp.readWrite(CSR.VPU_CONFIG, 0 -> api.vpuConfig)

    buildBefore.release()
  }
}