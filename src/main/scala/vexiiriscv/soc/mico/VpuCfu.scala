package vexiiriscv.soc.mico

import spinal.core._

import spinal.lib._
import spinal.lib.fsm._
import spinal.lib.bus._
import spinal.lib.bus.misc._
import spinal.lib.bus.tilelink._
import spinal.lib.misc._
import spinal.lib.misc.pipeline._


import vexiiriscv.execute.cfu._

object VectorDotCompute extends AreaObject {

    def DotProduct(op_a : Bits, op_b : Bits, elen : Int) : SInt = {
        val a_vec = op_a.subdivideIn(elen bits)
        val b_vec = op_b.subdivideIn(elen bits)
        val a_tmp = Vec(a_vec.zip(b_vec).map{case (a_i, b_i) => a_i.asSInt * b_i.asSInt})
        a_tmp.reduceBalancedTree(_ +^ _).resized
    }

    def DotProductSym1Bit(op_a : Bits, op_b : Bits) : SInt = {
        val xor = (op_a ^ op_b).asBools
        val count_n = xor.sCount(True)         // True is -1
        (S(op_a.getWidth) - (count_n << 1).asSInt).resized
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
  var vregs : Int = 2,
  var noWaitCompute : Boolean = false,
  var rfRam : Boolean = true,
  var computePipe : Boolean = true
){
    def pendingSize = vlen / xlen
}

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
    val reslen = cfuParam.CFU_OUTPUT_DATA_W


    val io = new Bundle {
        val bus = slave(CfuBus(cfuParam))         // Linking CPU
        val dBus = master(tilelink.Bus(busParam)) // Linking Memory
    }

    import VectorDotCompute._
    val func3 = io.bus.cmd.function_id.asBits

    // Vector Register File
    val vectorRegsReg = Vec(Reg(Bits(vlen bits)) init(0), vregs)
    val vectorRegsBank = p.rfRam generate new Area {
        val banks = Seq.fill(nLoad)(
            Mem(Bits(xlen bits), wordCount = vregs)
        )
        val wdata = Bits(xlen bits)
        val wen = Vec.fill(nLoad)(Bool())
        val waddr = UInt(vregsLog2 bits)

        // Defaults
        wdata := 0
        wen.foreach(_ := False)
        waddr := 0

        for(i <- 0 until nLoad){
            banks(i).write(
                address = waddr,
                data = wdata,
                enable = wen(i)
            )
        }
    }

    def vectorRead(addr: UInt): Bits = {
        if (p.rfRam){
            val reads = vectorRegsBank.banks.map(_.readAsync(addr))
            reads.reverse.reduce(_ ## _)
        } else {
            vectorRegsReg(addr)
        }
    }
    def vectorWrite(addr: UInt, index: UInt, data: Bits): Unit = {
        if (p.rfRam){
            vectorRegsBank.waddr := addr
            vectorRegsBank.wdata := data
            vectorRegsBank.wen(index) := True
        } else {
            val offset = index.resized << log2Up(xlen)
            vectorRegsReg(addr)(offset, xlen bits) := data
        }
    }

    val isLoad   = func3 === B"100"
    val isConfig = func3 === B"010"
    val isVDot   = func3 === B"001"

    // CFU response defaults
    io.bus.rsp.valid := False
    io.bus.rsp.response_id := io.bus.cmd.request_id
    if (cfuParam.CFU_WITH_STATUS) io.bus.rsp.status := B"000"

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

    val rs1_offset = Reg(UInt(vlenLog2 bits)) init(0)
    val rs2_offset = Reg(UInt(vlenLog2 bits)) init(0)
    
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
        val isFirst = p.noWaitCompute.mux(rs1_offset === 0, False)
        val rs1 = vectorRead(isFirst.mux(decode.RS1, RS1))
        val rs2 = vectorRead(isFirst.mux(decode.RS2, RS2))
    }

