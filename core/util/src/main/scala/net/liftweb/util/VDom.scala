package net.liftweb.util

import net.liftweb.json._

import scala.annotation.tailrec
import scala.xml._

object VDom {
  import VDomHelpers._
  case class VNode(tag:String, attributes:Map[String, String] = Map(), children:List[VNode] = List(), text:Option[String] = None)

  // TODO: Attribute updates
  trait VNodePatch
  case class VNodeInsert(index:Int, node:VNode) extends VNodePatch
  case class VNodeDelete(index:Int) extends VNodePatch
  case class VNodeReorder(permutation:List[Int]) extends VNodePatch
  case class VNodeAttrSet(k:String, v:String) extends VNodePatch
  case class VNodeAttrRm(k:String) extends VNodePatch

  case class VNodePatchTree(index:Int, patches:List[VNodePatch], children:List[VNodePatchTree])

  object typeHints extends TypeHints {
    val classToHint:Map[Class[_], String] = Map(
      classOf[VNodeInsert] -> "insert",
      classOf[VNodeDelete] -> "delete",
      classOf[VNodeReorder] -> "reorder",
      classOf[VNodeAttrSet] -> "attrSet",
      classOf[VNodeAttrRm] -> "attrRm"
    )
    val hint2Class:Map[String, Class[_]] = classToHint.map { case (c, h) => h -> c }.toMap
    override val hints: List[Class[_]] = classToHint.keysIterator.toList
    override def hintFor(clazz: Class[_]):String = classToHint(clazz)
    override def classFor(hint: String) = hint2Class.get(hint)
  }
  val formats = new Formats {
    override val dateFormat: DateFormat = DefaultFormats.lossless.dateFormat
    override val typeHints = VDom.typeHints
    override val typeHintFieldName = "type"
  }

  private [util] object DiffMatrix {
    val empty = DiffMatrix(Map(), Nil, Nil)
  }
  private [util] case class DiffMatrix(matches:Map[Int, (Int, Float)], notInA: List[Int], notInB: List[Int])
  private [util] def diffMatrix(aChildren:List[Node], bChildren:List[Node]):DiffMatrix = {
    val compares = for {
      (a, i) <- aChildren.zipWithIndex
      (b, j) <- bChildren.zipWithIndex
    } yield (compare(a, i, b, j), i, j)
    val sortedCompares = compares.sortBy(_._1)

    val aWithMatchAndScore = sortedCompares.foldLeft(Map.empty[Int, (Int, Float)]) {
      case (acc, (score, a, b)) =>
        acc.find(_._2._1 == b) match {
          case Some((origA, (origB, origScore))) if origScore < score => (acc - origA) + (a -> (b, score))
          case None => acc + (a -> (b, score))
          case _ => acc
        }
    }
    val bWithMatchAndScore = sortedCompares.foldLeft(Map.empty[Int, (Int, Float)]) {
      case (acc, (score, a, b)) =>
        acc.find(_._2._1 == a) match {
          case Some((origB, (origA, origScore))) if origScore < score => (acc - origB) + (b -> (a, score))
          case None => acc + (b -> (a, score))
          case _ => acc
        }
    }
    val matches = aWithMatchAndScore.collect {
      case (a, (b, score)) if score > 0.0f => a -> (b, score)
    }
    val notInA = bWithMatchAndScore.collect {
      case (b, (a, score)) if score <= 0.0f => b
    }.toList ++ bChildren.zipWithIndex.collect {
      case (_, i) if !bWithMatchAndScore.keySet.contains(i) => i
    }
    val notInB = aWithMatchAndScore.collect {
      case (a, (b, score)) if score <= 0.0f => a
    }.toList ++ aChildren.zipWithIndex.collect {
      case (_, i) if !aWithMatchAndScore.keySet.contains(i) => i
    }

    DiffMatrix(matches, notInA, notInB)
  }

