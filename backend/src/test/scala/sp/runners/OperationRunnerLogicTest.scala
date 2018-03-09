package sp.runners

import akka.actor._
import akka.testkit._
import com.typesafe.config._
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}
import sp.domain.Logic._
import sp.domain._
import sp.domain.logic.{ActionParser, PropositionParser}
import sp.runners.{APIOperationRunner => api}


/**
  * Created by kristofer on 2016-05-04.
  */
class OperationRunnerLogicTest(_system: ActorSystem) extends TestKit(_system) with ImplicitSender
  with WordSpecLike with Matchers with BeforeAndAfterAll with Parsing  {

  def this() = this(ActorSystem("myTest", ConfigFactory.parseString(
    """
      |akka.loglevel = INFO
    """.stripMargin)))

  val p = TestProbe()
  val e = TestProbe()
  //val sh = system.actorOf(OperatorService.props(p.ref), "OperatorService")

  override def beforeAll: Unit = {

  }

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }




  "Op runner logic" must {

    val t1 = Thing("t1")
    val t2 = Thing("t2")
    val o1x = Operation("o1")
    val o2x = Operation("o2")
    val o3x = Operation("o3")
    val a1 = Thing("a1")
    val a2 = Thing("a2")
    val a3 = Thing("a3")


    val ids = List(t1, t2, o1x, o2x, o3x)
    val o1Pre = prop(ids, "t1 == 0", List("t1 := 1"))
    val o1Post = prop(ids, "", List("t1 := 2"), "post")

    val o2Pre = prop(ids, "o1 == f", List("t2 := 1"))
    val o3Pre = prop(ids, "t1 == 2", List("t1 := 3"))

    val o1 = o1x.copy(conditions = List(o1Pre, o1Post))
    val o2 = o2x.copy(conditions = List(o2Pre))
    val o3 = o3x.copy(conditions = List(o3Pre))



    val init = ids.collect{
      case x: Thing => x.id -> SPValue(0)
      case x: Operation => x.id -> SPValue("i")
    } toMap

    val abOp = Map(
      o1.id -> a1.id,
      o2.id -> a2.id,
      o3.id -> a3.id
    )

    val setup = api.Setup("r1", ID.newID, Set(o1, o2, o3), abOp, init)

    val initState = SPState(state = init)
    val ops = Set(o1, o2, o3)


    "evaluate ops" in {
      val logic = new OperationRunnerLogic{def log = akka.event.Logging.getLogger(system, this)}
      val res = logic.evaluateOps(List(o1, o2, o3), initState)
      assert(res == List(o1))

      var upd = logic.runOp(o1, initState)
      assert(upd(o1.id) == SPValue("e"))
      assert(upd(t1.id) == SPValue(1))

      val upd2 = logic.completeOP(o1, upd)
      assert(upd2(o1.id) == SPValue("f"))
      assert(upd2(t1.id) == SPValue(2))

      val res2 = logic.evaluateOps(List(o1, o2, o3), upd2)
      assert(res2 == List(o2, o3))
    }

    "upd state" in {
      val logic = new OperationRunnerLogic{def log = akka.event.Logging.getLogger(system, this)}
      val s = initState.add(Map[ID, SPValue](o1.id -> "f", t1.id -> 2))

      val res = logic.evaluateOps(List(o1, o2, o3), s)
      assert(res == List(o2, o3))

      var starting = List[ID]()
      val f = (o: ID) => starting = o :: starting

      var states = List[SPState]()
      val f2 = (o: SPState) => states = o :: states

      val upd = logic.newState(s, ops, abOp, f,  f2, false)
      println("jhsfd")
      println(upd)
      println(starting)
      println(states)
      assert(starting == List(a3.id, a2.id))
      assert(upd(t1.id) == SPValue(3) && upd(t2.id) == SPValue(1) &&
        upd(o2.id) == SPValue("e") && upd(o3.id) == SPValue("e"))

    }


    "run ops" in {
      val logic = new OperationRunnerLogic{def log = akka.event.Logging.getLogger(system, this)}
      val newS = setup.copy(opAbilityMap = Map(o1.id -> a1.id))
      logic.addRunner(newS)


      var starting = List[ID]()
      val f = (o: ID) => starting = o :: starting

      var states = List[SPState]()
      val f2 = (o: SPState, id: ID) => states = o :: states

      logic.setRunnerState(setup.runnerID, initState, f, f2(_, setup.runnerID))

      logic.newAbilityState(a1.id, SPValue("enabled"), f, f2)
      logic.newAbilityState(a1.id, SPValue("finished"), f, f2)

      logic.tickRunner(setup.runnerID, f, f2(_, setup.runnerID))


      //println("sfdsdf")
      //println(starting)
      //println(states)

      starting shouldEqual List(a1.id)
      states.head.get(o3.id).get shouldEqual  SPValue(OperationState.finished)
    }
    "run ops, reset and no ability" in {
      val logic = new OperationRunnerLogic{def log = akka.event.Logging.getLogger(system, this)}

      val resetC = Condition(AlwaysTrue, List(), SPAttributes("kind"->"reset"))
      val updO3 = o3.copy(conditions = o3.conditions :+ resetC)


      val newS = setup.copy(
        opAbilityMap = Map(),
        ops = Set(o1, o2, updO3)
      )

      logic.addRunner(newS)


      var starting = List[ID]()
      val f = (o: ID) => starting = o :: starting

      var states = List[SPState]()
      val f2 = (o: SPState, id: ID) => states = o :: states

      logic.setRunnerState(setup.runnerID, initState, f, f2(_, setup.runnerID))

      logic.tickRunner(setup.runnerID, f, f2(_, setup.runnerID))
      logic.tickRunner(setup.runnerID, f, f2(_, setup.runnerID))
      logic.tickRunner(setup.runnerID, f, f2(_, setup.runnerID))


      //println("sfdsdf")
      //println(starting)
      //println(states)

      starting shouldEqual List()
      states.head.get(o3.id).get shouldEqual  SPValue(OperationState.init)
    }

    "testing messages" in {
      val s = OperationRunnerInfo.apischema
      println(s)
      val t = api.GetRunners
      println(SPValue(t))
    }

  }


}

import sp.domain.logic.{PropositionParser, ActionParser}
trait Parsing {
  def v(name: String, drivername: String) = Thing(name, SPAttributes("drivername" -> drivername))
  def prop(vars: List[IDAble], cond: String, actions: List[String] = List(), kind: String = "pre") = {
    def c(condition: String): Option[Proposition] = {
      PropositionParser(vars).parseStr(condition) match {
        case Right(p) => Some(p)
        case Left(err) => println(s"Parsing failed on condition: $condition: $err"); None
      }
    }

    def a(actions: List[String]): List[Action] = {
      actions.flatMap { action =>
        ActionParser(vars).parseStr(action) match {
          case Right(a) => Some(a)
          case Left(err) => println(s"Parsing failed on action: $action: $err"); None
        }
      }
    }

    val cRes = if (cond.isEmpty) AlwaysTrue else c(cond).get
    val aRes = a(actions)

    Condition(cRes, aRes, SPAttributes("kind" -> kind))
  }

}