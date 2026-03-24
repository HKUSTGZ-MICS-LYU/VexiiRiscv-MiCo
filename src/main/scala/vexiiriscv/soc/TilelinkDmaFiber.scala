package vexiiriscv.soc

import spinal.core._
import spinal.core.fiber._
import spinal.lib._
import spinal.lib.bus._
import spinal.lib.bus.misc._
import spinal.lib.bus.tilelink._
import spinal.lib.bus.tilelink.fabric._

/*
Totally Vibe Coded Tilelink DMA Engine
*/

object TilelinkDmaFiber {
  def getTilelinkSupport(proposed: bus.tilelink.M2sSupport, addressWidth: Int, dataWidth: Int) =
    bus.tilelink.SlaveFactory.getSupported(
      addressWidth = addressWidth,
      dataWidth = dataWidth,
      allowBurst = false,
      proposed = proposed
    )

  def getM2sParameters(name: Nameable, width: Int = 32, pendingSize: Int = 2, addressWidth: Int = 32) = tilelink.M2sParameters(
    addressWidth = addressWidth,
    dataWidth = width,
    masters = List(
      tilelink.M2sAgent(
        name = name,
        mapping = List(
          tilelink.M2sSource(
            id = SizeMapping(0, pendingSize),
            emits = M2sTransfers(
              get = tilelink.SizeRange(1, width / 8),
              putFull = tilelink.SizeRange(1, width / 8)
            )
          )
        )
      )
    )
  )
}

case class DmaParameter(
    addressWidth: Int = 32,
    dataWidth: Int = 32,
    ctrlAddressWidth: Int = 12,
    ctrlDataWidth: Int = 32,
    lengthWidth: Int = 32,
    pendingSize: Int = 2,
    readerSourceId: Int = 0,
    writerSourceId: Int = 1,
    withInternalLoopback: Boolean = false
) {
  require(dataWidth % 8 == 0, "DMA dataWidth must be byte-aligned")
  require(lengthWidth >= 8, "DMA lengthWidth must be >= 8")
  require(readerSourceId != writerSourceId, "Reader and writer source IDs must be distinct")
  require(
    pendingSize > (readerSourceId max writerSourceId),
    "pendingSize must include reader/writer source IDs"
  )
}

case class DmaCtrl(addressWidth: Int, lengthWidth: Int) extends Bundle {
  val base = UInt(addressWidth bits)
  val length = UInt(lengthWidth bits)
  val enable = Bool()
  val loop = Bool()
}

case class DmaStatus(lengthWidth: Int) extends Bundle {
  val done = Bool()
  val busy = Bool()
  val error = Bool()
  val offset = UInt(lengthWidth bits)
}

object DmaEngineState extends SpinalEnum {
  val IDLE, SEND_CMD, WAIT_RSP, PUSH_DATA, DONE = newElement()
}

class DmaReader(dmaParam: DmaParameter, busParam: BusParameter) extends Component {
  private val beatBytes = dmaParam.dataWidth / 8
  private val beatShift = log2Up(beatBytes)

  val io = new Bundle {
    val ctrl = in(DmaCtrl(dmaParam.addressWidth, dmaParam.lengthWidth))
    val status = out(DmaStatus(dmaParam.lengthWidth))
    val output = master(Stream(Bits(dmaParam.dataWidth bits)))
    val bus = master(tilelink.Bus(busParam))
  }

  val offsetWords = Reg(UInt(dmaParam.lengthWidth bits)) init (0)
  val rspData = Reg(Bits(dmaParam.dataWidth bits)) init (0)
  val rspValid = RegInit(False)
  val rspLast = RegInit(False)
  val errorReg = RegInit(False)
  val state = RegInit(DmaEngineState.IDLE)

  val lengthWords = (io.ctrl.length >> beatShift).resized
  val hasTransfer = io.ctrl.length =/= U(0, dmaParam.lengthWidth bits)
  val wordsMinusOne = (lengthWords - U(1, dmaParam.lengthWidth bits)).resized
  val isLastWord = offsetWords === wordsMinusOne
  val currentAddress = (io.ctrl.base + (offsetWords << beatShift).resized).resized

