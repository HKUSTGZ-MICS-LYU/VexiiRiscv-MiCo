package vexiiriscv.soc.mico

import spinal.core._
import spinal.core.fiber._
import spinal.lib._
import spinal.lib.bus._
import spinal.lib.bus.tilelink._
import spinal.lib.misc.pipeline._
import spinal.lib.misc.plugin._

import vexiiriscv.execute.cfu._

/**
  * Plugin based BNCFU implementation.
  *
  * The legacy BitNetCfu is intentionally kept separate.  This version uses a
  * local PluginHost for ownership/lifecycle and Streams for the data/control
  * paths.  There is no StateMachine: long operations are represented by
  * valid/ready streams and small transaction registers.
  */
case class BitNetCfuV2Parameter(
    var vlen: Int = 256,
    var xlen: Int = 64,
    var cfuInputWidth: Int = 32,
    var maclen: Int = 128,
    var regDepth: Int = 5,
    var qType: String = "1.5b",
    var withQ2: Boolean = false,
    var withQ2T: Boolean = true,
    var withQ8: Boolean = false,
    var quantWidth: Int = 128,
    var dotPipeStages: Int = 1,
    var quantPipeStages: Int = 1,
    var loadBufferDepth: Int = 0,
    var rfRam: Boolean = true,
    var rfSync: Boolean = true,
    var burstLoad: Boolean = false,
    var quantStandard: Boolean = false,
    var asicSram: Boolean = false
) {
  def quantWidthEffective = if (quantWidth == 0) vlen min 128 else quantWidth
  def pendingSize = vlen / xlen
  def loadBytes = vlen / 8
  def beatBytes = xlen / 8
  def loadBufferDepthEffective = if (loadBufferDepth == 0) pendingSize max 2 else loadBufferDepth
  def qTypeId = BitNetCfuCompute.qTypeId(qType)
}

private object BitNetCfuV2Checks {
  def isPow2(value: Int): Boolean = value > 0 && ((value & (value - 1)) == 0)
}

case class BitNetCfuV2Command(p: BitNetCfuV2Parameter) extends Bundle {
  val input0 = Bits(p.cfuInputWidth bits)
  val input1 = Bits(p.cfuInputWidth bits)
  val raw = Bits(32 bits)
  val functionId = UInt(3 bits)
  val rs1Raw = UInt(5 bits)
  val rs2Raw = UInt(5 bits)
  val func7 = UInt(7 bits)
  val rs1 = UInt(log2Up(p.regDepth) max 1 bits)
  val rs2 = UInt(log2Up(p.regDepth) max 1 bits)
}

case class BitNetCfuV2LoadBeat(p: BitNetCfuV2Parameter) extends Bundle {
  val reg = UInt(log2Up(p.regDepth) max 1 bits)
  val beat = UInt(log2Up(p.vlen / p.xlen) max 1 bits)
  val last = Bool()
  val data = Bits(p.xlen bits)
}

case class BitNetCfuV2RfWrite(p: BitNetCfuV2Parameter) extends Bundle {
  val reg = UInt(log2Up(p.regDepth) max 1 bits)
  val beat = UInt(log2Up(p.vlen / p.xlen) max 1 bits)
  val data = Bits(p.xlen bits)
}

case class BitNetCfuV2RfRead(p: BitNetCfuV2Parameter) extends Bundle {
  val reg = UInt(log2Up(p.regDepth) max 1 bits)
}

case class BitNetCfuV2RfReadRsp(p: BitNetCfuV2Parameter) extends Bundle {
  val data = Bits(p.vlen bits)
}

case class BitNetCfuV2DotTask(p: BitNetCfuV2Parameter) extends Bundle {
  val opa = Bits(p.maclen bits)
  val opw = Bits((p.maclen / 8) * 2 bits)
  val qType = UInt(2 bits)
  val last = Bool()
}

case class BitNetCfuV2DotResult(p: BitNetCfuV2Parameter, resultWidth: Int) extends Bundle {
  val result = Bits(resultWidth bits)
  val last = Bool()
}

case class BitNetCfuV2QuantTask(p: BitNetCfuV2Parameter) extends Bundle {
  val beat = UInt(log2Up(p.vlen / p.xlen) max 1 bits)
  val data = Bits(p.xlen bits)
}

/** Shared hardware-facing service.  It is deliberately an Area rather than a
  * Scala-only registry: the signals here are the actual wires between the
  * plugins. */
