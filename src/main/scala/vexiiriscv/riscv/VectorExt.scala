package vexiiriscv.riscv

import spinal.core._

import scala.collection.mutable

object VectorExt extends AreaObject {
    import VectorRegFile._

    val VecADD      =   TypeR(M"0000000----------100-----0001111") // Vector addition
    val VecDOT      = TypeV2I(M"1000000----------100-----0001111") // Vector dot product
    
    val VecLD       = TypeILQ(M"-----------------011-----0001111") // Load 64bits to a register
}