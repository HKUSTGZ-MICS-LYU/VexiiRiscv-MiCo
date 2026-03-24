package vexiiriscv

import vexiiriscv.execute.{BitNetBufferPlugin, BitNetPlugin, MiCoMultiCyclePlugin}

import java.lang.reflect.Modifier

class ParamMiCo extends ParamSimple {
  var withMiCo = false
  var micoWidth = 32
  var micoStaged = false
  var withBitNet = false
  var bitNetQType = "1.5b"
  var bitNetVersion = 32

  override def addOptions(parser: scopt.OptionParser[Unit]) = {
    super.addOptions(parser)
    import parser._
    opt[Unit]("mico") action { (v, c) => withMiCo = true }
    opt[Int]("mico-width") action { (v, c) => micoWidth = v }
    opt[Unit]("mico-staged") action { (v, c) => micoStaged = true }
    opt[String]("bitnet-qtype") action { (v, c) => bitNetQType = v }
    opt[Int]("bitnet-version") action { (v, c) => bitNetVersion = v }
    opt[Unit]("bitnet") action { (v, c) => withBitNet = true }
  }

  override def plugins(hartId: Int = 0) = {
    val pa = super.pluginsArea(hartId)
    if(withMiCo) {
      pa.plugins += new MiCoMultiCyclePlugin(pa.early0, staged = micoStaged, simdWidth = micoWidth)
      // if(withMiCo) plugins += new MiCoPluginV2(early0)
    }
    if(withBitNet){
      if(bitNetVersion == 4) pa.plugins += new BitNetPlugin(pa.early0, bitNetQType)
      else pa.plugins += new BitNetBufferPlugin(pa.early0, bitNetQType, bitNetVersion)
    }
    pa.plugins
  }

  def setMiCo(width: Int = 32, staged: Boolean = false): this.type = {
    withMiCo = true
    micoWidth = width
    micoStaged = staged
    this
  }

  def setBitNet(qType: String = "1.5b", version: Int = 4): this.type = {
    withBitNet = true
    bitNetQType = qType
    bitNetVersion = version
    this
  }

  override def getName(): String = {
    val base = super.getName()
    val withMiCoName = if(withMiCo) s"${base}_micoW${micoWidth}${if(micoStaged) "S" else ""}" else base
    if(withBitNet) s"${withMiCoName}_bnV${bitNetVersion}Q${bitNetQType.replace(".", "p")}" else withMiCoName
  }

  // ParamSimple hashCode is based on getDeclaredFields of runtime class.
  // For subclasses, include both subclass and parent fields to keep uniqueness.
  override def hashCode(): Int = {
    val md = new StringBuilder()
    var cls: Class[_] = this.getClass
    while(cls != null && cls != classOf[Object]) {
      for(f <- cls.getDeclaredFields if !Modifier.isStatic(f.getModifiers) && !f.isSynthetic) {
        f.setAccessible(true)
        val o = f.get(this)
        if(o != null) o.asInstanceOf[Any] match {
          case e: Boolean => md ++= s" $e"
          case e: Int => md ++= s" $e"
          case e: Long => md ++= s" $e"
          case e: BigInt => md ++= s" $e"
          case e: String => md ++= s" $e"
          case e: Product => md ++= s" $e"
          case e =>
            if(e.getClass.getName == "scala.Enumeration$Val") {
              md ++= s" ${e.toString}"
            } else {
              println(s"$e")
              ???
            }
        }
      }
      cls = cls.getSuperclass
    }
    Math.abs(md.toString.hashCode())
  }
}
