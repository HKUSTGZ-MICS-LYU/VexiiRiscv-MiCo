package vexiiriscv.soc.mico.MiCoSA

import spinal.core._
import spinal.core.sim._

object Config {
  def spinal = SpinalConfig(
      defaultConfigForClockDomains = ClockDomainConfig(
      resetActiveLevel = HIGH
    ),
    onlyStdLogicVectorAtTopLevelIo = false
  )

  def sim = SimConfig.withConfig(spinal).withFstWave
}

case class MiCoSAConfig(
    size : Int = 4,
    dataWidth : Int = 8,
    accWidth : Int = 32
)