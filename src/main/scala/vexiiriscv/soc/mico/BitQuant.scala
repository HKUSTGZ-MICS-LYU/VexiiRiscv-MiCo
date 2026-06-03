package vexiiriscv.soc.mico

import spinal.core._
import spinal.lib._
import spinal.lib.misc.pipeline._

object BitQuantCompute extends AreaObject {
  val q8MaxQuantBits = 8
  val significandWidth = 24
  val q8ProductWidth = 32
  val q8CompareWidth = 40

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

  def constMulUInt(value: UInt, constant: Int, width: Int): UInt = {
    require(constant >= 0, "constMulUInt only supports non-negative constants")
    val terms = for(bit <- 0 until log2Up(constant + 1) if ((constant >> bit) & 1) != 0) yield {
      (value.resize(width) |<< bit).resize(width)
    }
    if(terms.isEmpty) U(0, width bits) else terms.reduce(_ +^ _).resize(width)
  }

  def shiftAddMulUInt(value: UInt, factor: UInt, width: Int): UInt = {
    val terms = for(bit <- 0 until factor.getWidth) yield {
      factor(bit).mux(
        (value.resize(width) |<< bit).resize(width),
        U(0, width bits)
      )
    }
    terms.reduceBalancedTree(_ +^ _).resize(width)
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

  def symmetricKeep(absmax: Bits, lane: Bits, trial: UInt, qBits: UInt, maxQuantBits: Int): Bool = {
    val absmaxMagnitude = absmax(30 downto 0).asUInt
    val absmaxExponent = absmax(30 downto 23).asUInt
    val absmaxIsValid = absmaxMagnitude =/= 0 && absmaxExponent =/= U(255, 8 bits)
    val productWidth = 32
    val absmaxParts = fp32MagnitudeParts(absmaxMagnitude)
    val magnitude = lane(30 downto 0).asUInt
    val laneParts = fp32MagnitudeParts(magnitude)

    val factorWidth = maxQuantBits + 1
    val qBitsShift = qBits.resize(log2Up(factorWidth))
    val scaleFactor = ((U(1, factorWidth bits) |<< qBitsShift) - U(2, factorWidth bits)).resize(factorWidth)
    val thresholdFactor = ((trial.resize(factorWidth) |<< 1) - U(1, factorWidth bits)).resize(factorWidth)
    val scaledMagnitude = shiftAddMulUInt(laneParts.significand, scaleFactor, productWidth)
    val thresholdProduct = shiftAddMulUInt(absmaxParts.significand, thresholdFactor, productWidth)

    absmaxIsValid && magnitude =/= 0 &&
      fp32ScaledGte(laneParts.effectiveExponent, scaledMagnitude, absmaxParts.effectiveExponent, thresholdProduct)
  }

  def q8Scale254(significand: UInt): UInt = {
    val wide = significand.resize(q8ProductWidth)
    ((wide |<< 8) - (wide |<< 1)).resize(q8ProductWidth)
  }

  def shiftLeft0To8(value: UInt, shift: UInt): UInt = {
    val shifted = UInt(q8CompareWidth bits)

    shifted := value.resize(q8CompareWidth)
    for(i <- 0 to 8) {
      when(shift === U(i, shift.getWidth bits)) {
        shifted := (value.resize(q8CompareWidth) |<< i).resize(q8CompareWidth)
      }
    }
    shifted
  }

  def shiftRight0To8(value: UInt, shift: UInt): UInt = {
    val shifted = UInt(q8ProductWidth bits)

    shifted := value
    for(i <- 0 to 8) {
      when(shift === U(i, shift.getWidth bits)) {
        shifted := (value |>> i).resize(q8ProductWidth)
      }
    }
    shifted
  }
}

case class BitQuantLaneParameter(
  maxQuantBits : Int = 8,
  comparePipe : Boolean = false
) {
  require(maxQuantBits >= 2, "BitQuantLane maxQuantBits must be >= 2")
  require(maxQuantBits <= 8, "BitQuantLane currently supports up to 8-bit quantization")
  def qBitsWidth = log2Up(maxQuantBits + 1) max 1
}

class BitQuantLaneIO(p: BitQuantLaneParameter) extends Bundle {
  val start = in Bool()
  val qBits = in UInt(p.qBitsWidth bits)
  val absmax = in Bits(32 bits)
  val absParts = in(new BitQuantAbsmaxParts)
  val value = in Bits(32 bits)
  val busy = out Bool()
  val done = out Bool()
  val result = out Bits(p.maxQuantBits bits)
}

class BitQuantAbsmaxParts extends Bundle {
  val valid = Bool()
  val effectiveExponent = UInt(8 bits)
  val significand = UInt(BitQuantCompute.significandWidth bits)
}

class BitQuantNormalizedLane(p: BitQuantLaneParameter) extends Component {
  import BitQuantCompute._

