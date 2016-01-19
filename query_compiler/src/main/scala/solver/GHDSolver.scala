package DunceCap

import DunceCap.attr.Attr

import scala.collection.mutable

object GHDSolver {
  def computeAJAR_GHD(rels: Set[QueryRelation], output: Set[String]):List[GHDNode] = {
    val components = getConnectedComponents(
      mutable.Set(rels.toList.filter(rel => !(rel.attrNames.toSet subsetOf output)):_*), List(), output)
    val componentsPlus = components.map(getAttrSet(_))
    val H_0_edges = rels.filter(rel => rel.attrNames.toSet subsetOf output) union
      componentsPlus.map(compPlus => output intersect compPlus).map(QueryRelationFactory.createImaginaryQueryRelationWithNoSelects(_)).toSet
    val characteristicHypergraphEdges = components.zip(componentsPlus).map({
      compAndCompPlus => getCharacteristicHypergraphEdges(compAndCompPlus._1.toSet, compAndCompPlus._2, output).toList
    })

    println(characteristicHypergraphEdges)
    val G_i_options = getMinFHWDecompositions(H_0_edges.toList)::characteristicHypergraphEdges
      .map(H => getMinFHWDecompositions(H.toList.filter(!_.isImaginary), H.find(_.isImaginary)))
   // println(G_i_options(0))
   // println(G_i_options(1))
    //println(G_i_options(2))
    println("here")

    val G_i_combos = G_i_options.foldLeft(List[List[GHDNode]](List[GHDNode]()))(allSubtreeAssignmentsFoldFunc)//.take(1) // need to make some copies here

    // These are the GHDs described in the AJAR paper;
    // We get rid of all the edges that don't correspond to relations
    // in order to get the GHD that we actually create our query plan from.
    // Note that the GHDs returned from this function might
    // therefore not actually conform to the definition of a GHD,
    // but we guarantee that the result of running yannakakis over it
    // is the same as if we had these imaginary edges that contain all possible tuples
    val theoreticalGHDs = G_i_combos.map(trees => {
      val reversedTrees = trees.reverse
      stitchTogether(duplicateTree(reversedTrees.head), reversedTrees.tail, componentsPlus, output)
    })
    println(theoreticalGHDs.head)
    val result = theoreticalGHDs.flatMap(deleteImaginaryEdges(_))
    println("starting printing all results +++++++++++++")
    result.map(println(_))
    println("end printing all results +++++++++++++")
    result
  }


  /**
   * Delete the imaginary edges (added when constructing the characteristic hypergraphs) from the GHD
   * @param validGHD
   * @return
   */
  def deleteImaginaryEdges(validGHD: GHDNode): Option[GHDNode] = {
    val realEdges = validGHD.rels.filter(!_.isImaginary)
    if (realEdges.isEmpty) { // you'll have to delete this entire node
      if (validGHD.children.isEmpty) {
        return None
      } else {
        val listOfOneorNone = validGHD.children.flatMap(deleteImaginaryEdges(_))
        if (listOfOneorNone.isEmpty) return None
        else {
          val newRoot = listOfOneorNone.head
          newRoot.children = newRoot.children:::listOfOneorNone.tail
          return Some(newRoot)
        }
      }
    } else {
      val newGHD = new GHDNode(realEdges)
      newGHD.bagFractionalWidth = validGHD.bagFractionalWidth
      newGHD.children = validGHD.children.flatMap(deleteImaginaryEdges(_))
      return Some(newGHD)
    }
  }

  def stitchTogether(G_0:GHDNode,
                     G_i:List[GHDNode],
                     componentPlus: List[Set[Attr]],
                     agg:Set[String]): GHDNode = {
    val G_0_nodes = G_0.toList
    G_i.zip(componentPlus).foreach({ case (g_i, compPlus) => {
      val stitchable = G_0_nodes.find(node => {
        (agg intersect compPlus) subsetOf node.attrSet
      })
      assert(stitchable.isDefined) // in theory, we always find a stitchable node
      stitchable.get.children = g_i::stitchable.get.children
      assert((agg intersect compPlus) subsetOf g_i.attrSet)
    }})
    return G_0
  }

