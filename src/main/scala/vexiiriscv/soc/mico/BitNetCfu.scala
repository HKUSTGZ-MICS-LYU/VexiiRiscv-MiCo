package vexiiriscv.soc.mico

import spinal.core._
import spinal.lib._
import spinal.lib.bus._
import spinal.lib.bus.tilelink._
import spinal.lib.fsm._
import spinal.lib.misc.pipeline._

import vexiiriscv.execute.cfu._

object BitNetCfuCompute extends AreaObject {
  val Q1B = 1
  val Q2B = 2
  val Q15B = 3

  def qTypeId(qType: String): Int = qType match {
    case "1b"   => Q1B
    case "1.5b" => Q15B
    case "2b"   => Q2B
  }

  def BitNetDot(opA: Bits, opW: Bits, lanes: Int, qType: UInt, resWidth: Int): SInt = {
    val partials = for(i <- 0 until lanes) yield new Area {
      val a = opA(i * 8, 8 bits).asSInt.resize(10)
      val w1 = opW(i)
      val w2 = opW(i * 2, 2 bits).asUInt
      val value = SInt(10 bits)

      value := 0
      when(qType === U(Q1B, qType.getWidth bits)) {
        when(w1) {
          value := -a
        } otherwise {
          value := a
        }
      } elsewhen(qType === U(Q15B, qType.getWidth bits)) {
        switch(w2) {
          is(U"2'b01") { value := a }
          is(U"2'b11") { value := -a }
        }
      } otherwise {
        switch(w2) {
          is(U"2'b01") { value := a }
          is(U"2'b11") { value := -a }
          is(U"2'b10") { value := -(a |<< 1).resized }
        }
      }
    }

    Vec(partials.map(_.value)).reduceBalancedTree(_ +^ _).resize(resWidth)
  }
}

case class BitNetCfuParameter(
  var vlen : Int = 256,
  var xlen : Int = 32,
  var maclen : Int = 256,
  var vregs : Int = 2,
  var qType : String = "1.5b",
  var noWaitCompute : Boolean = false,
  var rfRam : Boolean = true,
  var computePipe : Boolean = false
) {
  def pendingSize = vlen / xlen
  def singleCycle = noWaitCompute && (vlen == maclen) && !computePipe
  def qTypeId = BitNetCfuCompute.qTypeId(qType)
}

