package vexiiriscv.soc.mico.MiCoWoSA

import spinal.core._

import spinal.lib._
import spinal.lib.fsm._
import spinal.lib.bus._
import spinal.lib.bus.misc._
import spinal.lib.bus.tilelink._
import spinal.lib.misc._

import spinal.core.sim._

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

class MiCoMAC(dataWidth : Int, accWidth : Int) extends Component {
    import MiCoCompute._
    val io = new Bundle {
        val a = in(SInt(dataWidth bits))
        val b = in(SInt(dataWidth bits))
        val c = in(SInt(accWidth bits))
        val mode = in(Bool()) // false: int8, true: int4
        val result = out(SInt(accWidth bits))
    }

    val a_int4 = io.a.asBits.subdivideIn(4 bits)
    val b_int4 = io.b.asBits.subdivideIn(4 bits)

    val a0_mult_b0 = (a_int4(0).asUInt * b_int4(0).asUInt)
    val a1_mult_b1 = (a_int4(1).asUInt * b_int4(1).asUInt)

    val a0_mult_b1 = (a_int4(0).asUInt * b_int4(1).asUInt)
    val a1_mult_b0 = (a_int4(1).asUInt * b_int4(0).asUInt)

    // val mult_int8 = (
    //     a0_mult_b0.resize(16 bits) +^ 
    //     ((a0_mult_b1 +^ a1_mult_b0).resize(16 bits) << 4) +^ 
    //     (a1_mult_b1.resize(16 bits) << 8)
    // ).asSInt.resize(accWidth bits)
    
    val mult_int8 = (io.a * io.b).resize(accWidth bits)

    val mult_int4 = (
        (a0_mult_b0 +^ a1_mult_b1).asSInt
    ).resize(accWidth bits)

    io.result := io.c + io.mode.mux(mult_int4, mult_int8)
}
object SimulateMAC extends App {
    Config.sim.compile(new MiCoMAC(8, 32)).doSim { dut =>
        dut.clockDomain.forkStimulus(10)

        dut.io.a #= 0
        dut.io.b #= 0
        dut.io.c #= 0
        dut.io.mode #= false

        dut.clockDomain.waitSampling()

        // Test int8 mode
        dut.io.a #= -1
        dut.io.b #= 1
        dut.io.c #= 0
        dut.io.mode #= false

        dut.clockDomain.waitSampling()
        println(s"int8 MAC Result: ${dut.io.result.toBigInt}")

        // Test int4 mode
        dut.io.a #= 0x12 // 1 and 2
        dut.io.b #= 0x34 // 3 and 4
        dut.io.c #= 0
        dut.io.mode #= true

        dut.clockDomain.waitSampling()
        println(s"int4 MAC Result: ${dut.io.result.toBigInt}")
    }
}

class MiCoPE(config: MiCoWoSAConfig) extends Component {

    val dataWidth = config.dataWidth
    val accWidth = config.accWidth

    val io = new Bundle {
        val enable = in(Bool()) default(False)
        val clear = in(Bool()) default(False)
        val load = in(Bool()) default(False)
        val mode = in(Bool()) default(False) // false: int8, true: int4
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

    val mult = new MiCoMAC(dataWidth, accWidth)

    mult.io.a := reg_act
    mult.io.b := reg_wt
    mult.io.c := acc
    mult.io.mode := io.mode
    io.out_acc := mult.io.result

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