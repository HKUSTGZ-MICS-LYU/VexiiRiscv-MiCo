package vexiiriscv

import spinal.core._
import spinal.lib._
import spinal.core.sim._

import vexiiriscv.execute.{BitNetPlugin, BitNetBufferPlugin, DotProductPlugin}
import vexiiriscv.tester.{TestOptions, TestBench}

import spinal.lib.misc.plugin.Hostable

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer


object SimulateBnrv extends App{
  
  val param = new ParamSimple()

  val testOpt = new TestOptions()
  testOpt.withRvlsCheck = false

  val genConfig = SpinalConfig()
  genConfig.includeSimulation

  val simConfig = SpinalSimConfig()
  simConfig.withTestFolder
  simConfig.withConfig(genConfig)

  var useDotProduct = false
  var useBnrv = false
  var bnrvQtype = "1.5b"
  var bnrvVersion = 4

  assert(new scopt.OptionParser[Unit]("VexiiRiscv") {
    help("help").text("prints this usage text")
    opt[Unit]("use-dot-product") action { (v, c) => useDotProduct = true }
    opt[Unit]("use-bnrv") action { (v, c) => useBnrv = true }
    opt[Int]("bnrv-version") action { (v, c) => bnrvVersion = v }
    opt[String]("bnrv-qtype") action { (v, c) => bnrvQtype = v }
    simConfig.addOptions(this)
    testOpt.addOptions(this)
    param.addOptions(this)
  }.parse(args, ()).nonEmpty)

  println(s"With Vexiiriscv parm :\n - ${param.getName()}")
  val compiled = simConfig.compile {
    val pa = param.pluginsArea()
    if(useDotProduct) pa.plugins += new DotProductPlugin(pa.early0)
    if(useBnrv){
      if(bnrvVersion == 4) pa.plugins += new BitNetPlugin(pa.early0, bnrvQtype)
      else pa.plugins += new BitNetBufferPlugin(pa.early0, bnrvQtype, bnrvVersion)
    }
    ParamSimple.setPma(pa.plugins)
    VexiiRiscv(pa.plugins)
  }
  testOpt.test(compiled)
}