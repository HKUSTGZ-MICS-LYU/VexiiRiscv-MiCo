package vexiiriscv.soc.mico

import spinal.core._

import spinal.lib._
import spinal.lib.fsm._
import spinal.lib.bus._
import spinal.lib.bus.misc._
import spinal.lib.bus.tilelink._
import spinal.lib.misc._


import vexiiriscv.execute.cfu._

object VectorDotCompute extends AreaObject {

    def DotProduct(op_a : Bits, op_b : Bits, elen : Int) : SInt = {
        val a_vec = op_a.subdivideIn(elen bits)
        val b_vec = op_b.subdivideIn(elen bits)
        val a_tmp = Vec(a_vec.zip(b_vec).map{case (a_i, b_i) => a_i.asSInt * b_i.asSInt})
        a_tmp.reduceBalancedTree(_ +^ _).resize(32)
    }

    def DotProductSym1Bit(op_a : Bits, op_b : Bits) : SInt = {
        val xor = (op_a ^ op_b).asBools
        val count_n = xor.sCount(True)         // True is -1
        (S(op_a.getWidth) - (count_n << 1).asSInt).resize(32)
    }

    def Extend1bTo2b(op : Bits) : Bits = {
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
}

case class VpuCfuParameter(
  var vlen : Int = 128,
  var xlen : Int = 64,
  var maclen : Int = 32,
  var vregs : Int = 2
)

class VpuCfu(cfuParam: CfuBusParameter, 
            busParam: BusParameter, 
            p: VpuCfuParameter) extends Component {

    val xlen = busParam.dataWidth
    val vlen = p.vlen
    val vregs = p.vregs
    val maclen = p.maclen
    val offset_max = maclen % vlen // when maclen == vlen, offset_max = 0
    val vlenLog2 = log2Up(vlen)
    val vregsLog2 = log2Up(vregs)
    val nLoad = vlen / xlen
    val nCompute = vlen / maclen

    val io = new Bundle {
        val bus = slave(CfuBus(cfuParam))         // Linking CPU
        val dBus = master(tilelink.Bus(busParam)) // Linking Memory
    }

    import VectorDotCompute._
    val func3 = io.bus.cmd.function_id.asBits

    // Vector Register File (TODO: Full FF implementation is not efficient!)
    val vectorRegs = Vec(Reg(Bits(vlen bits)) init(0), vregs)

    val accessAddr = Reg(UInt(32 bits)) init(0)

    // Load
    val memValid = RegInit(False)
    val memReady = RegInit(False)
    val loadVecOffset = Reg(UInt(log2Up(nLoad) bits)) init(0)
    val bufferArray = Vec(Reg(Bits(xlen bits)) init(0), nLoad - 1)
    
    val isLoad   = func3 === B"100"
    val isConfig = func3 === B"010"
    val isVDot   = func3 === B"001"

    // CFU response defaults
    io.bus.rsp.valid := False
    io.bus.rsp.response_id := io.bus.cmd.request_id
    if (cfuParam.CFU_WITH_STATUS) io.bus.rsp.status := B"000"

    // Tilelink bus defaults
    val mask = B(xlen / 8 bits, default -> True)
    io.dBus.a.opcode  := tilelink.Opcode.A.GET
    io.dBus.a.param   := 0
    io.dBus.a.source  := 0
    io.dBus.a.data    := 0
    io.dBus.a.address := accessAddr
    io.dBus.a.mask    := mask
    io.dBus.a.size    := log2Up(xlen / 8)
    io.dBus.a.corrupt := False
    io.dBus.a.valid   := memValid
    io.dBus.d.ready   := memReady

    val decode = new Area {
        val RS1 = UInt(vregsLog2 bits)
        val RS2 = UInt(vregsLog2 bits)
        val QA = UInt(4 bits) // Element width for vector register 8/4/2/1 bits
        val QB = UInt(4 bits) // Element width for vector register 8/4/2/1 bits
        QA := io.bus.cmd.raw_insn(19 downto 15).resize(4).asUInt // rs1
        QB := io.bus.cmd.raw_insn(24 downto 20).resize(4).asUInt // rs2
        RS1 := io.bus.cmd.raw_insn(19 downto 15).resize(vregsLog2).asUInt // rs1
        RS2 := io.bus.cmd.raw_insn(24 downto 20).resize(vregsLog2).asUInt // rs2
    }

    val LoadRD = Reg(UInt(vregsLog2 bits)) init(0)

    val shared_offset = Reg(UInt(vlenLog2 bits)) init(0)
    val sub_offset = Reg(UInt(vlenLog2 bits)) init(0)

    val rs1 = vectorRegs(decode.RS1)
    val rs2 = vectorRegs(decode.RS2)

    val config = new Area {
        val qa = Reg(UInt(4 bits)) init(8) // Element width for vector register 8/4/2/1 bits
        val qb = Reg(UInt(4 bits)) init(8) // Element width for vector register 8/4/2/1 bits
        val inc = UInt(vlenLog2 bits) // Increment for vector register offset
        
        inc := qa.mux(
            U(8) -> qb.mux(
                U(4) -> U(maclen / 2, vlenLog2 bits),
                U(2) -> U(maclen / 4, vlenLog2 bits),
                U(1) -> U(maclen / 8, vlenLog2 bits),
                default -> U(offset_max, vlenLog2 bits)
            ),
            U(4) -> qb.mux(
                U(2) -> U(maclen / 2, vlenLog2 bits),
                U(1) -> U(maclen / 4, vlenLog2 bits),
                default -> U(offset_max, vlenLog2 bits)
            ),
            U(2) -> qb.mux(
                U(1) -> U(maclen / 2, vlenLog2 bits),
                default -> U(offset_max, vlenLog2 bits)
            ),
            default -> U(offset_max, vlenLog2 bits)
        )
    }

    val rfRead = new Area {
        val RS1 = Reg(UInt(vregsLog2 bits)) init(0)
        val RS2 = Reg(UInt(vregsLog2 bits)) init(0)
        when(io.bus.cmd.fire && isVDot){
            RS1 := decode.RS1
            RS2 := decode.RS2
        }
        val rs1 = vectorRegs(RS1)
        val rs2 = vectorRegs(RS2)
    }

    val compute = new Area {

        val isFirst = shared_offset === 0
        val opa = isFirst.mux(
            rs1(shared_offset, maclen bits), 
            rfRead.rs1(shared_offset, maclen bits)) // A Look-ahead Logic to save one cycle
        val opb = isFirst.mux(
            rs2(shared_offset, maclen bits), 
            rfRead.rs2(shared_offset, maclen bits)) // A Look-ahead Logic to save one cycle
        // Extract
        val opb_d1 = opb(sub_offset, maclen bits)
        val opb_d2 = opb(sub_offset, maclen / 2 bits)
        val opb_d4 = opb(sub_offset, maclen / 4 bits)
        val opb_d8 = opb(sub_offset, maclen / 8 bits)

        val opbDot8 = config.qb.mux(
            U(8) -> opb_d1,
            U(4) -> ExtendTo8b(opb_d2, 4),
            U(2) -> ExtendTo8b(opb_d4, 2),
            U(1) -> ExtendTo8b(Extend1bTo2b(opb_d8), 2),
            default -> B(0, maclen bits)
        )
        val opbDot4 = config.qb.mux(
            U(4) -> opb_d1,
            U(2) -> Extend2bTo4b(opb_d2),
            U(1) -> Extend2bTo4b(Extend1bTo2b(opb_d4)),
            default -> B(0, maclen bits)
        )
        val opbDot2 = config.qb.mux(
            U(2) -> opb_d1,
            U(1) -> Extend1bTo2b(opb_d2),
            default -> B(0, maclen bits)
        )
        val opbDot1 = opb_d1
    
        // Compute
        val acc = Reg(SInt(32 bits)) init(0)
        val sel = Bool()
        val done = Bool()
        val partial = SInt(32 bits)
        val res = acc + partial

        sel := False // Default Unsel

        partial := config.qa.mux(
            U(8) -> DotProduct(opa, opbDot8, 8),
            U(4) -> DotProduct(opa, opbDot4, 4),
            U(2) -> DotProduct(opa, opbDot2, 2),
            U(1) -> DotProductSym1Bit(opa, opbDot1),
            default -> S(0, 32 bits)
        )

        done := (shared_offset === (vlen - maclen))

        when(sel){
            shared_offset := shared_offset + offset_max
            sub_offset := sub_offset + config.inc
            if(nCompute != 1) acc := res
        }
    }

    val VpuFsm = new StateMachine {
        val IDLE = new State with EntryPoint
        val LOAD = new State
        val VDOTP = new State

        IDLE.whenIsActive {
            when(io.bus.cmd.fire){
                when(isLoad) {
                    goto(LOAD)
                }
                when(isVDot) {
                    compute.sel := True // Start compute as soon as cmd arrives
                    if(nCompute == 1) {
                        io.bus.rsp.valid := True
                        io.bus.rsp.outputs(0) := compute.res.asBits
                    } else{
                        goto(VDOTP)
                    }
                }
                when(isConfig) {
                    io.bus.rsp.valid := True
                    // Write Config
                    config.qa := decode.QA
                    config.qb := decode.QB
                    // Clear Compute
                    shared_offset := 0
                    sub_offset := 0
                    compute.acc := 0
                }
            }
        }

        VDOTP.whenIsActive{
            compute.sel := True
            when(compute.done){
                io.bus.rsp.valid := True
                io.bus.rsp.outputs(0) := compute.res.asBits
                compute.acc := 0
                goto(IDLE)
            }
        }

        // Loading State
        LOAD.onEntry {
            accessAddr := io.bus.cmd.inputs(0).asUInt
            LoadRD := io.bus.cmd.raw_insn(24 downto 20).resize(vregsLog2).asUInt // rd = rs2
            loadVecOffset := 0
            memValid := True
        }
        LOAD.whenIsActive {
            when(io.dBus.a.fire) {
                // report(L"[Memory Test] Command Sent: address 0x$accessAddr")
                accessAddr := accessAddr + (xlen / 8)
                memValid := False
                memReady := True
            }
            when(io.dBus.d.fire) {
                // report(L"[Memory Test] Read data 0x${io.dBus.d.data} from address 0x$accessAddr")
                loadVecOffset := loadVecOffset + 1
                memReady := False
                if(nLoad == 1){
                    io.bus.rsp.valid := True
                    vectorRegs(LoadRD) := io.dBus.d.data
                    goto(IDLE)
                }
                else{
                    when(loadVecOffset === (nLoad - 1)) {
                        io.bus.rsp.valid := True
                        vectorRegs(LoadRD) := io.dBus.d.data ## bufferArray.asBits
                        goto(IDLE)
                    } otherwise {
                        if(nLoad == 2){
                            bufferArray(0) := io.dBus.d.data
                        }else{
                            bufferArray(loadVecOffset) := io.dBus.d.data
                        }
                        memValid := True
                    }
                }
            }
        }
        LOAD.onExit {
            memValid := False
            memReady := False
        }
    }

    // CFU command ready
    io.bus.cmd.ready := VpuFsm.isActive(VpuFsm.IDLE)
    io.bus.rsp.outputs(0) := 0 // Default output
}