package sp.runners

import sp.domain._
import Logic._
import akka.actor._
import akka.util._
import akka.stream._
import akka.stream.scaladsl._
import scala.concurrent.duration._
import scala.concurrent.Future

case class RunnerPipeline(operations: List[Operation],
                          transitionSystem: List[RunnerLogic.OperationTransition],
                          initialState: SPState,
                          name: String,
                          system: ActorSystem
                          ) {




  implicit val askTimeout = Timeout(5 seconds)
  import akka.pattern.ask
  val runnerA = system.actorOf(Props(classOf[RunnerActor], transitionSystem, initialState, operations))

  def runnerFlow(includeTicker: Option[FiniteDuration] = None) = {
    val ticker = includeTicker.map(t => Source.tick(t, t, StateUpd(SPState("tick", Map()), List()))).getOrElse(Source.empty[StateUpd])
    Flow[StateUpd]
      .merge(ticker)
      .ask[RunnerLogic.OneOperationRun](2)(runnerA)
      .mapConcat(x =>
        x.sequence.map(_._2).reverse
      )
  }


  // Below not tested!

  def getRunnerData: Future[SPAttributes] = {
    val res = (runnerA ? GetRunnerData).mapTo[RunnerState]

    // Make a better API with case classes in API. Do not leak RunnerActorAPI though
    res.map(i =>
      SPAttributes(
        "operations" -> i.ops,
        "controlledTransitions" -> i.controlledTransitions.map(_.id),
        "persistentEvents" -> i.persistentEvents.map(e => SPAttributes("event" -> e.event, "operation" -> e.operation)),
        "disabledGroups" -> i.disabledGroups
      )
    )(system.dispatcher)
  }

  def makeTransitionsControlled(transitions: List[ID]) = {
    runnerA ! MakeControlled(transitions)
  }


//
//  def addOperations(ops: List[Operation], initialState: SPState) = {
//    (runnerA ? AddOps(ops, initialState)).mapTo[RunnerState].map{rs =>
//      val newOps = rs.ops ++ ops distinct
//      val newS = rs.state.next(initialState.state)
//      runnerA ! SetRunnerData()
//    }(system.dispatcher)
//
//
//    runnerA ! MakeUnControlled(transitions)
//  }





  //def updateState(next: )


}







trait RunnerActorAPI
case object GetRunnerData extends RunnerActorAPI
case class MakeControlled(ts: List[ID]) extends RunnerActorAPI
case class MakeUnControlled(ts: List[ID]) extends RunnerActorAPI
case class RemoveFromState(ids: List[ID]) extends RunnerActorAPI
case class AddOps(ops: List[Operation], initialState: SPState) extends RunnerActorAPI
case class RemoveOps(ops: List[ID]) extends RunnerActorAPI
case class AddDisabledGroups(disabledGroups: List[SPValue]) extends RunnerActorAPI
case class RemoveDisabledGroups(disabledGroups: List[SPValue]) extends RunnerActorAPI
case class AddPersistentEvents(persistentEvents: List[RunnerLogic.FireEvent]) extends RunnerActorAPI
case class RemovePersistentEvents(persistentEvents: List[RunnerLogic.FireEvent]) extends RunnerActorAPI





case class StateUpd(s: SPState, events: List[RunnerLogic.FireEvent]) extends RunnerActorAPI
object StateUpd {
  def empty = StateUpd(SPState("empty", Map()), List())
}


case class RunnerState(state: Option[SPState],
                       ops: List[Operation],
                       controlledTransitions: List[RunnerLogic.OperationTransition],
                       unControlledTransitions: List[RunnerLogic.OperationTransition],
                       persistentEvents: List[RunnerLogic.FireEvent],
                       disabledGroups: Set[SPValue]
                      )


class RunnerActor(transitionSystem: List[RunnerLogic.OperationTransition],
                  initialState: SPState,
                  operations: List[Operation]
                 ) extends Actor {


  var internal = RunnerState(
    state = None,
    ops = operations,
    controlledTransitions = transitionSystem.filter(_.event.nonEmpty),
    unControlledTransitions = transitionSystem.filter(_.event.isEmpty),
    persistentEvents = List(),
    disabledGroups = Set()
  )
  var state = initialState




  override def receive = {
    case StateUpd(s, es) =>  // Only allowed to be called via the flow!
      //println("Tjo")
      //println(internal)

      val updS = state.next(s.state)
      val res = RunnerLogic.runOperations(
        internal.ops,
        updS,
        es ++ internal.persistentEvents,
        internal.controlledTransitions,
        internal.unControlledTransitions,
        internal.disabledGroups
      )
      state = res.lastState
      sender() ! res


    case GetRunnerData => sender() ! theState()
    case RemoveFromState(xs) =>
      state = state.copy(state = state.state.filter(kv => !xs.contains(kv._1)))
      sender() ! theState()
    case AddOps(xs, s) =>
      internal = internal.copy(ops = (internal.ops ++ xs).distinct)
      state = state.next(s.state)
      sender() ! theState()
    case RemoveOps(xs) =>
      internal = internal.copy(ops = internal.ops.filter(o => !xs.contains(o.id)))
      state = state.copy(state = state.state.filter(kv => !xs.contains(kv._1)))
      sender() ! theState()
    case AddDisabledGroups(xs) =>
      internal = internal.copy(disabledGroups = internal.disabledGroups ++ xs)
      sender() ! theState()
    case RemoveDisabledGroups(xs) =>
      internal = internal.copy(disabledGroups = internal.disabledGroups.filter(x => !xs.contains(x)))
      sender() ! theState()
    case AddPersistentEvents(xs) =>
      internal = internal.copy(persistentEvents = (internal.persistentEvents ++ xs).distinct)
    case RemovePersistentEvents(xs) =>
        internal = internal.copy(persistentEvents = internal.persistentEvents.filter(x => !xs.contains(x)))
        sender() ! theState()
    case MakeControlled(ts) =>
      val xs = transitionSystem.filter(t => ts.contains(t.id))
      val unc = internal.unControlledTransitions.filter(xs.contains)
      val c = (internal.controlledTransitions ++ xs).distinct
      internal = internal.copy(
        controlledTransitions = c,
        unControlledTransitions = unc
      )
      sender() ! theState()
    case MakeUnControlled(ts) =>
      val xs = transitionSystem.filter(t => ts.contains(t.id))
      val c = internal.controlledTransitions.filter(xs.contains)
      val unc = (internal.unControlledTransitions ++ xs).distinct
      internal = internal.copy(
        controlledTransitions = c,
        unControlledTransitions = unc
      )
      sender() ! theState()

  }

  def theState() = internal.copy(state = Some(state))


//  private def validateState = {
//    val opsState = internal.ops.map(o => o.id -> state.get(o.id))
//    if (opsState.exists(_._2.isEmpty)){
//      internal = internal.copy(state = state.next(opsState.map(kv => kv._1 -> kv._2.getOrElse(SPValue(""))).toMap))
//    }
//  }

}