  def reroot(oldRoot:GHDNode, newRoot:GHDNode): GHDNode = {
    val path = getPathToNode(oldRoot, newRoot)
    path.take(path.size-1).foldRight((Option.empty[GHDNode], path.last))((prev:GHDNode, removeAndLastRoot:(Option[GHDNode], GHDNode)) => {
      val (remove, lastRoot) = removeAndLastRoot
      if (remove.isDefined) {
        prev.children = prev.children.filter(c => !(c eq remove.get))
      }
      lastRoot.children = prev::lastRoot.children
      prev.children = prev.children.filter(child => !(child eq lastRoot))
      (Some(prev), lastRoot)
    })
    return path.last
  }

  def getPathToNode(root:GHDNode, n: GHDNode):List[GHDNode] = {
    if (root eq n) {
      return List(n)
    } else if (root.children.isEmpty) {
      return List()
    } else  {
        val pathsFromChildren = root.children.map(getPathToNode(_, n))
        root::pathsFromChildren.find(path => !path.isEmpty).get // todo error case
    }
  }

  def getCharacteristicHypergraphEdges(comp: Set[QueryRelation], compPlus: Set[String], agg: Set[String]): mutable.Set[QueryRelation] = {
    val characteristicHypergraphEdges:mutable.Set[QueryRelation] = mutable.Set[QueryRelation](comp.toList:_*)
    characteristicHypergraphEdges += QueryRelationFactory.createImaginaryQueryRelationWithNoSelects(compPlus intersect agg)
    return characteristicHypergraphEdges
  }

  def getAttrSet(rels: List[QueryRelation]): Set[String] = {
    return rels.foldLeft(Set[String]())(
      (accum: Set[String], rel : QueryRelation) => accum | rel.attrNames.toSet[String])
  }

  private def getConnectedComponents(rels: mutable.Set[QueryRelation], comps: List[List[QueryRelation]], ignoreAttrs: Set[String]): List[List[QueryRelation]] = {
    if (rels.isEmpty) return comps
    val component = getOneConnectedComponent(rels, ignoreAttrs)
    return getConnectedComponents(rels, component::comps, ignoreAttrs)
  }

  private def getOneConnectedComponent(rels: mutable.Set[QueryRelation], ignoreAttrs: Set[String]): List[QueryRelation] = {
    val curr = rels.head
    rels -= curr
    return DFS(mutable.LinkedHashSet[QueryRelation](curr), curr, rels, ignoreAttrs)
  }

  private def DFS(seen: mutable.Set[QueryRelation], curr: QueryRelation, rels: mutable.Set[QueryRelation], ignoreAttrs: Set[String]): List[QueryRelation] = {
    for (rel <- rels) {
      // if these two hyperedges are connected
      if (!((curr.attrNames.toSet[String] & rel.attrNames.toSet[String]) &~ ignoreAttrs).isEmpty) {
        seen += curr
        rels -= curr
        DFS(seen, rel, rels, ignoreAttrs)
      }
    }
    return seen.toList
  }

  // Visible for testing
  def getPartitions(leftoverBags: List[QueryRelation], // this cannot contain chosen
                    chosen: List[QueryRelation],
                    parentAttrs: Set[String],
                    tryBagAttrSet: Set[String]): Option[List[List[QueryRelation]]] = {
    // first we need to check that we will still be able to satisfy
    // the concordance condition in the rest of the subtree
    for (bag <- leftoverBags.toList) {
      if (!(bag.attrNames.toSet[String] & parentAttrs).subsetOf(tryBagAttrSet)) {
        return None
      }
    }

    // if the concordance condition is satisfied, figure out what components you just
    // partitioned your graph into, and do ghd on each of those disconnected components
    val relations = mutable.LinkedHashSet[QueryRelation]() ++ leftoverBags
    return Some(getConnectedComponents(relations, List[List[QueryRelation]](), getAttrSet(chosen).toSet[String]))
  }

  /**
   * @param partitions
   * @param parentAttrs
   * @return Each list in the returned list could be the children of the parent that we got parentAttrs from
   */
  private def getListsOfPossibleSubtrees(partitions: List[List[QueryRelation]], parentAttrs: Set[String]): List[List[GHDNode]] = {
    assert(!partitions.isEmpty)
    val subtreesPerPartition: List[List[GHDNode]] = partitions.map((l: List[QueryRelation]) => getDecompositions(l, None, parentAttrs))
    return subtreesPerPartition.foldLeft(List[List[GHDNode]](List[GHDNode]()))(allSubtreeAssignmentsFoldFunc)
  }

