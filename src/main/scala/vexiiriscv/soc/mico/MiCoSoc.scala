package vexiiriscv.soc.mico

import spinal.core._
import spinal.core.fiber.Fiber
import spinal.lib._
import spinal.lib.bus.amba3.apb.Apb3
import spinal.lib.bus.tilelink
import spinal.lib.bus.tilelink._
import spinal.lib.bus.tilelink.fabric.{Node, SlaveBus}
import spinal.lib.bus.misc.SizeMapping
import spinal.lib.com.spi.ddr.{SpiXdrMasterCtrl, SpiXdrParameter}
import spinal.lib.com.spi.xdr.TilelinkSpiXdrMasterFiber
import spinal.lib.com.uart.TilelinkUartFiber
import spinal.lib.misc.{Elf, TilelinkClintFiber}
import spinal.lib.misc.plic.TilelinkPlicFiber
import spinal.lib.system.tag.MemoryConnection
import vexiiriscv.execute.cfu.{CfuPlugin, CfuTest}
import vexiiriscv.soc.{DmaParameter, TilelinkDmaFiber, TilelinkVexiiRiscvFiber, TilelinkCfuFiber}
import spinal.lib.bus.tilelink.coherent.{CacheFiber, HubFiber, SelfFLush}
import spinal.lib.system.tag.PMA

import vexiiriscv.soc.micro.{SocCtrl}

// Lets define our SoC toplevel
class MiCoSoc(p : MiCoSocParam) extends Component {
  // socCtrl will provide clocking, reset controllers and debugModule (through jtag) to our SoC
  val socCtrl = new SocCtrl(p.socCtrl)

