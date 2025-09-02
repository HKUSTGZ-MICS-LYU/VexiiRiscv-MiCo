package vexiiriscv.soc

import spinal.core
import spinal.core._
import spinal.core.fiber._

import spinal.lib._
import spinal.lib.bus._
import spinal.lib.bus.misc._
import spinal.lib.bus.tilelink._
import spinal.lib.bus.tilelink.fabric._
import spinal.lib.misc._

import scala.collection.mutable.ArrayBuffer

import vexiiriscv.execute.cfu._

/* A Template for a Tilelink CFU (Custom Functional Unit) Fiber.
 * This fiber connects a CFU bus to CPU, allowing custom instructions
 * to be executed in a VexiiRiscv system.
 */

object TilelinkCfuFiber {
  def getTilelinkSupport(proposed: bus.tilelink.M2sSupport) = bus.tilelink.SlaveFactory.getSupported(
        addressWidth = 32,
        dataWidth = 32,
        allowBurst = false,
        proposed = proposed
    )

  def getM2sParameters(name: Nameable) = tilelink.M2sParameters(
        addressWidth = 32,
        dataWidth = 32,
        masters = List(
          tilelink.M2sAgent(
            name = name,
            mapping = List(
              tilelink.M2sSource(
                id = SizeMapping(0, 1),
                emits = M2sTransfers(
                  get = tilelink.SizeRange(1, 32 / 8),
                  putFull = tilelink.SizeRange(1, 32 / 8)
                )
              )
            )
          )
        )
      )
  
  def getCfuBusParameters = CfuBusParameter(
        CFU_VERSION = 0,
        CFU_INTERFACE_ID_W = 0,
        CFU_FUNCTION_ID_W = 3,
        CFU_REORDER_ID_W = 0,
        CFU_REQ_RESP_ID_W = 0,
        CFU_INPUTS = 2,
        CFU_INPUT_DATA_W = 32,
        CFU_OUTPUTS = 1,
        CFU_OUTPUT_DATA_W = 32,
        CFU_FLOW_REQ_READY_ALWAYS = false,
        CFU_FLOW_RESP_READY_ALWAYS = false,
        CFU_WITH_STATUS = true,
        CFU_RAW_INSN_W = 32,
        CFU_CFU_ID_W = 4,
        CFU_STATE_INDEX_NUM = 5
      )
}

object VectorDotCompute extends AreaObject {
    def DotProduct(op_a : Bits, op_b : Bits, vlen : Int) : SInt = {
        val a_vec = op_a.subdivideIn(vlen bits)
        val b_vec = op_b.subdivideIn(vlen bits)
        val a_tmp = Vec(a_vec.zip(b_vec).map{case (a_i, b_i) => a_i.asSInt * b_i.asSInt})
        a_tmp.reduceBalancedTree(_ +^ _).resize(32)
    }

