package vexiiriscv.asic

import spinal.core.MemBlackboxingPolicy
import spinal.core.internals.MemTopology

/**
  * SRAM policy for the ASAP7 macros used by the MiCo ASIC flow.
  *
  * The supplied macros have one synchronous physical address/operation port.
  * The standard Spinal memory phase creates the Ram_1wrs or Ram_1w_1rs
  * component after this policy accepts a compatible topology.
  */
object Asap7SramBlackboxPolicy extends MemBlackboxingPolicy {
  private def hasNoInitialContent(topology: MemTopology): Boolean =
    topology.mem.initialContent == null

  private def isSinglePortReadWrite(topology: MemTopology): Boolean =
    topology.portCount == 1 &&
      topology.readWriteSync.size == 1

  private def isOneWriteOneSyncRead(topology: MemTopology): Boolean =
    topology.portCount == 2 &&
      topology.writes.size == 1 &&
      topology.readsSync.size == 1 &&
      topology.readsAsync.isEmpty &&
      topology.readWriteSync.isEmpty &&
      topology.writeReadSameAddressSync.isEmpty

  override def translationInterest(topology: MemTopology): Boolean =
    hasNoInitialContent(topology) &&
      (isSinglePortReadWrite(topology) || isOneWriteOneSyncRead(topology))

  override def onUnblackboxable(topology: MemTopology, who: Any, message: String): Unit = {
    // Incompatible memories remain ordinary Spinal memories for Yosys/OpenROAD.
  }
}