  def diff(index:Int, a:Node, b:Node):VNodePatchTree = {
    if(getAttr(a, "data-lift-ignore-on-update").isDefined || getAttr(b, "data-lift-ignore-on-update").isDefined) VNodePatchTree(index, Nil, Nil)
    else {
      // TODO: Pull pieces of this long function out into small private functions

      val aChildren = a.nonEmptyChildren.filter(isntWhitespace).toList
      val bChildren = b.nonEmptyChildren.filter(isntWhitespace).toList
      val matrix = diffMatrix(aChildren, bChildren)

      val matches = matrix.matches.map {
        case(a, (b, score)) => a -> b
      }

      val reorders: List[VNodeReorder] = {
        val matchesAdjustedForSubtractions:Map[Int, Int] = matrix.notInB.foldLeft(matches) {
          case (acc, i) => acc.map {
            case (j, k) => if (i > j) (j, k) else (j - 1, k)
          }
        }

        // TODO: Can this be done in the foldLeft above?
        val matchesAdjusted:Map[Int, Int] = matrix.notInA.foldLeft(matchesAdjustedForSubtractions) {
          case (acc, i) => acc.map {
            case (j, k) => if (i > j) (j, k) else (j - (j-k), k)
          }
        }.filterNot(z => z._1 == z._2)

        // TODO: Generalize :P
        val (nonBinaryCyclesDirty, binaryCyclesDirty): (List[List[Int]], List[(Int, Int)]) = matchesAdjusted
          .foldRight(List.empty[List[Int]], List.empty[(Int, Int)]) {
            case ((ai: Int, bi: Int), (nonBinaryCycles: List[List[Int]], binaryCycles:List[(Int, Int)])) =>
              val cycleList = if(nonBinaryCycles.map(_.toSet) contains Set(ai, bi)) {
                (nonBinaryCycles.dropWhile(_ == List(bi, ai)), binaryCycles :+ (bi, ai))
              }
              else (List(List(ai, bi)) ++ nonBinaryCycles, binaryCycles)
              cycleList
          }

        // TODO: This suggests to me that we only support at most one cycle of length > 2
        val nonBinaryCycles : List[Int] = nonBinaryCyclesDirty.flatten.distinct
        val binaryCycles: List[List[Int]] = binaryCyclesDirty.map {
          case (first, second) =>
            val diff = (first - second).abs
            if (diff > 1) {
              val thing = for (x <- 2 to diff) yield List(second + x, second + (x - 1))
              List(first, second) +: thing
            }
            else List(List(first, second))
        }.flatten

        val cycles = if(nonBinaryCycles.isEmpty) binaryCycles else nonBinaryCycles +: binaryCycles

        cycles.map(VNodeReorder.apply)
      }

      // TODO: Fix the diff matrix to handle whatever is happening in property test 8
      val actualNotInA: List[Int] = matrix.notInA.filterNot {
        i => matches.values.toList.contains(i)
      }

      // TODO: Eliminate var.
      var num = 0
      val additions: List[VNodeInsert] = actualNotInA.map { i => VNodeInsert(i, VNode.fromXml(bChildren(i))) }.map {
        // Compensate for #text nodes' lack of presence in the children array in the browser
        case VNodeInsert(i, n) if n.tag == "#text" => num = num + 1; VNodeInsert(i - num + 1, n)
        case VNodeInsert(i, n) => VNodeInsert(i - num, n)
      }
      val removals: List[VNodeDelete] = matrix.notInB.map { i => VNodeDelete(i) }.reverse

      def getAttrsForDiff(n:Node): Map[String, String] = {
        val attrs = getAttrs(n) - "id" // id can change on forms when Lift puts an ID on it

        if (n.label == "input" && isLiftId(attrs.get("name"))) attrs - "name"
        else attrs
      }

      val aAttrs = getAttrsForDiff(a)
      val bAttrs = getAttrsForDiff(b)
      val setAttrs = bAttrs.collect { case (a, v) if aAttrs.get(a) != Some(v) => VNodeAttrSet(a, v) }
      val rmAttrs  = aAttrs.collect { case (a, v) if bAttrs.get(a) == None => VNodeAttrRm(a) }

      val patches = removals ++ additions ++ reorders ++ setAttrs ++ rmAttrs

      val nonTexts = additions.filterNot(_.node.tag == "#text")
      val numInserts = additions.length - removals.length
      val numNonTextInserts = nonTexts.length - removals.length
      val insertIndeces = additions.map(_.index)

      val children = matrix.matches.toList.sortBy(_._1).collect {
        case (ai, (bi, score)) if score < 1.0f || aChildren(ai) != bChildren(bi) => // The != is necessary for the case where equal ids made the match == 1.0f
          // TODO: Seems we are handling the fact that #text nodes are not in the children collection here too
          val nextIndex =
            if (numInserts > 0 && numInserts == numNonTextInserts) {
              if (bi - numInserts >= index) bi
              else if (bi - numInserts >= 0 ) bi - numInserts
              else bi
            }
            else if (numInserts > 0 && numInserts > numNonTextInserts) {
              val numInsertsBeforeBi = insertIndeces.filter(_ < bi).length
              if (bi - numInserts >= 0 && numInsertsBeforeBi > 0) {
                bi - numInserts
              }
              else bi
            }
            else bi

          diff(nextIndex, aChildren(ai), bChildren(bi))
      }.filter(pt => !pt.children.isEmpty || !pt.patches.isEmpty) // No need to send empty trees

      VNodePatchTree(index, patches, children)
    }

  }

