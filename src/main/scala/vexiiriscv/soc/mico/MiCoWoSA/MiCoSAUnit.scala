package vexiiriscv.soc.mico.MiCoWoSA

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._
import spinal.lib.bus._
import spinal.lib.bus.misc._
import spinal.lib.bus.tilelink._
import spinal.lib.misc._

import spinal.core.sim._

/*
    MiCoSAUnit: MiCo Systolic Array Unit with controller
*/
class MiCoSAUnit(config : MiCoWoSAConfig) extends Component {
  
  val size = config.size
  val dataWidth = config.dataWidth
  val accWidth = config.accWidth

}