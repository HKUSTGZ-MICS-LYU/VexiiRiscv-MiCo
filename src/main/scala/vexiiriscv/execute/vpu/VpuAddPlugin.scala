package vexiiriscv.execute.vpu

import spinal.core._
import spinal.core.sim.SpinalSimConfig
import spinal.lib._
import spinal.lib.pipeline.Stageable
import vexiiriscv.execute._
import vexiiriscv.decode
import spinal.lib.misc.pipeline._
import vexiiriscv.Generate.args
import vexiiriscv.{Global, ParamSimple, VexiiRiscv}
import vexiiriscv.compat.MultiPortWritesSymplifier
import vexiiriscv.riscv.{VectorRegFile, RS1, RS2, Riscv, VectorExt}
import vexiiriscv.tester.TestOptions

object VpuAddCompute extends AreaObject {
    def VecAdd(op_a: Bits, op_b: Bits, vlen: Int): Bits = {   // width 8 bits
        val a_vec = op_a.subdivideIn(vlen bits)
        val b_vec = op_b.subdivideIn(vlen bits)
        Vec(a_vec.zip(b_vec).map{case (a_i, b_i) => (a_i.asSInt +^ b_i.asSInt).resize(vlen)}).asBits
    }
}

class VpuAddPlugin(val layer: LaneLayer)
    extends ExecutionUnitElementSimple(layer) {

    val logic = during setup new Logic {
        awaitBuild()
        val config = host[VpuConfigService]
        val wbp = host.find[WriteBackPlugin](p => p.rf == VectorRegFile && p.lane == layer.lane)

        import VpuAddCompute._
        import SrcKeys._

        val wb = wbp.createPort(at = 0)
        add(VectorExt.VecADD).decode(SEL -> True)

        val spec = layer(VectorExt.VecADD)
        spec.addRsSpec(RS1, executeAt = 0)
        spec.addRsSpec(RS2, executeAt = 0)
        spec.setCompletion(0)
        wbp.addMicroOp(wb, spec)

        uopRetainer.release()

        val process = new el.Execute(id = 0) {

            val rs1 = el(VectorRegFile, RS1)  // rs1 holds the 1st vector
            val rs2 = el(VectorRegFile, RS2)  // rs2 holds the 2nd vector

            val rd = Bits(128 bits)

            val result = config.getRS1Width().mux(
                U(3) -> VecAdd(rs1, rs2, 8),
                U(2) -> VecAdd(rs1, rs2, 4),
                U(1) -> VecAdd(rs1, rs2, 2),
                default -> B(0, 128 bits)
            )

            rd := result.asBits
            
            wb.valid := isValid && SEL
            wb.payload := rd
        }
    }
}