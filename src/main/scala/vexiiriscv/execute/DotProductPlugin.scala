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

object MiCoCompute extends AreaObject {
    def DotProduct(op_a : Bits, op_b : Bits, vlen : Int) : SInt = {
        val a_vec = op_a.subdivideIn(vlen slices)
        val b_vec = op_b.subdivideIn(vlen slices)
        val a_tmp = Vec(a_vec.zip(b_vec).map{case (a_i, b_i) => a_i.asSInt * b_i.asSInt})
        a_tmp.reduceBalancedTree(_ +^ _).resize(32)
    }
}

object DotProductPlugin {
    // 8 * 8-bit vector DotP 8  * 8-bit vector
    val DOTP8x8   = IntRegFile.TypeR(M"0000001----------100-----0001011")
}

class DotProductPlugin(val layer : LaneLayer) 
    extends ExecutionUnitElementSimple(layer)  {

    val logic = during setup new Logic {
        awaitBuild()

        //Let's assume we only support RV32 for now
        assert(Riscv.XLEN.get == 32)

        //Let's get the hardware interface that we will use to provide the result of our custom instruction
        val wb = newWriteback(ifp, 0)
        
        import DotProductPlugin._
        import MiCoCompute._
        import SrcKeys._

        // 8-bit Engine Data Path
        add(DOTP8x8).srcs(SRC1.RF, SRC2.RF)

        //Now that we are done specifying everything about the instructions, we can release the Logic.uopRetainer
        //This will allow a few other plugins to continue their elaboration (ex : decoder, dispatcher, ...)
        uopRetainer.release()

        //Let's define some logic in the execute lane [0]
        val process = new el.Execute(id = 0) {
            //Get the RISC-V RS1/RS2 values from the register file
            val rs1 = el(IntRegFile, RS1)  // rs1 holds the 1st vector (A)
            val rs2 = el(IntRegFile, RS2)  // rs2 holds the 2nd vector (W)

            val rd = Bits(32 bits)

            val result = DotProduct(rs1, rs2, 4)
            rd := result.asBits
            //Provide the computation value for the writeback
            wb.valid := SEL
            wb.payload := rd
        }
    }
}