  val io = new BitQuantLaneIO(p)

  val busyReg = RegInit(False)
  val doneReg = RegInit(False)
  val resultReg = Reg(Bits(p.maxQuantBits bits)) init(0)
  val modeQ8Reg = RegInit(False)
  val signReg = RegInit(False)
  val validReg = RegInit(False)
  val xExpGreaterReg = RegInit(False)
  val expShiftReg = Reg(UInt(4 bits)) init(0)
  val expDiffLargeReg = RegInit(False)
  val scaledMagnitudeReg = Reg(UInt(q8ProductWidth bits)) init(0)
  val absSignificandReg = Reg(UInt(significandWidth bits)) init(0)
  val levelReg = Reg(UInt(p.maxQuantBits bits)) init(0)
  val levelProductReg = Reg(UInt(q8ProductWidth bits)) init(0)
  val cursorReg = Reg(UInt(p.maxQuantBits bits)) init(0)

  val valueMagnitude = io.value(30 downto 0).asUInt
  val absValid = io.absParts.valid
  val valueNonZero = valueMagnitude =/= 0
  val valueParts = fp32MagnitudeParts(valueMagnitude)
  val expDiff = (io.absParts.effectiveExponent.resize(9) - valueParts.effectiveExponent.resize(9)).resize(9)
  val expDiffValid = io.absParts.effectiveExponent >= valueParts.effectiveExponent
  val expDiffLarge = expDiff >= U(9, 9 bits)
  val expShift = UInt(4 bits)

  expShift := 0
  when(expDiffValid) {
    expShift := expDiff.resize(4)
    when(expDiff >= U(8, 9 bits)) {
      expShift := U(8, 4 bits)
    }
  }

  val q2tExpPlusOne = valueParts.effectiveExponent.resize(9) + U(1, 9 bits)
  val q2tAbsExp = io.absParts.effectiveExponent.resize(9)
  val q2tKeep =
    absValid && valueNonZero &&
    (q2tExpPlusOne > q2tAbsExp ||
      (q2tExpPlusOne === q2tAbsExp && valueParts.significand >= io.absParts.significand))
  val q2tResult = Bits(p.maxQuantBits bits)
  val q2tLevel = U(1, p.maxQuantBits bits)

  q2tResult := io.value(31).mux((U(0, p.maxQuantBits bits) - q2tLevel).asBits, q2tLevel.asBits)

  val q8StartBit = UInt(log2Up(p.maxQuantBits) max 1 bits)
  val q8StartCursor = UInt(p.maxQuantBits bits)
  q8StartBit := U(6 min (p.maxQuantBits - 1), q8StartBit.getWidth bits)
  q8StartCursor := 0
  when(expDiffValid) {
    when(expDiff <= U(2, 9 bits)) {
      q8StartBit := U(6 min (p.maxQuantBits - 1), q8StartBit.getWidth bits)
    } otherwise {
      for(i <- 3 to 8) {
        when(expDiff === U(i, 9 bits)) {
          q8StartBit := U((8 - i) min (p.maxQuantBits - 1), q8StartBit.getWidth bits)
        }
      }
    }
  }
  for(i <- 0 until p.maxQuantBits) {
    when(q8StartBit === U(i, q8StartBit.getWidth bits)) {
      q8StartCursor := U(1 << i, p.maxQuantBits bits)
    }
  }

