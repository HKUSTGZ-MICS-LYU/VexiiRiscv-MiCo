package vexiiriscv.soc.mico
 
import spinal.core._
import spinal.core.fiber._
import spinal.lib._
import spinal.lib.fsm._
import spinal.lib.bus._
import spinal.lib.bus.misc._
import spinal.lib.bus.tilelink._
import spinal.lib.misc.InterruptNode

import scala.collection.mutable.ArrayBuffer

/*


*/

object BitSerialCore {

    def getTilelinkSupport(proposed: bus.tilelink.M2sSupport) = bus.tilelink.SlaveFactory.getSupported(
        addressWidth = 12,
        dataWidth = 32,
        allowBurst = false,
        proposed = proposed
    )

  def getM2sParameters(name: Nameable, width: Int = 32) = tilelink.M2sParameters(
        addressWidth = 32,
        dataWidth = width,
        masters = List(
          tilelink.M2sAgent(
            name = name,
            mapping = List(
              tilelink.M2sSource(
                id = SizeMapping(0, 1),
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

case class BitSerialCoreParam(
    val dataWidth : Int = 8,
    val xlen : Int = 32
)


class BitSerialCore (
  param : BitSerialCoreParam, 
  upParam: BusParameter, 
  downParam: BusParameter) extends Component{

    val io = new Bundle {
        val cBus = slave(tilelink.Bus(upParam))
        val dBus = master(tilelink.Bus(downParam))
        val interrupt = out Bool()
    }

    val mapper = new tilelink.SlaveFactory(io.cBus, allowBurst = false)

    val ctrl = new Area {
        // Basic Status Registers
        val start = mapper.createReadAndWrite(Bool(), 0x00) init(False)
        val busy = mapper.createReadOnly(Bool(), 0x04) init(False)
        val done = mapper.createReadAndClearOnSet(Bool(), 0x08) init(False)

        // Config Registers
        val computeLength = mapper.createReadAndWrite(UInt(32 bits), 0x0C) init(0)
        val inputWidth = mapper.createReadAndWrite(UInt(5 bits), 0x10) init(0)
        val weightWidth = mapper.createReadAndWrite(UInt(5 bits), 0x14) init(0)
        val inputAddr = mapper.createReadAndWrite(UInt(32 bits), 0x18) init(0)
        val weightAddr = mapper.createReadAndWrite(UInt(32 bits), 0x1C) init(0)

        // Data Registers
        val result = mapper.createReadOnly(SInt(32 bits), 0x20) init(0)
    }

    val xlen = downParam.dataWidth
    val memAccessReady = RegInit(False)
    val memAccessValid = RegInit(False)

    // Default Bus Config
    val mask = B(xlen / 8 bits, default -> True)
    io.dBus.a.opcode  := tilelink.Opcode.A.GET
    io.dBus.a.param   := 0
    io.dBus.a.source  := 0
    io.dBus.a.data    := 0
    io.dBus.a.address := 0
    io.dBus.a.mask    := mask
    io.dBus.a.size    := log2Up(xlen / 8)
    io.dBus.a.corrupt := False
    io.dBus.a.valid   := memAccessReady
    io.dBus.d.ready   := memAccessValid

    when(ctrl.start){
      //TODO: Implement
      memAccessReady := False
      memAccessValid := False
      ctrl.busy := True
      ctrl.result := 0
    }
}


class BitSerialCoreFiber (param: BitSerialCoreParam) extends Area{
    val up = tilelink.fabric.Node.up()
    val down = tilelink.fabric.Node.down()
    // val interrupt = InterruptNode.master()

    val logic = Fiber build new Area{

        down.m2s forceParameters BitSerialCore.getM2sParameters(BitSerialCoreFiber.this, param.xlen)
        down.s2m.supported load tilelink.S2mSupport.none()

        up.m2s.supported.load(
            BitSerialCore.getTilelinkSupport(up.m2s.proposed)
        )
        up.s2m.none()

        val core = new BitSerialCore(param, up.bus.p, down.bus.p)

        // Let instantiate our hardware and bind it
        core.io.cBus <> up.bus
        core.io.dBus <> down.bus
        // core.io.interrupt <> interrupt.flag
    }
}