    val compute = new Area {

        // Global Signals
        val acc = Reg(SInt(reslen bits)) init(0)
        val sel = Bool()
        val doneNow = (rs1_offset === (vlen - maclen)) && sel

        sel := False // Default Unsel

        // Pipeline Nodes
        val usePipe = p.computePipe
        val nStages = if(usePipe) 1 else 0
        
        val stages = Array.fill(nStages + 1)(Node())

        val extractStage = stages(0)
        val computeStage = stages(nStages)

        // Offset Shift
        when(sel){
            rs1_offset := rs1_offset + offset_max
            rs2_offset := rs2_offset + config.inc
        }

        val SEL = Payload(Bool())
        val DONE = Payload(Bool())
        val OPA = Payload(Bits(maclen bits))
        val OPB = Payload(Bits(maclen bits))

        // Extract Stage
        val extract = new extractStage.Area {
            val opa = rfRead.rs1(rs1_offset, maclen bits)
            val opb = rfRead.rs2
            val opb_d1 = opb(rs2_offset, maclen bits)
            val opb_d2 = opb(rs2_offset, maclen / 2 bits)
            val opb_d4 = opb(rs2_offset, maclen / 4 bits)
            val opb_d8 = opb(rs2_offset, maclen / 8 bits)

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
            val opbExtract = config.qa.mux(
                U(8) -> opbDot8,
                U(4) -> opbDot4,
                U(2) -> opbDot2,
                U(1) -> opbDot1,
                default -> B(0, maclen bits)
            )

            // Pipeline Signals
            SEL := sel
            DONE := doneNow
            OPA := opa
            OPB := opbExtract
        }

        val dotproduct = new computeStage.Area {
            val partial = SInt(reslen bits)
            val res = acc + partial

            val opaStage = OPA
            val opbStage = OPB
            val doAcc = SEL

            partial := config.qa.mux(
                U(8) -> DotProduct(opaStage, opbStage, 8),
                U(4) -> DotProduct(opaStage, opbStage, 4),
                U(2) -> DotProduct(opaStage, opbStage, 2),
                U(1) -> DotProductSym1Bit(opaStage, opbStage),
                default -> S(0, reslen bits)
            )
            when(doAcc){
                if(nCompute != 1) acc := res
            }
        }

        val res = dotproduct.res
        val done = computeStage(DONE)

        // Pipeline Stages
        if (usePipe){
            val links = for (i <- 0 to nStages - 1) yield StageLink(stages(i), stages(i + 1))
            Builder(links)
        }
    }

    val baseAddr = Reg(UInt(32 bits)) init(0)
    val offsetAddr = Reg(UInt(32 bits)) init(0)
    val offsetNext = offsetAddr + (xlen / 8)
    val accessAddr = baseAddr + offsetAddr
    val cmdLast = offsetNext === (vlen / 8)
    val loadVecHits = Vec.fill(nLoad)(RegInit(False))
    val loadVecCount = loadVecHits.sCount(True)
    val rspLast = loadVecCount === (nLoad - 1)

    // Load
    val memValid = RegInit(False)
    val memReady = RegInit(False)
    val memFireId = Reg(UInt(log2Up(vlen / xlen) bits)) init(0)
    
    // Tilelink bus defaults
    val mask = B(xlen / 8 bits, default -> True)

    io.dBus.a.opcode  := tilelink.Opcode.A.GET
    io.dBus.a.param   := tilelink.Param.Hint.NO_ALLOCATE_ON_MISS
    io.dBus.a.source  := memFireId
    io.dBus.a.data    := 0
    io.dBus.a.address := accessAddr
    io.dBus.a.mask    := mask
    io.dBus.a.size    := log2Up(xlen / 8)
    io.dBus.a.corrupt := False
    io.dBus.a.valid   := memValid
    io.dBus.d.ready   := memReady

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
                    if(p.noWaitCompute){
                        compute.sel := True
                        if(nCompute == 1 && !p.computePipe) {
                            io.bus.rsp.valid := True
                            io.bus.rsp.outputs(0) := compute.res.asBits
                        } else {
                            goto(VDOTP)
                        }
                    } else {
                        goto(VDOTP)
                    }
                }
                when(isConfig) {
                    io.bus.rsp.valid := True
                    // Write Config
                    config.qa := decode.QA
                    config.qb := decode.QB
                    // Clear Compute
                    rs1_offset := 0
                    rs2_offset := 0
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
            baseAddr := io.bus.cmd.inputs(0).asUInt.resized
            LoadRD := io.bus.cmd.raw_insn(24 downto 20).resize(vregsLog2).asUInt // rd = rs2
            offsetAddr := 0
            memFireId := 0
            memValid := True
            memReady := True
            loadVecHits.foreach(_ := False)
        }
        LOAD.whenIsActive {
            when(io.dBus.a.fire) {
                // report(L"[Memory Test] Command Sent: address 0x$accessAddr")
                offsetAddr := offsetNext
                if (nLoad != 1){memFireId :=  memFireId + 1}
                when (cmdLast) {
                    memValid := False
                }
            }
            when(io.dBus.d.fire) {
                // report(L"[Memory Test] Read data 0x${io.dBus.d.data} from address 0x$accessAddr")
                when (rspLast) {
                    memReady := False
                    io.bus.rsp.valid := True
                    goto(IDLE)
                }
                loadVecHits(io.dBus.d.source) := True
                val loadIndex = io.dBus.d.source
                val loadData = io.dBus.d.data
                vectorWrite(LoadRD, loadIndex, loadData)
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
