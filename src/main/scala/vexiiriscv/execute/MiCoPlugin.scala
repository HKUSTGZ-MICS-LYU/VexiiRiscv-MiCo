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
    
    def AdderTree(vec: Vec[SInt]) : SInt = {
        vec.reduceBalancedTree(_ +^ _).resize(Riscv.XLEN.get)
    } 

    def DotProduct(op_a : Bits, op_b : Bits, bitWidth : Int) : SInt = {
        val a_vec = op_a.subdivideIn(bitWidth bits)
        val b_vec = op_b.subdivideIn(bitWidth bits)
        val a_tmp = Vec(a_vec.zip(b_vec).map{case (a_i, b_i) => a_i.asSInt * b_i.asSInt})
        a_tmp.reduceBalancedTree(_ +^ _).resize(Riscv.XLEN.get)
    }

    def DotProductSym2Bit(op_a : Bits, op_b : Bits) : SInt = {
        val a_vec = op_a.subdivideIn(2 bits)
        val b_vec = op_b.subdivideIn(2 bits)

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
        a_tmp.reduceBalancedTree(_ +^ _).resize(Riscv.XLEN.get)
    }

    def DotProductSym1Bit(op_a : Bits, op_b : Bits) : SInt = {
        val xor = (op_a ^ op_b).asBools
        val count_n = xor.sCount(True)         // True is -1
        (S(op_a.getWidth) - (count_n << 1).asSInt).resize(32)
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
    def ExtendTo8b(op : Bits, bitWidth: Int) : Bits = {
        // Sign Extend 4-bit to 8-bit
        val ext = op.subdivideIn(bitWidth bits).reverse.map{
            i => i.asSInt.resize(8).asBits
        }.reduce(_ ## _)
        ext
    }
    // def Extend1p5To2b(op : Bits) : Bits = {
    //     // Decoder that decode 1.58-bit to 2-bit
    // }
}

object MiCoPlugin extends AreaObject{
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
}

class MiCoPlugin(val layer : LaneLayer,
                var extractAt : Int = 0,
                var computeAt : Int = 1,
                var formatAt : Int = 1) extends ExecutionUnitElementSimple(layer) {
    
    import MiCoPlugin._
    import MiCoCompute._
    import QType._
    
    val logic = during setup new Logic {
        awaitBuild()
        import SrcKeys._

        val xlen = Riscv.XLEN.get
        val xlenLog2 = log2Up(xlen)

        val OPA = Payload(Bits(xlen bits)) // Operand A (RS1)
        val INC = Payload(UInt(xlenLog2 bits)) // Increment Bits (0, 4, 8, 16, 32)
        val RES = Payload(Bits(xlen bits)) // Result of the Dot Product
        val ToDOTP8, ToDOTP4, ToDOTP2, ToDOTP1 = Payload(Bits(xlen bits))

        //Let's get the hardware interface that we will use to provide the result of our custom instruction
        val wb = newWriteback(ifp, formatAt)

        // 8-bit Engine Data Path
        add(DOTP8x8).srcs(SRC1.RF, SRC2.RF).decode(AQ -> Q8, WQ -> Q8, INC -> U(0, xlenLog2 bits))
        add(DOTP8x4).srcs(SRC1.RF, SRC2.RF).decode(AQ -> Q8, WQ -> Q4, INC -> U(xlen/2, xlenLog2 bits))
        add(DOTP8x2).srcs(SRC1.RF, SRC2.RF).decode(AQ -> Q8, WQ -> Q2, INC -> U(xlen/4, xlenLog2 bits))
        add(DOTP8x1).srcs(SRC1.RF, SRC2.RF).decode(AQ -> Q8, WQ -> Q1, INC -> U(xlen/8, xlenLog2 bits))

        // 4-bit Engine Data Path
        add(DOTP4x4).srcs(SRC1.RF, SRC2.RF).decode(AQ -> Q4, WQ -> Q4, INC -> U(0, xlenLog2 bits))
        add(DOTP4x2).srcs(SRC1.RF, SRC2.RF).decode(AQ -> Q4, WQ -> Q2, INC -> U(xlen/2, xlenLog2 bits))
        add(DOTP4x1).srcs(SRC1.RF, SRC2.RF).decode(AQ -> Q4, WQ -> Q1, INC -> U(xlen/4, xlenLog2 bits))

        // 2-bit Engine Data Path
        add(DOTP2x2).srcs(SRC1.RF, SRC2.RF).decode(AQ -> Q2, WQ -> Q2, INC -> U(0, xlenLog2 bits))
        add(DOTP2x1).srcs(SRC1.RF, SRC2.RF).decode(AQ -> Q2, WQ -> Q1, INC -> U(xlen/2, xlenLog2 bits))

        // 1-bit Engine Data Path
        add(DOTP1x1).srcs(SRC1.RF, SRC2.RF).decode(AQ -> Q1, WQ -> Q1, INC -> U(0, xlenLog2 bits))

        //Now that we are done specifying everything about the instructions, we can release the Logic.uopRetainer
        //This will allow a few other plugins to continue their elaboration (ex : decoder, dispatcher, ...)
        uopRetainer.release()

        val extract = new el.Execute(extractAt) {
            //Get the RISC-V RS1/RS2 values from the register file
            val rs1 = el(IntRegFile, RS1).asBits  // rs1 holds the 1st vector
            val rs2 = el(IntRegFile, RS2).asBits  // rs2 holds the 2nd vector

            val offset = Reg(UInt(xlenLog2 bits)) init(0)

            when(isValid && SEL){
                offset := offset + INC
            }

            val rs2d2 = rs2(offset, xlen/2 bits)
            val rs2d4 = rs2(offset, xlen/4 bits)
            val rs2d8 = rs2(offset, xlen/8 bits)

            ToDOTP8 := WQ.mux(
                Q8 -> rs2,
                Q4 -> ExtendTo8b(rs2d2, bitWidth = 4),
                Q2 -> ExtendTo8b(Extend2bTo4b(rs2d4), bitWidth = 4),
                Q1 -> ExtendTo8b(Extend1bTo2b(rs2d8), bitWidth = 2)
            )
            ToDOTP4 := WQ.mux(
                Q4 -> rs2,
                Q2 -> Extend2bTo4b(rs2d2),
                Q1 -> Extend2bTo4b(Extend1bTo2b(rs2d4)),
                default -> B(0, xlen bits) // Invalid
            )
            ToDOTP2 := WQ.mux(
                Q2 -> rs2,
                Q1 -> Extend1bTo2b(rs2d2),
                default -> B(0, xlen bits) // Invalid
            )
            ToDOTP1 := WQ.mux(
                Q1 -> rs2,
                default -> B(0, xlen bits) // Invalid
            )

            OPA := rs1
        }

        // Staging extract / compute will create 5 * 32/64-bit registers
        // But it can greatly solve the timing issues

        val compute = new el.Execute(computeAt){
            val result = AQ.mux(
                Q8 -> DotProduct(OPA, ToDOTP8, bitWidth = 8),
                Q4 -> DotProduct(OPA, ToDOTP4, bitWidth = 4),
                Q2 -> DotProduct(OPA, ToDOTP2, bitWidth = 2),
                Q1 -> DotProductSym1Bit(OPA, ToDOTP1)
            )
            RES := result.asBits
        }

        val format = new el.Execute(id = formatAt) {
            //Provide the computation value for the writeback
            wb.valid := SEL
            wb.payload := RES
        }
    }
}

class MiCoPluginV2(val layer : LaneLayer,
                var extractAt : Int = 0,
                var prodAt : Int = 1,
                var sumAt : Int = 1,
                var formatAt : Int = 1) extends ExecutionUnitElementSimple(layer) {
    
    import MiCoPlugin._
    import MiCoCompute._
    import QType._
    
    val logic = during setup new Logic {
        awaitBuild()
        import SrcKeys._

        val xlen = Riscv.XLEN.get
        val xlenLog2 = log2Up(xlen)

        val OPA = Payload(Bits(xlen bits)) // Operand A (RS1)
        val INC = Payload(UInt(xlenLog2 bits)) // Increment Bits (0, 4, 8, 16, 32)
        val RES = Payload(Bits(xlen bits)) // Result of the Dot Product
        val ToDOTP8, ToDOTP4, ToDOTP2, ToDOTP1 = Payload(Bits(xlen bits))

        //Let's get the hardware interface that we will use to provide the result of our custom instruction
        val wb = newWriteback(ifp, formatAt)

        // 8-bit Engine Data Path
        add(DOTP8x8).srcs(SRC1.RF, SRC2.RF).decode(AQ -> Q8, WQ -> Q8, INC -> U(0, xlenLog2 bits))
        add(DOTP8x4).srcs(SRC1.RF, SRC2.RF).decode(AQ -> Q8, WQ -> Q4, INC -> U(xlen/2, xlenLog2 bits))
        add(DOTP8x2).srcs(SRC1.RF, SRC2.RF).decode(AQ -> Q8, WQ -> Q2, INC -> U(xlen/4, xlenLog2 bits))
        add(DOTP8x1).srcs(SRC1.RF, SRC2.RF).decode(AQ -> Q8, WQ -> Q1, INC -> U(xlen/8, xlenLog2 bits))

        // 4-bit Engine Data Path
        add(DOTP4x4).srcs(SRC1.RF, SRC2.RF).decode(AQ -> Q4, WQ -> Q4, INC -> U(0, xlenLog2 bits))
        add(DOTP4x2).srcs(SRC1.RF, SRC2.RF).decode(AQ -> Q4, WQ -> Q2, INC -> U(xlen/2, xlenLog2 bits))
        add(DOTP4x1).srcs(SRC1.RF, SRC2.RF).decode(AQ -> Q4, WQ -> Q1, INC -> U(xlen/4, xlenLog2 bits))

        // 2-bit Engine Data Path
        add(DOTP2x2).srcs(SRC1.RF, SRC2.RF).decode(AQ -> Q2, WQ -> Q2, INC -> U(0, xlenLog2 bits))
        add(DOTP2x1).srcs(SRC1.RF, SRC2.RF).decode(AQ -> Q2, WQ -> Q1, INC -> U(xlen/2, xlenLog2 bits))

        // 1-bit Engine Data Path
        add(DOTP1x1).srcs(SRC1.RF, SRC2.RF).decode(AQ -> Q1, WQ -> Q1, INC -> U(0, xlenLog2 bits))

        //Now that we are done specifying everything about the instructions, we can release the Logic.uopRetainer
        //This will allow a few other plugins to continue their elaboration (ex : decoder, dispatcher, ...)
        uopRetainer.release()

        val extract = new el.Execute(extractAt) {
            //Get the RISC-V RS1/RS2 values from the register file
            val rs1 = el(IntRegFile, RS1).asBits  // rs1 holds the 1st vector
            val rs2 = el(IntRegFile, RS2).asBits  // rs2 holds the 2nd vector

            val offset = Reg(UInt(xlenLog2 bits)) init(0)

            when(isValid && SEL){
                offset := offset + INC
            }

            val rs2d2 = rs2(offset, xlen/2 bits)
            val rs2d4 = rs2(offset, xlen/4 bits)
            val rs2d8 = rs2(offset, xlen/8 bits)

            ToDOTP8 := WQ.mux(
                Q8 -> rs2,
                Q4 -> ExtendTo8b(rs2d2, bitWidth = 4),
                Q2 -> ExtendTo8b(Extend2bTo4b(rs2d4), bitWidth = 4),
                Q1 -> ExtendTo8b(Extend1bTo2b(rs2d8), bitWidth = 2)
            )
            ToDOTP4 := WQ.mux(
                Q4 -> rs2,
                Q2 -> Extend2bTo4b(rs2d2),
                Q1 -> Extend2bTo4b(Extend1bTo2b(rs2d4)),
                default -> B(0, xlen bits) // Invalid
            )
            ToDOTP2 := WQ.mux(
                Q2 -> rs2,
                Q1 -> Extend1bTo2b(rs2d2),
                default -> B(0, xlen bits) // Invalid
            )
            ToDOTP1 := WQ.mux(
                Q1 -> rs2,
                default -> B(0, xlen bits) // Invalid
            )

            OPA := rs1
        }

        val PROD8 = Payload(Vec(SInt(16 bits), xlen/8))
        val PROD4 = Payload(Vec(SInt(8 bits), xlen/4))
        val PROD2 = Payload(Vec(SInt(4 bits), xlen/2))
        val PROD1 = Payload(Vec(SInt(2 bits), xlen/1))

        val prod = new el.Execute(prodAt) {
            //Compute the product of the two vectors
            val prod8 = Product(OPA, ToDOTP8, bitWidth = 8)
            val prod4 = Product(OPA, ToDOTP4, bitWidth = 4)
            val prod2 = Product(OPA, ToDOTP2, bitWidth = 2)
            val prod1 = ProductBin(OPA, ToDOTP1)
            
            PROD8.zipWithIndex.foreach{case (p, i) =>
                p := prod8(i)
            }
            PROD4.zipWithIndex.foreach{case (p, i) =>
                p := prod4(i)
            }
            PROD2.zipWithIndex.foreach{case (p, i) =>
                p := prod2(i)
            }
            PROD1.zipWithIndex.foreach{case (p, i) =>
                p := prod1(i)
            }
        }

        val sum = new el.Execute(sumAt) {
            //Compute the sum of the products
            RES := AQ.mux(
                Q8 -> AdderTree(PROD8),
                Q4 -> AdderTree(PROD4),
                Q2 -> AdderTree(PROD2),
                Q1 -> AdderTree(PROD1)
                ).asBits
        }

        val format = new el.Execute(id = formatAt) {
            //Provide the computation value for the writeback
            wb.valid := SEL
            wb.payload := RES
        }
    }
}


class MiCoMultiCyclePlugin(
                val layer : LaneLayer,
                var formatAt : Int = 1,
                var simdWidth : Int = 32) extends ExecutionUnitElementSimple(layer) {
    
    import MiCoPlugin._
    import MiCoCompute._
    import QType._
    
    val logic = during setup new Logic {
        awaitBuild()
        import SrcKeys._

        val xlen = Riscv.XLEN.get
        val xlenLog2 = log2Up(xlen)
        assert(xlen >= simdWidth && (xlen % simdWidth) == 0, "xlen must be a multiple of simdWidth")
        //Let's get the hardware interface that we will use to provide the result of our custom instruction
        val wb = newWriteback(ifp, formatAt)

        // 8-bit Engine Data Path
        add(DOTP8x8).srcs(SRC1.RF, SRC2.RF).decode(AQ -> Q8, WQ -> Q8)
        add(DOTP8x4).srcs(SRC1.RF, SRC2.RF).decode(AQ -> Q8, WQ -> Q4)
        add(DOTP8x2).srcs(SRC1.RF, SRC2.RF).decode(AQ -> Q8, WQ -> Q2)
        add(DOTP8x1).srcs(SRC1.RF, SRC2.RF).decode(AQ -> Q8, WQ -> Q1)

        // 4-bit Engine Data Path
        add(DOTP4x4).srcs(SRC1.RF, SRC2.RF).decode(AQ -> Q4, WQ -> Q4)
        add(DOTP4x2).srcs(SRC1.RF, SRC2.RF).decode(AQ -> Q4, WQ -> Q2)
        add(DOTP4x1).srcs(SRC1.RF, SRC2.RF).decode(AQ -> Q4, WQ -> Q1)

        // 2-bit Engine Data Path
        add(DOTP2x2).srcs(SRC1.RF, SRC2.RF).decode(AQ -> Q2, WQ -> Q2)
        add(DOTP2x1).srcs(SRC1.RF, SRC2.RF).decode(AQ -> Q2, WQ -> Q1)

        // 1-bit Engine Data Path
        add(DOTP1x1).srcs(SRC1.RF, SRC2.RF).decode(AQ -> Q1, WQ -> Q1)


        //Now that we are done specifying everything about the instructions, we can release the Logic.uopRetainer
        //This will allow a few other plugins to continue their elaboration (ex : decoder, dispatcher, ...)
        uopRetainer.release()

        val RES = Payload(Bits(xlen bits)) // Result of the Dot Product

        val process = new el.Execute(0) {
            //Get the RISC-V RS1/RS2 values from the register file
            val rs1 = el(IntRegFile, RS1).asBits  // rs1 holds the 1st vector
            val rs2 = el(IntRegFile, RS2).asBits  // rs2 holds the 2nd vector

            val rs1_offset = Reg(UInt(xlenLog2 bits)) init(0)
            val rs2_offset = Reg(UInt(xlenLog2 bits)) init(0)

            val opa = rs1(rs1_offset, simdWidth bits) // rs1 holds the 1st vector
            val opb = rs2(rs2_offset, simdWidth bits) // rs2 holds the 2nd vector
            val acc = Reg(SInt(xlen bits)) init(0) // Accumulator for the Dot Product

            val opb_d1 = opb(0, simdWidth bits)
            val opb_d2 = opb(0, simdWidth / 2 bits)
            val opb_d4 = opb(0, simdWidth / 4 bits)
            val opb_d8 = opb(0, simdWidth / 8 bits)

            val opbDot8 = WQ.mux(
                Q8 -> opb_d1,
                Q4 -> ExtendTo8b(opb_d2, 4),
                Q2 -> ExtendTo8b(opb_d4, 2),
                Q1 -> ExtendTo8b(Extend1bTo2b(opb_d8), 2),
            )
            val opbDot4 = WQ.mux(
                Q4 -> opb_d1,
                Q2 -> Extend2bTo4b(opb_d2),
                Q1 -> Extend2bTo4b(Extend1bTo2b(opb_d4)),
                default -> B(0, simdWidth bits)
            )
            val opbDot2 = WQ.mux(
                Q2 -> opb_d1,
                Q1 -> Extend1bTo2b(opb_d2),
                default -> B(0, simdWidth bits)
            )
            val opbDot1 = opb_d1

            val partial_sum = AQ.mux(
                Q8 -> DotProduct(opa, opbDot8, bitWidth = 8),
                Q4 -> DotProduct(opa, opbDot4, bitWidth = 4),
                Q2 -> DotProduct(opa, opbDot2, bitWidth = 2),
                Q1 -> DotProductSym1Bit(opa, opbDot1)
            )

            // Multi-Cycle Control
            val request = isValid && SEL
            
            // Calculate how many cycles we need based on data width
            val totalCycles = xlen / simdWidth
            val singleCycle = totalCycles == 1
            val offset_inc = if (singleCycle) 0 else simdWidth
            val cycleCount = if (singleCycle) U(0) else Reg(UInt(log2Up(totalCycles) bits)) init(0)
            val isLastCycle = cycleCount === (totalCycles - 1)

            val acc_add = acc + partial_sum

            val rs2_inc = UInt(xlenLog2 bits)

            rs2_inc := AQ.mux(
                Q8 -> WQ.mux(
                    Q4 -> U(simdWidth / 2, xlenLog2 bits),
                    Q2 -> U(simdWidth / 4, xlenLog2 bits),
                    Q1 -> U(simdWidth / 8, xlenLog2 bits),
                    default -> U(offset_inc, xlenLog2 bits)
                    ),
                Q4 -> WQ.mux(
                    Q2 -> U(simdWidth / 2, xlenLog2 bits),
                    Q1 -> U(simdWidth / 4, xlenLog2 bits),
                    default -> U(offset_inc, xlenLog2 bits)
                ),
                Q2 -> WQ.mux(
                    Q1 -> U(simdWidth / 2, xlenLog2 bits),
                    default -> U(offset_inc, xlenLog2 bits)
                ),
                default -> U(offset_inc, xlenLog2 bits)
            )
            
            when(request){
                rs1_offset := rs1_offset + offset_inc
                rs2_offset := rs2_offset + rs2_inc
                acc := acc_add
                if(!singleCycle) cycleCount := cycleCount + 1
            } otherwise {
                acc := 0
                if(!singleCycle) cycleCount := 0
            }

            val unscheduleRequest = RegNext(isCancel) clearWhen (isReady) init (False)
            val freeze = request && !isLastCycle && !unscheduleRequest
            el.freezeWhen(freeze)

            RES := acc_add.asBits
        }
        val format = new el.Execute(id = formatAt) {
            //Provide the computation value for the writeback
            wb.valid := SEL
            wb.payload := RES
        }
    }
}