package sp.patrikmodel

import play.api.libs.json.Json
import sp.domain.logic.{ActionParser, PropositionParser}
import sp.domain.logic.AttributeLogic.SPValueLogic
import sp.domain._
import sp.supremicaStuff.base._
import sp.domain.Logic._


/**
  * Creates a wmod module from existing operations, things (variables), and SOPspec (forbidden expressions/mutex operations).
  * This module is synthesized.
  * The synthesized supervisor is returned as an extra preGuard for a subset of the operations.
  * Creates a Condition for each operation based on its attributes
  */


object Synthesize extends SynthesizeModel
trait SynthesizeModel {
  def synthesizeModel(ids: List[IDAble], moduleName : String = "dummy"): (List[Operation], SPAttributes, Map[String, Int] => Option[Boolean]) = {

    // Extract from IDAbles
    val ops = ids.filter(_.isInstanceOf[Operation]).map(_.asInstanceOf[Operation])
    val vars = ids.filter(_.isInstanceOf[Thing]).map(_.asInstanceOf[Thing])
    val sopSpecs = ids.filter(_.isInstanceOf[SOPSpec]).map(_.asInstanceOf[SOPSpec])

      //Create Supremica Module and synthesize guards.
      val ptmw = ParseToModuleWrapper(moduleName, vars, ops, sopSpecs)
      val ptmwModule = {
        ptmw.addVariables()
        ptmw.saveToWMODFile("./testFiles/gitIgnore/")
        ptmw.addOperations() // add operations, with transitions and guards as events to the EFA for the later synthesis
        ptmw.saveToWMODFile("./testFiles/gitIgnore/")
        ptmw.addForbiddenExpressions()
        ptmw.saveToWMODFile("./testFiles/gitIgnore/")
        ptmw.saveToWMODFile("./testFiles/gitIgnore/raw/")
        ptmw.SupervisorAsBDD()
      }

      val optSupervisorGuards = ptmwModule.getSupervisorGuards.map(_.filter(og => !og._2.equals("1")))
      val updatedOps = ops.map(o => ptmw.addSPConditionFromAttributes(ptmw.addSynthesizedGuardsToAttributes(o, optSupervisorGuards), optSupervisorGuards))

      lazy val synthesizedGuards = optSupervisorGuards.getOrElse(Map()).foldLeft(SPAttributes()) { case (acc, (event, guard)) =>
        acc merge SPAttributes("synthesizedGuards" -> SPAttributes(event -> guard))
      }
      lazy val nbrOfStates = SPAttributes("nbrOfStatesInSupervisor" -> ptmwModule.nbrOfStates())
      println(s"Nbr of states in supervisor: ${nbrOfStates.getAs[String]("nbrOfStatesInSupervisor").getOrElse("-")}")
      if (synthesizedGuards.value.nonEmpty) println(synthesizedGuards.pretty)

      ptmw.addSupervisorGuardsToFreshFlower(optSupervisorGuards)
      ptmw.saveToWMODFile("./testFiles/gitIgnore/")

      lazy val opsWithSynthesizedGuard = optSupervisorGuards.getOrElse(Map()).keys
      lazy val spAttributes = synthesizedGuards merge nbrOfStates merge SPAttributes("info" -> s"Model synthesized. ${opsWithSynthesizedGuard.size} operations are extended with a guard: ${opsWithSynthesizedGuard.mkString(", ")}") merge SPAttributes("moduleName" -> moduleName)

    (updatedOps, spAttributes, (x => ptmwModule.containsState(x)))
  }
}