  when(io.start && !busyReg) {
    val modeQ8 = io.qBits > U(2, p.qBitsWidth bits)
    val directZero = !absValid || !valueNonZero || (modeQ8 && expDiffValid && expDiffLarge)

    doneReg := True
    busyReg := False
    modeQ8Reg := modeQ8
    signReg := io.value(31)
    validReg := absValid && valueNonZero
    xExpGreaterReg := !expDiffValid
    expShiftReg := expShift
    expDiffLargeReg := expDiffValid && expDiffLarge
    scaledMagnitudeReg := q8Scale254(valueParts.significand)
    absSignificandReg := io.absParts.significand
    levelReg := 0
    levelProductReg := 0
    cursorReg := 0
    resultReg := 0

    when(!directZero) {
      when(modeQ8) {
        doneReg := False
        busyReg := True
        cursorReg := q8StartCursor
      } otherwise {
        when(q2tKeep) {
          resultReg := q2tResult
        }
      }
    }
  }

  val selectedAdd = UInt(q8ProductWidth bits)
  selectedAdd := 0
  for(i <- 0 until p.maxQuantBits) {
    when(cursorReg(i)) {
      selectedAdd := (absSignificandReg.resize(q8ProductWidth) |<< i).resize(q8ProductWidth)
    }
  }

  val candidateLevel = levelReg | cursorReg
  val candidateLevelProduct = (levelProductReg + selectedAdd).resize(q8ProductWidth)
  val thresholdProductWide = ((candidateLevelProduct.resize(q8ProductWidth + 1) |<< 1) - absSignificandReg.resize(q8ProductWidth + 1)).resize(q8ProductWidth)
  val shiftedMagnitude = shiftRight0To8(scaledMagnitudeReg, expShiftReg)
  val q8Keep =
    validReg &&
    (xExpGreaterReg || (!expDiffLargeReg && shiftedMagnitude >= thresholdProductWide))
  val selectedLevel = UInt(p.maxQuantBits bits)
  val q8Code = Bits(p.maxQuantBits bits)

  selectedLevel := levelReg
  when(q8Keep) {
    selectedLevel := candidateLevel
  }
  q8Code := signReg.mux((U(0, p.maxQuantBits bits) - selectedLevel).asBits, selectedLevel.asBits)

  when(busyReg && modeQ8Reg) {
    when(q8Keep) {
      levelReg := candidateLevel
      levelProductReg := candidateLevelProduct
    }
    when(cursorReg(0)) {
      busyReg := False
      doneReg := True
      resultReg := q8Code
    } otherwise {
      cursorReg := (cursorReg |>> 1).resize(p.maxQuantBits)
    }
  }

  io.busy := busyReg
  io.done := doneReg
  io.result := resultReg
}

class BitQuantLevelShiftReg(width: Int, qBitsWidth: Int) extends Area {
  val clear = Bool()
  val loadQBits = UInt(qBitsWidth bits)
  val set = Bool()
  val advance = Bool()
  val valueRegs = Vec.fill(width)(RegInit(False))
  val cursorRegs = Vec.fill(width)(RegInit(False))
  val value = UInt(width bits)
  val trial = UInt(width bits)
  val last = cursorRegs(0)

  clear := False
  loadQBits := U(2, qBitsWidth bits)
  set := False
  advance := False

  for(i <- 0 until width) {
    value(i) := valueRegs(i)
    trial(i) := valueRegs(i) || cursorRegs(i)
  }

