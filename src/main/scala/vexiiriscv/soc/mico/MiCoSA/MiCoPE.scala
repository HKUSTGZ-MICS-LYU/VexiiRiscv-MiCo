package vexiiriscv.soc.mico.MiCoSA

import spinal.core._

import spinal.lib._
import spinal.lib.fsm._
import spinal.lib.bus._
import spinal.lib.bus.misc._
import spinal.lib.bus.tilelink._
import spinal.lib.misc._

object MiCoCompute extends AreaObject {
    def Product(op_a : Bits, op_b : Bits, bitWidth : Int) : Vec[SInt] = {
        val a_vec = op_a.subdivideIn(bitWidth bits)
        val b_vec = op_b.subdivideIn(bitWidth bits)
        Vec(a_vec.zip(b_vec).map{case (a_i, b_i) => a_i.asSInt * b_i.asSInt})
    }

    def ProductBin(op_a : Bits, op_b : Bits) : Vec[SInt] = {
        // Binary Product
        val a_vec = op_a.subdivideIn(1 bits)
        val b_vec = op_b.subdivideIn(1 bits)
        Vec(a_vec.zip(b_vec).map{case (a_i, b_i) => (a_i ^ b_i).muxList(
            List((B"0", S(1, 2 bits)),
                (B"1", S(-1, 2 bits))))})
    }
    
    def AdderTree(vec: Vec[SInt], resWidth: Int = 32) : SInt = {
        vec.reduceBalancedTree(_ +^ _).resize(resWidth bits)
    } 
}

class MiCoPE(config : MiCoSAConfig) extends Component {

    val dataWidth = config.dataWidth
    val accWidth = config.accWidth

    val io = new Bundle {
        val in_a = in(SInt(dataWidth bits))
        val in_b = in(SInt(dataWidth bits))
        val out_a = out(SInt(dataWidth bits))
        val out_b = out(SInt(dataWidth bits))
        val in_res = in(SInt(accWidth bits))
        val out_res = out(SInt(accWidth bits))
        val enable = in(Bool()) default(True)
        val propagate = in(Bool()) default(False)
        val clear = in(Bool()) default(False)
    }

    val reg_a = Reg(SInt(dataWidth bits)) init(0)
    val reg_b = Reg(SInt(dataWidth bits)) init(0)
    val acc = Reg(SInt(accWidth bits)) init(0)
    val mac = acc + (io.in_a * io.in_b).resized
    when(io.enable) {
        reg_a := io.in_a
        reg_b := io.in_b
        
        when(io.clear) {
            acc := 0
            reg_a := 0
            reg_b := 0
        } elsewhen(io.propagate) {
            acc := io.in_res
        } otherwise {
            acc := mac
        }
    }
    io.out_a := reg_a
    io.out_b := reg_b
    io.out_res := io.propagate.mux(acc, mac)
}


object GeneratePE extends App {
    Config.spinal.generateVerilog(
        new MiCoPE(MiCoSAConfig(size = 4, dataWidth = 8, accWidth = 32)))
}