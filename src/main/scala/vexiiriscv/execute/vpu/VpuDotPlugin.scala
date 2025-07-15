package vexiiriscv.execute.vpu

import spinal.core._
import spinal.core.sim.SpinalSimConfig
import spinal.lib._
import spinal.lib.pipeline.Stageable
import spinal.lib.misc.plugin.FiberPlugin
import vexiiriscv.execute._
import vexiiriscv.decode
import spinal.lib.misc.pipeline._
import vexiiriscv.Generate.args
import vexiiriscv.{Global, ParamSimple, VexiiRiscv}
import vexiiriscv.compat.MultiPortWritesSymplifier
import vexiiriscv.riscv.{VectorRegFile, RS1, RS2, Riscv, VectorExt}
import vexiiriscv.tester.TestOptions
import vexiiriscv.riscv._

object VpuDotCompute extends AreaObject {
    def DotProduct(op_a : Bits, op_b : Bits, vlen : Int) : SInt = {
        val a_vec = op_a.subdivideIn(vlen bits)
        val b_vec = op_b.subdivideIn(vlen bits)
        val a_tmp = Vec(a_vec.zip(b_vec).map{case (a_i, b_i) => a_i.asSInt * b_i.asSInt})
        a_tmp.reduceBalancedTree(_ +^ _).resize(Riscv.XLEN.get)
    }

    def DotProductSym2Bit(op_a : Bits, op_b : Bits) : SInt = {
        val vlen = 2
        val a_vec = op_a.subdivideIn(vlen bits)
        val b_vec = op_b.subdivideIn(vlen bits)

        val a_tmp = a_vec.zip(b_vec).map{
            case (a_i, w_i) => {
                val a_tmp_i = SInt(2 bits)
                val neg_a  = -(a_i.asSInt)
                val neg_2a = neg_a |<< 1
                a_tmp_i := w_i.muxList(
                    List((B"2'b01", a_i.asSInt),
                        ( B"2'b11", neg_a),
                        ( B"2'b10", neg_2a),
                        ( B"2'b00", S(0))))
                a_tmp_i
            }
        }
        
        a_tmp.reduceBalancedTree(_ +^ _).resize(Riscv.XLEN.get)
    }

    def Extend2bTo4b(op : Bits) : Bits = {
        // Sign Extend 2-bit to 4-bit
        val ext = op.subdivideIn(2 bits).reverse.map{
            i => i.asSInt.resize(4).asBits
        }.reduce(_ ## _)
        ext
    }

    def Extend4bTo8b(op : Bits) : Bits = {
        // Sign Extend 4-bit to 8-bit
        val ext = op.subdivideIn(4 bits).reverse.map{
            i => i.asSInt.resize(8).asBits
        }.reduce(_ ## _)
        ext
    }
}

class VpuDotPlugin(val layer: LaneLayer)
    extends ExecutionUnitElementSimple(layer) {
    val logic = during setup new Logic {
        awaitBuild()
        import VpuDotCompute._
        import SrcKeys._
        import QType._

        val config = host[VpuConfigService]

        val wbp = host.find[IntFormatPlugin](p => p.lane == layer.lane)
        val wb = wbp.access(0)
        val INC = Payload(UInt(7 bits))
        add(VectorExt.VecDOT).decode(SEL -> True)

        val spec = layer(VectorExt.VecDOT)
        spec.addRsSpec(RS1, executeAt = 0)
        spec.addRsSpec(RS2, executeAt = 0)
        wbp.addMicroOp(wb, spec)

        uopRetainer.release()

        val process = new el.Execute(id = 0) {

            val rs1 = el(VectorRegFile, RS1)  // rs1 holds the 1st vector
            val rs2 = el(VectorRegFile, RS2)  // rs2 holds the 2nd vector
            val rd = Bits(Riscv.XLEN.get bits)

            INC := config.getINC()
            val offset = Reg(UInt(8 bits)) init(0)
            when(isValid && SEL){
                offset := offset + INC
            }

            val raw_rs2 = rs2.asBits
            val rs2_64 = rs2(offset, 64 bits)
            val rs2_32 = rs2(offset, 32 bits)

            val ToDOTP8 = config.getRS2Width().mux(
                U(3) -> raw_rs2,
                U(2) -> Extend4bTo8b(rs2_64),
                U(1) -> Extend4bTo8b(Extend2bTo4b(rs2_32)),
                default -> B(0, 128 bits)
            )

            val ToDOTP4 = config.getRS2Width().mux(
                U(2) -> raw_rs2,
                U(1) -> Extend2bTo4b(rs2_64),
                default -> B(0, 128 bits)
            )

            val ToDOTP2 = config.getRS2Width().mux(
                U(1) -> raw_rs2,
                default -> B(0, 128 bits)
            )

            val result = config.getRS1Width().mux(
                U(3) -> DotProduct(rs1, ToDOTP8, 8),   // RS1 8bits wide
                U(2) -> DotProduct(rs1, ToDOTP4, 4),   // RS1 4bits wide
                U(1) -> DotProductSym2Bit(rs1, ToDOTP2),// RS1 2bits wide
                default -> S(0, Riscv.XLEN.get bits)
            )

            rd := result.asBits
            
            wb.valid := isValid && SEL
            wb.payload := rd
        }
    }
}