  private [this] def getAttrs(n:Node):Map[String, String] = n.attributes.collect {
    case UnprefixedAttribute(a, Text(v), _) => a -> v
    case UnprefixedAttribute(a, List(),  _) => a -> ""
  }.toMap
  private [this] def getAttr(n:Node, attr:String):Option[String] = getAttrs(n).get(attr)
  private [this] def getId(n:Node) = getAttr(n, "id")
  private [this] def getName(n:Node) = getAttr(n, "name")
  private [this] def getType(n:Node) = getAttr(n, "type")

  // TODO: Do this for all Lift IDs, not just forms?
  def areFormsWithLiftIds(a:Node, b:Node):Boolean =
    if(a.label != "form" || b.label != "form") false
    else isLiftId(getId(a)) && isLiftId(getId(b))

  def hasSameId(a:Node, b:Node):Boolean = {
    val aId = getId(a)
    val bId = getId(b)

    (aId.isDefined || bId.isDefined) && aId == bId
  }

  def hasDifferentIds(a:Node, b:Node):Boolean = {
    val aId = getId(a)
    val bId = getId(b)

    (aId.isDefined || bId.isDefined) && aId != bId
  }

  def isLiftId(maybeId:Option[String]):Boolean = maybeId.map(id => id.startsWith("F") && id.length == 19).getOrElse(false)

  def areSameInputs(a:Node, b:Node):Boolean =
    if(a.label != "input" || b.label != "input") false
    else {
      val aName = getName(a)
      val bName = getName(b)
      val aType = getType(a)
      val bType = getType(b)

      (aName == bName || (isLiftId(aName) && isLiftId(bName))) && aType == bType
    }

  def areDifferentInputs(a:Node, b:Node):Boolean =
    if(a.label != "input" || b.label != "input") false
    else {
      val aName = getName(a)
      val bName = getName(b)
      val aType = getType(a)
      val bType = getType(b)

      aName != bName || bType != aType
    }

