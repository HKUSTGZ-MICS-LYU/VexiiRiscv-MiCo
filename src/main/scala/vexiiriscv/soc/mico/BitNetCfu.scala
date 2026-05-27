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
    val aLanes = opA.subdivideIn(8 bits)
    val w1Lanes = opW.subdivideIn(1 bits)
    val w2Lanes = opW.subdivideIn(2 bits)
    val partials = for(i <- 0 until lanes) yield new Area {
      val a = aLanes(i).asSInt.resize(10)
      val negA = -a
      val zero = S(0, 10 bits)
      val neg2A = if(withQ2) (-(a |<< 1).resized) else zero
      val w1 = w1Lanes(i).asBool
      val w2 = w2Lanes(i).asUInt
      val value = SInt(10 bits)

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

  def BitNetQ2TFast(absmax: Bits, op: Bits, lanes: Int, resWidth: Int): Bits = {
    val absmaxMagnitude = absmax(30 downto 0).asUInt
    val absmaxExponent = absmax(30 downto 23).asUInt
    val absmaxFraction = absmax(22 downto 0)
    val absmaxIsValid = absmaxMagnitude =/= 0 && absmaxExponent =/= U(255, 8 bits)

    val threshold = UInt(31 bits)
    val normalHalfExponent = (absmaxExponent - 1).asBits
    val minNormalHalfFraction = ((B"1'b1" ## absmaxFraction).asUInt |>> 1).asBits.resize(23)
    val subnormalHalfFraction = (absmaxFraction.asUInt |>> 1).asBits.resize(23)

    threshold := 0
    when(absmaxExponent > U(1, 8 bits)) {
      threshold := (normalHalfExponent ## absmaxFraction).asUInt
    } elsewhen(absmaxExponent === U(1, 8 bits)) {
      threshold := (B(0, 8 bits) ## minNormalHalfFraction).asUInt
    } otherwise {
      threshold := (B(0, 8 bits) ## subnormalHalfFraction).asUInt
    }

    val fp32 = op.subdivideIn(32 bits)
    val packed = Bits(resWidth bits)
    packed := 0

    for(i <- 0 until lanes) {
      val lane = fp32(i)
      val magnitude = lane(30 downto 0).asUInt
      val code = Bits(2 bits)

      code := B"00"
      when(absmaxIsValid && magnitude =/= 0 && magnitude >= threshold) {
        code := lane(31).mux(B"11", B"01")
      }
      packed(2 * i, 2 bits) := code
    }

    packed
  }

  def fp32MagnitudeParts(magnitude: UInt) = new Area {
    val exponent = magnitude(30 downto 23)
    val fraction = magnitude(22 downto 0)
    val effectiveExponent = exponent.mux(
      U(0, 8 bits) -> U(1, 8 bits),
      default -> exponent
    )
    val significand = exponent.mux(
      U(0, 8 bits) -> (B"1'b0" ## fraction).asUInt,
      default -> (B"1'b1" ## fraction).asUInt
    )
  }

  def fp32ScaledGte(aExponent: UInt, aProduct: UInt, bExponent: UInt, bProduct: UInt): Bool = {
    val productWidth = aProduct.getWidth max bProduct.getWidth
    val productWideWidth = productWidth * 2
    val expDiffWidth = 9
    val shiftWidth = log2Up(productWideWidth)
    val aExpGte = aExponent >= bExponent
    val expDiff = aExpGte.mux(
      (aExponent.resize(expDiffWidth) - bExponent.resize(expDiffWidth)).resize(expDiffWidth),
      (bExponent.resize(expDiffWidth) - aExponent.resize(expDiffWidth)).resize(expDiffWidth)
    )
    val expDiffLarge = expDiff >= U(productWidth, expDiffWidth bits)
    val aWide = aProduct.resize(productWideWidth)
    val bWide = bProduct.resize(productWideWidth)
    val shiftInput = aExpGte.mux(aWide, bWide)
    val shiftedProduct = (shiftInput |<< expDiff.resize(shiftWidth)).resize(productWideWidth)
    val result = Bool()

    result := !expDiffLarge && aWide >= shiftedProduct
    when(aExpGte) {
      result := expDiffLarge || shiftedProduct >= bWide
    }

    result
  }

  def BitNetQ(absmax: Bits, op: Bits, lanes: Int, intN: Int, symmetric: Boolean, resWidth: Int): Bits = {
    require(intN >= 2 && intN <= 8, "BitNetQ supports signed INT2..INT8 quantization")
    require(lanes * intN <= resWidth, "BitNetQ packed result must fit in resWidth")

    val absmaxMagnitude = absmax(30 downto 0).asUInt
    val absmaxExponent = absmax(30 downto 23).asUInt
    val absmaxIsValid = absmaxMagnitude =/= 0 && absmaxExponent =/= U(255, 8 bits)
    val qMaxPositive = (1 << (intN - 1)) - 1
    val qMaxNegative = if(symmetric) qMaxPositive else 1 << (intN - 1)
    val qMaxMagnitude = qMaxPositive max qMaxNegative
    val qScaleWidth = log2Up((qMaxMagnitude << 1) + 1) max 1
    val thresholdMulWidth = log2Up(qMaxMagnitude << 1) max 1
    val productWidth = 24 + (qScaleWidth max thresholdMulWidth)
    val absmaxParts = fp32MagnitudeParts(absmaxMagnitude)
    val thresholdProducts = for(value <- 1 to qMaxMagnitude) yield {
      (absmaxParts.significand * U((value << 1) - 1, thresholdMulWidth bits)).resize(productWidth)
    }

    val fp32 = op.subdivideIn(32 bits)
    val packed = Bits(resWidth bits)
    packed := 0

    for(i <- 0 until lanes) {
      val lane = fp32(i)
      val magnitude = lane(30 downto 0).asUInt
      val laneParts = fp32MagnitudeParts(magnitude)
      val qMax = UInt(intN bits)
      val qScale = UInt(qScaleWidth bits)
      val scaledMagnitude = UInt(productWidth bits)
      val level = UInt(intN bits)
      val code = Bits(intN bits)

      qMax := lane(31).mux(U(qMaxNegative, intN bits), U(qMaxPositive, intN bits))
      qScale := (qMax.resize(qScaleWidth) |<< 1).resize(qScaleWidth)
      scaledMagnitude := (laneParts.significand * qScale).resize(productWidth)

      level := 0
      for(value <- 1 to qMaxMagnitude) {
        when(absmaxIsValid && magnitude =/= 0 && U(value, intN bits) <= qMax &&
             fp32ScaledGte(laneParts.effectiveExponent, scaledMagnitude, absmaxParts.effectiveExponent, thresholdProducts(value - 1))) {
          level := U(value, intN bits)
        }
      }
      code := lane(31).mux((U(0, intN bits) - level).asBits, level.asBits)
      packed(intN * i, intN bits) := code
    }
    packed
  }

  def BitNetQ2T(absmax: Bits, op: Bits, lanes: Int, resWidth: Int): Bits = {
    BitNetQ2TFast(absmax, op, lanes, resWidth)
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
  var q2tWidth : Int = 0,
  var noWaitCompute : Boolean = false,
  var rfRam : Boolean = true,
  var computePipe : Boolean = false
) {
  def q2tWidthEffective = if(q2tWidth == 0) vlen min 512 else q2tWidth
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
  val regDepth = p.regDepth
  val maclen = p.maclen
  val q2tWidth = p.q2tWidthEffective
  val lanes = maclen / 8
  val q2tLanes = q2tWidth / 32
  val q2tResultLanes = vlen / 32
  val weightSliceBitsMax = lanes * 2
  val vlenLog2 = log2Up(vlen)
  val regSelWidth = log2Up(regDepth) max 1
  val nLoad = vlen / xlen
  val nCompute = vlen / maclen
  val nQ2TCompute = vlen / q2tWidth
  val reslen = cfuParam.CFU_OUTPUT_DATA_W

  assert(RiscvBits.isPow2(vlen), "BitNetCfu vlen must be a power of two")
  assert(RiscvBits.isPow2(xlen), "BitNetCfu xlen must be a power of two")
  assert(RiscvBits.isPow2(maclen), "BitNetCfu maclen must be a power of two")
  assert(vlen % xlen == 0, "BitNetCfu vlen must be a multiple of xlen")
  assert(vlen % maclen == 0, "BitNetCfu vlen must be a multiple of maclen")
  assert(maclen % 8 == 0, "BitNetCfu maclen must hold complete int8 activation lanes")
  assert(weightSliceBitsMax <= vlen, "BitNetCfu weight slice must fit in one vector register")
  assert(regDepth >= 2, "BitNetCfu must have at least two vector registers")
  assert(p.withQ2 || p.qType != "2b", "BitNetCfu qType=2b requires --bitnet-cfu-with-q2")
  if(p.withQ2T) {
    assert(q2tWidth <= vlen, "BitNetCfu q2tWidth must fit in one vector register")
    assert(vlen % q2tWidth == 0, "BitNetCfu vlen must be a multiple of q2tWidth")
    assert(q2tWidth % 32 == 0, "BitNetCfu q2tWidth must hold complete FP32 lanes")
    assert(q2tWidth <= 512, "BitNetCfu q2tWidth is capped by the 32-bit packed result")
    assert(q2tResultLanes * 2 <= reslen, "BitNetCfu Q2T VLEN result must fit in the CFU response")
  }

  val io = new Bundle {
    val bus = slave(CfuBus(cfuParam))
    val dBus = master(tilelink.Bus(busParam))
  }

  val func3 = io.bus.cmd.function_id.asBits
  // BNCFU custom0 ISA:
  // func3=4: LOAD rs1=address value, rs2=vector register index encoded in raw instruction
  // func3=3: Q2T rd=ternary_quant(absmax_bits=rs1 value, fp32_reg=rs2 raw index)
  // func3=2: CONFIG/RESET
  // func3=1: BDOT rd=dot(int8_reg=rs1 raw index, lowbit_reg=rs2 raw index), advance low-bit cursor
  // func3=0: BDOT_HOLD, same operands/result, keep low-bit cursor for operand reuse
  val isLoad   = func3 === B"100"
  val isQ2T    = if(p.withQ2T) func3 === B"011" else False
  val isConfig = func3 === B"010"
  val isBDot   = func3 === B"001"
  val isBDotHold = func3 === B"000"
  val isDot = isBDot || isBDotHold

  val vecRegsReg = Vec(Reg(Bits(vlen bits)) init(0), regDepth)
  val vecRegsBank = p.rfRam generate new Area {
    val banks = Seq.fill(nLoad)(Mem(Bits(xlen bits), wordCount = regDepth))
    val wdata = Bits(xlen bits)
    val wen = Vec.fill(nLoad)(Bool())
    val waddr = UInt(regSelWidth bits)

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

  def vecRead(addr: UInt): Bits = {
    if(p.rfRam) {
      val reads = vecRegsBank.banks.map(_.readAsync(addr))
      reads.reverse.reduce(_ ## _)
    } else {
      vecRegsReg(addr)
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
    val RS1_RAW = io.bus.cmd.raw_insn(19 downto 15).asUInt
    val RS2_RAW = io.bus.cmd.raw_insn(24 downto 20).asUInt
    val RS1 = io.bus.cmd.raw_insn(19 downto 15).asUInt.resize(regSelWidth)
    val RS2 = io.bus.cmd.raw_insn(24 downto 20).asUInt.resize(regSelWidth)
  }

  val loadRD = Reg(UInt(regSelWidth bits)) init(0)
  val vecOffsets = Vec(Reg(UInt(vlenLog2 bits)) init(0), regDepth)

  val config = new Area {
    val qType = Reg(UInt(2 bits)) init(U(p.qTypeId, 2 bits))
    val qTypeCmd = decode.RS1_RAW.resize(2)
    val qTypeValid = qTypeCmd === U(Q1B, 2 bits) || qTypeCmd === U(Q2B, 2 bits) || qTypeCmd === U(Q15B, 2 bits)
    val weightInc = UInt(vlenLog2 bits)

    weightInc := qType.mux(
      U(Q1B) -> U(lanes, vlenLog2 bits),
      default -> U(weightSliceBitsMax, vlenLog2 bits)
    )
  }

  val rfRead = new Area {
    val RS1 = Reg(UInt(regSelWidth bits)) init(0)
    val RS2 = Reg(UInt(regSelWidth bits)) init(1)
    val ADVANCE = Reg(Bool()) init(True)
    val lowbitCursor = Reg(UInt(vlenLog2 bits)) init(0)

    when(io.bus.cmd.fire && isDot) {
      RS1 := decode.RS1
      RS2 := decode.RS2
      ADVANCE := isBDot
      lowbitCursor := vecOffsets(decode.RS2)
    }

    val useCmd = p.noWaitCompute.mux(io.bus.cmd.fire && isDot, False)
    val rs1 = useCmd.mux(decode.RS1, RS1)
    val rs2 = useCmd.mux(decode.RS2, RS2)
    val advanceLowbit = useCmd.mux(isBDot, ADVANCE)
    val int8 = vecRead(rs1)
    val lowbit = vecRead(rs2)
    val int8Offset = vecOffsets(rs1)
    val lowbitOffset = useCmd.mux(vecOffsets(decode.RS2), lowbitCursor)
  }

  val q2tRead = p.withQ2T generate new Area {
    val ABSMAX = Reg(Bits(32 bits)) init(0)
    val RS2 = Reg(UInt(regSelWidth bits)) init(0)

    when(io.bus.cmd.fire && isQ2T) {
      ABSMAX := io.bus.cmd.inputs(0).asBits.resize(32)
      RS2 := decode.RS2
    }
  }

  val compute = new Area {
    val acc = Reg(SInt(reslen bits)) init(0)
    val sel = Bool()
    val doneNow = (if(nCompute == 1) True else rfRead.int8Offset === U(vlen - maclen, vlenLog2 bits)) && sel

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

    val shiftInt8 = sel && !doneNow
    val shiftLowbitCursor = sel && !doneNow
    when(shiftInt8) {
      if(nCompute != 1) vecOffsets(rfRead.rs1) := rfRead.int8Offset + U(maclen, vlenLog2 bits)
    }
    when(shiftLowbitCursor) {
      rfRead.lowbitCursor := rfRead.lowbitOffset + config.weightInc
    }

    val extract = new extractStage.Area {
      val opwQ1 = rfRead.lowbit(rfRead.lowbitOffset, lanes bits).resize(weightSliceBitsMax)
      val opwWide = rfRead.lowbit(rfRead.lowbitOffset, weightSliceBitsMax bits)
      OPA := rfRead.int8(rfRead.int8Offset, maclen bits)
      OPW := (config.qType === U(Q1B, 2 bits)).mux(opwQ1, opwWide)
      QTYPE := config.qType
      SEL := sel
      DONE := doneNow
    }

    val dot = new computeStage.Area {
      val partial = BitNetDot(OPA, OPW, lanes, QTYPE, reslen, p.withQ2)
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

  val q2t = p.withQ2T generate new Area {
    val offset = Reg(UInt(vlenLog2 bits)) init(0)
    val acc = Reg(Bits(reslen bits)) init(0)
    val sel = Bool()
    val doneNow = (if(nQ2TCompute == 1) True else offset === U(vlen - q2tWidth, vlenLog2 bits)) && sel

    sel := False

    when(io.bus.cmd.fire && isQ2T) {
      offset := 0
      acc := 0
    }

    val usePipe = p.computePipe
    val nStages = if(usePipe) 1 else 0
    val stages = Array.fill(nStages + 1)(Node())
    val extractStage = stages(0)
    val computeStage = stages(nStages)

    val SEL = Payload(Bool())
    val DONE = Payload(Bool())
    val ABS = Payload(Bits(32 bits))
    val OP = Payload(Bits(q2tWidth bits))
    val OFFSET = Payload(UInt(vlenLog2 bits))

    val shiftOffset = sel && !doneNow
    when(shiftOffset) {
      if(nQ2TCompute != 1) offset := offset + U(q2tWidth, vlenLog2 bits)
    }

    val extract = new extractStage.Area {
      ABS := q2tRead.ABSMAX
      OP := vecRead(q2tRead.RS2)(offset, q2tWidth bits)
      OFFSET := offset
      SEL := sel
      DONE := doneNow
    }

    val quant = new computeStage.Area {
      val partial = BitNetQ2T(ABS, OP, q2tLanes, reslen)
      val shift = (OFFSET |>> 4).resize(log2Up(reslen + 1))
      val shifted = (partial.asUInt |<< shift).asBits.resize(reslen)
      val packed = acc | shifted

      when(SEL) {
        if(nQ2TCompute != 1) acc := packed
      } otherwise {
        acc := 0
      }
    }

    val result = quant.packed
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
    val Q2TP = p.withQ2T generate new State

    IDLE.whenIsActive {
      when(io.bus.cmd.fire) {
        when(isLoad) {
          goto(LOAD)
        }
        when(isDot) {
          if(p.noWaitCompute) {
            compute.sel := True
            if(p.singleCycle) {
              io.bus.rsp.valid := True
              io.bus.rsp.outputs(0) := compute.res.asBits
              vecOffsets(rfRead.rs1) := U(0, vlenLog2 bits)
              when(rfRead.advanceLowbit) {
                vecOffsets(rfRead.rs2) := rfRead.lowbitOffset + config.weightInc
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
          vecOffsets.foreach(_ := U(0, vlenLog2 bits))
          rfRead.lowbitCursor := U(0, vlenLog2 bits)
          compute.acc := 0
        }
        if(p.withQ2T) {
          when(isQ2T) {
            goto(Q2TP)
          }
        }
      }
    }

    if(p.withQ2T) {
      Q2TP.whenIsActive {
        q2t.sel := (if(p.computePipe) !q2t.done else True)
        when(q2t.done) {
          io.bus.rsp.valid := True
          io.bus.rsp.outputs(0) := q2t.result
          goto(IDLE)
        }
      }
    }

    BDOTP.whenIsActive {
      compute.sel := (if(p.computePipe) !compute.done else True)
      when(compute.done) {
        io.bus.rsp.valid := True
        io.bus.rsp.outputs(0) := compute.res.asBits
        vecOffsets(rfRead.rs1) := U(0, vlenLog2 bits)
        when(rfRead.advanceLowbit) {
          vecOffsets(rfRead.rs2) := rfRead.lowbitOffset + config.weightInc
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
      loadVecHits.foreach(_ := False)
      vecOffsets(decode.RS2) := U(0, vlenLog2 bits)
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
        vecWrite(loadRD, io.dBus.d.source, io.dBus.d.data)
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