  when(clear) {
    for(i <- 0 until width) {
      valueRegs(i) := False
      cursorRegs(i) := loadQBits === U(i + 2, qBitsWidth bits)
    }
  } otherwise {
    when(set) {
      for(i <- 0 until width) {
        when(cursorRegs(i)) {
          valueRegs(i) := True
        }
      }
    }
    when(advance) {
      for(i <- 0 until width - 1) {
        cursorRegs(i) := cursorRegs(i + 1)
      }
      cursorRegs(width - 1) := False
    }
  }
}

class BitQuantLane(p: BitQuantLaneParameter) extends Component {
  import BitQuantCompute._

  val io = new BitQuantLaneIO(p)

  val busyReg = RegInit(False)
  val doneReg = RegInit(False)
  val qBitsReg = Reg(UInt(p.qBitsWidth bits)) init(2)
  val quantLevel = new BitQuantLevelShiftReg(p.maxQuantBits, p.qBitsWidth)
  val absReg = Reg(Bits(32 bits)) init(0)
  val valueReg = Reg(Bits(32 bits)) init(0)
  val resultReg = Reg(Bits(p.maxQuantBits bits)) init(0)
  val usePipe = p.comparePipe
  val inFlight = if(usePipe) RegInit(False) else null

  doneReg := False

  when(io.start && !busyReg) {
    busyReg := True
    doneReg := False
    qBitsReg := io.qBits
    quantLevel.clear := True
    quantLevel.loadQBits := io.qBits
    absReg := io.absmax
    valueReg := io.value
    resultReg := 0
    if(usePipe) inFlight := False
  }

  val nStages = if(usePipe) 2 else 0
  val stages = Array.fill(nStages + 1)(Node())
  val prepareStage = stages(0)
  val compareStage = stages(if(usePipe) 1 else 0)
  val commitStage = stages(nStages)

  val SEL = Payload(Bool())
  val LAST = Payload(Bool())
  val ABS = Payload(Bits(32 bits))
  val VALUE = Payload(Bits(32 bits))
  val QBITS = Payload(UInt(p.qBitsWidth bits))
  val LEVEL = Payload(UInt(p.maxQuantBits bits))
  val TRIAL = Payload(UInt(p.maxQuantBits bits))
  val KEEP = Payload(Bool())
  val QUANT_RESULT = Payload(Bits(p.maxQuantBits bits))

  val launch = busyReg && !doneReg && (if(usePipe) !inFlight else True)

  val prepare = new prepareStage.Area {
    SEL := launch
    LAST := quantLevel.last
    ABS := absReg
    VALUE := valueReg
    QBITS := qBitsReg
    LEVEL := quantLevel.value
    TRIAL := quantLevel.trial
  }

  val compare = new compareStage.Area {
    val keep = symmetricKeep(ABS, VALUE, TRIAL, QBITS, p.maxQuantBits)
    val selectedLevel = UInt(p.maxQuantBits bits)
    val quantCode = Bits(p.maxQuantBits bits)

    selectedLevel := LEVEL
    when(keep) {
      selectedLevel := TRIAL
    }
    quantCode := VALUE(31).mux((U(0, p.maxQuantBits bits) - selectedLevel).asBits, selectedLevel.asBits)

    KEEP := keep
    QUANT_RESULT := quantCode
  }

  val commit = new commitStage.Area {
    when(SEL) {
      if(usePipe) inFlight := False
      quantLevel.set := KEEP
      when(LAST) {
        busyReg := False
        doneReg := True
        resultReg := QUANT_RESULT
      } otherwise {
        quantLevel.advance := True
      }
    }
  }

  when(launch) {
    if(usePipe) inFlight := True
  }

  if(usePipe) {
    val links = for(i <- 0 until nStages) yield StageLink(stages(i), stages(i + 1))
    Builder(links)
  }

  io.busy := busyReg
  io.done := doneReg
  io.result := resultReg
}
