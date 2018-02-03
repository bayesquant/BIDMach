package BIDMach.allreduce.buffer

import java.util

case class ScatteredDataBuffer(dataSize: Int,
                               peerSize: Int,
                               reducingThreshold: Float,
                               maxChunkSize: Int) extends AllReduceBuffer(dataSize, peerSize, maxChunkSize) {

  val minChunkRequired: Int = (reducingThreshold * peerSize).toInt

  var reducedFlag: Array[Boolean] = new Array[Boolean](numChunks)

  def count(chunkId: Int): Int = {
    countFilled(chunkId)
  }

  override def store(data: Array[Float], srcId: Int, chunkId: Int) = {
    super.store(data, srcId, chunkId)
  }

  def reduce(chunkId: Int): (Array[Float], Int) = {

    val chunkStartPos = chunkId * maxChunkSize
    val chunkEndPos = math.min(dataSize, (chunkId + 1) * maxChunkSize)
    val chunkSize = chunkEndPos - chunkStartPos
    val reducedArr = new Array[Float](chunkSize)
    for (i <- 0 until peerSize) {
      val tBuf = temporalBuffer(i);
      var j = 0;
      while (j < chunkSize) {
        reducedArr(j) += tBuf(chunkStartPos + j);
        j += 1;
      }
    }
    reducedFlag(chunkId) = true
    (reducedArr, count(chunkId))
  }

  def getUnreducedChunkIds(): List[Int] = {
    val ids = 0 until(numChunks)
    ids.filterNot(reducedFlag(_)).toList
  }

  def prepareNewRound() = {
    var chunkId = 0
    while (chunkId < numChunks) {
      val chunkStartPos = chunkId * maxChunkSize
      val chunkEndPos = math.min(dataSize, (chunkId + 1) * maxChunkSize)
      val tBuf = temporalBuffer
      for (peerId <- 0 until peerSize) {
        util.Arrays.fill(
          tBuf(peerId),
          chunkStartPos,
          chunkEndPos,
          0
        )
      }
      countFilled(chunkId) = 0
      reducedFlag(chunkId) = false
      chunkId += 1
    }
  }

  def reachReducingThreshold(chunkId: Int): Boolean = {
    countFilled(chunkId) == minChunkRequired
  }

}

object ScatteredDataBuffer {
  def empty = {
    ScatteredDataBuffer(0, 0, 0f, 0)
  }
}