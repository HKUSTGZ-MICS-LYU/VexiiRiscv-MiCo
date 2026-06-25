package vexiiriscv

import scala.language.dynamics
import scala.collection.mutable

case class ExtensionVersion(major: Int, minor: Int)

case class Extension(name: String,
                     var _version: ExtensionVersion = ExtensionVersion(1, 0),
                     var depends: Seq[String] = Seq(),
                     var requires: Seq[String] = Seq(),
                     var implies: Seq[String] = Seq(),
                     var reportIf: Seq[String] => Boolean = _ => true,
                     var ignore: Boolean = false,
                     var dependCheck: Boolean = false,
                     )
{
  def version(major: Int, minor: Int): this.type = {
    _version = ExtensionVersion(major, minor)
    this
  }

  def depend(exts: String*): this.type = {
    depends = exts
    this
  }

  def require(exts: String*): this.type = {
    requires = exts
    this
  }

  def imply(exts: String*): this.type = {
    implies = exts
    this
  }

  def withoutReport: this.type = {
    reportIf = _ => false
    this
  }

  def withReport: this.type = {
    reportIf = _ => true
    this
  }

  def withCondReport(reportCheck: Seq[String] => Boolean): this.type = {
    reportIf = reportCheck
    this
  }

  def needReport(exts: Seq[String]): Boolean = reportIf(exts)

  def ignored: this.type = {
    ignore = true
    this
  }

  def withDependCheck: this.type = {
    dependCheck = true
    this
  }
}

object ExtensionList {
  def E(name: String) = Extension(name)

  val supported = Seq(
    /* Base / one-letter extensions and aliases */
    E("i").version(2, 1),
    E("e").version(2, 0),
    E("m").version(2, 0).imply("zmmul"),
    E("a").version(2, 1).depend("zaamo", "zalrsc").withDependCheck,
    E("f").version(2, 2).depend("zicsr"),
    E("d").version(2, 2).depend("f"),
    E("c").version(2, 0).depend("zca"),
    E("b").depend("zba", "zbb", "zbc", "zbs").withDependCheck,
    E("h").depend("s"),

    /* Special abbreviation extension */
    E("g").depend("i", "m", "a", "f", "d", "zicsr", "zifencei").withDependCheck.ignored,

    /* Privilege-level single-letter support */
    E("s").version(1, 13).depend("u").ignored,
    E("u").version(1, 13).ignored,

    /* Z* extensions */
    E("zicbom"),
    E("zicsr").version(2, 0),
    E("zicntr").version(2, 0),
    E("zifencei").version(2, 0),
    E("zihpm").version(2, 0).depend("zicntr"),
    E("zmmul").withCondReport(!_.contains("m")),
    E("zaamo"),
    E("zalrsc"),
    E("zba"),
    E("zbb"),
    E("zbc"),
    E("zbs"),
    E("zca").depend("c").withDependCheck,
    E("zcd").depend("c", "d").withDependCheck,
    E("zknd"),
    E("zkne"),

    /* Ss* extensions */
    E("ssaia").require("s").depend("smaia", "sscsrind"),
    E("sscofpmf").require("s"),
    E("sscsrind").require("s").depend("smcsrind"),
    E("sstc").require("s").depend("zicntr"),

    /* Sh* extensions */
    E("shlcofideleg").require("h").depend("sscofpmf"),

    /* Sm* extensions */
    E("smcntrpmf"),
    E("smaia").depend("smcsrind"),
    E("smcsrind"),
  )

  val indexed = supported.map(e => e.name -> e).toMap

  def versionOf(name: String): Option[ExtensionVersion] = {
    val key = name.toLowerCase
    indexed.get(key).map(_._version)
  }
}

case class ExtensionManager(var _isas: mutable.Set[String] = mutable.Set()) extends Dynamic {
  private val SingleExtension = "withRv([a-z])".r
  private val MultiExtension = "with([ZS][a-z0-9]+)".r

  def selectDynamic(name: String): Boolean = name match {
    case "withMachine" => true
    case "withSupervisor" => check("s")
    case "withUser" => check("u")
    case "withHypervisor" => check("h")

    case "withMul" => check("zmmul")
    case "withDiv" => check("m")

    case "withIndirectCsr" => check("smcsrind") // sscsrind implies sscsrind
    case "withRdTime" => check("zicntr")
    case "withPerformanceCounters" => check("zicntr")
    case "withPerformanceScountovf" => check("sscofpmf")

    case SingleExtension(ext) => check(ext)
    case MultiExtension(ext) => check(ext.toLowerCase)
    case _ => ???
  }

  def add(exts: String*): Unit = {
    for (ext <- exts.map(_.toLowerCase())) {
      if (ExtensionList.indexed.contains(ext)) {
        if (!check(ext)) {
          val e = ExtensionList.indexed(ext)
          _isas += e.name
          add(e.depends :_*)
          add(e.implies :_*)
          dependCheck(false)
        }
      } else {
        println(s"ISA: ${ext} is not supported")
      }
    }
  }

  def remove(exts: String*): Unit = {
    for (ext <- exts.map(_.toLowerCase())) {
      if (check(ext)) {
        val required = _isas.exists(isa => {
          val e = ExtensionList.indexed(isa)
          (e.requires ++ e.depends ++ e.implies).exists(_ == ext)
        })

        if (!required) {
          _isas -= ext
          dependCheck(true)
        } else {
          println(s"ISA: ${ext} can not be removed as it is required or implied")
        }
      }
    }
  }

  def dependCheck(allowRemove: Boolean): Unit = {
    /* Set all hint extension */
    ExtensionList.supported.filter(_.dependCheck).foreach(e => {
      if (check(e.depends :_*)) add(e.name)
      else if (allowRemove) remove(e.name)
    })
  }

  def finialize(): Unit = {
    add("i")

    if (check("e")) {
      /*
       * Try remove base I first as user force enable base E,
       * then remove base E if I can not be removed
       */
      remove("i")
      if (check("i")) {
        remove("e")
      }
    }

    /* ISA change for VexiiRiscv implementation */

    if (check("zknd") || check("zkne")) {
      add("zknd", "zkne")
    }

    dependCheck(true)

    val brokenExtensions = _isas.filter(e => !check(ExtensionList.indexed(e).requires :_*))
    assert(brokenExtensions.size == 0, s"Unmet Extensions ${brokenExtensions}")
  }

  def check(exts: String*): Boolean = exts.forall(e => _isas.contains(e.toLowerCase()))

  def getIsaStr(includeIgnored: Boolean = false): String = ExtensionManager.getIsaStr(_isas, includeIgnored)

  def getIsaNameArray(includeIgnored: Boolean = false): Seq[String] = ExtensionManager.getIsaNameArray(_isas, includeIgnored)
}

object ExtensionManager {
  def getIsaStr(exts: scala.collection.Set[String], includeIgnored: Boolean = false): String = {
    val extArray = ExtensionManager.getIsaNameArray(exts, includeIgnored)
    val single = extArray.filter(_.length() == 1).mkString
    val multi = extArray.filter(_.length() > 1).map("_" + _).mkString

    single + multi
  }

  def getIsaNameArray(exts: scala.collection.Set[String], includeIgnored: Boolean = false): Seq[String] = {
    ExtensionList.supported.filter(
      e => exts.contains(e.name) && e.needReport(exts.toSeq) && (!e.ignore || includeIgnored)
    ).map(_.name).sortBy(e => ExtensionList.supported.indexWhere(_.name == e))
  }
}
