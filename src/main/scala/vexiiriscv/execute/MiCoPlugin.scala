package vexiiriscv.execute

import spinal.core._
import spinal.core.sim.SpinalSimConfig
import spinal.lib._
import spinal.lib.pipeline.Stageable
import vexiiriscv.decode
import spinal.lib.misc.pipeline._
import vexiiriscv.Generate.args
import vexiiriscv.{Global, ParamSimple, VexiiRiscv}
import vexiiriscv.compat.MultiPortWritesSymplifier
import vexiiriscv.riscv.{IntRegFile, RS1, RS2, Riscv}
import vexiiriscv.tester.TestOptions

object QType extends SpinalEnum(defaultEncoding=binaryOneHot){
  val Q8, Q4, Q2, Q1 = newElement()
}

object MiCoCompute extends AreaObject {
    def DotProduct(op_a : Bits, op_b : Bits, vlen : Int) : SInt = {
        val a_vec = op_a.subdivideIn(vlen slices)
        val b_vec = op_b.subdivideIn(vlen slices)
        val a_tmp = Vec(a_vec.zip(b_vec).map{case (a_i, b_i) => a_i.asSInt * b_i.asSInt})
        a_tmp.reduceBalancedTree(_ +^ _).resize(32)
    }

    def DotProductSym2Bit(op_a : Bits, op_b : Bits) : SInt = {
        val vlen = 16
        val a_vec = op_a.subdivideIn(vlen slices)
        val b_vec = op_b.subdivideIn(vlen slices)

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
        // A more accurate implementation, but larger area
        // val a_tmp = Vec(a_vec.zip(b_vec).map{case (a_i, b_i) => a_i.asSInt * b_i.asSInt})
        a_tmp.reduceBalancedTree(_ +^ _).resize(32)
    }

    def DotProductSym1Bit(op_a : Bits, op_b : Bits) : SInt = {
        val xor = (op_a ^ op_b).asBools
        val count_n = xor.sCount(True)         // True is -1
        (S(32) - (count_n << 1).asSInt).resize(32)
    }

    def DotProductAsym1Bit(op_a : Bits, op_b : Bits) : SInt = {
        // Let's assume XLEN == 32 here
        val vlen = 4 // 4 x 8-bit integer vector
        val a_vec = op_a.subdivideIn(vlen slices)
        val b_vec = op_b.subdivideIn(vlen slices)
        val a_tmp = Vec(a_vec.zip(b_vec).map{
            case (a_i, b_i) => (b_i.asBool ? -a_i.asSInt | a_i.asSInt)})
        a_tmp.reduceBalancedTree(_ +^ _).resize(32)
    }