case class ParseToModuleWrapper(moduleName: String, vars: List[Thing], ops: List[Operation], sopSpec: List[SOPSpec]) extends FlowerPopulater with Exporters with Algorithms with TextFilePrefix {

  lazy val variableNameDomainMap = vars.flatMap(v => {
    val optDomain = v.attributes.findAs[Seq[String]]("domain").headOption
    if (optDomain.isDefined) Some(v.name -> optDomain.get) else None
  }).toMap

  lazy val mModule = SimpleModuleFactory(moduleName)

  // These two functions extract transition conditions from operations
  private def directAttrValues(o: Operation, directAttr: Set[String]) = directAttr.flatMap(attr => o.attributes.getAs[Set[String]](attr)).flatten

  private def nestedAttrValues(o: Operation, nestedAttr: Set[TransformationPatternInAttributes => Option[String]], operator: String) = {
    Set("carrierTrans", "resourceTrans").flatMap { key =>
      o.attributes.getAs[SPAttributes](key).map {
        _.value.flatMap { case (variable, toTpia) =>
          implicit val fT: JSFormat[TransformationPatternInAttributes] = Json.format[TransformationPatternInAttributes]
          lazy val tpia = toTpia.to[TransformationPatternInAttributes].get
          nestedAttr.flatMap(_(tpia)).map(value => {
            val conditionList = value.split(" OR ")
            if(conditionList.size > 1)
              conditionList.mkString("(" + s"$variable $operator ", ")|(" + s"$variable $operator ", ")")
            else
              s"$variable $operator $value"}
          )
        }
      }
    }.flatten
  }

  private def addTransition(o: Operation, event: String, directGuardAttr: Set[String], nestedGuardAttr: Set[TransformationPatternInAttributes => Option[String]],
                            directActionAttr: Set[String], nestedActionAttr: Set[TransformationPatternInAttributes => Option[String]]) = {
    // The guards & actions of the operation can be stored directly in the operation's attributes as "pre(Guard/Action)" or "post(Guard/Action)"
    // They can also be part of a so called resource transition or carrier transition, which is the case in the Volvo Scheduler service, in which case the extraction is a bit more complicated as in nestedAttrValues
    val allGuards = directAttrValues(o, directGuardAttr) ++ nestedAttrValues(o, nestedGuardAttr, "==")
    val guardAsString = if (allGuards.isEmpty) "" else allGuards.mkString("(", ")&(", ")")
    val actionsAsStrings = directAttrValues(o, directActionAttr) ++ nestedAttrValues(o, nestedActionAttr, "=")

    // This will add the event to an EFA. Before doing so, the guards and actions are converted to supremica syntax (The variable string values are replaced by corresponding numbers ( idle = "0" ..etc. .))
    addLeaf(event, stringPredicateToSupremicaSyntax(guardAsString),
      actionsAsStrings.map(stringActionToSupremicaSyntax).mkString("; "))
  }

  def addOperations() = {
    ops.foreach { o =>
      //pre
      val startEvent = o.name
      addEventIfNeededElseReturnExistingEvent(startEvent, unControllable = false)
      addTransition(o, startEvent, Set("preGuard"), Set(_.atStart), Set("preAction"), Set(_.atExecute))

      //post
      val compEvent = s"$UNCONTROLLABLE_PREFIX$startEvent"

      addEventIfNeededElseReturnExistingEvent(compEvent, unControllable = true)
      addTransition(o, compEvent, Set("postGuard"), Set(_.atExecute), Set("postAction"), Set(_.atComplete))

      //Add operation events  to module comment
      mModule.setComment(s"$getComment$OPERATION_PREFIX${o.name} $TRANSITION_PREFIX$startEvent,$compEvent")
    }
  }

  def addVariables() = {
    vars.foreach { v => for {
      domain <- variableNameDomainMap.get(v.name)
      allInit = Set("init", "idleValue").flatMap(key => v.attributes.findAs[String](key))
      init <- if (allInit.size == 1) Some(allInit.head)
      else {
        println(s"Problem with variable ${v.name}, attribute keys init and idleValue do not point to the same value")
        None
      }
      intInit <- getFromVariableDomain(v.name, init, "Problem with init") // get index value of the init variable

      // Todo: Here, the marked state is the same as the initial state, and no more than 1 marked state is allowed. This needs investigating..
      idleValueAttr = v.attributes.getAs[String]("idleValue").map(Set(_))
      markingsAttr = v.attributes.getAs[Set[String]]("markings")
      allMarkings = Set(idleValueAttr, markingsAttr).flatten
      markings <- if (allMarkings.size == 1) Some(allMarkings.head)
      else {
        println(s"Problem with variable ${v.name}, attribute keys markings and idleValue do not point to the same value(s)")
        None
      }
      intMarkings = markings.flatMap(m => getFromVariableDomain(v.name, m, "Problem with marking"))

    } yield {
      addVariable(v.name, 0, domain.size - 1, intInit, intMarkings)
      //Add variable values to module comment
      mModule.setComment(s"$getComment${TextFilePrefix.VARIABLE_PREFIX}${v.name} d${TextFilePrefix.COLON}${domain.mkString(",")}")
    }
    }
  }

  def addForbiddenExpressions() = {
    sopSpec.foreach { s =>
      s.attributes.getAs[Set[String]]("forbiddenExpressions").foreach(fes =>
        addForbiddenExpression(forbiddenExpression = stringPredicateToSupremicaSyntax(fes.mkString("(", ")|(", ")")), addSelfLoop = false, addInComment = true))
      s.sop.foreach {
        case a: Arbitrary if a.sop.forall(sopIsOneOpOrAStraightSeqOfOps) => addForbiddenExpressionWorkerForOneArbitrary(a)
        case s: Sequence if s.sop.forall { node => node.isInstanceOf[Arbitrary] && node.asInstanceOf[Arbitrary].sop.forall(sopIsOneOpOrAStraightSeqOfOps) } =>
          s.sop.foreach(a => addForbiddenExpressionWorkerForOneArbitrary(a.asInstanceOf[Arbitrary]))
        case _ => //do nothing
      }
    }
  }

  private def addForbiddenExpressionWorkerForOneArbitrary(a: Arbitrary): Unit = {
    lazy val operationIdMap = ops.map(o => o.id -> o).toMap
    def getOperationSeqsFromSop(sopNode: SOP): Seq[Seq[Operation]] = {
      sopNode.sop.map{
        case n: OperationNode => Seq(operationIdMap.get(n.operation)).flatten
        case s: Sequence => s.sop.flatMap(h => operationIdMap.get(h.asInstanceOf[OperationNode].operation)).toSeq
      }
    }
    def getExpressionOfExecutingSeq(seq: Seq[Operation]): String = {
      //For seq.head only expression for being executing
      //For seq.tail both expression for being enabled to start and executing
      def enabledToStart(o: Operation): String = (directAttrValues(o, Set("preGuard")) ++ nestedAttrValues(o, Set(_.atStart), "==")).mkString("(", ")&(", ")")
      def executing(o: Operation): String = (directAttrValues(o, Set("postGuard")) ++ nestedAttrValues(o, Set(_.atExecute), "==")).mkString("(", ")&(", ")")
      lazy val headExp = Seq(executing(seq.head))
      lazy val tailExps = seq.tail.flatMap(o => Seq(enabledToStart(o), executing(o)))
      return (headExp ++ tailExps).mkString("(", ")|(", ")")
    }
    def addForbiddenExpressionForRemainingSeqExps(remainingSeqExps: Seq[String]): Unit = remainingSeqExps match {
      case exp +: exps if exps.nonEmpty =>
        exps.foreach { otherExp =>
          addForbiddenExpression(forbiddenExpression = stringPredicateToSupremicaSyntax(s"($exp)&($otherExp)"), addSelfLoop = false, addInComment = true)
        }
        addForbiddenExpressionForRemainingSeqExps(exps)
      case _ => //do nothing
    }
    //--This case starts here---
    //TODO Need to ignore operations that are the same from different sequences
    lazy val opSeqs = getOperationSeqsFromSop(a)
    lazy val seqsAsExp = opSeqs.map(getExpressionOfExecutingSeq)
    addForbiddenExpressionForRemainingSeqExps(seqsAsExp)
  }

  private def sopIsOneOpOrAStraightSeqOfOps(sopToCheck: SOP): Boolean = sopToCheck match {
    case n: OperationNode => true
    case s: Sequence =>
      lazy val seq = sopToCheck.asInstanceOf[Sequence]
      seq.sop.forall(_.isInstanceOf[OperationNode])
    case _ => false
  }

  private def getFromVariableDomain(variable: String, value: String, errorMsg: String): Option[Int] = {
    variableNameDomainMap.get(variable) match {
      case Some(domain) => domain.indexOf(value) match {
        case -1 => println(s"$errorMsg\nValue: $value is not in the domain of variable: $variable. The result will not be correct!"); None
        case other => Some(other)
      }
      case _ => println(s"$errorMsg\nVariable: $variable is not defined. The result will not be correct!"); None

    }
  }

  def addSynthesizedGuardsToAttributes(o: Operation, optSynthesizedGuardMap: Option[Map[String, String]]) = {
    if (optSynthesizedGuardMap.isEmpty) o
    else {
      lazy val updatedAttribute = optSynthesizedGuardMap.get.get(o.name) match {
        case Some(guard) => o.attributes merge SPAttributes("preGuard" -> Set(guard))
        case _ => o.attributes
      }
      o.copy(attributes = updatedAttribute)
    }
  }

  def addSPConditionFromAttributes(o: Operation, optSynthesizedGuardMap: Option[Map[String, String]]): Operation = {
    if (optSynthesizedGuardMap.isEmpty) o
    else {
      def parseAttributesToPropositionCondition(op: Operation, idablesToParseFromString: List[IDAble]): Option[Operation] = {

        def getGuard(directGuardAttr: Set[String], nestedGuardAttr: Set[TransformationPatternInAttributes => Option[String]]) = {
          lazy val allGuards = directAttrValues(o, directGuardAttr) ++ nestedAttrValues(o, nestedGuardAttr, "==")
          var guardAsString = if (allGuards.isEmpty) "" else allGuards.mkString("(", ")&(", ")")
          PropositionParser(idablesToParseFromString).parseStr(stringPredicateToSupremicaSyntax(guardAsString)) match {
            case Right(p) => Some(p)
            case Left(fault) => println(s"PropositionParser failed for operation ${op.name} on guard: $guardAsString. Failure message: $fault"); None
          }
        }

        def getAction(directActionAttr: Set[String], nestedActionAttr: Set[TransformationPatternInAttributes => Option[String]]) = {
          val actionsAsStrings = directAttrValues(o, directActionAttr) ++ nestedAttrValues(o, nestedActionAttr, "=")
          actionsAsStrings.flatMap { action =>
            ActionParser(idablesToParseFromString).parseStr(stringActionToSupremicaSyntax(action)) match {
              case Right(a) => Some(a)
              case Left(fault) => println(s"ActionParser failed for operation ${op.name} on action: $action. Failure message: $fault"); None
            }
          }.toList
        }

        for {
          preGuard <- getGuard(Set("preGuard"), Set(_.atStart))
          postGuard <- getGuard(Set("postGuard"), Set(_.atExecute))
        } yield {
          op.copy(conditions = List(Condition(preGuard, getAction(Set("preAction"), Set(_.atExecute)), SPAttributes("kind" -> "precondition")),
            Condition(postGuard, getAction(Set("postAction"), Set(_.atComplete)), SPAttributes("kind" -> "postcondition"))))
        }
      }
      parseAttributesToPropositionCondition(o, vars).getOrElse(o)
    }
  }

  //To get correct syntax of guards and actions in Supremica
  //Variable values are changed to index in domain

  import sp.domain.logic.PropositionParser
  import sp.domain._

  private def stringActionToSupremicaSyntax(s: String) = ActionParser(vars).parseStr(s) match {
    case Right(a) => actionToSupremicaSyntax(a) match {
      case Some(r) => r
      case _ => a.toString
    }
    case other => other.toString
  }

  private def actionToSupremicaSyntax(a: Action) = {
    val varsIdMap = vars.map(v => v.id -> v.name).toMap
    val value = a.value match {
      case ValueHolder(play.api.libs.json.JsString(v)) => v
      case ValueHolder(play.api.libs.json.JsNumber(v)) => v.toString()
      case other =>
        println(s"actionToSupremicaSyntax cannot handle: $other right now. sorry")
        other.toString
    }
    for {
      variable <- varsIdMap.get(a.id)
    } yield {
      s"$variable=${if (isInt(value)) value else getFromVariableDomain(variable, value, "Problem with action").getOrElse("NONE")}"
    }
  }

  private def stringPredicateToSupremicaSyntax(s: String) = PropositionParser().parseStr(s) match {
    case Right(p) => propToSupremicaSyntax(p)
    case other => other.toString
  }

  private def propToSupremicaSyntax(p: Proposition): String = p match {
    case AND(ps) => ps.map(propToSupremicaSyntax).mkString("(", ")&(", ")")
    case OR(ps) => ps.map(propToSupremicaSyntax).mkString("(", ")|(", ")")
    case NOT(q) => s"!${propToSupremicaSyntax(q)}"
    case EQ(l, r) => leftRight(l, "==", r)
    case NEQ(l, r) => leftRight(l, "!=", r)
    case GREQ(l, r) => leftRight(l, ">=", r)
    case GR(l, r) => leftRight(l, ">", r)
    case LEEQ(l, r) => leftRight(l, "<=", r)
    case LE(l, r) => leftRight(l, "<", r)
    case AlwaysTrue => "1"
    case AlwaysFalse => "0"
    case other =>
      println(s"propToSupremicaSyntax cannot handle: $other right now. sorry")
      other.toString
  }

  private def leftRight(l: StateEvaluator, operator: String, r: StateEvaluator) = {
    val left = stateEvalToSupremicaSyntax(l)
    val right = stateEvalToSupremicaSyntax(r)
    s"$left$operator${if (isInt(right)) right else getFromVariableDomain(left, right, "Problem with guard").getOrElse("NONE")}"
  }

  private def stateEvalToSupremicaSyntax(se: StateEvaluator): String = se match {
    case ValueHolder(play.api.libs.json.JsTrue) => "1"
    case ValueHolder(play.api.libs.json.JsFalse) => "0"
    case ValueHolder(play.api.libs.json.JsString(v)) => v
    case ValueHolder(play.api.libs.json.JsNumber(v)) => v.toString()
    case other =>
      println(s"stateEvalToSupremicaSyntax cannot handle: $other right now. sorry")
      other.toString
  }

  private def isInt(s: String): Boolean = {
    try {
      s.toInt
      true
    } catch {
      case e: Exception => false
    }
  }

}
