package vexiiriscv.riscv

import spinal.core._

import scala.collection.mutable

object VectorExt extends AreaObject {
    import VectorRegFile._

    val VecADD      =   TypeR(M"0000000----------100-----0001111") // Vector addition
    val VecDOT      = TypeV2I(M"1000000----------100-----0001111") // Vector dot product
    
    val VecLDLow    = TypeILQ(M"-----------------011-----0001111") // Load 64bits to the lower 64bits of a register
    val VecLDHigh   = TypeILQ(M"-----------------111-----0001111") // Load 64bits to the higher 64bits of a register
    val VecSTLow    = TypeSSQ(M"-----------------011-----0011011") // Store lower 64bits of a register to memory
    val VecSTHigh   = TypeSSQ(M"-----------------111-----0011011") // Store higher 64bits of a register to memory
}