    def DotProductAsym2Bit(op_a : Bits, op_b : Bits) : SInt = {
        // Let's assume XLEN == 32 here
        val vlen = 4 // 4 x 8-bit integer vector
        val a_vec = op_a.subdivideIn(vlen slices)
        val b_vec = op_b.subdivideIn(vlen slices)

        val a_tmp = a_vec.zip(b_vec).map{
            case (a_i, w_i) => {
                val a_tmp_i = SInt(8 bits)
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
        // A more accurate implementation, but larger area
        // val a_tmp = Vec(a_vec.zip(b_vec).map{case (a_i, b_i) => a_i.asSInt * b_i.asSInt})
        a_tmp.reduceBalancedTree(_ +^ _).resize(32)
    }

    def Extend1bTo2b(op : Bits) : Bits = {
        // A simple map of:
        // 0 -> 01 (+1 in 2-bit)
        // 1 -> 11 (-1 in 2-bit)
        // So that we can re-use some logic
        val ext = op.subdivideIn(1 bits).reverse.map{
            i => i ## B"1"}.reduce(_ ## _)
        ext
    }
    def Extend2bTo4b(op : Bits) : Bits = {
        // Sign Extend 2-bit to 4-bit
        val ext = op.subdivideIn(2 bits).reverse.map{
            i => i.asSInt.resize(4).asBits
        }.reduce(_ ## _)
        ext
    }
    def ExtendTo8b(op : Bits) : Bits = {
        // Sign Extend 4-bit to 8-bit
        val ext = op.subdivideIn(4 slices).reverse.map{
            i => i.asSInt.resize(8).asBits
        }.reduce(_ ## _)
        ext
    }
    // def Extend1p5To2b(op : Bits) : Bits = {
    //     // Decoder that decode 1.58-bit to 2-bit
    // }
}

object MiCoPlugin {
    // 8 * 8-bit vector DotP 8  * 8-bit vector
    val DOTP8x8   = IntRegFile.TypeR(M"0000001----------100-----0001011")
    val DOTP8x4   = IntRegFile.TypeR(M"0000001----------101-----0001011")
    val DOTP8x2   = IntRegFile.TypeR(M"0000001----------110-----0001011")
    val DOTP8x1   = IntRegFile.TypeR(M"0000001----------111-----0001011")

    // 8  * 4-bit vector DotP 8  * 4-bit vector
    val DOTP4x4   = IntRegFile.TypeR(M"0000010----------101-----0001011")
    val DOTP4x2   = IntRegFile.TypeR(M"0000010----------110-----0001011")
    val DOTP4x1   = IntRegFile.TypeR(M"0000010----------111-----0001011")

    // 16 * 2-bit vector DotP 16 * 2-bit vector
    val DOTP2x2   = IntRegFile.TypeR(M"0000100----------110-----0001011")
    val DOTP2x1   = IntRegFile.TypeR(M"0000100----------111-----0001011")

    // 32 * 1-bit vector DotP 32 * 1-bit vector
    val DOTP1x1   = IntRegFile.TypeR(M"0001000----------111-----0001011")

    val AQ = Payload(QType())  // 1 2 4 8
    val WQ = Payload(QType())  // 1 2 4 8

    val INC = Payload(UInt(5 bits)) // Increment Bits (0, 4, 8, 16)
}

class MiCoPlugin(val layer : LaneLayer) 
    extends ExecutionUnitElementSimple(layer)  {

    val logic = during setup new Logic {
        awaitBuild()

        //Let's assume we only support RV32 for now
        assert(Riscv.XLEN.get == 32)

        //Let's get the hardware interface that we will use to provide the result of our custom instruction
        val wb = newWriteback(ifp, 0)
        
        import MiCoPlugin._
        import MiCoCompute._
        import QType._
        import SrcKeys._

        // 8-bit Engine Data Path
        add(DOTP8x8).srcs(SRC1.RF, SRC2.RF).decode(AQ -> Q8, WQ -> Q8, INC -> U"5'd00")
        add(DOTP8x4).srcs(SRC1.RF, SRC2.RF).decode(AQ -> Q8, WQ -> Q4, INC -> U"5'd16")
        add(DOTP8x2).srcs(SRC1.RF, SRC2.RF).decode(AQ -> Q8, WQ -> Q2, INC -> U"5'd08")
        add(DOTP8x1).srcs(SRC1.RF, SRC2.RF).decode(AQ -> Q8, WQ -> Q1, INC -> U"5'd04")

        // 4-bit Engine Data Path
        add(DOTP4x4).srcs(SRC1.RF, SRC2.RF).decode(AQ -> Q4, WQ -> Q4, INC -> U"5'd00")
        add(DOTP4x2).srcs(SRC1.RF, SRC2.RF).decode(AQ -> Q4, WQ -> Q2, INC -> U"5'd16")
        add(DOTP4x1).srcs(SRC1.RF, SRC2.RF).decode(AQ -> Q4, WQ -> Q1, INC -> U"5'd08")

        // 2-bit Engine Data Path
        add(DOTP2x2).srcs(SRC1.RF, SRC2.RF).decode(AQ -> Q2, WQ -> Q2, INC -> U"5'd00")
        add(DOTP2x1).srcs(SRC1.RF, SRC2.RF).decode(AQ -> Q2, WQ -> Q1, INC -> U"5'd16")

        // 1-bit Engine Data Path
        add(DOTP1x1).srcs(SRC1.RF, SRC2.RF).decode(AQ -> Q1, WQ -> Q1, INC -> U"5'd00")

        //Now that we are done specifying everything about the instructions, we can release the Logic.uopRetainer
        //This will allow a few other plugins to continue their elaboration (ex : decoder, dispatcher, ...)
        uopRetainer.release()

        //Let's define some logic in the execute lane [0]
        val process = new el.Execute(id = 0) {
            //Get the RISC-V RS1/RS2 values from the register file
            val rs1 = el(IntRegFile, RS1)  // rs1 holds the 1st vector (A)
            val rs2 = el(IntRegFile, RS2)  // rs2 holds the 2nd vector (W)

            val rd = Bits(32 bits)
            val offset = Reg(UInt(log2Up(32) bits)) init(0)        

            when(isValid && SEL){
                offset := offset + INC
            }

            val W32b = rs2.asBits
            val W16b = rs2(offset, 16 bits)
            val W8b  = rs2(offset,  8 bits)
            val W4b  = rs2(offset,  4 bits)

            val ToDOTP8 = WQ.mux(
                Q8 -> W32b,
                Q4 -> ExtendTo8b(W16b),
                Q2 -> ExtendTo8b(Extend2bTo4b(W8b)),
                Q1 -> ExtendTo8b(Extend1bTo2b(W4b))
            )
            val ToDOTP4 = WQ.mux(
                Q4 -> W32b,
                Q2 -> Extend2bTo4b(W16b),
                Q1 -> Extend2bTo4b(Extend1bTo2b(W8b)),
                default -> B"32'b0" // Invalid
            )
            val ToDOTP2 = WQ.mux(
                Q2 -> W32b,
                Q1 -> Extend1bTo2b(W16b),
                default -> B"32'b0" // Invalid
            )
            val ToDOTP1 = WQ.mux(
                Q1 -> W32b,
                default -> B"32'b0" // Invalid
            )

            val result = AQ.mux(
                Q8 -> DotProduct(rs1, ToDOTP8, 4),
                Q4 -> DotProduct(rs1, ToDOTP4, 8),
                Q2 -> DotProductSym2Bit(rs1, ToDOTP2),
                Q1 -> DotProductSym1Bit(rs1, ToDOTP1)
            )
            rd := result.asBits
            //Provide the computation value for the writeback
            wb.valid := SEL
            wb.payload := rd
        }
    }
}