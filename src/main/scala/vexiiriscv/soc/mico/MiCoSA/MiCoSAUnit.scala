package vexiiriscv.soc.mico.MiCoSA

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._
import spinal.lib.bus._
import spinal.lib.bus.misc._
import spinal.lib.bus.tilelink._
import spinal.lib.misc._

import spinal.core.sim._

case class MiCoSAUnitConfig(
    size : Int = 4,
    dataWidth : Int = 8,
    accWidth : Int = 32,
)

/*
    MiCoSAUnit: MiCo Systolic Array Unit with controller
*/
class MiCoSAUnit(config : MiCoSAUnitConfig) extends Component {
  
  val size = config.size
  val dataWidth = config.dataWidth
  val accWidth = config.accWidth

  val io = new Bundle {
    val start = in(Bool())
    val done = out(Bool())
  }

  // Banked Memories for inputs and outputs
  val inputBuffer = Array.fill(size)(Mem(SInt(dataWidth bits), size))
  val weightBuffer = Array.fill(size)(Mem(SInt(dataWidth bits), size))
  val outputBuffer = Array.fill(size)(Mem(SInt(accWidth bits), size))

  val start = Reg(Bool()) init(False)
  val done = Reg(Bool()) init(False)

  val computeCycles = (size - 1) * 2

  val sa = new MiCoSA(MiCoSAConfig(size, dataWidth, accWidth))
  val lsCount = Reg(UInt(log2Up(size) bits)) init(0)
  val computeCount = Reg(UInt(log2Up(computeCycles) bits)) init(0)

  val readEnable = Bool()
  val writeEnable = Bool()

  readEnable := False
  writeEnable := False

  // Default SA Control Signals
  sa.io.enable := False
  sa.io.clear := True
  sa.io.propagate := False
  sa.io.input_zero := False

  io.done := done

  val inputRead = new Area {
    for (i <- 0 until size) {
      sa.io.a_inputs(i) := inputBuffer(i).readSync(lsCount, enable = readEnable)
      sa.io.b_inputs(i) := weightBuffer(i).readSync(lsCount, enable = readEnable)
    }
  }

  val outputWrite = new Area {
    for (i <- 0 until size) {
      outputBuffer(i).write(lsCount, sa.io.results(i), enable = writeEnable)
    }
  }

  // Simple FSM for controlling the SA Unit
  val fsm = new StateMachine {
    val IDLE = new State with EntryPoint
    val LOAD = new State
    val COMPUTE = new State
    val STORE = new State
    IDLE.whenIsActive {
      done := False
      when(io.start) {
        start := True
        goto(LOAD)
      }
    }
    LOAD.onEntry{
      readEnable := True
      lsCount := lsCount + 1
    }
    LOAD.whenIsActive{

      sa.io.enable := True
      sa.io.clear := False

      readEnable := True
      when(lsCount === 0){
        goto(COMPUTE)
      } otherwise {
        lsCount := lsCount + 1
      }
    }
    COMPUTE.onEntry{
      computeCount := 0
    }
    COMPUTE.whenIsActive{
      sa.io.enable := True
      sa.io.input_zero := True
      sa.io.clear := False
      readEnable := False

      when(computeCount === (computeCycles - 1)){
        computeCount := 0
        goto(STORE)
      } otherwise {
        computeCount := computeCount + 1
      }
    }
    STORE.whenIsActive{

      writeEnable := True

      sa.io.propagate := True
      sa.io.enable := True
      sa.io.input_zero := True
      sa.io.clear := False

      when(lsCount === (size - 1)){
        lsCount := 0
        done := True
        start := False
        goto(IDLE)
      } otherwise {
        lsCount := lsCount + 1
      }
    }
  }
}

object SimulateSAUnit extends App {
    val config = MiCoSAUnitConfig(size = 16, dataWidth = 8, accWidth = 32)
    
    val N = config.size 
    // Deterministic matrices:
    // - Keep the original example for N=2
    // - Otherwise create simple values within dataWidth range
    val maxMag = 64
    def clip(x: Int) = Math.max(-maxMag, Math.min(maxMag, x))

    val rnd = new scala.util.Random(42) // Fixed seed for reproducibility
    
    val A: Array[Array[Int]] = Array.tabulate(N, N) { (i, j) =>
      // clip(rnd.nextInt(2 * maxMag + 1) - maxMag)
      clip(rnd.nextInt(maxMag))
      // 1
    }

    val B: Array[Array[Int]] = Array.tabulate(N, N) { (i, j) =>
      // clip(rnd.nextInt(2 * maxMag + 1) - maxMag)
      clip(rnd.nextInt(maxMag))
      // 1
    }

    // Compute reference result: C = A * B
    val C_ref = Array.tabulate(N, N) { (i, j) =>
      (0 until N).map(k => A(i)(k) * B(j)(k)).sum // FIXME: Note the B(j)(k)
    }

    SimConfig.withWave.compile {
      val dut = new MiCoSAUnit(config)
      dut.inputBuffer.foreach(_.simPublic())
      dut.weightBuffer.foreach(_.simPublic())
      dut.outputBuffer.foreach(_.simPublic())
      dut
    }
    .doSim("test") { dut =>
        dut.clockDomain.forkStimulus(10)

        // Load input and weight matrices into the SA Unit's buffers
        for (i <- 0 until N) {
          for (j <- 0 until N) {
            dut.inputBuffer(i).setBigInt(j, BigInt(A(i)(j)))
            dut.weightBuffer(i).setBigInt(j, BigInt(B(i)(j)))
            dut.clockDomain.waitSampling(1)
          }
        }

        // Start the SA Unit
        dut.io.start #= true
        dut.clockDomain.waitRisingEdge(1)
        dut.io.start #= false

        // Wait for computation to complete
        while(!dut.io.done.toBoolean) {
          dut.clockDomain.waitRisingEdge(1)
        }

        // Read back results from output buffer
        val C_sim = Array.ofDim[Int](N, N)
        for (i <- 0 until N) {
          for (j <- 0 until N) {
            // FIXME: Note the C_sim(j)(i)
            C_sim(i)(j) = dut.outputBuffer(i).getBigInt(j.toLong).toInt
            dut.clockDomain.waitSampling(1)
          }
        }

        // Print matrices
        println("Matrix A:")
        for (i <- 0 until N) {
          println(A(i).map(v => f"$v%4d").mkString(" "))
        }
        println("Matrix B:")
        for (i <- 0 until N) {
          println(B(i).map(v => f"$v%4d").mkString(" "))
        }

        // Compare results
        println("\n==== Result Comparison ====")
        var allCorrect = true
        for (i <- 0 until N) {
          for (j <- 0 until N) {
            val correct = C_ref(i)(j)
            val sim = C_sim(i)(j)
            val status = if (correct == sim) "OK" else "ERROR"
            if (correct != sim) allCorrect = false
            println(f"C_ref($i,$j) = $correct%6d, C_sim($i,$j) = $sim%6d --> $status")
          }
        }
        if (allCorrect) {
          println("\nAll results are correct!")
        } else {
          println("\nSome results are incorrect.")
        }
    }
}
