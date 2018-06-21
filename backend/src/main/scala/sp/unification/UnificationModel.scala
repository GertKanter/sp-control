package sp.unification

import sp.modelSupport._
import sp.domain.Logic._
import sp.domain._
import sp.devicehandler._

import sp.drivers.ROSFlatStateDriver
import sp.drivers.ROSHelpers

trait ROSSupport extends ModelDSL {
  def writer(driver: String, messageType: String, topic: String, rate: Int) = {
    val emptyMsg = ROSHelpers.createROSMsg(messageType).get // puke if we mis-spell
    val attr = ROSHelpers.ROSMsgToSPAttributes(emptyMsg).get // puke if we can't parse
    attr.value.foreach {
      case (field, nested: SPAttributes) =>
      // TODO: later
      case (field, v) =>
        val ident = messageType + ":" + topic + ":" + field + ":" + rate
        dv(field, driver, ident, WriteOnly)
    }
  }
  def reader(driver: String, messageType: String, topic: String) = {
    val emptyMsg = ROSHelpers.createROSMsg(messageType).get // puke if we mis-spell
    val attr = ROSHelpers.ROSMsgToSPAttributes(emptyMsg).get // puke if we can't parse
    attr.value.foreach {
      case (field, nested: SPAttributes) =>
      // TODO: later
      case (field, v) =>
        val ident = messageType + ":" + topic + ":" + field
        dv(field, driver, ident, ReadOnly)
    }
  }
}



object UnificationModel {
  val bolts = (1 to 3).map(i => s"BoltPair$i")
  def farAboveBolt(b: String) = s"FarAbove${b}TCP"
  def closeAboveBolt(b: String) = s"CloseAbove${b}TCP"
  def atBolt(b: String) = s"At${b}TCP"


  /// UR POSES
  // joint poses
  val HomeJOINT = "HomeJOINT"
  val ResetJOINT = "ResetJOINT"
  val PreAttachAtlasFarJOINT = "PreAttachAtlasFarJOINT"
  val PreAttachLFToolFarJOINT = "PreAttachLFToolFarJOINT"
  val PreAttachOFToolFarJOINT = "PreAttachOFToolFarJOINT"
  val PreFindEngineJOINT = "PreFindEngineJOINT"
  val FindEngineRightJOINT = "FindEngineRightJOINT"
  val FindEngineLeftJOINT = "FindEngineLeftJOINT"
  val FindEngineMidJOINT = "FindEngineMidJOINT"
  val AboveEngineJOINT = "AboveEngineJOINT"

  // above bolts
  val FarAboveBolts = bolts.map(farAboveBolt)
  val CloseAboveBolts = bolts.map(closeAboveBolt)
  val AtBolts = bolts.map(atBolt)

  // tcp poses
  val PreAttachAtlasCloseTCP = "PreAttachAtlasCloseTCP"
  val AttachAtlasTCP = "AttachAtlasTCP"
  val PreAttachLFToolCloseTCP = "PreAttachLFToolCloseTCP"
  val AttachLFToolTCP = "AttachLFToolTCP"
  val PreAttachOFToolCloseTCP = "PreAttachOFToolCloseTCP"
  val AttachOFToolTCP = "AttachOFToolTCP"
  val OFToolFrame1TCP = "OFToolFrame1TCP"
  val OFToolFrame2TCP = "OFToolFrame2TCP"
  val OFToolFrame3TCP = "OFToolFrame3TCP"
  val FindEngineRight2TCP = "FindEngineRight2TCP"
  val FindEngineLeft2TCP = "FindEngineLeft2TCP"
  val FindEngineMid2TCP = "FindEngineMid2TCP"
  val FindEngineRight3TCP = "FindEngineRight3TCP"
  val FindEngineLeft3TCP = "FindEngineLeft3TCP"
  val FindEngineMid3TCP = "FindEngineMid3TCP"

