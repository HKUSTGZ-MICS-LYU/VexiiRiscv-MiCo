package vexiiriscv.soc.mico.MiCoSA

import spinal.core._
import spinal.lib._

import spinal.core.sim._

case class MiCoSAConfig(
    size : Int = 4,
    dataWidth : Int = 8,
    accWidth : Int = 32
)

/*
    MiCoSA : MiCo Systolic Array
*/
class MiCoSA(config : MiCoSAConfig) extends Component {

    val size = config.size
    val dataWidth = config.dataWidth
    val accWidth = config.accWidth

    val io = new Bundle {
        val a_inputs = in(Vec(SInt(dataWidth bits), size))
        val b_inputs = in(Vec(SInt(dataWidth bits), size))
        val results = out(Vec(SInt(accWidth bits), size))
        val enable = in(Bool())
        val input_zero = in(Bool())
        val propagate = in(Bool())
        val clear = in(Bool()) 
    }

    // Create 2D array of PEs
    val pes = Array.fill(size, size)(new MiCoPE(config))
    
    for (i <- 0 until size; j <- 0 until size) {
        val pe = pes(i)(j)

        pe.io.enable := io.enable
        pe.io.clear := io.clear
        pe.io.propagate := io.propagate
        
        val a_val = io.input_zero.mux(S(0, dataWidth bits), io.a_inputs(j))
        val b_val = io.input_zero.mux(S(0, dataWidth bits), io.b_inputs(i))

        val a = Delay(a_val, j, init = S(0, dataWidth bits), when = io.enable)
        val b = Delay(b_val, i, init = S(0, dataWidth bits), when = io.enable)
        
        // Connect inputs using pattern matching on indices
        pe.io.in_a := (if (i == 0) a else pes(i - 1)(j).io.out_a)
        pe.io.in_b := (if (j == 0) b else pes(i)(j - 1).io.out_b)
        // Propagate Result from bottom to top
        pe.io.in_res := (if (j == size - 1) S(0, accWidth bits) else pes(i)(j + 1).io.out_res)
    }
    for (i <- 0 until size) {
        io.results(i) := pes(i)(0).io.out_res
    }
}

object SimulateSA extends App {

    val config = MiCoSAConfig(size = 32, dataWidth = 8, accWidth = 32)
    val N = config.size

    // Deterministic matrices:
    // - Keep the original example for N=2
    // - Otherwise create simple values within dataWidth range
    val maxMag = 64
    def clip(x: Int) = Math.max(-maxMag, Math.min(maxMag, x))

    val rnd = new scala.util.Random(42) // Fixed seed for reproducibility
    
    val A: Array[Array[Int]] = Array.tabulate(N, N) { (i, j) =>
      clip(rnd.nextInt(2 * maxMag + 1) - maxMag)
    }

    val B: Array[Array[Int]] = Array.tabulate(N, N) { (i, j) =>
      clip(rnd.nextInt(2 * maxMag + 1) - maxMag)
    }

    // Compute reference result: C = A * B
    val C_ref = Array.tabulate(N, N) { (i, j) =>
      (0 until N).map(k => A(i)(k) * B(k)(j)).sum
    }

    Config.sim.compile(new MiCoSA(config)).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)

      println(s"Starting MiCoSA test, N=$N, dataWidth=${config.dataWidth}, accWidth=${config.accWidth}")
      println("Matrix A:")
      A.foreach(row => println(row.mkString("[", ", ", "]")))
      println("Matrix B:")
      B.foreach(row => println(row.mkString("[", ", ", "]")))
      println("Expected C = A * B:")
      C_ref.foreach(row => println(row.mkString("[", ", ", "]")))
      println("------------------------------------")

      // Initialize controls
      dut.io.enable #= false
      dut.io.propagate #= false
      dut.io.clear #= false
      dut.io.input_zero #= false
      for (i <- 0 until N) {
        dut.io.a_inputs(i) #= 0
        dut.io.b_inputs(i) #= 0
      }

      dut.clockDomain.assertReset()
      dut.clockDomain.waitRisingEdge(1)
      dut.clockDomain.deassertReset()

      // Clear accumulators
      dut.io.clear #= true
      dut.clockDomain.waitRisingEdge(1)

      // Cleaning phase
      dut.io.clear #= false

      def dumpRes(tag: String = ""): Unit = {
        if (tag.nonEmpty) println(tag)
        for (i <- 0 until N) {
          println(s"Result($i) = ${dut.io.results(i).toInt}")
        }
        println("--------------------")
      }

      dut.io.enable #= true
      dut.io.input_zero #= false
      val feedCycles = N
      for (t <- 0 until feedCycles) {
        // Feed column t of matrix A
        for (i <- 0 until N) {
          dut.io.a_inputs(i) #= A(i)(t)
        }
        // Feed column t of matrix B
        for (i <- 0 until N) {
          dut.io.b_inputs(i) #= B(t)(i)
        }
        dut.clockDomain.waitRisingEdge(1)
        dumpRes(s"After feed cycle t=$t")
      }
      dut.io.input_zero #= true
      dut.clockDomain.waitRisingEdge(2*N-2)
      dumpRes("After Computation Phase")

      // Start Propagation Phase
      dut.io.propagate #= true
      dut.clockDomain.waitRisingEdge(1)
      val resultArray = Array.ofDim[Int](N, N)
      for (t <- 0 until N) {
        // Capture results
        dumpRes(s"After propagate cycle t=$t")
        for (i <- 0 until N) {
          resultArray(t)(i) = dut.io.results(i).toInt
        }
        dut.clockDomain.waitRisingEdge(1)
      }

      println("\n==== Final Results Comparison ====")
      var allMatch = true
      var errorCount = 0
      for (i <- 0 until N; j <- 0 until N) {
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
        println(s"[fail] ✗ Mismatch detected! Total errors: $errorCount/${N*N}")
      }
        println("Simulation completed!")
    }
}