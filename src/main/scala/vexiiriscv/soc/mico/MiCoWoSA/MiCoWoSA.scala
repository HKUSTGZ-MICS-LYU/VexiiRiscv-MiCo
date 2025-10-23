package vexiiriscv.soc.mico.MiCoWoSA

import spinal.core._
import spinal.lib._

import spinal.core.sim._


// Weight Stationary Array Microarchitecture

class MiCoWoSA(config : MiCoWoSAConfig) extends Component {

    val size = config.size
    val dataWidth = config.dataWidth
    val accWidth = config.accWidth

    val io = new Bundle {
        val a_inputs = in(Vec(SInt(dataWidth bits), size))
        val w_inputs = in(Vec(SInt(dataWidth bits), size))
        val w_load = in(Bool())
        val mode = in(Bool()) // false: int8, true: int4
        val w_sel = in(UInt(log2Up(size) bits))
        val results = out(Vec(SInt(accWidth bits), size))
        val enable = in(Bool())
        val input_zero = in(Bool())
        val clear = in(Bool()) 
    }

    // Create 2D array of PEs
    val pes = Array.fill(size, size)(new MiCoPE(config))
    
    for (i <- 0 until size; j <- 0 until size) {
        val pe = pes(i)(j)

        // Broadcast control signals
        pe.io.enable := io.enable
        pe.io.clear := io.clear
        pe.io.mode := io.mode
        
        val a_val = io.input_zero.mux(S(0, dataWidth bits), io.a_inputs(j))
        val a = Delay(a_val, j, init = S(0, dataWidth bits), when = io.enable)

        pe.io.in_act := (if (i == 0) a else pes(i - 1)(j).io.out_act)
        pe.io.in_acc := (if (j == 0) S(0, accWidth bits) else pes(i)(j - 1).io.out_acc)
        pe.io.in_wt := io.w_inputs(j)
        pe.io.load := (io.w_sel === U(i, log2Up(size) bits)) && io.w_load
    }
    for (i <- 0 until size) {
        io.results(i) := Delay(pes(i)(size - 1).io.out_acc, size - 1 - i, init = S(0, accWidth bits), when = io.enable)
    }
}

object SimulateSA extends App {

    val config = MiCoWoSAConfig(size = 4, dataWidth = 8, accWidth = 32)
    val N = config.size

    // Allow activation rows M <= N via program argument, default to N
    val M = config.size / 4

    // Deterministic matrices:
    val maxMag = 64
    def clip(x: Int) = Math.max(-maxMag, Math.min(maxMag, x))

    val rnd = new scala.util.Random(42) // Fixed seed for reproducibility
    
    // A is M x N (activation shape)
    val A: Array[Array[Int]] = Array.tabulate(M, N) { (i, j) =>
      clip(rnd.nextInt(2 * maxMag + 1) - maxMag)
    }

    // B remains N x N (weights shape)
    val B: Array[Array[Int]] = Array.tabulate(N, N) { (i, j) =>
      clip(rnd.nextInt(2 * maxMag + 1) - maxMag)
    }

    // Reference result: C (M x N). Note: this SA computes A * B^T (row-by-row dot with B rows)
    val C_ref = Array.tabulate(M, N) { (i, j) =>
      (0 until N).map(k => A(i)(k) * B(j)(k)).sum
    }

    Config.sim.compile(new MiCoWoSA(config)).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)

      println(s"Starting MiCoSA test, N=$N, M=$M, dataWidth=${config.dataWidth}, accWidth=${config.accWidth}")
      println(s"Activation A shape: ${M} x ${N}")
      A.foreach(row => println(row.mkString("[", ", ", "]")))
      println("Weights B (N x N):")
      B.foreach(row => println(row.mkString("[", ", ", "]")))
      println("Expected C (M x N) = A * B^T:")
      C_ref.foreach(row => println(row.mkString("[", ", ", "]")))
      println("------------------------------------")

      // Initialize controls
      dut.io.enable #= false
      dut.io.clear #= false
      dut.io.input_zero #= false
      dut.io.w_load #= false
      dut.io.w_sel #= 0
      dut.io.mode #= false // int8 mode
      for (i <- 0 until N) {
        dut.io.a_inputs(i) #= 0
        dut.io.w_inputs(i) #= 0
      }
      dut.clockDomain.waitSampling(1)

      // Clear SA
      dut.io.clear #= true
      dut.clockDomain.waitSampling(1)

      // Load weights (row-wise into the array, select row by w_sel)
      dut.io.clear #= false
      dut.io.w_load #= true
      for (i <- 0 until N) {
        dut.io.w_sel #= i
        for (j <- 0 until N) {
          dut.io.w_inputs(j) #= B(i)(j)
        }
        dut.clockDomain.waitSampling(1)
      }
      dut.io.w_load #= false
      // Start compute
      dut.io.enable #= true
      for (i <- 0 until M) {
        for (j <- 0 until N) {
          dut.io.a_inputs(j) #= A(i)(j)
        }
        dut.clockDomain.waitSampling(1)
      }
      dut.io.input_zero #= true
      dut.clockDomain.waitSampling(2*N-M)

      val resultArray = Array.ofDim[Int](M, N)
      for (i <- 0 until M) {
        for (j <- 0 until N) {
          resultArray(i)(j) = dut.io.results(j).toInt
        }
        dut.clockDomain.waitSampling(1)
      }

      // Compare only M x N results
      println("\n==== Final Results Comparison (M x N) ====")
      var allMatch = true
      var errorCount = 0
      for (i <- 0 until M; j <- 0 until N) {
        val dutResult = resultArray(i)(j)
        val expected = C_ref(i)(j)
        val match_str = if (dutResult == expected) "✓" else "✗"
        println(f"C($i,$j): DUT = $dutResult%8d, Expected = $expected%8d $match_str")
        if (dutResult != expected) {
          allMatch = false
          errorCount += 1
        }
      }
      println("==================================")
      
      if (allMatch) {
        println("[success] ✓ All results match!")
      } else {
        println(s"[fail] ✗ Mismatch detected! Total errors: $errorCount/${M*N}")
      }
      println("Simulation completed!")
    }
}