  io.status.done := state === DmaEngineState.DONE
  io.status.busy := state =/= DmaEngineState.IDLE && state =/= DmaEngineState.DONE
  io.status.error := errorReg
  io.status.offset := offsetWords

  io.output.valid := rspValid
  io.output.payload := rspData

  io.bus.a.valid := False
  io.bus.a.opcode := tilelink.Opcode.A.GET
  io.bus.a.param := 0
  io.bus.a.source := U(dmaParam.readerSourceId, widthOf(io.bus.a.source) bits)
  io.bus.a.address := currentAddress
  io.bus.a.mask := B(beatBytes bits, default -> True)
  io.bus.a.data := 0
  io.bus.a.corrupt := False
  io.bus.a.size := log2Up(beatBytes)
  io.bus.d.ready := False

  when(!io.ctrl.enable) {
    state := DmaEngineState.IDLE
    offsetWords := 0
    rspValid := False
    rspLast := False
    errorReg := False
  } otherwise {
    switch(state) {
      is(DmaEngineState.IDLE) {
        offsetWords := 0
        errorReg := False
        when(hasTransfer) {
          state := DmaEngineState.SEND_CMD
        } otherwise {
          state := DmaEngineState.DONE
        }
      }

      is(DmaEngineState.SEND_CMD) {
        io.bus.a.valid := True
        when(io.bus.a.fire) {
          state := DmaEngineState.WAIT_RSP
        }
      }

      is(DmaEngineState.WAIT_RSP) {
        io.bus.d.ready := !rspValid
        when(io.bus.d.fire) {
          val goodOpcode = io.bus.d.opcode === tilelink.Opcode.D.ACCESS_ACK_DATA
          when(io.bus.d.denied || io.bus.d.corrupt || !goodOpcode) {
            errorReg := True
            state := DmaEngineState.DONE
          } otherwise {
            rspData := io.bus.d.data
            rspValid := True
            rspLast := isLastWord
            state := DmaEngineState.PUSH_DATA
          }
        }
      }

      is(DmaEngineState.PUSH_DATA) {
        when(io.output.fire) {
          rspValid := False
          when(rspLast) {
            when(io.ctrl.loop) {
              offsetWords := 0
              state := DmaEngineState.SEND_CMD
            } otherwise {
              state := DmaEngineState.DONE
            }
          } otherwise {
            offsetWords := offsetWords + 1
            state := DmaEngineState.SEND_CMD
          }
        }
      }

      is(DmaEngineState.DONE) {
      }
    }
  }
}

class DmaWriter(dmaParam: DmaParameter, busParam: BusParameter) extends Component {
  private val beatBytes = dmaParam.dataWidth / 8
  private val beatShift = log2Up(beatBytes)

  val io = new Bundle {
    val ctrl = in(DmaCtrl(dmaParam.addressWidth, dmaParam.lengthWidth))
    val status = out(DmaStatus(dmaParam.lengthWidth))
    val input = slave(Stream(Bits(dmaParam.dataWidth bits)))
    val bus = master(tilelink.Bus(busParam))
  }

  val offsetWords = Reg(UInt(dmaParam.lengthWidth bits)) init (0)
  val lastIssued = RegInit(False)
  val errorReg = RegInit(False)
  val state = RegInit(DmaEngineState.IDLE)

  val lengthWords = (io.ctrl.length >> beatShift).resized
  val hasTransfer = io.ctrl.length =/= U(0, dmaParam.lengthWidth bits)
  val wordsMinusOne = (lengthWords - U(1, dmaParam.lengthWidth bits)).resized
  val isLastWord = offsetWords === wordsMinusOne
  val currentAddress = (io.ctrl.base + (offsetWords << beatShift).resized).resized

  io.status.done := state === DmaEngineState.DONE
  io.status.busy := state =/= DmaEngineState.IDLE && state =/= DmaEngineState.DONE
  io.status.error := errorReg
  io.status.offset := offsetWords

  io.input.ready := False

  io.bus.a.valid := False
  io.bus.a.opcode := tilelink.Opcode.A.PUT_FULL_DATA
  io.bus.a.param := 0
  io.bus.a.source := U(dmaParam.writerSourceId, widthOf(io.bus.a.source) bits)
  io.bus.a.address := currentAddress
  io.bus.a.mask := B(beatBytes bits, default -> True)
  io.bus.a.data := io.input.payload
  io.bus.a.corrupt := False
  io.bus.a.size := log2Up(beatBytes)
  io.bus.d.ready := False