class BitNetCfuV2Context(
    val cfuParam: CfuBusParameter,
    val p: BitNetCfuV2Parameter,
    val cfu: CfuBus,
    val dBus: tilelink.Bus
) extends Area {
  val command = Stream(BitNetCfuV2Command(p))
  val loadReq = Stream(BitNetCfuV2Command(p))
  val configReq = Stream(BitNetCfuV2Command(p))
  val dotReq = Stream(BitNetCfuV2Command(p))
  val quantReq = Stream(BitNetCfuV2Command(p))

  val loadBeat = Stream(BitNetCfuV2LoadBeat(p))
  val rfWrite = Stream(BitNetCfuV2RfWrite(p))
  val rfReadA = Stream(BitNetCfuV2RfRead(p))
  val rfReadB = Stream(BitNetCfuV2RfRead(p))
  val rfReadRspA = Stream(BitNetCfuV2RfReadRsp(p))
  val rfReadRspB = Stream(BitNetCfuV2RfReadRsp(p))

  val rspLoad = Stream(CfuRsp(cfuParam))
  val rspConfig = Stream(CfuRsp(cfuParam))
  val rspDot = Stream(CfuRsp(cfuParam))
  val rspQuant = Stream(CfuRsp(cfuParam))

  val qType = Reg(UInt(2 bits)) init (U(p.qTypeId, 2 bits))
  val offsets = Vec(Reg(UInt(log2Up(p.vlen) max 1 bits)) init (0), p.regDepth)
  val loadBusy = Vec(RegInit(False), p.regDepth)
}

private class BitNetCfuV2CommandPlugin(ctx: BitNetCfuV2Context) extends FiberPlugin {
  val logic = new Area {
    val cmd = ctx.cfu.cmd

    ctx.command.valid := cmd.valid
    cmd.ready := ctx.command.ready
    ctx.command.payload.input0 := cmd.inputs(0)
    ctx.command.payload.input1 := cmd.inputs(1)
    ctx.command.payload.raw := cmd.raw_insn
    ctx.command.payload.functionId := cmd.function_id.resized
    ctx.command.payload.rs1Raw := cmd.raw_insn(19 downto 15).asUInt
    ctx.command.payload.rs2Raw := cmd.raw_insn(24 downto 20).asUInt
    ctx.command.payload.func7 := cmd.raw_insn(31 downto 25).asUInt
    ctx.command.payload.rs1 := cmd.raw_insn(19 downto 15).asUInt.resized
    ctx.command.payload.rs2 := cmd.raw_insn(24 downto 20).asUInt.resized

    val isLoad = cmd.function_id === U(4, 3 bits)
    val isConfig = cmd.function_id === U(2, 3 bits)
    val isDot = cmd.function_id === U(0, 3 bits) || cmd.function_id === U(1, 3 bits)
    val isQ2T = if (ctx.p.withQ2T) cmd.function_id === U(3, 3 bits) else False
    val isQ8 = if (ctx.p.withQ8) cmd.function_id === U(5, 3 bits) else False

    ctx.loadReq.valid := ctx.command.valid && isLoad
    ctx.configReq.valid := ctx.command.valid && isConfig
    ctx.dotReq.valid := ctx.command.valid && isDot
    ctx.quantReq.valid := ctx.command.valid && (isQ2T || isQ8)
    ctx.loadReq.payload := ctx.command.payload
    ctx.configReq.payload := ctx.command.payload
    ctx.dotReq.payload := ctx.command.payload
    ctx.quantReq.payload := ctx.command.payload

    ctx.command.ready :=
      isLoad.mux(ctx.loadReq.ready,
        isConfig.mux(ctx.configReq.ready,
          isDot.mux(ctx.dotReq.ready,
            (isQ2T || isQ8).mux(ctx.quantReq.ready, False))))
  }
}

private class BitNetCfuV2ConfigPlugin(ctx: BitNetCfuV2Context) extends FiberPlugin {
  val logic = new Area {
    val pending = RegInit(False)

    ctx.configReq.ready := !pending
    ctx.rspConfig.valid := pending
    ctx.rspConfig.payload.response_id := 0
    ctx.rspConfig.payload.outputs(0) := 0
    if (ctx.cfuParam.CFU_WITH_STATUS) ctx.rspConfig.payload.status := 0

    when (ctx.configReq.fire) {
      val q = ctx.configReq.payload.rs1Raw.resize(2)
      when (q === U(BitNetCfuCompute.Q1B, 2 bits) ||
        q === U(BitNetCfuCompute.Q15B, 2 bits) ||
        q === U(BitNetCfuCompute.Q2B, 2 bits)) {
        ctx.qType := q
      } otherwise {
        ctx.qType := U(ctx.p.qTypeId, 2 bits)
      }
      ctx.offsets.foreach(_ := U(0, log2Up(ctx.p.vlen) max 1 bits))
      pending := True
    }
    when (ctx.rspConfig.fire) {
      pending := False
    }
  }
}

