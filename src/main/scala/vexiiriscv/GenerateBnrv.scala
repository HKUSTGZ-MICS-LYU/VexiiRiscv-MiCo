package vexiiriscv

import spinal.core._
import spinal.core.fiber.Fiber
import spinal.lib.{AnalysisUtils, LatencyAnalysis}
import spinal.lib.bus.misc.SizeMapping
import spinal.lib.bus.tilelink.{M2sTransfers, SizeRange}
import spinal.lib.misc.{InterruptNode, PathTracer}
import spinal.lib.system.tag.{MemoryEndpoint, PMA, PmaRegion, PmaRegionImpl, VirtualEndpoint}
import vexiiriscv.compat.MultiPortWritesSymplifier
import vexiiriscv.decode.{Decode, DecodePipelinePlugin}
import vexiiriscv.execute.{CsrRamPlugin, ExecuteLanePlugin, SrcPlugin}
import vexiiriscv.execute.lsu._
import vexiiriscv.execute.{BitNetPlugin, BitNetBufferPlugin, DotProductPlugin}
import vexiiriscv.fetch._
import vexiiriscv.misc.{EmbeddedRiscvJtag, PrivilegedPlugin}
import vexiiriscv.prediction.BtbPlugin
import vexiiriscv.regfile.RegFilePlugin
import vexiiriscv.soc.TilelinkVexiiRiscvFiber

import scala.collection.mutable.ArrayBuffer

// Generates VexiiRiscv verilog using command line arguments
object GenerateBnrv extends App {
  val param = new ParamSimple()
  val sc = SpinalConfig()
  val regions = ArrayBuffer[PmaRegion]()
  val analysis = new AnalysisUtils
  var reportModel = false

  var useDotProduct = false
  var useBnrv = false
  var bnrvQtype = "1.5b"
  var bnrvVersion = 4

  assert(new scopt.OptionParser[Unit]("VexiiRiscv") {
    help("help").text("prints this usage text")
    opt[Unit]("report-model") action { (v, c) => reportModel = true }
    opt[Unit]("use-dot-product") action { (v, c) => useDotProduct = true }
    opt[Unit]("use-bnrv") action { (v, c) => useBnrv = true }
    opt[Int]("bnrv-version") action { (v, c) => bnrvVersion = v }
    opt[String]("bnrv-qtype") action { (v, c) => bnrvQtype = v }
    param.addOptions(this)
    analysis.addOption(this)
    ParamSimple.addOptionRegion(this, regions)
  }.parse(args, ()).nonEmpty)

  if(regions.isEmpty) regions ++= ParamSimple.defaultPma

  val report = sc.generateVerilog {
    val pa = param.pluginsArea()
    if(useDotProduct) pa.plugins += new DotProductPlugin(pa.early0)
    if(useBnrv){
      if(bnrvVersion == 4) pa.plugins += new BitNetPlugin(pa.early0, bnrvQtype)
      else pa.plugins += new BitNetBufferPlugin(pa.early0, bnrvQtype, bnrvVersion)
    }
    pa.plugins.foreach{
      case p : EmbeddedRiscvJtag => {
        p.debugCd = ClockDomain.current.copy(reset = Bool().setName("EmbeddedRiscvJtag_logic_debug_reset"))
        p.noTapCd = ClockDomain(Bool().setName("EmbeddedRiscvJtag_logic_jtagInstruction_tck"))
      }
      case _ =>
    }
    ParamSimple.setPma(pa.plugins, regions)
    VexiiRiscv(pa.plugins)
  }

  analysis.report(report)

  if(reportModel){
    misc.Reporter.model(report.toplevel)
  }
}