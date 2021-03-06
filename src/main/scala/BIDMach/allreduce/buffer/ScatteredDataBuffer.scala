package BIDMach.allreduce.buffer

case class ScatteredDataBuffer(dataSize: Int,
                               peerSize: Int,
                               maxLag: Int,
                               reducingThreshold: Float,
                               maxChunkSize: Int) extends AllReduceBuffer(dataSize, peerSize, maxLag, maxChunkSize) {

  val minChunkRequired: Int = (reducingThreshold * peerSize).toInt

  def reachReducingThreshold(row: Int, chunkId: Int): Boolean = {
    countFilled(timeIdx(row))(chunkId) == minChunkRequired
  }


  def count(row: Int, chunkId: Int): Int = {
    countFilled(timeIdx(row))(chunkId)
  }

  def reduce(row : Int, chunkId: Int) : (Array[Float], Int) = {

    val chunkStartPos = chunkId * maxChunkSize
    val chunkEndPos = math.min(dataSize, (chunkId + 1) * maxChunkSize)
    val chunkSize = chunkEndPos - chunkStartPos
    val reducedArr = Array.fill[Float](chunkSize)(0)
    for (i <- 0 until peerSize) {
      val tbuf = temporalBuffer(timeIdx(row))(i);
      var j = 0;
      while (j < chunkSize) {
        reducedArr(j) += tbuf(chunkStartPos + j);
        j += 1;
      }
    }
    (reducedArr, count(row, chunkId))
  }


}

object ScatteredDataBuffer {
  def empty = {
    ScatteredDataBuffer(0, 0, 0, 0f, 1024)
  }
}