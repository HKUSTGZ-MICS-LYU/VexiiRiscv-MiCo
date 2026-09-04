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

  def BitNetDot(opA: Bits, opW: Bits, lanes: Int, qType: UInt, resWidth: Int, withQ2: Boolean): SInt = {
    val laneWidth = if(withQ2) 10 else 9
    val aLanes = opA.subdivideIn(8 bits)
    val w1Lanes = opW.subdivideIn(1 bits)
    val w2Lanes = opW.subdivideIn(2 bits)
    val partials = for(i <- 0 until lanes) yield new Area {
      val a = aLanes(i).asSInt.resize(laneWidth)
      val negA = -a
      val zero = S(0, laneWidth bits)
      val neg2A = if(withQ2) (-(a |<< 1).resized) else zero
      val w1 = w1Lanes(i).asBool
      val w2 = w2Lanes(i).asUInt
      val value = SInt(laneWidth bits)

      value := zero
      when(qType === U(Q1B, qType.getWidth bits)) {
        when(w1) {
          value := negA
        } otherwise {
          value := a
        }
      } elsewhen(qType === U(Q15B, qType.getWidth bits)) {
        switch(w2) {
          is(U"2'b01") { value := a }
          is(U"2'b11") { value := negA }
        }
      } otherwise {
        switch(w2) {
          is(U"2'b01") { value := a }
          is(U"2'b11") { value := negA }
          if(withQ2) {
            is(U"2'b10") { value := neg2A }
          }
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
  var regDepth : Int = 2,
  var qType : String = "1.5b",
  var withQ2 : Boolean = false,
  var withQ2T : Boolean = true,
  var withQ8 : Boolean = false,
  var quantWidth : Int = 0,
  var noWaitCompute : Boolean = false,
  var rfRam : Boolean = true,
  var rfSync : Boolean = true,
  var computePipe : Boolean = false,
  var q8ComparePipe : Boolean = false,
  var quantStandard : Boolean = false,
  var burstLoad : Boolean = false,
  var asicSram : Boolean = false
) {
  def quantWidthEffective = if(quantWidth == 0) vlen min 128 else quantWidth
  def pendingSize = vlen / xlen
  def maxLoadBytes = if(burstLoad) vlen / 8 else xlen / 8
  def singleCycle = noWaitCompute && (vlen == maclen) && !computePipe && !rfSync
  def qTypeId = BitNetCfuCompute.qTypeId(qType)
}

class BitNetCfu(cfuParam: CfuBusParameter,
                busParam: BusParameter,
                p: BitNetCfuParameter) extends Component {
  import BitNetCfuCompute._

  val xlen = busParam.dataWidth
  val vlen = p.vlen
  val regDepth = p.regDepth
  val maclen = p.maclen
  val quantWidth = p.quantWidthEffective
  val lanes = maclen / 8
  val quantLanes = quantWidth / 32
  val q2tResultLanes = vlen / 32
  val weightSliceBitsMax = lanes * 2
  val dotLaneWidth = if(p.withQ2) 10 else 9
  val dotAccWidth = dotLaneWidth + log2Up(vlen / 8)
  val loadBytes = vlen / 8
  val beatBytes = xlen / 8
  val vlenLog2 = log2Up(vlen)
  val regSelWidth = log2Up(regDepth) max 1
  val nLoad = vlen / xlen
  val nCompute = vlen / maclen
  val weightChunkCount = vlen / lanes
  val weightChunkIndexWidth = log2Up(weightChunkCount) max 1
  val weightCursorWidth = log2Up(weightChunkCount + 1) max 1
  val computeChunkWidth = log2Up(nCompute) max 1
  val quantChunks = vlen / quantWidth
  val reslen = cfuParam.CFU_OUTPUT_DATA_W

  assert(RiscvBits.isPow2(vlen), "BitNetCfu vlen must be a power of two")
  assert(RiscvBits.isPow2(xlen), "BitNetCfu xlen must be a power of two")
  assert(RiscvBits.isPow2(maclen), "BitNetCfu maclen must be a power of two")
  assert(vlen % xlen == 0, "BitNetCfu vlen must be a multiple of xlen")
  assert(vlen % maclen == 0, "BitNetCfu vlen must be a multiple of maclen")
  assert(maclen % 8 == 0, "BitNetCfu maclen must hold complete int8 activation lanes")
  assert(!p.burstLoad || RiscvBits.isPow2(loadBytes), "BitNetCfu burst load size must be a power of two")
  assert(!p.burstLoad || loadBytes <= 4096, "BitNetCfu burst load size must fit TileLink transfer range")
  assert(weightSliceBitsMax <= vlen, "BitNetCfu weight slice must fit in one vector register")
  assert(regDepth >= 2, "BitNetCfu must have at least two vector registers")
  assert(p.withQ2 || p.qType != "2b", "BitNetCfu qType=2b requires --bitnet-cfu-with-q2")
  assert(!p.rfSync || p.rfRam, "BitNetCfu sync vector RF requires RAM-backed vector registers")
  assert(!p.asicSram || (p.rfRam && p.rfSync), "BitNetCfu ASIC SRAM mode requires a synchronous RAM-backed vector RF")
  assert(!p.rfSync || !p.noWaitCompute, "BitNetCfu sync vector RF does not support noWaitCompute")
  if(p.withQ2T || p.withQ8) {
    assert(RiscvBits.isPow2(quantWidth), "BitNetCfu quantWidth must be a power of two")
    assert(quantWidth <= vlen, "BitNetCfu quantWidth must fit in one vector register")
    assert(vlen % quantWidth == 0, "BitNetCfu vlen must be a multiple of quantWidth")
    assert(quantWidth % 32 == 0, "BitNetCfu quantWidth must hold complete FP32 lanes")
    assert(quantWidth <= 128, "BitNetCfu quantWidth is capped by the 32-bit Q8 packed response")
  }
  if(p.withQ2T) {
    assert(q2tResultLanes * 2 <= reslen, "BitNetCfu Q2T VLEN result must fit in the CFU response")
  }
  if(p.withQ8) {
    assert(quantLanes * 8 <= reslen, "BitNetCfu Q8 result must fit in the CFU response")
    assert(quantChunks <= 128, "BitNetCfu Q8 chunk index is encoded in func7")
  }
  val io = new Bundle {
    val bus = slave(CfuBus(cfuParam))
    val dBus = master(tilelink.Bus(busParam))
  }

  val func3 = io.bus.cmd.function_id.asBits
  // BNCFU custom0 ISA:
  // func3=5: Q8 rd=int8_quant(absmax_bits=rs1 value, fp32_reg=rs2 raw index, chunk=func7)
  // func3=4: LOAD rs1=address value, rs2=vector register index encoded in raw instruction
  // func3=3: Q2T rd=ternary_quant(absmax_bits=rs1 value, fp32_reg=rs2 raw index)
  // func3=2: CONFIG/RESET
  // func3=1: BDOT rd=dot(int8_reg=rs1 raw index, lowbit_reg=rs2 raw index), advance low-bit cursor
  // func3=0: BDOT_HOLD, same operands/result, keep low-bit cursor for operand reuse
  val isQ8     = if(p.withQ8) func3 === B"101" else False
  val isLoad   = func3 === B"100"
  val isQ2T    = if(p.withQ2T) func3 === B"011" else False
  val isConfig = func3 === B"010"
  val isBDot   = func3 === B"001"
  val isBDotHold = func3 === B"000"
  val isDot = isBDot || isBDotHold

  val vecRegsReg = Vec(Reg(Bits(vlen bits)) init(0), regDepth)
  val vecRegsBank = p.rfRam generate new Area {
    val banks = Seq.fill(nLoad)(Mem(Bits(xlen bits), wordCount = regDepth))
    if (p.asicSram) banks.foreach(_.generateAsBlackBox())
    val wdata = Bits(xlen bits)
    val wen = Vec.fill(nLoad)(Bool())
    val waddr = UInt(regSelWidth bits)
    val raddr = if(p.rfSync) Vec(UInt(regSelWidth bits), 2) else null
    val ren = if(p.rfSync) Vec(Bool(), 2) else null
    val rdata = if(p.rfSync) Vec(Bits(vlen bits), 2) else null

    wdata := 0
    wen.foreach(_ := False)
    waddr := 0
    if(p.rfSync) {
      raddr.foreach(_ := 0)
      ren.foreach(_ := False)
    }

    for(i <- 0 until nLoad) {
      banks(i).write(
        address = waddr,
        data = wdata,
        enable = wen(i)
      )
    }

    if(p.rfSync) {
      for(port <- 0 until 2) {
        val reads = banks.map(_.readSync(raddr(port), ren(port)))
        rdata(port) := reads.reverse.reduce(_ ## _)
      }
    }
  }

  def vecRead(addr: UInt, port: Int = 0): Bits = {
    if(p.rfRam) {
      if(p.rfSync) {
        vecRegsBank.rdata(port)
      } else {
        val reads = vecRegsBank.banks.map(_.readAsync(addr))
        reads.reverse.reduce(_ ## _)
      }
    } else {
      vecRegsReg(addr)
    }
  }

  def vecReadSyncCmd(addr: UInt, port: Int = 0): Unit = {
    if(p.rfRam && p.rfSync) {
      vecRegsBank.raddr(port) := addr
      vecRegsBank.ren(port) := True
    }
  }

  def vecWrite(addr: UInt, index: UInt, data: Bits): Unit = {
    if(p.rfRam) {
      vecRegsBank.waddr := addr
      vecRegsBank.wdata := data
      vecRegsBank.wen(index) := True
    } else {
      val offset = index.resized << log2Up(xlen)
      vecRegsReg(addr)(offset, xlen bits) := data
    }
  }

  io.bus.rsp.valid := False
  io.bus.rsp.response_id := io.bus.cmd.request_id
  io.bus.rsp.outputs(0) := 0
  if(cfuParam.CFU_WITH_STATUS) io.bus.rsp.status := B"000"

  val decode = new Area {
    val FUNC7 = io.bus.cmd.raw_insn(31 downto 25).asUInt
    val RS1_RAW = io.bus.cmd.raw_insn(19 downto 15).asUInt
    val RS2_RAW = io.bus.cmd.raw_insn(24 downto 20).asUInt
    val RS1 = io.bus.cmd.raw_insn(19 downto 15).asUInt.resize(regSelWidth)
    val RS2 = io.bus.cmd.raw_insn(24 downto 20).asUInt.resize(regSelWidth)
  }

  val loadRD = Reg(UInt(regSelWidth bits)) init(0)
  val weightCursors = Vec(Reg(UInt(weightCursorWidth bits)) init(0), regDepth)

  val config = new Area {
    val qType = Reg(UInt(2 bits)) init(U(p.qTypeId, 2 bits))
    val qTypeCmd = decode.RS1_RAW.resize(2)
    val qTypeValid = qTypeCmd === U(Q1B, 2 bits) || qTypeCmd === U(Q2B, 2 bits) || qTypeCmd === U(Q15B, 2 bits)
    val weightStep = UInt(2 bits)
    weightStep := (qType === U(Q1B, 2 bits)).mux(U(1, 2 bits), U(2, 2 bits))
  }

  val rfRead = new Area {
    val RS1 = Reg(UInt(regSelWidth bits)) init(0)
    val RS2 = Reg(UInt(regSelWidth bits)) init(1)
    val ADVANCE = Reg(Bool()) init(True)
    val lowbitCursorReg = Reg(UInt(weightCursorWidth bits)) init(0)

    when(io.bus.cmd.fire && isDot) {
      RS1 := decode.RS1
      RS2 := decode.RS2
      ADVANCE := isBDot
      lowbitCursorReg := weightCursors(decode.RS2)
    }

    val useCmd = p.noWaitCompute.mux(io.bus.cmd.fire && isDot, False)
    val rs1 = useCmd.mux(decode.RS1, RS1)
    val rs2 = useCmd.mux(decode.RS2, RS2)
    val advanceLowbit = useCmd.mux(isBDot, ADVANCE)
    val int8 = vecRead(rs1, 0)
    val lowbit = vecRead(rs2, if(p.rfSync) 1 else 0)
    val weightCursor = useCmd.mux(weightCursors(decode.RS2), lowbitCursorReg)
    val weightChunkIndex = weightCursor(weightChunkIndexWidth - 1 downto 0)
  }

  val q2tRead = p.withQ2T generate new Area {
    val ABSMAX = Reg(Bits(32 bits)) init(0)
    val RS2 = Reg(UInt(regSelWidth bits)) init(0)

    when(io.bus.cmd.fire && isQ2T) {
      ABSMAX := io.bus.cmd.inputs(0).asBits.resize(32)
      RS2 := decode.RS2
    }
  }

  val q8Read = p.withQ8 generate new Area {
    val ABSMAX = Reg(Bits(32 bits)) init(0)
    val RS2 = Reg(UInt(regSelWidth bits)) init(0)
    val OFFSET = Reg(UInt(vlenLog2 bits)) init(0)

    when(io.bus.cmd.fire && isQ8) {
      ABSMAX := io.bus.cmd.inputs(0).asBits.resize(32)
      RS2 := decode.RS2
      OFFSET := (decode.FUNC7.resize(vlenLog2) |<< log2Up(quantWidth)).resize(vlenLog2)
    }
  }

  val compute = new Area {
    val acc = Reg(SInt(dotAccWidth bits)) init(0)
    val chunkIndex = Reg(UInt(computeChunkWidth bits)) init(0)
    val sel = Bool()
    val doneNow = (if(nCompute == 1) True else chunkIndex === U(nCompute - 1, computeChunkWidth bits)) && sel

    sel := False

    when(io.bus.cmd.fire && isDot) {
      chunkIndex := 0
    }

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

    val shiftInt8 = sel && !doneNow
    val shiftLowbitCursor = sel && !doneNow
    when(shiftInt8) {
      if(nCompute != 1) chunkIndex := chunkIndex + 1
    }
    when(shiftLowbitCursor) {
      rfRead.lowbitCursorReg := (rfRead.weightCursor + config.weightStep).resize(weightCursorWidth)
    }

    val extract = new extractStage.Area {
      val int8Chunks = rfRead.int8.subdivideIn(maclen bits)
      val weightChunks = rfRead.lowbit.subdivideIn(lanes bits)
      val nextWeightChunk = (rfRead.weightChunkIndex + U(1, weightChunkIndexWidth bits)).resize(weightChunkIndexWidth)
      val opwQ1 = weightChunks(rfRead.weightChunkIndex).resize(weightSliceBitsMax)
      val opwWide = (weightChunks(nextWeightChunk) ## weightChunks(rfRead.weightChunkIndex)).resize(weightSliceBitsMax)
      // Keep wide dot operands quiet when this pipeline slot is invalid.
      // SEL/DONE retain their original timing and the active-cycle operands
      // remain unchanged.
      OPA := sel.mux(int8Chunks(chunkIndex), B(0, maclen bits))
      OPW := sel.mux(
        (config.qType === U(Q1B, 2 bits)).mux(opwQ1, opwWide),
        B(0, weightSliceBitsMax bits)
      )
      QTYPE := config.qType
      SEL := sel
      DONE := doneNow
    }

    val dot = new computeStage.Area {
      val partial = BitNetDot(OPA, OPW, lanes, QTYPE, dotAccWidth, p.withQ2)
      val res = (acc + partial).resize(dotAccWidth)

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

  val quant = (p.withQ2T || p.withQ8) generate new Area {
    val result = Bits(reslen bits)
    val done = Bool()

    result := 0
    done := False

    val selQ2T = if(p.withQ2T) Bool() else null
    val selQ8 = if(p.withQ8) Bool() else null

    if(p.withQ2T) selQ2T := False
    if(p.withQ8) selQ8 := False

    val busy = RegInit(False)
    val doneReg = RegInit(False)
    val modeQ8 = Reg(Bool()) init(False)
    val offset = Reg(UInt(vlenLog2 bits)) init(0)
    val laneActive = RegInit(False)
    val absReg = Reg(Bits(32 bits)) init(0)
    val opReg = Reg(Bits(quantWidth bits)) init(0)
    val resultReg = Reg(Bits(reslen bits)) init(0)
    val quantLaneParam = BitQuantLaneParameter(
      maxQuantBits = if(p.withQ8) 8 else 2,
      comparePipe = p.q8ComparePipe
    )
    val qBits2 = U(2, quantLaneParam.qBitsWidth bits)
    val absMagnitude = absReg(30 downto 0).asUInt
    val absExponent = absReg(30 downto 23).asUInt
    val absPartsDecoded = BitQuantCompute.fp32MagnitudeParts(absMagnitude)
    val absParts = new BitQuantAbsmaxParts

    absParts.valid := absMagnitude =/= 0 && absExponent =/= U(255, 8 bits)
    absParts.effectiveExponent := absPartsDecoded.effectiveExponent
    absParts.significand := absPartsDecoded.significand

    val quantLanesIo = Array.tabulate(quantLanes) { _ =>
      if(p.quantStandard) {
        val lane = new BitQuantLane(quantLaneParam)
        lane.io
      } else {
        val lane = new BitQuantNormalizedLane(quantLaneParam)
        lane.io
      }
    }
    val quantLaneDone = quantLanesIo.map(_.done).reduce(_ && _)
    val q2tPacked = Bits(2 * quantLanes bits)
    val q8Packed = Bits(reslen bits)

    q2tPacked := 0
    q8Packed := 0
    for(i <- 0 until quantLanes) {
      if(p.withQ8) {
        quantLanesIo(i).qBits := modeQ8.mux(U(8, quantLaneParam.qBitsWidth bits), qBits2)
      } else {
        quantLanesIo(i).qBits := qBits2
      }
      quantLanesIo(i).absmax := absReg
      quantLanesIo(i).absParts := absParts
      quantLanesIo(i).value := opReg(32 * i, 32 bits)
      q2tPacked(2 * i, 2 bits) := quantLanesIo(i).result(0, 2 bits)
      if(p.withQ8) q8Packed(8 * i, 8 bits) := quantLanesIo(i).result(0, 8 bits)
    }

    val selected = (if(p.withQ2T) selQ2T && !modeQ8 else False) || (if(p.withQ8) selQ8 && modeQ8 else False)
    val launch = selected && busy && !laneActive && !doneReg

    for(i <- 0 until quantLanes) {
      quantLanesIo(i).start := launch
    }
    when(launch) {
      laneActive := True
    }

    val lastQ2TChunk = offset === U(vlen - quantWidth, vlenLog2 bits)
    val lastQuantChunk = (if(p.withQ8) modeQ8 else False) || (if(p.withQ2T) !modeQ8 && lastQ2TChunk else False)

    when(laneActive && quantLaneDone) {
      laneActive := False
      if(p.withQ8) {
        when(modeQ8) {
          resultReg := q8Packed
        }
      }
      if(p.withQ2T) {
        when(!modeQ8) {
          for(chunk <- 0 until quantChunks) {
            when(offset === U(chunk * quantWidth, vlenLog2 bits)) {
              resultReg(2 * chunk * quantLanes, 2 * quantLanes bits) := q2tPacked
            }
          }
        }
      }
      when(lastQuantChunk) {
        busy := False
        doneReg := True
      } otherwise {
        if(p.withQ2T) {
          val nextOffset = offset + U(quantWidth, vlenLog2 bits)
          offset := nextOffset
          opReg := vecRead(q2tRead.RS2, 0)(nextOffset, quantWidth bits)
        }
      }
    }

    val startQ2T = if(p.withQ2T) selQ2T && !busy && !doneReg else False
    val startQ8 = if(p.withQ8) selQ8 && !busy && !doneReg else False

    if(p.withQ2T) {
      when(startQ2T) {
        busy := True
        doneReg := False
        modeQ8 := False
        laneActive := False
        offset := 0
        absReg := q2tRead.ABSMAX
        opReg := vecRead(q2tRead.RS2, 0)(0, quantWidth bits)
        resultReg := 0
      }
    }
    if(p.withQ8) {
      when(startQ8) {
        busy := True
        doneReg := False
        modeQ8 := True
        laneActive := False
        offset := q8Read.OFFSET
        absReg := q8Read.ABSMAX
        opReg := vecRead(q8Read.RS2, 0)(q8Read.OFFSET, quantWidth bits)
        resultReg := 0
      }
    }
    when(!(if(p.withQ2T) selQ2T else False) && !(if(p.withQ8) selQ8 else False)) {
      when(doneReg) {
        doneReg := False
      }
    }

    when(doneReg) {
      result := resultReg
      done := True
    }
  }

  val baseAddr = Reg(UInt(32 bits)) init(0)
  val offsetAddr = Reg(UInt(32 bits)) init(0)
  val offsetNext = offsetAddr + beatBytes
  val accessAddr = baseAddr + offsetAddr
  val loadBurst = if(p.burstLoad) RegInit(False) else False
  val loadVecHits = Vec.fill(nLoad)(RegInit(False))
  val loadVecCount = loadVecHits.sCount(True)
  val nonBurstRspLast = loadVecCount === (nLoad - 1)

  val memValid = RegInit(False)
  val memReady = RegInit(False)
  val memFireId = Reg(UInt(log2Up(vlen / xlen) bits)) init(0)
  val mask = B(beatBytes bits, default -> True)
  val cmdLast = (if(p.burstLoad) loadBurst else False) || offsetNext === loadBytes
  val loadSize = if(p.burstLoad) loadBurst.mux(U(log2Up(loadBytes), widthOf(io.dBus.a.size) bits), U(log2Up(beatBytes), widthOf(io.dBus.a.size) bits)) else U(log2Up(beatBytes), widthOf(io.dBus.a.size) bits)
  val loadAddress = if(p.burstLoad) loadBurst.mux(baseAddr, accessAddr) else accessAddr
  val loadSource = if(p.burstLoad) loadBurst.mux(U(0, widthOf(io.dBus.a.source) bits), memFireId.resized) else memFireId.resized

  io.dBus.a.opcode  := tilelink.Opcode.A.GET
  io.dBus.a.param   := tilelink.Param.Hint.NO_ALLOCATE_ON_MISS
  io.dBus.a.source  := loadSource
  io.dBus.a.data    := 0
  io.dBus.a.address := loadAddress
  io.dBus.a.mask    := mask
  io.dBus.a.size    := loadSize
  io.dBus.a.corrupt := False
  io.dBus.a.valid   := memValid
  io.dBus.d.ready   := memReady

  val fsm = new StateMachine {
    val IDLE = new State with EntryPoint
    val LOAD = new State
    val BDOTP = new State
    val Q2TP = p.withQ2T generate new State
    val Q8P = p.withQ8 generate new State

    IDLE.whenIsActive {
      when(io.bus.cmd.fire) {
        when(isLoad) {
          io.bus.rsp.valid := True
          goto(LOAD)
        }
        when(isDot) {
          if(p.rfSync) {
            vecReadSyncCmd(decode.RS1, 0)
            vecReadSyncCmd(decode.RS2, 1)
            goto(BDOTP)
          } else if(p.noWaitCompute) {
            compute.sel := True
            if(p.singleCycle) {
              io.bus.rsp.valid := True
              io.bus.rsp.outputs(0) := compute.res.resize(reslen).asBits
              when(rfRead.advanceLowbit) {
                weightCursors(rfRead.rs2) := (rfRead.weightCursor + config.weightStep).resize(weightCursorWidth)
              }
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
          when(config.qTypeValid) {
            config.qType := config.qTypeCmd
          } otherwise {
            config.qType := U(p.qTypeId, 2 bits)
          }
          weightCursors.foreach(_ := U(0, weightCursorWidth bits))
          rfRead.lowbitCursorReg := U(0, weightCursorWidth bits)
          compute.chunkIndex := 0
          compute.acc := 0
        }
        if(p.withQ2T) {
          when(isQ2T) {
            if(p.rfSync) {
              vecReadSyncCmd(decode.RS2, 0)
            }
            goto(Q2TP)
          }
        }
        if(p.withQ8) {
          when(isQ8) {
            if(p.rfSync) {
              vecReadSyncCmd(decode.RS2, 0)
            }
            goto(Q8P)
          }
        }
      }
    }

    if(p.withQ2T) {
      Q2TP.whenIsActive {
        quant.selQ2T := (if(p.computePipe) !quant.done else True)
        when(quant.done) {
          io.bus.rsp.valid := True
          io.bus.rsp.outputs(0) := quant.result
          goto(IDLE)
        }
      }
    }

    if(p.withQ8) {
      Q8P.whenIsActive {
        quant.selQ8 := !quant.done
        when(quant.done) {
          io.bus.rsp.valid := True
          io.bus.rsp.outputs(0) := quant.result
          goto(IDLE)
        }
      }
    }

    BDOTP.whenIsActive {
      compute.sel := (if(p.computePipe) !compute.done else True)
      when(compute.done) {
        io.bus.rsp.valid := True
        io.bus.rsp.outputs(0) := compute.res.resize(reslen).asBits
        when(rfRead.advanceLowbit) {
          weightCursors(rfRead.rs2) := (rfRead.weightCursor + config.weightStep).resize(weightCursorWidth)
        }
        compute.acc := 0
        goto(IDLE)
      }
    }

    LOAD.onEntry {
      baseAddr := io.bus.cmd.inputs(0).asUInt.resized
      loadRD := decode.RS2
      offsetAddr := 0
      memFireId := 0
      memValid := True
      memReady := True
      if(p.burstLoad) {
        if(nLoad == 1) {
          loadBurst := False
        } else {
          val base = io.bus.cmd.inputs(0).asUInt
          loadBurst := base(log2Up(loadBytes) - 1 downto 0) === 0
        }
      }
      loadVecHits.foreach(_ := False)
      weightCursors(decode.RS2) := U(0, weightCursorWidth bits)
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
        if(p.burstLoad) {
          when(loadBurst) {
            vecWrite(loadRD, io.dBus.d.beatCounter().resized, io.dBus.d.data)
            when(io.dBus.d.isLast()) {
              memReady := False
              goto(IDLE)
            }
          } otherwise {
            when(nonBurstRspLast) {
              memReady := False
              goto(IDLE)
            }
            loadVecHits(io.dBus.d.source) := True
            vecWrite(loadRD, io.dBus.d.source, io.dBus.d.data)
          }
        } else {
          when(loadVecCount === (nLoad - 1)) {
            memReady := False
            goto(IDLE)
          }
          loadVecHits(io.dBus.d.source) := True
          vecWrite(loadRD, io.dBus.d.source, io.dBus.d.data)
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
