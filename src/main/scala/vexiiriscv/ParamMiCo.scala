package vexiiriscv

import vexiiriscv.execute.MiCoMultiCyclePlugin

import java.lang.reflect.Modifier

class ParamMiCo extends ParamSimple {
  var withMiCo = false
  var micoWidth = 32
  var micoStaged = false

  override def addOptions(parser: scopt.OptionParser[Unit]) = {
    super.addOptions(parser)
    import parser._
    opt[Unit]("mico") action { (v, c) => withMiCo = true }
    opt[Int]("mico-width") action { (v, c) => micoWidth = v }
    opt[Unit]("mico-staged") action { (v, c) => micoStaged = true }
  }

  override def plugins(hartId: Int = 0) = {
    val pa = super.pluginsArea(hartId)
    if(withMiCo) {
      pa.plugins += new MiCoMultiCyclePlugin(pa.early0, staged = micoStaged, simdWidth = micoWidth)
      // if(withMiCo) plugins += new MiCoPluginV2(early0)
    }
    pa.plugins
  }

  def setMiCo(width: Int = 32, staged: Boolean = false): this.type = {
    withMiCo = true
    micoWidth = width
    micoStaged = staged
    this
  }

  override def getName(): String = {
    val base = super.getName()
    if(withMiCo) s"${base}_micoW${micoWidth}${if(micoStaged) "S" else ""}" else base
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
