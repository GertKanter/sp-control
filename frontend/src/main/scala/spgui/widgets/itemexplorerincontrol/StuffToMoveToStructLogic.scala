package spgui.widgets.itemexplorerincontrol

import sp.domain._


object StuffToMoveToStructLogic {

  // stolen from StructLogic
  def getChildren(node: StructNode, struct: Struct): List[StructNode] = {
    struct.items.filter(_.parent.contains(node.nodeID)).toList
  }

  def getAllChildren(nodeID: ID, struct: Struct): List[StructNode] = {
    struct.items.find(_.nodeID == nodeID).map(getAllChildren(_, struct)).getOrElse(List())
  }

  // stolen from StructLogic
  def getAllChildren(node: StructNode, struct: Struct): List[StructNode] = {
    def rec(currentNode: StructNode, aggr: Set[StructNode]): Set[StructNode] = {
      /*
      if (aggr.contains(currentNode)) aggr
      else {
        val direct = getChildren(currentNode, struct).toSet
        val updA = aggr ++ direct
        direct.flatMap(n => rec(n, updA))
      }
      */
      val directChildren = getChildren(currentNode, struct).toSet
      if (directChildren.isEmpty) aggr
      else directChildren.flatMap(c => rec(c, aggr ++ directChildren))
    }
    rec(node, Set()).toList
  }

  def moveNode(movedNodeID: ID, receivingNodeID: ID, struct: Struct) = {
    if (!containsNode(receivingNodeID, struct)) { // TODO item explorer job, (re)move
      struct
    } else {
      val movedNode = struct.items.find(_.nodeID == movedNodeID)
      val updatedNodeSet = movedNode.map(n => struct.items - n + n.copy(parent = Some(receivingNodeID)))
      val newStruct = updatedNodeSet.map(ns => struct.copy(items = ns))
      val newStructLoopLess = newStruct.flatMap(s => if (hasLoops(s)) None else Some(s))
      newStructLoopLess.getOrElse(struct)
    }
  }

  def containsNode(nodeID: ID, struct: Struct) = {
    struct.items.exists(_.nodeID == nodeID)
  }

  // stolen from StructLogic, returns opposite value there
  def hasLoops(x: Struct) = {
    def req(currentNode: StructNode, aggr: Set[ID]): Boolean = {
      currentNode.parent match {
        case None => true
        case Some(n) if aggr.contains(n) => false
        case Some(n) =>
          val p = x.nodeMap(n)
          req(p, aggr + currentNode.nodeID)
      }
    }
    !x.items.forall(s => req(s, Set()))
  }

  // maybe use the function + in StructLogic
  def addItem(itemID: ID, struct: Struct) = {
    struct.copy(items = struct.items + StructNode(itemID))
  }

  // called + in StructLogic
  def addNode(node: StructNode, struct: Struct) = {
    struct.copy(items = struct.items + node)
  }

  // called ++ in StructLogic
  def addNodes(nodes: List[StructNode], struct: Struct) = {
    struct.copy(items = struct.items ++ nodes)
  }

  // stolen from StructLogic
  def removeNode(nodeID: ID, struct: Struct) = {
    val filtered = struct.items.filter(_.nodeID == nodeID)
    struct.copy(items = filtered)
  }

  def removeNodes(nodeIDs: List[ID], struct: Struct): Struct = {
    nodeIDs.headOption.map(id => removeNodes(nodeIDs.tail, removeNode(id, struct))).getOrElse(struct)
  }

  def removeNodeAndChildren(nodeID: ID, struct: Struct) = {
    struct.items.find(_.nodeID == nodeID).map { node =>
      val childrenIDs = getAllChildren(node, struct).map(_.nodeID)
      removeNodes(childrenIDs, struct)
    }.getOrElse(struct)
  }
}