  private def allSubtreeAssignmentsFoldFunc(accum: List[List[GHDNode]], subtreesForOnePartition: List[GHDNode]): List[List[GHDNode]] = {
    accum.map((children : List[GHDNode]) => {
      subtreesForOnePartition.map((subtree : GHDNode) => {
        subtree::children
      })
    }).flatten
  }

  private def allSubtreeAssignmentsDeepCopyFoldFunc(accum: List[List[GHDNode]], subtreesForOnePartition: List[GHDNode]): List[List[GHDNode]] = {
    accum.map((children : List[GHDNode]) => {
      subtreesForOnePartition.map((subtree : GHDNode) => {
        duplicateTree(subtree)::children
      })
    }).flatten
  }

  private def duplicateTree(ghd: GHDNode): GHDNode = {
    val newGHD = new GHDNode(ghd.rels)
    newGHD.bagFractionalWidth = ghd.bagFractionalWidth
    newGHD.children = ghd.children.map(duplicateTree(_))
    return newGHD
  }

  private def bagCannotBeExpanded(bag: GHDNode, leftOverRels: Set[QueryRelation]): Boolean = {
    // true if each remaining rels are not entirely covered by bag
    val b = leftOverRels.forall(rel => !rel.attrNames.forall(attrName => bag.attrSet.contains(attrName)))
    return b
  }

  private def getDecompositions(rels: List[QueryRelation],
                                imaginaryRel: Option[QueryRelation],
                                parentAttrs: Set[String]): List[GHDNode] =  {

    val treesFound = mutable.ListBuffer[GHDNode]()
    for (tryNumRelationsTogether <- (1 to rels.size).toList) {
      for (combo <- rels.combinations(tryNumRelationsTogether).toList) {
        val bag =
          if (imaginaryRel.isDefined) {
            imaginaryRel.get::combo
          } else {
            combo
          }
        // If your edges cover attributes that a larger set of edges could cover, then
        // don't bother trying this bag
        val leftoverBags = rels.toSet[QueryRelation] &~ bag.toSet[QueryRelation]
        val newNode = new GHDNode(bag)
        if (bagCannotBeExpanded(newNode, leftoverBags)) {
          if (leftoverBags.toList.isEmpty) {
            treesFound.append(newNode)
          } else {
            val bagAttrSet = getAttrSet(bag)
            val partitions = getPartitions(leftoverBags.toList, bag, parentAttrs, bagAttrSet)
            if (partitions.isDefined) {
              // lists of possible children for |bag|
              val possibleSubtrees: List[List[GHDNode]] = getListsOfPossibleSubtrees(partitions.get, bagAttrSet)
              for (subtrees <- possibleSubtrees) {
                newNode.children = subtrees
                treesFound.append(newNode)
              }
            }
          }
        }
      }
    }
    return treesFound.toList
  }

  def getDecompositions(rels: List[QueryRelation], imaginaryRel:Option[QueryRelation]): List[GHDNode] = {
    return getDecompositions(rels, imaginaryRel, Set[String]())
  }

  def getMinFHWDecompositions(rels: List[QueryRelation], imaginaryRel:Option[QueryRelation] = None): List[GHDNode] = {
    val decomps = getDecompositions(rels, imaginaryRel)
    val fhwsAndDecomps = decomps.map((root : GHDNode) => (root.fractionalScoreTree(), root))
    val minScore = fhwsAndDecomps.unzip._1.min

    case class Precision(val p:Double)
    class withAlmostEquals(d:Double) {
      def ~=(d2:Double)(implicit p:Precision) = (d-d2).abs <= p.p
    }
    implicit def add_~=(d:Double) = new withAlmostEquals(d)
    implicit val precision = Precision(0.001)

    val minFhws = fhwsAndDecomps.filter((scoreAndNode : (Double, GHDNode)) => scoreAndNode._1 ~= minScore)
    return minFhws.unzip._2
  }
}