  def compare(a:Node, ai:Int, b:Node, bi:Int):Float = {
    if (a eq b) if(ai == bi) 1f else 0.5f
    else if (a.label != b.label) 0f
    else if (a.label == pcdata) if (a.text == b.text) 1f else 0f
    else if (areFormsWithLiftIds(a, b)) 1f
    else if (hasSameId(a, b)) 1f
    else if (hasDifferentIds(a, b)) 0f
    else if (areSameInputs(a, b)) 1f
    else if (areDifferentInputs(a, b)) 0f
    else {
      // Compare children
      val aChildren = a.nonEmptyChildren.filter(isntWhitespace).toList
      val bChildren = b.nonEmptyChildren.filter(isntWhitespace).toList

      // A single text node as a child is a special case. We want similar text to weigh more heavily than index
      val (childrenSum, childrenLength) = (aChildren, bChildren) match {
        case (a :: Nil, b :: Nil) if a.label == pcdata && b.label == pcdata =>
          val sum = if(a.text == b.text) 2f else 0f

          (sum, 2)

        case _ =>
          val matrix = diffMatrix(aChildren, bChildren)
          val sum = matrix.matches.foldLeft(0.0f) { case (acc, (_, (_, score))) => acc + score }
          val length = Math.max(aChildren.length, bChildren.length)

          (sum, length)
      }

      // Compare attributes
      val aAttrs = getAttrs(a)
      val bAttrs = getAttrs(b)
      val numAttrs = (aAttrs.keySet ++ bAttrs.keySet).size
      val sameAttrs = aAttrs.collect { case (a, v) if bAttrs.get(a) == Some(v) => a }.size

      // Compare indices
      val indexCompare = if(ai == bi) 1.0f else 0.0f

      val totalPossible = childrenLength + numAttrs + 2 // 1 for index, 1 for tag matching
      val total = childrenSum + sameAttrs.toFloat + indexCompare + 1 // 1 for tag matching

      total / totalPossible
    }
  }

  object VNode {
    def text(t:String):VNode = VNode("#text", Map(), List(), Some(t))
    def fromXml(n:Node):VNode = {
      if(n.label == pcdata) text(n.text)
      else {
        val attrs:Map[String, String] = n.attributes
          .collect { case UnprefixedAttribute(k, Text(v), _) => k -> v }
          .toMap
        val children:List[VNode] = n.nonEmptyChildren
          .filter(isntWhitespace)
          .map(fromXml)
          .toList

        VNode(n.label, attrs, children)
      }
    }
  }

  object VDomHelpers extends VDomHelpers
  trait VDomHelpers {
    val pcdata = "#PCDATA"
    def isText(n:Node) = n.label == pcdata
    def isWhitespace(n:Node)   = isText(n) && n.text.trim.isEmpty
    def isntWhitespace(n:Node) = !isWhitespace(n)

    def node(index:Int, child:VNodePatchTree*):VNodePatchTree = VNodePatchTree(index, List(), child.toList)
    def text(t:String) = VNode(pcdata, Map(), List(), Some(t))

    implicit class EnhancedVNodeTransformTree(t:VNodePatchTree) {
      def withPatches(patches:VNodePatch*) = t.copy(patches = patches.toList)
    }

    def nodeCount(n:Node):Int = {
      @tailrec
      def rec(ns:List[Node], acc:Int):Int = ns match {
        case n :: rest => rec(rest ++ n.nonEmptyChildren.filter(isntWhitespace), acc + 1)
        case Nil => acc
      }

      rec(List(n), 0)
    }

    def recFilter(n:Node, pred:Node => Boolean):Node = n match {
      case Elem(prefix, label, attributes, scope, children @ _*) =>
        Elem(prefix, label, attributes, scope, true, children.filter(pred).map(recFilter(_, pred)):_*)
      case _ => n
    }

    def withoutWhitespace(n:Node) = recFilter(n, isntWhitespace)