  val poses = List(
    HomeJOINT,
    ResetJOINT,
    PreAttachAtlasFarJOINT,
    PreAttachLFToolFarJOINT,
    PreAttachOFToolFarJOINT,
    PreFindEngineJOINT,
    FindEngineRightJOINT,
    FindEngineLeftJOINT,
    FindEngineMidJOINT,
    AboveEngineJOINT,
    PreAttachAtlasCloseTCP,
    AttachAtlasTCP,
    PreAttachLFToolCloseTCP,
    AttachLFToolTCP,
    PreAttachOFToolCloseTCP,
    AttachOFToolTCP,
    OFToolFrame1TCP,
    OFToolFrame2TCP,
    OFToolFrame3TCP,
    FindEngineRight2TCP,
    FindEngineLeft2TCP,
    FindEngineMid2TCP,
    FindEngineRight3TCP,
    FindEngineLeft3TCP,
    FindEngineMid3TCP) ++ FarAboveBolts ++ CloseAboveBolts ++ AtBolts



  def apply() = new UnificationModel
}

class UnificationModel extends ModelDSL {
  use("UR", new UR)
  use("Atlas", new Atlas)
  // use("MiR", new MiR)

  use("RECU", new RECU)
  use("HECU", new HECU)


  // runner (TODO: for now runners take everything and must be on the top level of the model)




  // MAIN MODEL

  import UnificationModel._

  // products
  v("lf_pos", "on_kitting", List("on_kitting", "on_engine"))
  bolts.foreach { b => v(b, "placed", List("empty", "placed", "tightened")) } // init state empty after testing
  v("filter1", "empty", List("empty", "placed", "tightened"))
  v("filter2", "empty", List("empty", "placed", "tightened"))
  v("pipes", "empty", List("empty", "placed"))

  // resources
  v("boltMode" ,"ur", List("ur", "human"))

  v("urMode", "running", List("running", "float", "stopped"))
  v("urTool", "none", List("none", "lfTool", "atlas", "filterTool")) // Endre: Maybe init none?

  // Endre edited the following also (added atLfToolFar, atLfToolClose, atOfToolFar, atOfToolClose)
  val urPoseDomain = (List("HOME",
    "atLfTool", "atAtlas", "atFilterTool", "aboveEngine", "atLfToolFar", "atLfToolClose",
    "atOfToolFar", "atOfToolClose") ++
    bolts.map(farAboveBolt) ++ bolts.map(closeAboveBolt) ++ bolts.map(atBolt) ++
    List("atFilter1", "atFilter2")).map(s=>SPValue(s))
  println(urPoseDomain)
  v("urPose", "HOME", urPoseDomain)

  // operations

  // goto above engine joint, the starting point for going to above bolt tcp poses
  o(s"gotoAboveEngineJOINT", s"UR.pose.goto_AboveEngineJOINT")(
    c("pre", s"urPose == 'HOME'"),
    c("post", "true", s"urPose := 'aboveEngine'"),
    c("reset", "true"))

  val boltUr = c("pre", "boltMode == 'ur' && urTool == 'atlas'")
  val boltHuman = c("pre", "boltMode == 'human' && (urTool != 'atlas' || urMode == 'float')")

  // go down from far above to nutrunner position and nutrunning, then back up
  bolts.foreach { b =>

    o(s"${b}goto${closeAboveBolt(b)}", s"UR.pose.goto_${closeAboveBolt(b)}")(
      c("pre", s"urPose == '${farAboveBolt(b)}' && $b == 'placed'"), boltUr,
      c("post", "true", s"urPose := '${closeAboveBolt(b)}'"),
      c("reset", "true"))

    o(s"${b}goto${atBolt(b)}", s"UR.pose.goto_${atBolt(b)}")(
      c("pre", s"urPose == '${closeAboveBolt(b)}' && $b == 'placed'"), boltUr,
      c("post", "true", s"urPose := '${atBolt(b)}'"),
      c("reset", "true"))

    o(s"${b}Tighten", "Atlas.startToolForward")(
      c("pre", s"urPose == '${closeAboveBolt(b)}' && $b == 'placed'"), boltUr,
      c("post", "true", s"$b := 'tightened'"),
      c("reset", "true"))

    o(s"${b}backUpTo${farAboveBolt(b)}", s"UR.pose.goto_${farAboveBolt(b)}")(
      c("pre", s"urPose == '${atBolt(b)}' && $b == 'tightened'"), boltUr,
      c("post", "true", s"urPose := '${atBolt(b)}'"),
      c("reset", "true"))

    // o(s"${b}HumanTightenMotion")(//, s"Human.tightenMotion$b")(
    //   c("pre", s"$b == 'placed'"), boltHuman,
    //   c("post", "true", s"urPose := 'atTCPnut$b'"),
    //   c("reset", "true"))
  }