    def Extend2bTo4b(op : Bits) : Bits = {
        // Sign Extend 2-bit to 4-bit
        val ext = op.subdivideIn(2 bits).reverse.map{
            i => i.asSInt.resize(4).asBits
        }.reduce(_ ## _)
        ext
    }

    def Extend4bTo8b(op : Bits) : Bits = {
        // Sign Extend 4-bit to 8-bit
        val ext = op.subdivideIn(4 bits).reverse.map{
            i => i.asSInt.resize(8).asBits
        }.reduce(_ ## _)
        ext
    }
}

class DemoCfu(cfuParam: CfuBusParameter, busParam: BusParameter) extends Component {

  def elemwiseAdd(opa: Bits, opb: Bits, elemWidth: Int) : Bits = {
      require(opa.getBitsWidth == opb.getBitsWidth, "Operands must have the same bit width")
      require(opa.getBitsWidth % elemWidth == 0, "Element width must divide the operand width")
      val veca = opa.subdivideIn(elemWidth bits)
      val vecb = opb.subdivideIn(elemWidth bits)
      val vecRes = veca.zip(vecb).map { case (a, b) => a.asSInt + b.asSInt }
      vecRes.asBits
  }
  def reduceDotProd(opa: Bits, opb: Bits, elemWidth: Int): Bits = {
      require(opa.getBitsWidth == opb.getBitsWidth, "Operands must have the same bit width")
      require(opa.getBitsWidth % elemWidth == 0, "Element width must divide the operand width")
      val veca = opa.subdivideIn(elemWidth bits)
      val vecb = opb.subdivideIn(elemWidth bits)
      val dotProd = veca.zip(vecb).map { case (a, b) => a.asSInt * b.asSInt }.reduce(_ + _)
      dotProd.asBits.resized
  }

  val io = new Bundle {
    val bus = slave(CfuBus(cfuParam))
    val dBus = master(tilelink.Bus(busParam))
  }
  io.bus.rsp.arbitrationFrom(io.bus.cmd)
  io.bus.rsp.response_id := io.bus.cmd.request_id

  if (cfuParam.CFU_WITH_STATUS) io.bus.rsp.status := B"000" 

  val cfuRes = Bits(32 bits)
  val func3 = io.bus.cmd.function_id.asBits

  cfuRes := func3.mux(
    B"000" -> elemwiseAdd(io.bus.cmd.inputs(0), io.bus.cmd.inputs(1), 8),
    B"001" -> reduceDotProd(io.bus.cmd.inputs(0), io.bus.cmd.inputs(1), 8),
    default -> B(0)
  )
  io.bus.rsp.outputs(0) := cfuRes

  io.dBus.a.opcode  := tilelink.Opcode.A.GET
  io.dBus.a.param   := 0
  io.dBus.a.source  := 0
  io.dBus.a.data    := 0
  io.dBus.a.address := 0
  io.dBus.a.mask    := B"1111"
  io.dBus.a.size    := 2 // 32 bits
  io.dBus.a.corrupt := False
  io.dBus.a.valid := False
  io.dBus.d.ready := False
}

class VPUCfu(cfuParam: CfuBusParameter, busParam: BusParameter) extends Component {
    val xlen = 32
    val vlen = 128
    val vlenLog2 = log2Up(vlen)

    val io = new Bundle {
        val bus = slave(CfuBus(cfuParam))         // Linking CPU
        val dBus = master(tilelink.Bus(busParam)) // Linking Memory
    }

    import VectorDotCompute._
    val func3 = io.bus.cmd.function_id.asBits

    val rs1 = Reg(Bits(vlen bits)) init(0)
    val rs2 = Reg(Bits(vlen bits)) init(0)

    // ---------------------------处理访存部分---------------------------
    val RD = RegInit(False)
    val accessAddr = Reg(UInt(32 bits)) init(0)
    
    // CSRs
    val cfuBusy = RegInit(False) // Indicating whether CSR is busy

    // Load
    val memValid = RegInit(False)                        // 控制内存请求有效信号
    val memReady = RegInit(False)                        // 控制内存响应准备信号
    val loadVecOffset = Reg(UInt(2 bits)) init(0)        // 已完成的读取次数
    val bufferArray = Vec(Reg(Bits(32 bits)) init(0), 3) // 已读取的数据
    
    val isLoad   = func3 === B"100"
    val isConfig = func3 === B"010"
    val isVDot   = func3 === B"001"

    // CFU response defaults
    io.bus.rsp.valid := False
    io.bus.rsp.response_id := io.bus.cmd.request_id
    if (cfuParam.CFU_WITH_STATUS) io.bus.rsp.status := B"000"

    // Tilelink bus defaults
    io.dBus.a.opcode  := tilelink.Opcode.A.GET
    io.dBus.a.param   := 0
    io.dBus.a.source  := 0
    io.dBus.a.data    := 0
    io.dBus.a.address := accessAddr
    io.dBus.a.mask    := B"1111"
    io.dBus.a.size    := 2 // 32 bits
    io.dBus.a.corrupt := False
    io.dBus.a.valid   := memValid
    io.dBus.d.ready   := memReady

    // CFU command ready: 只有在未进行操作时才接受新命令
    io.bus.cmd.ready := !cfuBusy
    io.bus.rsp.outputs(0) := 0

    // State Machine Control
    when(!cfuBusy) {
        // 空闲状态: 等待CFU命令
        when(io.bus.cmd.valid && isLoad) {
            accessAddr := io.bus.cmd.inputs(0).asUInt
            RD := io.bus.cmd.raw_insn(20)
            loadVecOffset := 0
            cfuBusy := True
            memValid := True  // 发起第一次内存请求
        }
        when(io.bus.cmd.valid && isVDot) {
            cfuBusy := False
            io.bus.rsp.valid := True
            io.bus.rsp.outputs(0) := DotProduct(rs1, rs2, 8).asBits
        }
    } otherwise {
        // 正在进行连续读取
        when(io.dBus.a.fire) {
            // report(L"[Memory Test] Command Sent: address 0x$accessAddr")
            accessAddr := accessAddr + 4
            memValid := False
            memReady := True  // 准备接收响应
        }
        
        when(io.dBus.d.fire) {
            // report(L"[Memory Test] Read data 0x${io.dBus.d.data} from address 0x$accessAddr")
            // 内存响应已接收
            loadVecOffset := loadVecOffset + 1
            memReady := False
            
            when(loadVecOffset === 3) {
                // 最后一次读取已完成，发送响应给CPU
                cfuBusy := False
                io.bus.rsp.valid := True
                io.bus.rsp.outputs(0) := bufferArray(loadVecOffset - 1) // 输出最后读取的数据
                when(!RD) {
                    rs1 := bufferArray.asBits ## io.dBus.d.data
                } otherwise {
                    rs2 := bufferArray.asBits ## io.dBus.d.data
                }
            } otherwise {
                bufferArray(loadVecOffset) := io.dBus.d.data
                memValid := True  // 发起下一次内存请求
            }
        }
    }
}

class TilelinkCfuFiber() extends Area {

  import TilelinkCfuFiber._

  val bus = Node.down()
  val dBus = bus.bus

  val logic = Fiber build new Area{
      bus.m2s forceParameters getM2sParameters(TilelinkCfuFiber.this)
      bus.s2m.supported load tilelink.S2mSupport.none()

      val cfuParam = getCfuBusParameters

      val cfuBus = CfuBus(cfuParam)

      val cfu = new VPUCfu(cfuParam, dBus.p)
      cfu.io.bus <> cfuBus
      cfu.io.dBus <> dBus
  }
}