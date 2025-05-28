package vexiiriscv.soc.accel

import spinal.core._
import spinal.lib.system.tag.MemoryConnection
import vexiiriscv.VexiiRiscv
import vexiiriscv.execute.lsu.{LsuCachelessPlugin, LsuCachelessTileLinkPlugin, LsuL1Plugin, LsuL1TileLinkPlugin, LsuPlugin, LsuTileLinkPlugin}
import vexiiriscv.fetch.{FetchCachelessPlugin, FetchCachelessTileLinkPlugin, FetchL1Plugin, FetchL1TileLinkPlugin}
import vexiiriscv.soc.TilelinkVexiiRiscvFiber

import java.io.{File, PrintWriter}
import scala.collection.mutable

import vexiiriscv.soc.micro._

object AccelSocGen extends App{
  val p = new AccelSocParam()

  assert(new scopt.OptionParser[Unit]("AccelSoc") {
    p.addOptions(this)
  }.parse(args, ()).nonEmpty)
  p.legalize()

  val report = SpinalVerilog(new AccelSoc(p))
  Bsp(new File("."), report.toplevel.system.cpu)

}


//  val h = report.toplevel.main.cpu.logic.core.host
//  val path = PathTracer.impl(h[SrcPlugin].logic.addsub.rs2Patched, h[TrapPlugin].logic.harts(0).trap.pending.state.tval)
//  println(path.report)