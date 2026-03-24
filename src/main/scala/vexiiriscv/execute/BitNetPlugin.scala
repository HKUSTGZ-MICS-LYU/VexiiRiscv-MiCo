package vexiiriscv.execute

import spinal.core._
import spinal.core.sim.SpinalSimConfig
import spinal.lib._
import spinal.lib.pipeline.Stageable
import spinal.lib.misc.pipeline._
import vexiiriscv.Generate.args
import vexiiriscv.{Global, ParamSimple, VexiiRiscv}
import vexiiriscv.compat.MultiPortWritesSymplifier
import vexiiriscv.riscv.{IntRegFile, RS1, RS2, Riscv}
import vexiiriscv.tester.TestOptions


object BitNetPlugin{
  //Define the instruction type and encoding that we will use
  val BNSUM   = IntRegFile.TypeR(M"0000000----------001-----0001011")
  def bitnetadd4(a: UInt, w : UInt, QType : String = "1.5b") : SInt = {
    val w_width = QType match {
      case "1b" => 1
      case "1.5b" => 2
      case "2b" => 2
    }
    val a_vec = Range(4, 0, -1).map{i => a(i*8-1 downto i*8-8).asSInt}
    val w_vec = Range(4, 0, -1).map{i => w(i*w_width-1 downto (i-1)*w_width)} 
    val a_tmp = a_vec.zip(w_vec).map{
        case (a_i, w_i) => {
            val a_tmp_i = SInt(8 bits)
            if (w_width == 2){
              when(w_i === B"01".asUInt){
                  a_tmp_i := a_i
              }.elsewhen(w_i === B"11".asUInt){
                  a_tmp_i := -a_i
              }.elsewhen(w_i === B"10".asUInt){
                if(QType == "1.5b"){
                  a_tmp_i := 0
                }
                else{
                  a_tmp_i := -(a_i |<< 1)
                }
              }.otherwise{
                  a_tmp_i := 0
              }
            } else{
              when(w_i === B"0".asUInt){
                a_tmp_i := a_i
              }.otherwise{
                a_tmp_i := -a_i
              }
            }
            a_tmp_i
        }
    }
    Vec(a_tmp).reduceBalancedTree(_ +^ _)
  }
}


class BitNetPlugin(
  val layer : LaneLayer, 
  val QType : String = "1.5b") extends ExecutionUnitElementSimple(layer)  {


  val w_width = QType match {
    case "1b" => 1
    case "1.5b" => 2
    case "2b" => 2
  }

  val logic = during setup new Logic {
    awaitBuild()

    //Let's assume we only support RV32 for now
    assert(Riscv.XLEN.get == 32)

    //Let's get the hardware interface that we will use to provide the result of our custom instruction
    val wb = newWriteback(ifp, 0)
    
    //Specify that the current plugin will implement the ADD4 instruction
    val bnsum = add(BitNetPlugin.BNSUM).spec

    //We need to specify on which stage we start using the register file values
    bnsum.addRsSpec(RS1, executeAt = 0)
    bnsum.addRsSpec(RS2, executeAt = 0)

    //Now that we are done specifying everything about the instructions, we can release the Logic.uopRetainer
    //This will allow a few other plugins to continue their elaboration (ex : decoder, dispatcher, ...)
    uopRetainer.release()

    //Let's define some logic in the execute lane [0]
    val process = new el.Execute(id = 0) {
      //Get the RISC-V RS1/RS2 values from the register file
      val rs1 = el(IntRegFile, RS1).asUInt
      val rs2 = el(IntRegFile, RS2).asUInt

      //Do some computation
      val rd = SInt(32 bits)

      rd := BitNetPlugin.bitnetadd4(rs1, rs2, QType).resized

      //Provide the computation value for the writeback
      wb.valid := SEL
      wb.payload := rd.asBits
    }
  }
}

object BitNetBufferPlugin extends AreaObject{
  //Define the instruction type and encoding that we will use
  val BNSUM   = IntRegFile.TypeR(M"0000000----------001-----0001011")
  val BNSTORE = IntRegFile.TypeR(M"0000000----------010-----0001011")

  val STORE = Payload(Bool())
  val RESULT = Payload(SInt(32 bits))
}

class BitNetBufferPlugin(val layer : LaneLayer,
    val QType : String = "1.5b",
    val bufferWidth : Int = 8) extends ExecutionUnitElementSimple(layer)  {


  val w_width = QType match {
    case "1b" => 1
    case "1.5b" => 2
    case "2b" => 2
  }
  assert(bufferWidth % 8 == 0, "Buffer width must be a multiple of 8")
  assert(w_width == 1 || bufferWidth < 64, 
    "Buffer width must be less than 64 when using 1.5-2b QType")
  val logic = during setup new Logic {
    awaitBuild()
    import SrcKeys._

    //Let's assume we only support RV32 for now
    assert(Riscv.XLEN.get == 32)

    //Let's get the hardware interface that we will use to provide the result of our custom instruction
    val wb = newWriteback(ifp, 0)
    import BitNetBufferPlugin._
    add(BNSUM).srcs(SRC1.RF, SRC2.RF).decode(STORE -> False)
    add(BNSTORE).srcs(SRC1.RF, SRC2.RF).decode(STORE -> True)

    //Now that we are done specifying everything about the instructions, we can release the Logic.uopRetainer
    //This will allow a few other plugins to continue their elaboration (ex : decoder, dispatcher, ...)
    uopRetainer.release()

    //Let's define some logic in the execute lane [0]
    val compute = new el.Execute(id = 0) {
      val buffer = Reg(Bits(w_width*bufferWidth bits)) init(0)
      //Get the RISC-V RS1/RS2 values from the register file
      val rs1 = el(IntRegFile, RS1).asUInt
      val rs2 = el(IntRegFile, RS2).asUInt

      import BitNetPlugin.bitnetadd4

      //Do some computation
      val rd = SInt(32 bits)
      val offset = Reg(UInt(log2Up(bufferWidth/8) bits)) init(0)

      if (bufferWidth > 8){
        when (isValid && SEL && !STORE){
          offset := offset + 1
          // report(L"Offset change: $offset")
        }
      }

      val addr_offset = offset<<log2Up(8*w_width)
      val data = (rs1 ## rs2).asBits

      val buffer_high = buffer(addr_offset + 4*w_width, 4*w_width bits).asUInt
      val buffer_low = buffer(addr_offset, 4*w_width bits).asUInt

      if(QType == "1b"){
        RESULT := (bitnetadd4(rs1, buffer_high, QType) + 
                bitnetadd4(rs2, buffer_low, QType)).resized
      } else {
        RESULT := (bitnetadd4(rs1, buffer_low, QType) + 
                  bitnetadd4(rs2, buffer_high, QType)).resized
      }

      when (isValid && SEL && STORE){
        // Store the weight to the buffer
        offset := 0
        buffer := data(w_width*bufferWidth-1 downto 0)
        // report(L"Store buffer: $buffer")
      }
    }
    val store = new el.Execute(id = 0) {
      wb.valid := SEL
      wb.payload := RESULT.asBits
    }
  }
}