class BitNetCfu(cfuParam: CfuBusParameter,
                busParam: BusParameter,
                p: BitNetCfuParameter) extends Component {
  import BitNetCfuCompute._

  val xlen = busParam.dataWidth
  val vlen = p.vlen
  val vregs = p.vregs
  val maclen = p.maclen
  val lanes = maclen / 8
  val weightSliceBitsMax = lanes * 2
  val vlenLog2 = log2Up(vlen)
  val vregsLog2 = log2Up(vregs)
  val nLoad = vlen / xlen
  val nCompute = vlen / maclen
  val reslen = cfuParam.CFU_OUTPUT_DATA_W

  assert(RiscvBits.isPow2(vlen), "BitNetCfu vlen must be a power of two")
  assert(RiscvBits.isPow2(xlen), "BitNetCfu xlen must be a power of two")
  assert(RiscvBits.isPow2(maclen), "BitNetCfu maclen must be a power of two")
  assert(vlen % xlen == 0, "BitNetCfu vlen must be a multiple of xlen")
  assert(vlen % maclen == 0, "BitNetCfu vlen must be a multiple of maclen")
  assert(maclen % 8 == 0, "BitNetCfu maclen must hold complete int8 activation lanes")
  assert(weightSliceBitsMax <= vlen, "BitNetCfu weight slice must fit in one vector register")

  val io = new Bundle {
    val bus = slave(CfuBus(cfuParam))
    val dBus = master(tilelink.Bus(busParam))
  }

  val func3 = io.bus.cmd.function_id.asBits
  val isLoad   = func3 === B"100"
  val isConfig = func3 === B"010"
  val isBDot   = func3 === B"001"

  val vectorRegsReg = Vec(Reg(Bits(vlen bits)) init(0), vregs)
  val vectorRegsBank = p.rfRam generate new Area {
    val banks = Seq.fill(nLoad)(Mem(Bits(xlen bits), wordCount = vregs))
    val wdata = Bits(xlen bits)
    val wen = Vec.fill(nLoad)(Bool())
    val waddr = UInt(vregsLog2 bits)

    wdata := 0
    wen.foreach(_ := False)
    waddr := 0

    for(i <- 0 until nLoad) {
      banks(i).write(
        address = waddr,
        data = wdata,
        enable = wen(i)
      )
    }
  }

  def vectorRead(addr: UInt): Bits = {
    if(p.rfRam) {
      val reads = vectorRegsBank.banks.map(_.readAsync(addr))
      reads.reverse.reduce(_ ## _)
    } else {
      vectorRegsReg(addr)
    }
  }

  def vectorWrite(addr: UInt, index: UInt, data: Bits): Unit = {
    if(p.rfRam) {
      vectorRegsBank.waddr := addr
      vectorRegsBank.wdata := data
      vectorRegsBank.wen(index) := True
    } else {
      val offset = index.resized << log2Up(xlen)
      vectorRegsReg(addr)(offset, xlen bits) := data
    }
  }

  io.bus.rsp.valid := False
  io.bus.rsp.response_id := io.bus.cmd.request_id
  io.bus.rsp.outputs(0) := 0
  if(cfuParam.CFU_WITH_STATUS) io.bus.rsp.status := B"000"

  val decode = new Area {
    val RS1 = UInt(vregsLog2 bits)
    val RS2 = UInt(vregsLog2 bits)

    RS1 := io.bus.cmd.raw_insn(19 downto 15).resize(vregsLog2).asUInt
    RS2 := io.bus.cmd.raw_insn(24 downto 20).resize(vregsLog2).asUInt
  }

  val loadRD = Reg(UInt(vregsLog2 bits)) init(0)
  val rs1Offset = Reg(UInt(vlenLog2 bits)) init(0)
  val rs2Offset = Reg(UInt(vlenLog2 bits)) init(0)

  val config = new Area {
    val qType = U(p.qTypeId, 2 bits)
    val weightInc = UInt(vlenLog2 bits)

    weightInc := qType.mux(
      U(Q1B) -> U(lanes, vlenLog2 bits),
      default -> U(weightSliceBitsMax, vlenLog2 bits)
    )
  }

  val rfRead = new Area {
    val RS1 = Reg(UInt(vregsLog2 bits)) init(0)
    val RS2 = Reg(UInt(vregsLog2 bits)) init(0)

    when(io.bus.cmd.fire && isBDot) {
      RS1 := decode.RS1
      RS2 := decode.RS2
    }

    val isFirst = p.noWaitCompute.mux(rs1Offset === 0, False)
    val rs1 = vectorRead(isFirst.mux(decode.RS1, RS1))
    val rs2 = vectorRead(isFirst.mux(decode.RS2, RS2))
  }

  val compute = new Area {
    val acc = Reg(SInt(reslen bits)) init(0)
    val sel = Bool()
    val doneNow = (if(nCompute == 1) True else rs1Offset === U(vlen - maclen, vlenLog2 bits)) && sel

    sel := False

    val usePipe = p.computePipe
    val nStages = if(usePipe) 1 else 0
    val stages = Array.fill(nStages + 1)(Node())
    val extractStage = stages(0)
    val computeStage = stages(nStages)

    val SEL = Payload(Bool())
    val DONE = Payload(Bool())
    val OPA = Payload(Bits(maclen bits))
    val OPW = Payload(Bits(weightSliceBitsMax bits))
    val QTYPE = Payload(UInt(2 bits))

    val shiftRs1 = sel && !doneNow
    val shiftRs2 = sel
    when(shiftRs1) {
      if(nCompute != 1) rs1Offset := rs1Offset + U(maclen, vlenLog2 bits)
    }
    when(shiftRs2) {
      rs2Offset := rs2Offset + config.weightInc
    }

    val extract = new extractStage.Area {
      OPA := rfRead.rs1(rs1Offset, maclen bits)
      OPW := rfRead.rs2(rs2Offset, weightSliceBitsMax bits)
      QTYPE := config.qType
      SEL := sel
      DONE := doneNow
    }

    val dot = new computeStage.Area {
      val partial = BitNetDot(OPA, OPW, lanes, QTYPE, reslen)
      val res = acc + partial

      when(SEL) {
        if(nCompute != 1) acc := res
      } otherwise {
        acc := 0
      }
    }

    val res = dot.res
    val done = computeStage(DONE)

    if(usePipe) {
      val links = for(i <- 0 until nStages) yield StageLink(stages(i), stages(i + 1))
      Builder(links)
    }
  }

  val baseAddr = Reg(UInt(32 bits)) init(0)
  val offsetAddr = Reg(UInt(32 bits)) init(0)
  val offsetNext = offsetAddr + (xlen / 8)
  val accessAddr = baseAddr + offsetAddr
  val loadVecHits = Vec.fill(nLoad)(RegInit(False))
  val loadVecCount = loadVecHits.sCount(True)
  val rspLast = loadVecCount === (nLoad - 1)

  val memValid = RegInit(False)
  val memReady = RegInit(False)
  val memFireId = Reg(UInt(log2Up(vlen / xlen) bits)) init(0)
  val mask = B(xlen / 8 bits, default -> True)
  val cmdLast = offsetNext === (vlen / 8)

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

  val fsm = new StateMachine {
    val IDLE = new State with EntryPoint
    val LOAD = new State
    val BDOTP = new State

    IDLE.whenIsActive {
      when(io.bus.cmd.fire) {
        when(isLoad) {
          goto(LOAD)
        }
        when(isBDot) {
          if(p.noWaitCompute) {
            compute.sel := True
            if(p.singleCycle) {
              io.bus.rsp.valid := True
              io.bus.rsp.outputs(0) := compute.res.asBits
              rs1Offset := 0
              rs2Offset := 0
              compute.acc := 0
            } else {
              goto(BDOTP)
            }
          } else {
            goto(BDOTP)
          }
        }
        when(isConfig) {
          io.bus.rsp.valid := True
          rs1Offset := 0
          rs2Offset := 0
          compute.acc := 0
        }
      }
    }

    BDOTP.whenIsActive {
      compute.sel := (if(p.computePipe) !compute.done else True)
      when(compute.done) {
        io.bus.rsp.valid := True
        io.bus.rsp.outputs(0) := compute.res.asBits
        rs1Offset := 0
        compute.acc := 0
        goto(IDLE)
      }
    }

    LOAD.onEntry {
      baseAddr := io.bus.cmd.inputs(0).asUInt.resized
      loadRD := io.bus.cmd.raw_insn(24 downto 20).resize(vregsLog2).asUInt
      offsetAddr := 0
      memFireId := 0
      memValid := True
      memReady := True
      loadVecHits.foreach(_ := False)
    }

    LOAD.whenIsActive {
      when(io.dBus.a.fire) {
        offsetAddr := offsetNext
        if(nLoad != 1) memFireId := memFireId + 1
        when(cmdLast) {
          memValid := False
        }
      }

      when(io.dBus.d.fire) {
        when(rspLast) {
          memReady := False
          io.bus.rsp.valid := True
          goto(IDLE)
        }
        loadVecHits(io.dBus.d.source) := True
        vectorWrite(loadRD, io.dBus.d.source, io.dBus.d.data)
      }
    }

    LOAD.onExit {
      memValid := False
      memReady := False
    }
  }

  io.bus.cmd.ready := fsm.isActive(fsm.IDLE)
}

private object RiscvBits {
  def isPow2(value: Int): Boolean = value > 0 && ((value & (value - 1)) == 0)
}