  when(!io.ctrl.enable) {
    state := DmaEngineState.IDLE
    offsetWords := 0
    lastIssued := False
    errorReg := False
  } otherwise {
    switch(state) {
      is(DmaEngineState.IDLE) {
        offsetWords := 0
        errorReg := False
        when(hasTransfer) {
          state := DmaEngineState.SEND_CMD
        } otherwise {
          state := DmaEngineState.DONE
        }
      }

      is(DmaEngineState.SEND_CMD) {
        io.bus.a.valid := io.input.valid
        io.input.ready := io.bus.a.ready
        when(io.bus.a.fire) {
          lastIssued := isLastWord
          state := DmaEngineState.WAIT_RSP
        }
      }

      is(DmaEngineState.WAIT_RSP) {
        io.bus.d.ready := True
        when(io.bus.d.fire) {
          val goodOpcode = io.bus.d.opcode === tilelink.Opcode.D.ACCESS_ACK
          when(io.bus.d.denied || io.bus.d.corrupt || !goodOpcode) {
            errorReg := True
            state := DmaEngineState.DONE
          } otherwise {
            when(lastIssued) {
              when(io.ctrl.loop) {
                offsetWords := 0
                state := DmaEngineState.SEND_CMD
              } otherwise {
                state := DmaEngineState.DONE
              }
            } otherwise {
              offsetWords := offsetWords + 1
              state := DmaEngineState.SEND_CMD
            }
          }
        }
      }

      is(DmaEngineState.PUSH_DATA) {
        state := DmaEngineState.DONE
      }

      is(DmaEngineState.DONE) {
      }
    }
  }
}

class DmaController(dmaParam: DmaParameter, ctrlBusParam: BusParameter, memBusParam: BusParameter) extends Component {
  val io = new Bundle {
    val cBus = slave(tilelink.Bus(ctrlBusParam))
    val mBus = master(tilelink.Bus(memBusParam))
    val readerData = master(Stream(Bits(dmaParam.dataWidth bits)))
    val writerData = slave(Stream(Bits(dmaParam.dataWidth bits)))
    val interrupt = out(Bool())
  }

  val mapper = new tilelink.SlaveFactory(io.cBus, allowBurst = false)

  val readerBase = mapper.createReadAndWrite(UInt(dmaParam.addressWidth bits), 0x00) init (0)
  val readerLength = mapper.createReadAndWrite(UInt(dmaParam.lengthWidth bits), 0x04) init (0)
  val readerEnable = mapper.createReadAndWrite(Bool(), 0x08) init (False)
  val readerLoop = mapper.createReadAndWrite(Bool(), 0x0C) init (False)
  val readerDone = mapper.createReadOnly(Bool(), 0x10) init (False)
  val readerOffset = mapper.createReadOnly(UInt(dmaParam.lengthWidth bits), 0x14) init (0)
  val readerError = mapper.createReadOnly(Bool(), 0x18) init (False)
  val readerBusy = mapper.createReadOnly(Bool(), 0x1C) init (False)

  val writerBase = mapper.createReadAndWrite(UInt(dmaParam.addressWidth bits), 0x20) init (0)
  val writerLength = mapper.createReadAndWrite(UInt(dmaParam.lengthWidth bits), 0x24) init (0)
  val writerEnable = mapper.createReadAndWrite(Bool(), 0x28) init (False)
  val writerLoop = mapper.createReadAndWrite(Bool(), 0x2C) init (False)
  val writerDone = mapper.createReadOnly(Bool(), 0x30) init (False)
  val writerOffset = mapper.createReadOnly(UInt(dmaParam.lengthWidth bits), 0x34) init (0)
  val writerError = mapper.createReadOnly(Bool(), 0x38) init (False)
  val writerBusy = mapper.createReadOnly(Bool(), 0x3C) init (False)

  val interruptEnable = mapper.createReadAndWrite(Bool(), 0x40) init (False)
  val interruptPending = mapper.createReadOnly(Bool(), 0x44) init (False)

