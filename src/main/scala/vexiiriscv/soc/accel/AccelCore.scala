package vexiiriscv.soc.accel

import spinal.core._
import spinal.core.fiber._
import spinal.lib._
import spinal.lib.fsm._
import spinal.lib.bus._
import spinal.lib.bus.misc._
import spinal.lib.bus.tilelink._
import spinal.lib.misc.InterruptNode

import scala.collection.mutable.ArrayBuffer


object AccelCore {

    def getTilelinkSupport(proposed: bus.tilelink.M2sSupport) = bus.tilelink.SlaveFactory.getSupported(
        addressWidth = 12,
        dataWidth = 32,
        allowBurst = false,
        proposed = proposed
    )

    def getM2sParameters(name: Nameable) = tilelink.M2sParameters(
          addressWidth = 12,
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

    def DotProduct(op_a : Bits, op_b : Bits, vlen : Int) : SInt = {
        val a_vec = op_a.subdivideIn(vlen slices)
        val b_vec = op_b.subdivideIn(vlen slices)
        val a_tmp = Vec(a_vec.zip(b_vec).map{case (a_i, b_i) => a_i.asSInt * b_i.asSInt})
        a_tmp.reduceBalancedTree(_ +^ _).resize(32)
    }
}

class AccelCoreParam(
    val dataWidth : Int = 8,
    val maxSize : Int = 256,
    val numMul: Int = 4 // Number of multipliers to use in parallel
)

class AccelCore (param : AccelCoreParam, 
                upParam: BusParameter, 
                downParam: BusParameter) extends Component{

    val io = new Bundle {
        val bus = slave(tilelink.Bus(upParam))
        val memoryBus = master(tilelink.Bus(downParam))
        val interrupt = out Bool()
    }

    val mapper = new tilelink.SlaveFactory(io.bus, allowBurst = false)

    val computeWidth = param.dataWidth * param.numMul
    val depth = param.maxSize / param.numMul

    assert(isPow2(param.numMul), "numMul must be a power of 2")
    assert(computeWidth == 32, "dataWidth * numMul must equal 32 bits for dot product")
    assert(param.maxSize % param.numMul == 0, "maxSize must be a multiple of numMul")

    val vectorA = Mem(Bits(computeWidth bits), depth)
    val vectorB = Mem(Bits(computeWidth bits), depth)
    
    // Control/status registers
    val ctrl = new Area {
        val start = mapper.createReadAndWrite(Bool(), 0x00) init(False)
        val busy = mapper.createReadOnly(Bool(), 0x04) init(False)
        val done = mapper.createReadAndClearOnSet(Bool(), 0x08) init(False)
        val vectorSize = mapper.createReadAndWrite(UInt(log2Up(param.maxSize) bits), 0x0C) init(0)
        val result = mapper.createReadOnly(SInt(32 bits), 0x10) init(0)
        
        val vectorAAddr = mapper.createReadAndWrite(UInt(downParam.addressWidth bits), 0x14) init(0)
        val vectorBAddr = mapper.createReadAndWrite(UInt(downParam.addressWidth bits), 0x18) init(0)
    }

    // Dot product computation engine
    val compute = new Area {
        val counter = Reg(UInt(log2Up(depth) bits)) init(0)
        val accumulator = Reg(SInt(32 bits)) init(0)

        val memAccessReady = RegInit(False)
        val memAccessValid = RegInit(False)

        // Memory Access Bus Initialization (Read-only Mode)
        io.memoryBus.a.opcode  := tilelink.Opcode.A.GET
        io.memoryBus.a.param   := 0
        io.memoryBus.a.source  := 0
        io.memoryBus.a.data    := 0
        io.memoryBus.a.address := 0
        io.memoryBus.a.mask    := B"1111"
        io.memoryBus.a.size    := (computeWidth / 8 - 1)
        io.memoryBus.a.corrupt := False

        io.memoryBus.a.valid := memAccessValid
        io.memoryBus.d.ready := memAccessReady 

        // FSM for dot product calculation
        val fsm = new StateMachine {
            val IDLE, LOADING_A, LOADING_B, COMPUTING, DONE = State()
            
            setEntry(IDLE)
            
            IDLE.whenIsActive {
                when(ctrl.start && !ctrl.busy) {
                    counter := 0
                    accumulator := 0
                    ctrl.busy := True
                    ctrl.done := False
                    goto(LOADING_A)
                }
            }
            
            LOADING_A.whenIsActive {
                memAccessValid := True
                when(counter < ctrl.vectorSize / param.numMul) {
                    // Send Read request for vector A
                    io.memoryBus.a.address := ctrl.vectorAAddr + (counter * computeWidth / 8)

                    when(io.memoryBus.a.fire){
                        memAccessValid := False
                        memAccessReady := True
                    }

                    when(io.memoryBus.d.fire) {
                        vectorA(counter) := io.memoryBus.d.data
                        counter := counter + 1
                        memAccessReady := False 
                        memAccessValid := True
                    }
                } otherwise {
                    counter := 0
                    goto(LOADING_B)
                }
            }

            LOADING_B.whenIsActive {
                memAccessValid := True
                when(counter < ctrl.vectorSize / param.numMul) {
                    // Send Read request for vector B
                    io.memoryBus.a.address := ctrl.vectorBAddr + (counter * computeWidth / 8)

                    when(io.memoryBus.a.fire){
                        memAccessValid := False
                        memAccessReady := True
                    }

                    when(io.memoryBus.d.fire) {
                        vectorB(counter) := io.memoryBus.d.data
                        counter := counter + 1
                        memAccessReady := False 
                        memAccessValid := True
                    }

                } otherwise {
                    counter := 0
                    goto(COMPUTING)
                }
            }

            COMPUTING.whenIsActive {
                when(counter < ctrl.vectorSize / param.numMul) {
                    val a = vectorA(counter)
                    val b = vectorB(counter)
                    accumulator := accumulator + AccelCore.DotProduct(a, b, param.numMul)
                    counter := counter + 1
                } otherwise {
                    goto(DONE)
                }
            }
            
            DONE.whenIsActive {
                ctrl.result := accumulator
                ctrl.busy := False
                ctrl.done := True
                goto(IDLE)
            }
        }
    }
    
    // Interrupt signals completion of dot product calculation
    io.interrupt := ctrl.done
}

class AccelCoreFiber (param: AccelCoreParam) extends Area{
    val up = tilelink.fabric.Node.up()
    val down = tilelink.fabric.Node.down()
    val interrupt = InterruptNode.master()

    val logic = Fiber build new Area{

        down.m2s forceParameters AccelCore.getM2sParameters(AccelCoreFiber.this)
        down.s2m.supported load tilelink.S2mSupport.none()

        up.m2s.supported.load(
            AccelCore.getTilelinkSupport(up.m2s.proposed)
        )
        up.s2m.none()

        val core = new AccelCore(param, up.bus.p, down.bus.p)

        // Let instantiate our hardware and bind it
        core.io.bus <> up.bus
        core.io.memoryBus <> down.bus
        core.io.interrupt <> interrupt.flag
    }

}