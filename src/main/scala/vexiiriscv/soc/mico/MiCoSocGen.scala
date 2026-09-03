package vexiiriscv.soc.mico

import spinal.core._
import spinal.lib.system.tag.MemoryConnection
import vexiiriscv.VexiiRiscv
import vexiiriscv.asic.Asap7SramBlackboxPolicy
import vexiiriscv.execute.lsu.{LsuCachelessPlugin, LsuCachelessTileLinkPlugin, LsuL1Plugin, LsuL1TileLinkPlugin, LsuPlugin, LsuTileLinkPlugin}
import vexiiriscv.fetch.{FetchCachelessPlugin, FetchCachelessTileLinkPlugin, FetchL1Plugin, FetchL1TileLinkPlugin}
import vexiiriscv.soc.TilelinkVexiiRiscvFiber

import java.io.{File, PrintWriter}
import scala.collection.mutable


object Bsp {
  def apply(target : File, vexii: TilelinkVexiiRiscvFiber): Unit = {

    target.mkdirs()

    val socFile = new File(target, "soc.h")
    val headerWriter = new PrintWriter(socFile)

    headerWriter.println("#pragma once")

    def camelToUpperCase(str : String) = str.split("(?=\\p{Upper})").map(_.toUpperCase).mkString("_")
    val kv = mutable.LinkedHashMap[String, Any]()


    val peripherals = MemoryConnection.getMemoryTransfers(vexii.dBus)
    for(p <- peripherals){
      kv(p.node.getName() + "Addr") = p.where.mapping.lowerBound
    }


    for((name, value) <- kv){
      val patched = camelToUpperCase(name)
      value match {
        case value: Int => headerWriter.println(s"#define ${patched} $value")
        case value: Long => headerWriter.println(f"#define ${patched} 0x$value%x")
        case value: BigInt => headerWriter.println(f"#define ${patched} 0x$value%x")
        case value: FixedFrequency => headerWriter.println(s"#define ${patched} ${value.getValue.toBigDecimal.toBigInt.toString(10)}")
        case value: Boolean => headerWriter.println(s"#define ${patched} ${if (value) 1 else 0}")
        case _ =>
      }
    }

    headerWriter.close()
  }
}

object MiCoSocGen extends App{
  val p = new MiCoSocParam()
  var netlistDirectory = "."
  var netlistName = "MiCoSoc"

  assert(new scopt.OptionParser[Unit]("MiCoSoc") {
    p.addOptions(this)
    opt[String]("netlist-directory") action { (v, c) => netlistDirectory = v }
    opt[String]("netlist-name") action { (v, c) => netlistName = v }
  }.parse(args, ()).nonEmpty)
  p.legalize()

  val policy = if (p.asicSram) Asap7SramBlackboxPolicy
               else if (p.blackBoxASIC) blackboxAllWhatsYouCan
               else blackboxOnlyIfRequested
    
  val report = SpinalConfig(targetDirectory = netlistDirectory)
              .addStandardMemBlackboxing(policy)
              .generateVerilog(new MiCoSoc(p).setDefinitionName(netlistName))

  Bsp(new File(netlistDirectory), report.toplevel.system.cpu)
}




//  val h = report.toplevel.main.cpu.logic.core.host
//  val path = PathTracer.impl(h[SrcPlugin].logic.addsub.rs2Patched, h[TrapPlugin].logic.harts(0).trap.pending.state.tval)
//  println(path.report)