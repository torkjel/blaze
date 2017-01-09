package org.http4s.blaze.pipeline



/** By requiring a LeafBuilder, you are ensuring that the pipeline is capped
  * with a TailStage as the only way to get a LeafBuilder if by capping with a
  * TailStage or getting a new LeafBuilder from a TailStage
  * @param leaf the capped pipeline
  * @tparam I type the pipeline will read and write
  */
final class LeafBuilder[I, O] private[pipeline](leaf: Tail[I, O]) {
  
  def prepend[I1, O1](stage: MidStage[I1, O1, I, O]): LeafBuilder[I1, O1] = {
    if (stage._nextStage != null) sys.error(s"Stage $stage must be fresh")
    if (stage.isInstanceOf[HeadStage[_,_]]) sys.error("LeafBuilder cannot accept HeadStages!")

    leaf._prevStage = stage
    stage._nextStage = leaf
    new LeafBuilder[I1, O1](stage)
  }

  def prepend[I1, O1](tb: TrunkBuilder[I1, O1, I, O]): LeafBuilder[I1, O1] = tb.cap(this)

  def +:[I1, O1](tb: TrunkBuilder[I1, O1, I, O]): LeafBuilder[I1, O1] = prepend(tb)
  
  def base(root: HeadStage[I, O]): root.type = {
    if (root._nextStage != null) sys.error(s"Stage $root must be fresh")
    leaf._prevStage = root
    root._nextStage = leaf
    root    
  }
}

object LeafBuilder {
  def apply[I,O](leaf: TailStage[I,O]): LeafBuilder[I,O] = new LeafBuilder[I,O](leaf)

  implicit def tailToLeaf[I, O](tail: TailStage[I, O]): LeafBuilder[I, O] = LeafBuilder(tail)
}

/** Facilitates starting a pipeline from a MidStage. Can be appended and prepended
  * to build up the pipeline
  */
final class TrunkBuilder[I1, O1, I2, O2] private[pipeline](
  protected val head: MidStage[I1, O1, _, _],
  protected val tail: MidStage[_, _, I2, O2]
) {

  def append[I3, O3](stage: MidStage[I2, O2, I3, O3]): TrunkBuilder[I1, O1, I3, O3] = {
    if (stage._prevStage != null) sys.error(s"Stage $stage must be fresh")
    if (stage.isInstanceOf[HeadStage[_,_]]) sys.error("Cannot append HeadStages: $stage")

    tail._nextStage = stage
    stage._prevStage = tail

    new TrunkBuilder(head, stage)
  }

  def :+[I3, O3](stage: MidStage[I2, O2, I3, O3]): TrunkBuilder[I1, O1, I3, O3] = append(stage)

  def append[I3, O3](tb: TrunkBuilder[I2, O2, I3, O3]): TrunkBuilder[I1, O1, I3, O3] = {
    append(tb.head)
    new TrunkBuilder(this.head, tb.tail)
  }

  def cap(stage: TailStage[I2, O2]): LeafBuilder[I1, O1] = {
    if (stage._prevStage != null) {
      sys.error(s"Stage $stage must be fresh")
    }

    tail._nextStage = stage
    stage._prevStage = tail
    new LeafBuilder(head)
  }

  def cap(lb: LeafBuilder[I2, O2]): LeafBuilder[I1, O1] = {
    lb.prepend(tail)
    new LeafBuilder(head)
  }

  def prepend[I0, O0](stage: MidStage[I0, O0, I1, O1]): TrunkBuilder[I0, O0, I2, O2] = {
    if (stage._nextStage != null) sys.error(s"Stage $stage must be fresh")
    if (stage.isInstanceOf[HeadStage[_, _]]) sys.error("Cannot prepend HeadStage. Use method base")

    head._prevStage = stage
    stage._nextStage = head
    new TrunkBuilder(stage, tail)
  }

  def prepend[I0, O0](tb: TrunkBuilder[I0, O0, I1, O1]): TrunkBuilder[I0, O0, I2, O2] = {
    tb.append(this)
  }
}

object TrunkBuilder {
  def apply[I1, O1, I2, O2](mid: MidStage[I1, O1, I2, O2]): TrunkBuilder[I1, O1, I2, O2] =
    new TrunkBuilder(mid, mid)
}
