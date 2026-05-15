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
  var weightBanks : Int = 1,
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
  val weightBanks = p.weightBanks
  val maclen = p.maclen
  val lanes = maclen / 8
  val weightSliceBitsMax = lanes * 2
  val vlenLog2 = log2Up(vlen)
  val weightBankLog2 = log2Up(weightBanks) max 1
  val loadSelWidth = log2Up(weightBanks + 1) max 1
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
  assert(weightBanks >= 1, "BitNetCfu must have at least one low-bit weight bank")

  val io = new Bundle {
    val bus = slave(CfuBus(cfuParam))
    val dBus = master(tilelink.Bus(busParam))
  }

  val func3 = io.bus.cmd.function_id.asBits
  val isLoad   = func3 === B"100"
  val isConfig = func3 === B"010"
  val isBDot   = func3 === B"001"
  val int8Reg = Vec(Reg(Bits(xlen bits)) init(0), nLoad)
  val weightRegsReg = Vec(Reg(Bits(vlen bits)) init(0), weightBanks)
  val weightRegsBank = p.rfRam generate new Area {
    val banks = Seq.fill(nLoad)(Mem(Bits(xlen bits), wordCount = weightBanks))
    val wdata = Bits(xlen bits)
    val wen = Vec.fill(nLoad)(Bool())
    val waddr = UInt(weightBankLog2 bits)

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

  def int8Read(): Bits = int8Reg.reverse.reduce(_ ## _)

  def int8Write(index: UInt, data: Bits): Unit = {
    int8Reg(index) := data
  }

  def weightRead(addr: UInt): Bits = {
    if(p.rfRam) {
      val reads = weightRegsBank.banks.map(_.readAsync(addr))
      reads.reverse.reduce(_ ## _)
    } else {
      if(weightBanks == 1) weightRegsReg(0) else weightRegsReg(addr)
    }
  }

  def weightWrite(addr: UInt, index: UInt, data: Bits): Unit = {
    if(p.rfRam) {
      weightRegsBank.waddr := addr
      weightRegsBank.wdata := data
      weightRegsBank.wen(index) := True
    } else {
      val offset = index.resized << log2Up(xlen)
      if(weightBanks == 1) {
        weightRegsReg(0)(offset, xlen bits) := data
      } else {
        weightRegsReg(addr)(offset, xlen bits) := data
      }
    }
  }

  io.bus.rsp.valid := False
  io.bus.rsp.response_id := io.bus.cmd.request_id
  io.bus.rsp.outputs(0) := 0
  if(cfuParam.CFU_WITH_STATUS) io.bus.rsp.status := B"000"

  val decode = new Area {
    val BANK = UInt(loadSelWidth bits)

    BANK := io.bus.cmd.inputs(1).asUInt.resize(loadSelWidth)
  }

  val loadRD = Reg(UInt(loadSelWidth bits)) init(0)
  val rs1Offset = Reg(UInt(vlenLog2 bits)) init(0)
  val weightOffsets = Vec(Reg(UInt(vlenLog2 bits)) init(0), weightBanks)

  val config = new Area {
    val qType = U(p.qTypeId, 2 bits)
    val weightInc = UInt(vlenLog2 bits)

    weightInc := qType.mux(
      U(Q1B) -> U(lanes, vlenLog2 bits),
      default -> U(weightSliceBitsMax, vlenLog2 bits)
    )
  }

  val rfRead = new Area {
    val BANK = Reg(UInt(loadSelWidth bits)) init(1)

    when(io.bus.cmd.fire && isBDot) {
      BANK := decode.BANK
    }

    val isFirst = p.noWaitCompute.mux(rs1Offset === 0, False)
    val sel = isFirst.mux(decode.BANK, BANK)
    val bank = (sel - U(1, loadSelWidth bits)).resize(weightBankLog2)
    val int8 = int8Read()
    val weight = weightRead(bank)
    val weightOffset = if(weightBanks == 1) weightOffsets(0) else weightOffsets(bank)
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
      if(weightBanks == 1) {
        weightOffsets(0) := rfRead.weightOffset + config.weightInc
      } else {
        weightOffsets(rfRead.bank) := rfRead.weightOffset + config.weightInc
      }
    }

    val extract = new extractStage.Area {
      val opwQ1 = rfRead.weight(rfRead.weightOffset, lanes bits).resize(weightSliceBitsMax)
      val opwWide = rfRead.weight(rfRead.weightOffset, weightSliceBitsMax bits)
      OPA := rfRead.int8(rs1Offset, maclen bits)
      OPW := (config.qType === U(Q1B, 2 bits)).mux(opwQ1, opwWide)
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
          weightOffsets.foreach(_ := U(0, vlenLog2 bits))
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
      loadRD := decode.BANK
      offsetAddr := 0
      memFireId := 0
      memValid := True
      memReady := True
      loadVecHits.foreach(_ := False)
      when(decode.BANK =/= 0) {
        val bank = (decode.BANK - U(1, loadSelWidth bits)).resize(weightBankLog2)
        if(weightBanks == 1) {
          weightOffsets(0) := U(0, vlenLog2 bits)
        } else {
          weightOffsets(bank) := U(0, vlenLog2 bits)
        }
      }
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
        when(loadRD === 0) {
          int8Write(io.dBus.d.source, io.dBus.d.data)
        } otherwise {
          weightWrite((loadRD - U(1, loadSelWidth bits)).resize(weightBankLog2), io.dBus.d.source, io.dBus.d.data)
        }
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
