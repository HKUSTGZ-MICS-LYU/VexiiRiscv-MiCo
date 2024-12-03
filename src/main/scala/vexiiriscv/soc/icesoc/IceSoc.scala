package vexiiriscv.soc.icesoc

import rvls.spinal.RvlsBackend
import spinal.core._
import spinal.core.sim._
import spinal.core.fiber._
import spinal.lib.{ResetCtrlFiber, StreamPipe}
import spinal.lib.bus.misc.SizeMapping
import spinal.lib.bus.tilelink
import spinal.lib.bus.tilelink._
import spinal.lib.bus.tilelink.fabric.Node
import spinal.lib.com.uart.TilelinkUartFiber
import spinal.lib.com.uart.sim.{UartDecoder, UartEncoder}
import spinal.lib.cpu.riscv.RiscvHart
import spinal.lib.cpu.riscv.debug.DebugModuleFiber
import spinal.lib.eda.bench.Rtl
import spinal.lib.misc.plugin.Hostable
import spinal.lib.misc.{Elf, PathTracer, TilelinkClintFiber}
import spinal.lib.misc.plic.TilelinkPlicFiber
import spinal.lib.system.tag.PMA
import vexiiriscv.ParamSimple
import vexiiriscv.execute.SrcPlugin
import vexiiriscv.misc.TrapPlugin
import vexiiriscv.soc.TilelinkVexiiRiscvFiber
import vexiiriscv.test.VexiiRiscvProbe

import vexiiriscv._

import java.io.File
import scala.collection.mutable.ArrayBuffer
import scala.annotation.meta.param

class IceSoc(cpuParam: ParamSimple, blackboxRam: Boolean, clkFreq: Int) extends Component {
  val asyncReset = in Bool()
  val clkDomain = ClockDomain.external("cd100", withReset = false, 
    frequency = FixedFrequency(clkFreq MHz))

  val debugResetCtrl = clkDomain(new ResetCtrlFiber().addAsyncReset(asyncReset, HIGH))
  val mainResetCtrl  = clkDomain(new ResetCtrlFiber().addAsyncReset(debugResetCtrl))


  val main = mainResetCtrl.cd on new Area {
    val sharedBus = tilelink.fabric.Node()

    val plugins = cpuParam.plugins()

    val cpu = new TilelinkVexiiRiscvFiber(plugins)
    sharedBus << cpu.buses
    cpu.dBus.setDownConnection(a = StreamPipe.S2M)

    val ram = new tilelink.fabric.RamFiber(48 KiB)
    ram.up at 0x80000000l of sharedBus

    // Handle all the IO / Peripheral things
    val peripheral = new Area {
      val busXlen = Node().forceDataWidth(cpuParam.xlen)
      busXlen << sharedBus
      busXlen.setUpConnection(a = StreamPipe.HALF, d = StreamPipe.HALF)

      val bus32 = Node().forceDataWidth(32)
      bus32 << busXlen

      val clint = new TilelinkClintFiber()
      clint.node at 0x10010000 of busXlen

      val plic = new TilelinkPlicFiber()
      plic.node at 0x10C00000 of bus32

      val uart = new TilelinkUartFiber()
      uart.node at 0x10001000 of bus32
      plic.mapUpInterrupt(1, uart.interrupt)

      val cpuPlic = cpu.bind(plic)
      val cpuClint = cpu.bind(clint)
    }
    println(blackboxRam)
    if (blackboxRam) {
      val patches = Fiber build new Area{
        ram.thread.logic.mem.generateAsBlackBox()
      }
    }
  }
}

object IceSocGen extends App{
  val config = SpinalConfig(targetDirectory = "hw/rtl").addStandardMemBlackboxing(blackboxOnlyIfRequested)

  // Default Param for IceSoc
  val param = new ParamSimple()

  var blackboxRam = false
  var clkFreq = 100

  assert(new scopt.OptionParser[Unit]("VexiiRiscv") {
    help("help").text("prints this usage text")
    opt[Int]("freq").action { (v, c) => clkFreq = v }.text("Set SoC Clk Frequency (default 100 MHz)")
    opt[Unit]("blackboxram").action{(v, c) => blackboxRam = true}.text("Blackbox the ram")
    param.addOptions(this)
  }.parse(args, Unit).nonEmpty)
  val report = SpinalVerilog(config){
    new IceSoc(param, blackboxRam, clkFreq)
  }

  // val h = report.toplevel.main.cpu.logic.core.host
  // val path = PathTracer.impl(h[SrcPlugin].logic.addsub.rs2Patched, h[TrapPlugin].logic.harts(0).trap.pending.state.tval)
  // println(path.report)
}