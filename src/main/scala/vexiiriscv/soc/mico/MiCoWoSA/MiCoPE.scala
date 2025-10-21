package vexiiriscv.soc.mico.MiCoWoSA

import spinal.core._

import spinal.lib._
import spinal.lib.fsm._
import spinal.lib.bus._
import spinal.lib.bus.misc._
import spinal.lib.bus.tilelink._
import spinal.lib.misc._

class MiCoPE(config: MiCoWoSAConfig) extends Component {

    val dataWidth = config.dataWidth
    val accWidth = config.accWidth

    val io = new Bundle {
        val enable = in(Bool()) default(False)
        val clear = in(Bool()) default(False)
        val load = in(Bool()) default(False)
        val in_act = in(SInt(dataWidth bits))
        val in_wt = in(SInt(dataWidth bits))
        val in_acc = in(SInt(accWidth bits))
        val out_act = out(SInt(dataWidth bits))
        val out_acc = out(SInt(accWidth bits))
    }

    val reg_act = Reg(SInt(dataWidth bits)) init(0)
    val reg_wt = Reg(SInt(dataWidth bits)) init(0)
    val acc = Reg(SInt(accWidth bits)) init(0)

    io.out_act := reg_act
    io.out_acc := acc + (reg_act * reg_wt).resized

    when(io.clear) {
        acc := 0
        reg_act := 0
        reg_wt := 0
    } elsewhen(io.load){
        reg_wt := io.in_wt
    } elsewhen(io.enable){
        reg_act := io.in_act
        acc := io.in_acc
    }
}

object GeneratePE extends App {
    Config.spinal.generateVerilog(
        new MiCoPE(MiCoWoSAConfig(size = 4, dataWidth = 8, accWidth = 32))
    )
}