  val reader = new DmaReader(dmaParam, memBusParam)
  val writer = new DmaWriter(dmaParam, memBusParam)

  reader.io.ctrl.base := readerBase
  reader.io.ctrl.length := readerLength
  reader.io.ctrl.enable := readerEnable
  reader.io.ctrl.loop := readerLoop

  writer.io.ctrl.base := writerBase
  writer.io.ctrl.length := writerLength
  writer.io.ctrl.enable := writerEnable
  writer.io.ctrl.loop := writerLoop

  readerDone := reader.io.status.done
  readerOffset := reader.io.status.offset
  readerError := reader.io.status.error
  readerBusy := reader.io.status.busy

  writerDone := writer.io.status.done
  writerOffset := writer.io.status.offset
  writerError := writer.io.status.error
  writerBusy := writer.io.status.busy

  io.readerData.valid := reader.io.output.valid
  io.readerData.payload := reader.io.output.payload
  reader.io.output.ready := io.readerData.ready

  writer.io.input.valid := io.writerData.valid
  writer.io.input.payload := io.writerData.payload
  io.writerData.ready := writer.io.input.ready

  val readerPendingDone = readerDone && !readerEnable
  val writerPendingDone = writerDone && !writerEnable
  interruptPending := readerPendingDone || writerPendingDone || readerError || writerError
  io.interrupt := interruptEnable && interruptPending

  io.mBus.a.valid := reader.io.bus.a.valid || writer.io.bus.a.valid
  io.mBus.a.payload := reader.io.bus.a.payload
  reader.io.bus.a.ready := False
  writer.io.bus.a.ready := False

  val chooseWriterA = writer.io.bus.a.valid && !reader.io.bus.a.valid
  when(chooseWriterA) {
    io.mBus.a.payload := writer.io.bus.a.payload
  }
  when(io.mBus.a.valid) {
    when(chooseWriterA) {
      writer.io.bus.a.ready := io.mBus.a.ready
    } otherwise {
      reader.io.bus.a.ready := io.mBus.a.ready
    }
  }

  val sourceWidth = widthOf(io.mBus.d.source)
  val toReader = io.mBus.d.source === U(dmaParam.readerSourceId, sourceWidth bits)
  val toWriter = io.mBus.d.source === U(dmaParam.writerSourceId, sourceWidth bits)

  reader.io.bus.d.valid := io.mBus.d.valid && toReader
  reader.io.bus.d.payload := io.mBus.d.payload
  writer.io.bus.d.valid := io.mBus.d.valid && toWriter
  writer.io.bus.d.payload := io.mBus.d.payload

  io.mBus.d.ready := (toReader && reader.io.bus.d.ready) || (toWriter && writer.io.bus.d.ready)
  when(io.mBus.d.valid) {
    assert(toReader || toWriter)
  }
}

class TilelinkDmaFiber(dmaParam: DmaParameter) extends Area {
  import TilelinkDmaFiber._

  val cbus = Node.up()
  val mbus = Node.down()

  val logic = Fiber build new Area {
    mbus.m2s forceParameters getM2sParameters(
      name = TilelinkDmaFiber.this,
      width = dmaParam.dataWidth,
      pendingSize = dmaParam.pendingSize,
      addressWidth = dmaParam.addressWidth
    )
    mbus.s2m.supported load tilelink.S2mSupport.none()

    cbus.m2s.supported.load(
      getTilelinkSupport(
        proposed = cbus.m2s.proposed,
        addressWidth = dmaParam.ctrlAddressWidth,
        dataWidth = dmaParam.ctrlDataWidth
      )
    )
    cbus.s2m.none()

    val ctrl = new DmaController(dmaParam, cbus.bus.p, mbus.bus.p)
    ctrl.io.cBus <> cbus.bus
    ctrl.io.mBus <> mbus.bus

    if(dmaParam.withInternalLoopback) {
      ctrl.io.writerData << ctrl.io.readerData
    }

    val readerData = (!dmaParam.withInternalLoopback) generate ctrl.io.readerData.toIo()
    val writerData = (!dmaParam.withInternalLoopback) generate ctrl.io.writerData.toIo()
    val interrupt = ctrl.io.interrupt.toIo()
  }
}
