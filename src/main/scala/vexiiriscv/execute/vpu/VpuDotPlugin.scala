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
    def DotProductMul(op_a : Bits, op_b : Bits, vlen : Int) : Vec[SInt] = {
        val a_vec = op_a.subdivideIn(vlen bits)
        val b_vec = op_b.subdivideIn(vlen bits)
        Vec(a_vec.zip(b_vec).map{case (a_i, b_i) => a_i.asSInt * b_i.asSInt})
    }

    // def DotProductSym2BitMul(op_a : Bits, op_b : Bits) : Vec[SInt] = {
    //     val vlen = 2
    //     val a_vec = op_a.subdivideIn(vlen bits)
    //     val b_vec = op_b.subdivideIn(vlen bits)

    //     Vec(a_vec.zip(b_vec).map{case (a_i, b_i) => (a_i ^ b_i).muxList(
    //         List((B"0", S(1, 2 bits)),
    //             (B"1", S(-1, 2 bits))))})
    // }

    def DotProductAdd(product : Vec[SInt]) : SInt = {
        product.reduceBalancedTree(_ +^ _).resize(Riscv.XLEN.get)
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

class VpuDotPlugin(
    val layer: LaneLayer,
    val extractAt: Int = 0,
    val mulAt: Int = 0,
    val addAt: Int = 1,
    val wbAt: Int = 2) extends ExecutionUnitElementSimple(layer) {
    val logic = during setup new Logic {
        awaitBuild()
        import VpuDotCompute._

        val config = host[VpuConfigService]

        val xlen = Riscv.XLEN.get
        val vlen = Riscv.VLEN.get
        val INC = Payload(UInt(7 bits))

        val OPA = Payload(Bits(vlen bits))
        val ToDOTP8, ToDOTP4, ToDOTP2, ToDOTP1 = Payload(Bits(vlen bits))
        val RES = Payload(Bits(xlen bits))

        val wbp = host.find[IntFormatPlugin](p => p.lane == layer.lane)
        val wb = wbp.access(wbAt)

        add(VectorExt.VecDOT).decode(SEL -> True)
        val spec = layer(VectorExt.VecDOT)
        spec.addRsSpec(RS1, executeAt = 0)
        spec.addRsSpec(RS2, executeAt = 0)
        wbp.addMicroOp(wb, spec)

        uopRetainer.release()

        val extract = new el.Execute(id = extractAt) {

            val rs1 = el(VectorRegFile, RS1)  // rs1 holds the 1st vector
            val rs2 = el(VectorRegFile, RS2)  // rs2 holds the 2nd vector

            INC := config.getINC()
            val offset = Reg(UInt(8 bits)) init(0)
            when(isValid && SEL){
                offset := offset + INC
            }

            val raw_rs2 = rs2.asBits
            val rs2_half = rs2(offset, (vlen/2) bits)
            val rs2_qter = rs2(offset, (vlen/4) bits)

            ToDOTP8 := config.getRS2Width().mux(
                U(3) -> raw_rs2,
                U(2) -> Extend4bTo8b(rs2_half),
                U(1) -> Extend4bTo8b(Extend2bTo4b(rs2_qter)),
                default -> B(0, vlen bits)
            )

            ToDOTP4 := config.getRS2Width().mux(
                U(2) -> raw_rs2,
                U(1) -> Extend2bTo4b(rs2_half),
                default -> B(0, vlen bits)
            )

            ToDOTP2 := config.getRS2Width().mux(
                U(1) -> raw_rs2,
                default -> B(0, vlen bits)
            )

            OPA := rs1
        }

        val mulRES8 = Payload(Vec(SInt(16 bits), vlen / 8))
        val mulRES4 = Payload(Vec(SInt(8 bits), vlen / 4))
        val mulRES2 = Payload(Vec(SInt(4 bits), vlen / 2))

        val mul = new el.Execute(id = mulAt) {
            val mulRes8 = DotProductMul(OPA, ToDOTP8, 8)
            val mulRes4 = DotProductMul(OPA, ToDOTP4, 4)
            val mulRes2 = DotProductMul(OPA, ToDOTP2, 2)

            mulRES8.zipWithIndex.foreach{case (p, i) =>
                p := mulRes8(i)
            }
            mulRES4.zipWithIndex.foreach{case (p, i) =>
                p := mulRes4(i)
            }
            mulRES2.zipWithIndex.foreach{case (p, i) =>
                p := mulRes2(i)
            }
        }

        val add = new el.Execute(id = addAt) {
            RES := config.getRS1Width().mux(
                U(3) -> DotProductAdd(mulRES8),
                U(2) -> DotProductAdd(mulRES4),
                U(1) -> DotProductAdd(mulRES2),
                default -> S(0, xlen bits)
            ).asBits
        }

        val writeback = new el.Execute(id = wbAt) {
            //Provide the computation value for the writeback
            wb.valid := isValid && SEL
            wb.payload := RES
        }
    }
}