    private [this] def traverseUpdate(root:Node, atIndex:Int, siblingsAfterFound:(Node, List[Node]) => List[Node]):Node = {
      def rec(parent: Elem, siblingsBefore: List[Node], child: Node, siblingsAfter: List[Node], index: Int): (Node, Int) =
        (parent, child, child.nonEmptyChildren.filter(isntWhitespace).toList, siblingsAfter) match {
          // We found our node by index, so insert
          case (Elem(prefix, label, attributes, scope, _@_*), _, _, _) if index == atIndex =>
            (Elem(prefix, label, attributes, scope, true, siblingsBefore ++ siblingsAfterFound(child, siblingsAfter): _*), -1)

          // Child itself has children
          case (Elem(prefix, label, attributes, scope, _@_*), e: Elem, first :: rest, _) =>
            val (updatedChild, updatedIndex) = rec(e, Nil, first, rest, index + 1)
            if (updatedIndex < 0) (Elem(prefix, label, attributes, scope, true, siblingsBefore ++ (updatedChild :: siblingsAfter): _*), -1)
            else siblingsAfter match {
              case next :: rest if updatedIndex == atIndex => (Elem(prefix, label, attributes, scope, true, siblingsBefore ++ (child :: siblingsAfterFound(next, rest)): _*), -1)
              case next :: rest => rec(parent, siblingsBefore :+ child, next, rest, updatedIndex)
              case Nil => (parent, updatedIndex)
            }

          // We have more siblings to sift through
          case (_, _, _, next :: rest) =>
            rec(parent, siblingsBefore :+ child, next, rest, index + 1)

          // We have no children or siblings to check
          case _ => (parent, index + 1)
        }

      (root, root.nonEmptyChildren.filter(isntWhitespace).toList) match {
        case (e: Elem, first :: rest) => rec(e, Nil, first, rest, 1)._1
        case _ => root
      }
    }

    def insertNode(root:Node, newChild:Node, atIndex:Int, after:Boolean):Node = {
      def siblingsAfterFound(child:Node, siblingsAfter:List[Node]):List[Node] = {
        // Inserting text into a <ul> blows up property tests, and I can't reproduce it with unit test.
        // This is a workaround to avoid what appears to be a non-issue. Note that an empty <li></li> also misbehaves.
        val toInsert = if(child.label == "li" && newChild.label == pcdata) <li>{newChild}</li> else newChild
        child :: toInsert :: siblingsAfter
      }
      traverseUpdate(root, atIndex, siblingsAfterFound)
    }

    def removeNode(root:Node, atIndex:Int):Node = {
      def siblingsAfterFound(child:Node, siblingsAfter:List[Node]):List[Node] = siblingsAfter
      traverseUpdate(root, atIndex, siblingsAfterFound)
    }

    def swapChildren(root:Node, atIndex:Int, firstChild:Int, secondChild:Int):Node = {
      def siblingsAfterFound(child:Node, siblingsAfter:List[Node]):List[Node] = {
        val updated = child match {
          case Elem(prefix, label, attributes, scope, _@_*) =>
            val children = child.nonEmptyChildren.filter(isntWhitespace).toList

            val l1 = children.take(firstChild)
            val c1 = children(firstChild)
            val l2 = children.drop(firstChild + 1).take(secondChild - firstChild - 1)
            val c2 = children.drop(firstChild + 1).apply(secondChild - firstChild - 1)
            val l3 = children.drop(secondChild + 1)

            val updatedChildren = (l1 :+ c2) ++ l2 ++ (c1 +: l3)

            Elem(prefix, label, attributes, scope, true, updatedChildren:_*)
          case _ => child
        }

        updated :: siblingsAfter
      }
      traverseUpdate(root, atIndex, siblingsAfterFound)
    }

    def nodeAt(root:Node, atIndex:Int):Option[Node] = {
      def rec(child:Node, currentIndex:Int):(Int, Option[Node]) = {
        val children = child.nonEmptyChildren.filter(isntWhitespace)

        children.foldLeft((currentIndex + 1, Option.empty[Node])) {
          case ((_, opt @ Some(_)), next) => (-1, opt)
          case ((`atIndex`, None), next)  => (-1, Some(next))
          case ((i, None), next)          => rec(next, i)
        }
     }

      if(atIndex == 0) Some(root)
      else rec(root, 0)._2
    }
  }

}