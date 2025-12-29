package vexiiriscv.soc.mico

import rvls.spinal.RvlsBackend
import spinal.core._
import spinal.core.sim._
import spinal.core.fiber._
import spinal.lib.com.spi.sim.FlashModel
import spinal.lib.com.uart.sim.{UartDecoder, UartEncoder}
import spinal.lib.sim.SparseMemory
import spinal.lib.bus.tilelink.sim.{Checker, MemoryAgent}
import spinal.lib.misc.Elf
import vexiiriscv.test.VexiiRiscvProbe

import java.io.File

/**
 * To connect with openocd jtag :
 * - src/openocd -f $VEXIIRISCV/src/main/tcl/openocd/vexiiriscv_sim.tcl
 */

object MiCoSocSim extends App{
  var traceKonata = false
  var withRvlsCheck = false
  var elfFile: File = null
  val sim = SimConfig
  var speedPrinterPeriod = Option.empty[Double]
  sim.withTimeSpec(1 ns, 1 ps)
  val p = new MiCoSocParam()

  assert(new scopt.OptionParser[Unit]("VexiiRiscv") {
    help("help").text("prints this usage text")
    opt[String]("load-elf") action { (v, c) => elfFile = new File(v) }
    opt[Unit]("trace-konata") action { (v, c) => traceKonata = true }
    opt[Unit]("check-rvls") action { (v, c) => withRvlsCheck = true }
    opt[Double]("speed-printer") action { (v, c) => speedPrinterPeriod = Some(v) }
    sim.addOptions(this)
    p.addOptions(this)
  }.parse(args, ()).nonEmpty)
  p.legalize()

  class MiCoSocSim extends MiCoSoc(p){
    Fiber patch{
      system.ram.thread.logic.mem.simPublic()
    }
  }
  sim.compile(new MiCoSocSim).doSimUntilVoid("test", seed = 42){dut =>
    dut.socCtrl.systemClkCd.forkStimulus()
    dut.socCtrl.asyncReset #= true
    delayed(100 ns)(dut.socCtrl.asyncReset #= false)

    speedPrinterPeriod.foreach(SimSpeedPrinter(dut.socCtrl.systemClkCd, _))

    val uartBaudPeriod = hzToLong(115200 Hz)
    val uartTx = UartDecoder(
      uartPin = dut.system.peripheral.uart.logic.uart.txd,
      baudPeriod = uartBaudPeriod
    )
    val uartRx = UartEncoder(
      uartPin = dut.system.peripheral.uart.logic.uart.rxd,
      baudPeriod = uartBaudPeriod
    )

    val spiFlash = p.withSpiFlash generate new FlashModel(dut.system.peripheral.spiFlash.logic.spi, dut.socCtrl.system.cd)

    val konata = traceKonata.option(
      new vexiiriscv.test.konata.Backend(new File(currentTestPath, "konata.log")).spinalSimFlusher(hzToLong(1000 Hz))
    )
    val probe = new VexiiRiscvProbe(
      cpu = dut.system.cpu.logic.core,
      kb = konata
    )

    if (withRvlsCheck) probe.add(new RvlsBackend(new File(currentTestPath)).spinalSimFlusher(hzToLong(1000 Hz)))

    probe.autoRegions()

    if(p.socCtrl.withJtagTap) {
      probe.checkLiveness = false
      spinal.lib.com.jtag.sim.JtagRemote(dut.socCtrl.debugModule.tap.jtag, hzToLong(p.socCtrl.systemFrequency)*4)
    }

    // Use Sparse Memory to simulate off-chip mem instead of on-chip RAM block
    val mem = SparseMemory(seed = 0, randOffset = 0x80000000l)
    if(p.sparseMem){
      val factor = 1.0f - p.sparseMemDelay
      val ma = new MemoryAgent(
        dut.system.mBus.node.bus, 
        dut.socCtrl.system.cd , 
        seed = 0, 
        randomProberFactor = if(factor < 1.0f) 0.2f else 0.0f, 
        memArg = Some(mem))(null)
      val checker = if (ma.monitor.bus.p.withBCE) Checker(ma.monitor)
      ma.driver.driver.setFactor(factor) // with some random stalls
    }
    if(elfFile != null) {
      val elf = new Elf(elfFile, p.vexii.xlen)
      if(p.sparseMem){
        elf.load(mem, 0x80000000l)
        elf.load(dut.system.ram.thread.logic.mem, 0x40000000l, true)
      }
      else {elf.load(dut.system.ram.thread.logic.mem, 0x80000000l, true)}
      
      if(p.withSpiFlash) elf.loadArray(spiFlash.content, 0x20000000l, true)
      probe.backends.foreach(_.loadElf(0, elfFile))
      probe.backends.foreach(_.loadElf(0, elfFile))

      val withPass = elf.getELFSymbol("pass") != null
      val withFail = elf.getELFSymbol("fail") != null
      if (withPass || withFail) {
        def trunkPc(pc : Long) = (p.vexii.xlen == 32).mux(pc & 0xFFFFFFFFl, pc)
        val passSymbol = if(withPass) trunkPc(elf.getSymbolAddress("pass")) else -1
        val failSymbol = if(withFail) trunkPc(elf.getSymbolAddress("fail")) else -1

        // Wait for UART TX to be idle for a few bit times before ending the sim
        def finishAfterUartIdle(success: Boolean, msg: String = null): Unit = fork {
          val txPin        = dut.system.peripheral.uart.logic.uart.txd
          val bitPeriod    = uartBaudPeriod            // in sim time units
          val idleBits     = 16                        // how long line must stay idle (in bit times)
          val sampleStep   = (bitPeriod max 8L) / 8    // sampling step to detect activity
          val idleWindow   = bitPeriod * idleBits
          val timeoutGuard = bitPeriod * 200000        // safety timeout (~200k bits)
          val startTime    = simTime()

          var lastLevel    = txPin.toBoolean
          var lastChange   = simTime()

          // observe line; when no edges for idleWindow, consider UART drained
          while (simTime() - lastChange < idleWindow && simTime() - startTime < timeoutGuard) {
            val lvl = txPin.toBoolean
            if (lvl != lastLevel) {
              lastLevel  = lvl
              lastChange = simTime()
            }
            sleep(sampleStep)
          }

          if (success) simSuccess()
          else simFailure(Option(msg).getOrElse("Software reached the fail symbol :("))
        }
        var endScheduled = false
        probe.commitsCallbacks += { (hartId, pc) =>
          if (!endScheduled && pc == passSymbol) { endScheduled = true; finishAfterUartIdle(success = true) }
          if (!endScheduled && pc == failSymbol) { endScheduled = true; finishAfterUartIdle(success = false, "Software reached the fail symbol :(") }
        }
      }
    }
  }
}