private class BitNetCfuV2RegisterFilePlugin(ctx: BitNetCfuV2Context) extends FiberPlugin {
  val logic = new Area {
    val nLoad = ctx.p.vlen / ctx.p.xlen
    val regWidth = log2Up(ctx.p.regDepth) max 1
    val beatWidth = log2Up(nLoad) max 1

    ctx.rfWrite.ready := True

    val regs = Vec(Reg(Bits(ctx.p.vlen bits)) init (0), ctx.p.regDepth)
    val banks = if (ctx.p.rfRam) Some(Seq.fill(nLoad)(Mem(Bits(ctx.p.xlen bits), wordCount = ctx.p.regDepth))) else None
    if (ctx.p.asicSram) banks.foreach(_.foreach(_.generateAsBlackBox()))

    banks match {
      case Some(memories) =>
        for (i <- 0 until nLoad) {
          memories(i).write(
            address = ctx.rfWrite.payload.reg,
            data = ctx.rfWrite.payload.data,
            enable = ctx.rfWrite.fire && ctx.rfWrite.payload.beat === U(i, beatWidth bits)
          )
        }
      case None =>
        when (ctx.rfWrite.fire) {
          val bitOffset = ctx.rfWrite.payload.beat.resize(log2Up(ctx.p.vlen) max 1) * ctx.p.xlen
          regs(ctx.rfWrite.payload.reg)(bitOffset, ctx.p.xlen bits) := ctx.rfWrite.payload.data
        }
    }

    val readWordsA = banks match {
      case Some(memories) if ctx.p.rfSync => memories.map(_.readSync(ctx.rfReadA.payload.reg, ctx.rfReadA.fire))
      case Some(memories) => memories.map(_.readAsync(ctx.rfReadA.payload.reg))
      case None => Seq(regs(ctx.rfReadA.payload.reg))
    }
    val readWordsB = banks match {
      case Some(memories) if ctx.p.rfSync => memories.map(_.readSync(ctx.rfReadB.payload.reg, ctx.rfReadB.fire))
      case Some(memories) => memories.map(_.readAsync(ctx.rfReadB.payload.reg))
      case None => Seq(regs(ctx.rfReadB.payload.reg))
    }
    val readDataA = readWordsA.reverse.reduce(_ ## _)
    val readDataB = readWordsB.reverse.reduce(_ ## _)

    if (ctx.p.rfSync && ctx.p.rfRam) {
      val rspValidA = RegNext(ctx.rfReadA.fire) init (False)
      val rspValidB = RegNext(ctx.rfReadB.fire) init (False)
      ctx.rfReadA.ready := !rspValidA || ctx.rfReadRspA.ready
      ctx.rfReadB.ready := !rspValidB || ctx.rfReadRspB.ready
      ctx.rfReadRspA.valid := rspValidA
      ctx.rfReadRspB.valid := rspValidB
      ctx.rfReadRspA.payload.data := readDataA
      ctx.rfReadRspB.payload.data := readDataB
    } else {
      ctx.rfReadA.ready := ctx.rfReadRspA.ready
      ctx.rfReadB.ready := ctx.rfReadRspB.ready
      ctx.rfReadRspA.valid := ctx.rfReadA.fire
      ctx.rfReadRspB.valid := ctx.rfReadB.fire
      ctx.rfReadRspA.payload.data := readDataA
      ctx.rfReadRspB.payload.data := readDataB
    }
  }
}

private class BitNetCfuV2LoadPlugin(ctx: BitNetCfuV2Context) extends FiberPlugin {
  val logic = new Area {
    val p = ctx.p
    val nLoad = p.vlen / p.xlen
    val beatBytes = p.xlen / 8
    val loadBytes = p.vlen / 8
    val beatWidth = log2Up(nLoad) max 1
    val sourceWidth = widthOf(ctx.dBus.d.source)

    val active = RegInit(False)
    val responsePending = RegInit(False)
    val baseAddr = Reg(UInt(32 bits)) init (0)
    val offsetAddr = Reg(UInt(32 bits)) init (0)
    val loadReg = Reg(UInt(log2Up(p.regDepth) max 1 bits)) init (0)
    val memValid = RegInit(False)
    val memReady = RegInit(False)
    val memSource = Reg(UInt(beatWidth bits)) init (0)
    val burst = if (p.burstLoad) RegInit(False) else False
    val beatCount = Reg(UInt(log2Up(nLoad + 1) max 1 bits)) init (0)

    val offsetNext = offsetAddr + U(beatBytes, 32 bits)
    val alignedBurst = if (p.burstLoad && loadBytes > beatBytes) {
      ctx.loadReq.payload.input0.asUInt(log2Up(loadBytes) - 1 downto 0) === 0
    } else False
    val issueLast = (if (p.burstLoad) burst else False) || offsetNext === U(loadBytes, 32 bits)

    ctx.loadReq.ready := !active
    ctx.rspLoad.valid := responsePending
    ctx.rspLoad.payload.response_id := 0
    ctx.rspLoad.payload.outputs(0) := 0
    if (ctx.cfuParam.CFU_WITH_STATUS) ctx.rspLoad.payload.status := 0

    ctx.rfWrite.valid := ctx.dBus.d.valid && ctx.dBus.d.ready
    ctx.rfWrite.payload.reg := loadReg
    ctx.rfWrite.payload.beat := (if (p.burstLoad) ctx.dBus.d.beatCounter().resized else ctx.dBus.d.source.resized)
    ctx.rfWrite.payload.data := ctx.dBus.d.data

    ctx.loadBeat.valid := ctx.dBus.d.valid && ctx.dBus.d.ready
    ctx.loadBeat.payload.reg := loadReg
    ctx.loadBeat.payload.beat := (if (p.burstLoad) ctx.dBus.d.beatCounter().resized else ctx.dBus.d.source.resized)
    ctx.loadBeat.payload.last := ctx.dBus.d.isLast()
    ctx.loadBeat.payload.data := ctx.dBus.d.data

    ctx.dBus.d.ready := memReady && ctx.rfWrite.ready && ctx.loadBeat.ready
    ctx.dBus.a.valid := memValid
    ctx.dBus.a.opcode := tilelink.Opcode.A.GET
    ctx.dBus.a.param := tilelink.Param.Hint.NO_ALLOCATE_ON_MISS
    ctx.dBus.a.data := 0
    ctx.dBus.a.mask := B(beatBytes bits, default -> True)
    ctx.dBus.a.corrupt := False
    ctx.dBus.a.source := (if (p.burstLoad) burst.mux(U(0, sourceWidth bits), memSource.resized) else memSource.resized)
    ctx.dBus.a.address := (if (p.burstLoad) burst.mux(baseAddr, baseAddr + offsetAddr) else baseAddr + offsetAddr)
    ctx.dBus.a.size := (if (p.burstLoad) burst.mux(U(log2Up(loadBytes), widthOf(ctx.dBus.a.size) bits), U(log2Up(beatBytes), widthOf(ctx.dBus.a.size) bits)) else U(log2Up(beatBytes), widthOf(ctx.dBus.a.size) bits))

    when (ctx.loadReq.fire) {
      active := True
      responsePending := True
      baseAddr := ctx.loadReq.payload.input0.asUInt.resized
      offsetAddr := 0
      loadReg := ctx.loadReq.payload.rs2
      memSource := 0
      memValid := True
      memReady := True
      beatCount := 0
      ctx.loadBusy(ctx.loadReq.payload.rs2) := True
      ctx.offsets(ctx.loadReq.payload.rs2) := U(0, log2Up(p.vlen) max 1 bits)
      if (p.burstLoad) burst := alignedBurst
    }

    when (ctx.rspLoad.fire) {
      responsePending := False
    }

    when (ctx.dBus.a.fire) {
      offsetAddr := offsetNext
      memSource := memSource + 1
      when (issueLast) {
        memValid := False
      }
    }

    when (ctx.dBus.d.fire) {
      if (p.burstLoad) {
        when (burst && ctx.dBus.d.isLast()) {
          active := False
          memReady := False
          ctx.loadBusy(loadReg) := False
        } otherwise {
          when (!burst && beatCount === U(nLoad - 1, beatCount.getWidth bits)) {
            active := False
            memReady := False
            ctx.loadBusy(loadReg) := False
          }
          when (!burst) { beatCount := beatCount + 1 }
        }
      } else {
        when (beatCount === U(nLoad - 1, beatCount.getWidth bits)) {
          active := False
          memReady := False
          ctx.loadBusy(loadReg) := False
        }
        beatCount := beatCount + 1
      }
    }

    when (!active && !ctx.loadReq.fire) {
      memValid := False
      memReady := False
    }
  }
}

private class BitNetCfuV2DotProductPlugin(ctx: BitNetCfuV2Context) extends FiberPlugin {
  val logic = new Area {
    val p = ctx.p
    val lanes = p.maclen / 8
    val resWidth = ctx.cfuParam.CFU_OUTPUT_DATA_W
    val nCompute = p.vlen / p.maclen
    val offsetWidth = log2Up(p.vlen) max 1
    val regWidth = log2Up(p.regDepth) max 1

    val active = RegInit(False)
    val readIssued = RegInit(False)
    val haveChunk = RegInit(False)
    val inFlight = RegInit(False)
    val responsePending = RegInit(False)
    val rs1Reg = Reg(UInt(regWidth bits)) init (0)
    val rs2Reg = Reg(UInt(regWidth bits)) init (1)
    val advanceReg = RegInit(False)
    val intOffset = Reg(UInt(offsetWidth bits)) init (0)
    val weightOffset = Reg(UInt(offsetWidth bits)) init (0)
    val chunk = Reg(UInt(log2Up(nCompute) max 1 bits)) init (0)
    val opaReg = Reg(Bits(p.vlen bits)) init (0)
    val opwReg = Reg(Bits(p.vlen bits)) init (0)
    val acc = Reg(SInt(resWidth bits)) init (0)
    val responseData = Reg(Bits(resWidth bits)) init (0)

    val dotIn = Stream(BitNetCfuV2DotTask(p))
    val dotOut = Stream(BitNetCfuV2DotResult(p, resWidth))
    val stages = Array.fill((p.dotPipeStages max 0) + 1)(Node())
    val task = Payload(BitNetCfuV2DotTask(p))
    val links = for (i <- 0 until stages.length - 1) yield StageLink(stages(i), stages(i + 1))

    val dotInputStage = stages(0)
    val dotOutputStage = stages(stages.length - 1)
    if (p.dotPipeStages == 0) {
      dotOut.valid := dotIn.valid
      dotIn.ready := dotOut.ready
      val partial = BitNetCfuCompute.BitNetDot(dotIn.payload.opa, dotIn.payload.opw, lanes, dotIn.payload.qType, resWidth, p.withQ2)
      dotOut.payload.result := partial.asBits
      dotOut.payload.last := dotIn.payload.last
    } else {
      dotInputStage.arbitrateFrom(dotIn)
      dotOutputStage.arbitrateTo(dotOut)
      new dotInputStage.Area {
        stages(0)(task) := dotIn.payload
      }
      new dotOutputStage.Area {
        val stageTask = stages(stages.length - 1)(task)
        val partial = BitNetCfuCompute.BitNetDot(stageTask.opa, stageTask.opw, lanes, stageTask.qType, resWidth, p.withQ2)
        dotOut.payload.result := partial.asBits
        dotOut.payload.last := stageTask.last
      }
      Builder(links)
    }

    val lastChunk = if (nCompute == 1) True else chunk === U(nCompute - 1, chunk.getWidth bits)
    val intOffsetNow = intOffset
    val weightOffsetNow = weightOffset

    val dotRegsReady = !ctx.loadBusy(ctx.dotReq.payload.rs1) && !ctx.loadBusy(ctx.dotReq.payload.rs2)
    ctx.dotReq.ready := !active && !responsePending && dotRegsReady
    ctx.rspDot.valid := responsePending
    ctx.rspDot.payload.response_id := 0
    ctx.rspDot.payload.outputs(0) := responseData
    if (ctx.cfuParam.CFU_WITH_STATUS) ctx.rspDot.payload.status := 0

    ctx.rfReadA.valid := active && !readIssued && !haveChunk && !inFlight
    ctx.rfReadB.valid := active && !readIssued && !haveChunk && !inFlight
    ctx.rfReadA.payload.reg := rs1Reg
    ctx.rfReadB.payload.reg := rs2Reg
    ctx.rfReadRspA.ready := active && readIssued
    ctx.rfReadRspB.ready := active && readIssued

    dotIn.valid := active && haveChunk && !inFlight
    dotIn.payload.opa := opaReg(intOffsetNow, p.maclen bits)
    dotIn.payload.opw := (ctx.qType === U(BitNetCfuCompute.Q1B, 2 bits)).mux(
      opwReg(weightOffsetNow, lanes bits).resize(lanes * 2),
      opwReg(weightOffsetNow, lanes * 2 bits)
    )
    dotIn.payload.qType := ctx.qType
    dotIn.payload.last := lastChunk
    // With no registered stage the result is produced by the same
    // transaction as dotIn.  Requiring the previous-cycle inFlight bit here
    // would make the first direct transaction impossible to fire.
    dotOut.ready := (if (p.dotPipeStages == 0) True else inFlight)

    when (ctx.dotReq.fire) {
      active := True
      readIssued := False
      haveChunk := False
      inFlight := False
      responsePending := False
      rs1Reg := ctx.dotReq.payload.rs1
      rs2Reg := ctx.dotReq.payload.rs2
      advanceReg := ctx.dotReq.payload.functionId === U(1, 3 bits)
      intOffset := ctx.offsets(ctx.dotReq.payload.rs1)
      weightOffset := ctx.offsets(ctx.dotReq.payload.rs2)
      chunk := 0
      acc := 0
    }

    when (ctx.rfReadA.fire && ctx.rfReadB.fire) {
      readIssued := True
    }

    when (ctx.rfReadRspA.fire && ctx.rfReadRspB.fire) {
      opaReg := ctx.rfReadRspA.payload.data
      opwReg := ctx.rfReadRspB.payload.data
      haveChunk := True
      readIssued := False
    }

    when (dotIn.fire) {
      haveChunk := False
      inFlight := True
    }

    when (dotOut.fire) {
      val nextAcc = (acc + dotOut.payload.result.asSInt).resize(resWidth)
      inFlight := False
      when (dotOut.payload.last) {
        responseData := nextAcc.asBits
        responsePending := True
        active := False
        intOffset := U(0, offsetWidth bits)
        when (advanceReg) {
          val weightInc = (ctx.qType === U(BitNetCfuCompute.Q1B, 2 bits)).mux(U(lanes, offsetWidth bits), U(lanes * 2, offsetWidth bits))
          ctx.offsets(rs1Reg) := U(0, offsetWidth bits)
          ctx.offsets(rs2Reg) := weightOffset + weightInc
        } otherwise {
          ctx.offsets(rs1Reg) := U(0, offsetWidth bits)
        }
      } otherwise {
        acc := nextAcc
        chunk := chunk + 1
        intOffset := intOffset + U(p.maclen, offsetWidth bits)
        val weightInc = (ctx.qType === U(BitNetCfuCompute.Q1B, 2 bits)).mux(U(lanes, offsetWidth bits), U(lanes * 2, offsetWidth bits))
        weightOffset := weightOffset + weightInc
        haveChunk := True
      }
    }

    when (ctx.rspDot.fire) {
      responsePending := False
    }
  }
}

private class BitNetCfuV2QuantizePlugin(ctx: BitNetCfuV2Context) extends FiberPlugin {
  val logic = new Area {
    val p = ctx.p
    val resWidth = ctx.cfuParam.CFU_OUTPUT_DATA_W
    val nLoad = p.vlen / p.xlen
    val lanes = p.xlen / 32
    val laneParam = BitQuantLaneParameter(
      maxQuantBits = 8,
      comparePipe = p.quantPipeStages > 0
    )
    val absMagnitude = Reg(Bits(32 bits)) init (0)
    val absPartsDecoded = BitQuantCompute.fp32MagnitudeParts(absMagnitude(30 downto 0).asUInt)
    val absParts = new BitQuantAbsmaxParts
    absParts.valid := absMagnitude(30 downto 0).asUInt =/= 0 && absMagnitude(30 downto 23).asUInt =/= U(255, 8 bits)
    absParts.effectiveExponent := absPartsDecoded.effectiveExponent
    absParts.significand := absPartsDecoded.significand

    val active = RegInit(False)
    val responsePending = RegInit(False)
    val laneBusy = RegInit(False)
    val launchPending = RegInit(False)
    val pipePending = RegInit(False)
    val modeQ8 = RegInit(False)
    val startBeat = Reg(UInt(log2Up(nLoad) max 1 bits)) init (0)
    val seenCount = Reg(UInt(log2Up(nLoad + 1) max 1 bits)) init (0)
    val resultReg = Reg(Bits(resWidth bits)) init (0)
    val beatDataReg = Reg(Bits(p.xlen bits)) init (0)
    val beatForLane = Reg(UInt(log2Up(nLoad) max 1 bits)) init (0)
    val responseData = Reg(Bits(resWidth bits)) init (0)

    // TileLink GET responses may arrive out of issue order.  A FIFO indexed
    // by arrival order therefore cannot safely feed Q8 chunks: chunk 0 can
    // consume beat 2 before beat 1 arrives and permanently lose the latter.
    // Keep the load stream's beat tag and use it as the physical buffer index.
    // This is also a smaller control structure than a FIFO with discard logic.
    val quantBuffer = if (p.withQ2T || p.withQ8) {
      Some(Mem(Bits(p.xlen bits), nLoad))
    } else {
      None
    }
    val quantBufferValid = if (p.withQ2T || p.withQ8) {
      Some(Vec(RegInit(False), nLoad))
    } else {
      None
    }
    ctx.loadBeat.ready := True
    when (ctx.loadReq.fire) {
      quantBufferValid.foreach(_.foreach(_ := False))
    }
    when (ctx.loadBeat.fire) {
      quantBuffer.foreach(_.write(ctx.loadBeat.payload.beat, ctx.loadBeat.payload.data))
      quantBufferValid.foreach(valid => valid(ctx.loadBeat.payload.beat) := True)
    }
    val quantPipeIn = Stream(BitNetCfuV2QuantTask(p))
    val quantPipeOut = Stream(BitNetCfuV2QuantTask(p))
    val stages = Array.fill((p.quantPipeStages max 0) + 1)(Node())
    val task = Payload(BitNetCfuV2QuantTask(p))
    val links = for (i <- 0 until stages.length - 1) yield StageLink(stages(i), stages(i + 1))
    val quantInputStage = stages(0)
    val quantOutputStage = stages(stages.length - 1)
    if (p.quantPipeStages == 0) {
      quantPipeOut.valid := quantPipeIn.valid
      quantPipeIn.ready := quantPipeOut.ready
      quantPipeOut.payload := quantPipeIn.payload
    } else {
      quantInputStage.arbitrateFrom(quantPipeIn)
      quantOutputStage.arbitrateTo(quantPipeOut)
      new quantInputStage.Area { quantInputStage(task) := quantPipeIn.payload }
      new quantOutputStage.Area {
        val stageTask = stages(stages.length - 1)(task)
        quantPipeOut.payload.beat := stageTask.beat
        quantPipeOut.payload.data := stageTask.data
      }
      Builder(links)
    }

    val quantLanes = Array.tabulate[BitQuantLaneIO](lanes) { _ =>
      if (p.quantStandard) new BitQuantLane(laneParam).io else new BitQuantNormalizedLane(laneParam).io
    }
    val laneDone = quantLanes.map(_.done).reduce(_ && _)
    val packedQ8 = Bits(resWidth bits)
    val packedQ2T = Bits(resWidth bits)
    packedQ8 := 0
    packedQ2T := 0
    for (i <- 0 until lanes) {
      quantLanes(i).qBits := modeQ8.mux(U(8, laneParam.qBitsWidth bits), U(2, laneParam.qBitsWidth bits))
      quantLanes(i).absmax := absMagnitude
      quantLanes(i).absParts := absParts
      quantLanes(i).value := beatDataReg(32 * i, 32 bits)
      quantLanes(i).start := launchPending
      packedQ8((8 * i), 8 bits) := quantLanes(i).result
      packedQ2T((2 * i), 2 bits) := quantLanes(i).result(0, 2 bits)
    }

    val beatWidth = log2Up(nLoad) max 1
    val nextBeat = UInt(beatWidth bits)
    val q8NextBeat = UInt(beatWidth bits)
    val q2tNextBeat = UInt(beatWidth bits)
    q8NextBeat := (startBeat + seenCount.resize(beatWidth)).resized
    q2tNextBeat := seenCount.resize(beatWidth)
    nextBeat := modeQ8.mux(q8NextBeat, q2tNextBeat)
    val quantData = Bits(p.xlen bits)
    quantBuffer match {
      case Some(buffer) => quantData := buffer.readAsync(nextBeat)
      case None => quantData := 0
    }
    val quantDataValid = Bool()
    quantBufferValid match {
      case Some(valid) => quantDataValid := valid(nextBeat)
      case None => quantDataValid := False
    }
    val resultWithBeat = Bits(resWidth bits)
    resultWithBeat := resultReg
    if (p.withQ8) {
      for (i <- 0 until nLoad if i * lanes * 8 + lanes * 8 <= resWidth) {
        when (modeQ8 && beatForLane === (startBeat + U(i, beatForLane.getWidth bits)).resized) {
          resultWithBeat(i * lanes * 8, lanes * 8 bits) := packedQ8(lanes * 8 - 1 downto 0)
        }
      }
    }
    if (p.withQ2T) {
      for (i <- 0 until nLoad) {
        when (!modeQ8 && beatForLane === U(i, beatForLane.getWidth bits)) {
          resultWithBeat(i * lanes * 2, lanes * 2 bits) := packedQ2T(lanes * 2 - 1 downto 0)
        }
      }
    }

    // Start as soon as the first beat of this request is present.  The
    // beat-indexed buffer makes this safe even when TileLink returns later
    // beats before earlier ones; each following beat is checked independently
    // by quantDataValid.  This restores LOAD/Quantize overlap without relying
    // on FIFO arrival order.
    val loadInFlight = ctx.loadBusy.orR
    val requestQ8 = ctx.quantReq.payload.functionId === U(5, 3 bits)
    val requestStartBeat = UInt(beatWidth bits)
    val requestQ8Start = (ctx.quantReq.payload.func7.resize(beatWidth) *
      U(p.quantWidthEffective / p.xlen, beatWidth bits)).resized
    requestStartBeat := requestQ8.mux(requestQ8Start, U(0, beatWidth bits))
    val requestFirstBeatValid = quantBufferValid match {
      case Some(valid) => valid(requestStartBeat)
      case None => False
    }
    val quantReqReady = if (p.withQ2T || p.withQ8) {
      !active && !responsePending && (!loadInFlight || requestFirstBeatValid)
    } else False
    val selectedBeatCount = UInt(seenCount.getWidth bits)
    selectedBeatCount := modeQ8.mux(
      U(p.quantWidthEffective / p.xlen, seenCount.getWidth bits),
      U(nLoad, seenCount.getWidth bits)
    )
    ctx.quantReq.ready := quantReqReady
    ctx.rspQuant.valid := responsePending
    ctx.rspQuant.payload.response_id := 0
    ctx.rspQuant.payload.outputs(0) := responseData
    if (ctx.cfuParam.CFU_WITH_STATUS) ctx.rspQuant.payload.status := 0

    val quantPipeValid = if (p.withQ2T || p.withQ8) {
      active && !laneBusy && !launchPending && !pipePending && quantDataValid
    } else {
      False
    }
    quantPipeIn.valid := quantPipeValid
    quantPipeIn.payload.beat := nextBeat
    quantPipeIn.payload.data := quantData
    quantPipeOut.ready := !laneBusy && !launchPending

    when (ctx.quantReq.fire) {
      active := True
      responsePending := False
      laneBusy := False
      launchPending := False
      pipePending := False
      modeQ8 := ctx.quantReq.payload.functionId === U(5, 3 bits)
      absMagnitude := ctx.quantReq.payload.input0.asBits.resize(32)
      seenCount := 0
      resultReg := 0
      val q8Start = (ctx.quantReq.payload.func7.resize(log2Up(nLoad) max 1) * (p.quantWidthEffective / p.xlen)).resized
      when (ctx.quantReq.payload.functionId === U(5, 3 bits)) {
        startBeat := q8Start
      } otherwise {
        startBeat := 0
      }
    }

    when (quantPipeIn.fire) {
      pipePending := True
    }
    when (quantPipeOut.fire) {
      pipePending := False
      beatDataReg := quantPipeOut.payload.data
      beatForLane := quantPipeOut.payload.beat
      launchPending := True
      laneBusy := True
    }
    when (launchPending) {
      launchPending := False
    }
    when (laneBusy && laneDone && !launchPending) {
      laneBusy := False
      resultReg := resultWithBeat
      when (seenCount === (selectedBeatCount - 1).resized) {
        responseData := resultWithBeat
        responsePending := True
        active := False
      } otherwise {
        seenCount := seenCount + 1
      }
    }
    when (ctx.rspQuant.fire) {
      responsePending := False
    }
  }
}

private class BitNetCfuV2WritebackPlugin(ctx: BitNetCfuV2Context) extends FiberPlugin {
  val logic = new Area {
    val arbiter = StreamArbiterFactory().lowerFirst.noLock.buildOn(
      Seq(ctx.rspConfig, ctx.rspLoad, ctx.rspDot, ctx.rspQuant)
    )
    ctx.cfu.rsp << arbiter.io.output
  }
}

class BitNetCfuV2(
    cfuParam: CfuBusParameter,
    busParam: BusParameter,
    p: BitNetCfuV2Parameter
) extends Component {
  val io = new Bundle {
    val bus = slave(CfuBus(cfuParam))
    val dBus = master(tilelink.Bus(busParam))
  }

  require(BitNetCfuV2Checks.isPow2(p.vlen), "BitNetCfuV2 vlen must be a power of two")
  require(BitNetCfuV2Checks.isPow2(p.xlen), "BitNetCfuV2 xlen must be a power of two")
  require(BitNetCfuV2Checks.isPow2(p.maclen), "BitNetCfuV2 maclen must be a power of two")
  require(p.vlen % p.xlen == 0, "BitNetCfuV2 vlen must be a multiple of xlen")
  require(p.vlen % p.maclen == 0, "BitNetCfuV2 vlen must be a multiple of maclen")
  require(p.maclen % 8 == 0, "BitNetCfuV2 maclen must contain complete int8 lanes")
  require(p.regDepth >= 2, "BitNetCfuV2 requires at least two vector registers")
  require(p.dotPipeStages >= 0, "BitNetCfuV2 dotPipeStages must be non-negative")
  require(p.quantPipeStages >= 0, "BitNetCfuV2 quantPipeStages must be non-negative")
  require(!p.rfSync || p.rfRam, "BitNetCfuV2 synchronous RF requires RAM-backed registers")
  require(!p.asicSram || (p.rfRam && p.rfSync), "BitNetCfuV2 ASIC SRAM mode requires a synchronous RAM-backed vector RF")
  if (p.withQ2T || p.withQ8) {
    require(BitNetCfuV2Checks.isPow2(p.quantWidthEffective), "BitNetCfuV2 quantWidth must be a power of two")
    require(p.vlen % p.quantWidthEffective == 0, "BitNetCfuV2 vlen must be a multiple of quantWidth")
    require(p.quantWidthEffective % p.xlen == 0, "BitNetCfuV2 quantWidth must be a multiple of xlen")
  }

  val localHost = new PluginHost
  val context = new BitNetCfuV2Context(cfuParam, p, io.bus, io.dBus)
  localHost.addService(context)
  localHost.asHostOf(
    new BitNetCfuV2CommandPlugin(context),
    new BitNetCfuV2ConfigPlugin(context),
    new BitNetCfuV2RegisterFilePlugin(context),
    new BitNetCfuV2LoadPlugin(context),
    new BitNetCfuV2DotProductPlugin(context),
    new BitNetCfuV2QuantizePlugin(context),
    new BitNetCfuV2WritebackPlugin(context)
  )
}