    val system = new ClockingArea(socCtrl.system.cd) {
    // Let's define our main tilelink bus on which the CPU, RAM and peripheral "portal" will be plugged later.
    val mainBus = tilelink.fabric.Node()
    val ioBus = tilelink.fabric.Node() 

    val cpu = new TilelinkVexiiRiscvFiber(p.vexii.plugins())
    if(p.socCtrl.withDebug) socCtrl.debugModule.bindHart(cpu)
    mainBus << List(cpu.iBus, p.vexii.lsuL1Enable.mux(cpu.lsuL1Bus, cpu.dBus))
    ioBus << List(cpu.dBus)
    if (p.vexii.fetchL1Enable) cpu.iBus.setDownConnection { (down, up) =>
      down.a << up.a.halfPipe().halfPipe()
      up.d << down.d.m2sPipe()
    }
    if (p.vexii.lsuL1Enable) {
      cpu.lsuL1Bus.setDownConnection(
        a = p.vexii.lsuL1Coherency.mux(StreamPipe.HALF, StreamPipe.FULL),
        b = StreamPipe.HALF_KEEP,
        c = StreamPipe.FULL,
        d = StreamPipe.M2S_KEEP,
        e = StreamPipe.HALF
      )
      cpu.dBus.setDownConnection(a = StreamPipe.HALF, d = StreamPipe.M2S_KEEP)
    } else {
      cpu.dBus.setDownConnection(a = StreamPipe.S2M)
    }

    val vpuParam = VpuCfuParameter(
      vlen = p.MiCoVpuLen, 
      maclen = p.MiCoVpuWidth,
      xlen = p.MiCoVpuBusWidth,
      noWaitCompute = p.MiCoVpuStress,
      computePipe = p.MiCoVpuPipe)

    val bitNetCfuParam = BitNetCfuParameter(
      vlen = p.BitNetCfuLen,
      maclen = p.BitNetCfuWidth,
      xlen = p.BitNetCfuBusWidth,
      regDepth = p.BitNetCfuRegDepth,
      qType = p.BitNetCfuQType,
      withQ2 = p.BitNetCfuWithQ2,
      withQ2T = p.BitNetCfuWithQ2T,
      withQ8 = p.BitNetCfuWithQ8,
      quantWidth = p.BitNetCfuQuantWidth,
      noWaitCompute = p.BitNetCfuStress,
      rfSync = p.BitNetCfuRfSync,
      computePipe = p.BitNetCfuPipe,
      q8ComparePipe = p.BitNetCfuQ8ComparePipe,
      quantStandard = p.BitNetCfuQuantStandard,
      burstLoad = p.BitNetCfuBurstLoad,
      asicSram = p.asicSram)

    val bitNetCfuV2Param = BitNetCfuV2Parameter(
      vlen = p.BitNetCfuLen,
      maclen = p.BitNetCfuWidth,
      xlen = p.BitNetCfuBusWidth,
      cfuInputWidth = p.vexii.xlen,
      regDepth = p.BitNetCfuRegDepth,
      qType = p.BitNetCfuQType,
      withQ2 = p.BitNetCfuWithQ2,
      withQ2T = p.BitNetCfuWithQ2T,
      withQ8 = p.BitNetCfuWithQ8,
      quantWidth = p.BitNetCfuQuantWidth,
      dotPipeStages = p.BitNetCfuV2DotPipeStages,
      quantPipeStages = p.BitNetCfuV2QuantPipeStages,
      loadBufferDepth = p.BitNetCfuV2LoadBufferDepth,
      rfRam = true,
      rfSync = p.BitNetCfuRfSync,
      burstLoad = p.BitNetCfuBurstLoad,
      quantStandard = p.BitNetCfuQuantStandard,
      asicSram = p.asicSram)

    val cfu = p.useMiCoVpu generate new TilelinkVpuCfuFiber(vpuParam, p.vexii.xlen) {
      mainBus << bus
      bus.setDownConnection(a = StreamPipe.S2M)
    }

    val bitNetCfu = p.useBitNetCfu generate new TilelinkBitNetCfuFiber(bitNetCfuParam, p.vexii.xlen) {
      mainBus << bus
      bus.setDownConnection(a = StreamPipe.S2M)
    }

    val bitNetCfuV2 = p.useBitNetCfuV2 generate new TilelinkBitNetCfuV2Fiber(bitNetCfuV2Param, p.vexii.xlen) {
      mainBus << bus
      bus.setDownConnection(a = StreamPipe.S2M)
    }

    val cfuConnect = p.vexii.withCfu generate (Fiber patch new Area {
      val cpuCfuBus = cpu.logic.core.host[CfuPlugin].logic.bus
      if(p.useMiCoVpu) cfu.logic.cfuBus << cpuCfuBus
      if(p.useBitNetCfu) bitNetCfu.logic.cfuBus << cpuCfuBus
      if(p.useBitNetCfuV2) bitNetCfuV2.logic.cfuBus << cpuCfuBus
    })

    var memBus: Node = null
    val l2 = p.withL2Cache generate new Area {
      val cache = new CacheFiber(withCtrl = true)
      cache.parameter.cacheWays = p.l2Ways
      cache.parameter.cacheBytes = p.l2Bytes
      cache.up << mainBus
      cache.up.setUpConnection(a = StreamPipe.FULL, c = StreamPipe.FULL, d = StreamPipe.FULL)
      cache.down.setDownConnection(d = StreamPipe.S2M)
      memBus = cache.down
    }
    // val hub = p.withHub generate new Area {
    //   val hub = new HubFiber()
    //   hub.up << mainBus
    //   hub.up.setUpConnection(a = StreamPipe.FULL, c = StreamPipe.FULL, d = StreamPipe.FULL)
    //   hub.down.setDownConnection(d = StreamPipe.S2M)
    //   memBus = hub.down
    // }
    if(memBus == null) memBus = mainBus // No L2, no Hub, the CPU is directly connected to the memory bus
    // memBus.forceDataWidth(p.vexii.memDataWidth)

    val dmaMemBus = if(p.withL2Cache) mainBus else memBus

    val dma = p.withDma generate new TilelinkDmaFiber(
      DmaParameter(
        addressWidth = 32,
        dataWidth = 32,
        ctrlAddressWidth = 12,
        ctrlDataWidth = 32,
        lengthWidth = 32,
        pendingSize = 2,
        withInternalLoopback = true
      )
    ) {
      dmaMemBus << mbus
      mbus.setDownConnection(a = StreamPipe.FULL)
    }
    
    var mBus : SlaveBus = null
    if(p.sparseMem){
      val sparseMemAt = 0x82000000l
      val sparseMemSize = 0x10000000l // 256MB by default
      // Compute the actual CPU-side data width to match the sparse memory bus,
      // avoiding unnecessary TileLink width adapters that can trigger
      // "Stream valid persistence failed" assertions.
      val lsuMemDataWidth = if (p.vexii.lsuL1Enable) p.vexii.lsuMemDataWidth else p.vexii.xlen
      val cpuDataWidth = p.vexii.fetchMemDataWidth max lsuMemDataWidth
      mBus = new SlaveBus(
        M2sSupport(
          transfers = M2sTransfers.all,
          dataWidth = cpuDataWidth,
          addressWidth = 32
        ),
        S2mParameters(Nil)
      )
      mBus.node at SizeMapping(sparseMemAt, sparseMemSize) of memBus
      mBus.node.addTag(PMA.MAIN)
    }
    val ramAt = 0x80000000l
    val ramPort = p.ramPort generate new SlaveBus(
      M2sSupport(
        transfers = M2sTransfers.allGetPut,
        dataWidth = p.vexii.memDataWidth,
        addressWidth = log2Up(p.ramBytes)
      ),
      S2mParameters(Nil)
    )
    val ram = (!p.ramPort) generate new tilelink.fabric.RamFiber(p.ramBytes)
    if (p.ramPort) {
      ramPort.node at ramAt of memBus
      ramPort.node.addTag(PMA.MAIN)
    } else {
      ram.up at ramAt of memBus
    }

    // Handle all the IO / Peripheral things
    val peripheral = new Area {
      // Some peripheral may require to have an access as big as the CPU XLEN, so, lets define a bus which ensure it.
      val busXlen = Node()
      busXlen.forceDataWidth(p.vexii.xlen)
      busXlen << ioBus
      busXlen.setUpConnection(a = StreamPipe.HALF, d = StreamPipe.HALF)

      // Most peripheral will work with a 32 bits data bus.
      val bus32 = Node()
      bus32.forceDataWidth(32)
      bus32 << busXlen

      // The clint is a regular RISC-V timer peripheral
      val clint = new TilelinkClintFiber()
      clint.node at 0x10010000 of busXlen

      // The clint is a regular RISC-V interrupt controller
      val plic = new TilelinkPlicFiber()
      plic.node at 0x10C00000 of bus32

      val uart = new TilelinkUartFiber()
      uart.node at 0x10001000 of bus32
      plic.mapUpInterrupt(1, uart.interrupt)

      if(p.withDma) dma.cbus at 0x10003000 of bus32

      val spiFlash = p.withSpiFlash generate new TilelinkSpiXdrMasterFiber(SpiXdrMasterCtrl.MemoryMappingParameters(
        SpiXdrMasterCtrl.Parameters(8, 12, SpiXdrParameter(2, 2, 1)).addFullDuplex(0,1,false),
        xipEnableInit = true,
        xip = SpiXdrMasterCtrl.XipBusParameters(addressWidth = 24, lengthWidth = 6)
      )) {
        plic.mapUpInterrupt(2, interrupt)
        ctrl at 0x10002000 of bus32
        xip at 0x20000000 of bus32
      }
      if(p.withL2Cache) l2.cache.ctrl at 0x10040000 of bus32

      // Let's connect a few of the CPU interfaces to their respective peripherals
      val cpuPlic = cpu.bind(plic) // External interrupts connection
      val cpuClint = cpu.bind(clint) // Timer interrupt + time reference + stop time connection
    }

    val patcher = Fiber patch new Area {
      if (!p.ramPort) {
        p.ramElf.foreach(new Elf(_, p.vexii.xlen).init(ram.thread.logic.mem, 0x80000000l))
        if (p.ramBlackBox || p.asicSram) {
          ram.thread.logic.mem.generateAsBlackBox()
        }
      }
      println(MemoryConnection.getMemoryTransfers(cpu.dBus).mkString("\n"))
    }
  }
}
