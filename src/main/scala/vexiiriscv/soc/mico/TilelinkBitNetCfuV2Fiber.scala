package vexiiriscv.soc.mico

import spinal.core._
import spinal.core.fiber._
import spinal.lib._
import spinal.lib.bus._
import spinal.lib.bus.misc.SizeMapping
import spinal.lib.bus.tilelink._
import spinal.lib.bus.tilelink.fabric._

import vexiiriscv.execute.cfu._

/** TileLink/CFU integration wrapper for the plugin-based BitNetCfuV2. */
object TilelinkBitNetCfuV2Fiber {
  def getCfuBusParameters(xlen: Int = 32) = CfuBusParameter(
    CFU_VERSION = 0,
    CFU_INTERFACE_ID_W = 0,
    CFU_FUNCTION_ID_W = 3,
    CFU_REORDER_ID_W = 0,
    CFU_REQ_RESP_ID_W = 0,
    CFU_INPUTS = 2,
    CFU_INPUT_DATA_W = xlen,
    CFU_OUTPUTS = 1,
    CFU_OUTPUT_DATA_W = xlen,
    CFU_FLOW_REQ_READY_ALWAYS = false,
    CFU_FLOW_RESP_READY_ALWAYS = false,
    CFU_WITH_STATUS = true,
    CFU_RAW_INSN_W = 32,
    CFU_CFU_ID_W = 4,
    CFU_STATE_INDEX_NUM = 5
  )

  def getM2sParameters(name: Nameable, p: BitNetCfuV2Parameter) = {
    val maxGetBytes = if (p.burstLoad) p.loadBytes else p.beatBytes
    tilelink.M2sParameters(
      addressWidth = 32,
      dataWidth = p.xlen,
      masters = List(
        tilelink.M2sAgent(
          name = name,
          mapping = List(
            tilelink.M2sSource(
              id = SizeMapping(0, p.pendingSize),
              emits = M2sTransfers(
                get = tilelink.SizeRange(1, maxGetBytes),
                putFull = tilelink.SizeRange(1, p.beatBytes)
              )
            )
          )
        )
      )
    )
  }
}

class TilelinkBitNetCfuV2Fiber(p: BitNetCfuV2Parameter, xlen: Int) extends Area {
  val bus = Node.down()
  val dBus = bus.bus

  val logic = Fiber build new Area {
    bus.m2s forceParameters TilelinkBitNetCfuV2Fiber.getM2sParameters(TilelinkBitNetCfuV2Fiber.this, p)
    bus.s2m.supported load tilelink.S2mSupport.none()

    val cfuParam = TilelinkBitNetCfuV2Fiber.getCfuBusParameters(xlen)
    val cfuBus = CfuBus(cfuParam)
    val cfu = new BitNetCfuV2(cfuParam, dBus.p, p.copy(cfuInputWidth = xlen))

    cfu.io.bus <> cfuBus
    cfu.io.dBus <> dBus
  }
}