  // Endre adds stuff: start


  o(s"gotoPreAttachLFToolFarJOINT", s"UR.pose.goto_PreAttachLFToolFarJOINT")(
    c("pre", s"urPose == 'HOME'"),
    c("pre", s"lf_pos == 'on_kitting' && urTool == 'none'"),
    c("post", "true", s"urPose := 'atLfToolFar'"),
    c("reset", "true"))

  // goto close pre attach pose for the lf tool
  o(s"gotoPreAttachLFToolCloseTCP", s"UR.pose.goto_PreAttachLFToolCloseTCP")(
    c("pre", s"urPose == 'atLfToolFar'"),
    c("pre", s"(lf_pos == 'on_kitting' && urTool == 'none') || (lf_pos == 'on_engine' && urTool == 'lfTool')"),
    c("post", "true", s"urPose := 'atLfToolClose'"),
    c("reset", "true"))

  // goto attach pose for the lf tool
  o(s"gotoAttachLFToolTCP", s"UR.pose.goto_AttachLFToolTCP")(
    c("pre", s"urPose == 'atLfToolClose'"),
    c("pre", s"lf_pos == 'on_kitting' && urTool == 'none'"),
    c("post", "true", s"urPose := 'atLfTool'"),
    c("reset", "true"))

  // unlock RrsSP connector before attaching LF tool
//  o(s"releaseRspLfTool", s"RECU.unlock_rsp")(
//    c("pre", s"urPose == 'atLfToolClose' && urTool == 'none'" ),
//    c("post", "true", s"urPose := 'atLfTool'"),
//    c("reset", "true"))

  // Endre adds stuff: end


//  // sequence, from aboveEngine to nut 1..2..3..n.. back to aboveEngine
//  val bm = bolts.zipWithIndex.map{case (b,i) => i->b}.toMap
//  bm.foreach {
//    case (0, b) => // FIRST
//      o(s"${b}goto${farAboveBolt(b)}", s"UR.pose.goto_${farAboveBolt(b)}")(
//        c("pre", s"urPose == 'aboveEngine' && $b == 'placed'"), boltUr,
//        c("post", "true", s"urPose := '${farAboveBolt(b)}'"),
//        c("reset", "true"))
//
//    case (i, b) => // OTHERS
//      val prev = bm(i-1)
//      o(s"${b}goto${farAboveBolt(b)}", s"UR.pose.goto_${farAboveBolt(b)}")(
//        c("pre", s"urPose == '${farAboveBolt(prev)}' && $b == 'placed' && $prev == 'tightened'"), boltUr,
//        c("post", "true", s"urPose := '${farAboveBolt(b)}'"),
//        c("reset", "true"))
//  }
//
//  val lastB = bolts.last
//  o(s"${farAboveBolt(lastB)}toAboveEngine", "UR.pose.goto_AboveEngineJOINT")(
//    c("pre", s"urPose == '${farAboveBolt(lastB)}' && $lastB == 'tightened'"),
//    c("post", "true", s"urPose := 'aboveEngine'"),
//    c("reset", "true"))



  runner("runner")

  // share a single driver for all ROS nodes
  // driver("ROSdriver", ROSFlatStateDriver.driverType)
}
