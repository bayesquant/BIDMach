package BIDMach.networks.layers

import BIDMat.{Mat,SBMat,CMat,CSMat,DMat,FMat,IMat,LMat,HMat,GFilter,GMat,GDMat,GIMat,GLMat,GSMat,GSDMat,JSON,ND,SMat,SDMat,TMat}
import BIDMat.MatFunctions._
import BIDMat.SciFunctions._
import BIDMach.datasources._
import BIDMach.datasinks._
import BIDMach.updaters._
import BIDMach.mixins._
import BIDMach.models._
import BIDMach._
import BIDMach.networks.layers._
import jcuda.jcudnn._
import jcuda.jcudnn.JCudnn._
import scala.collection.mutable.Set;
import scala.collection.mutable.HashMap;
import akka.actor.{Actor,Props,ActorSystem,ActorRef,ActorSelection};

/**
 * Allreduce Layer compute Actor. Attach one of these to an Allreduce Layer. 
 * 
 *
 * 
 */

class AllReduceActor(val layer:ElasticLayer) extends Actor {
  import AllReduceActor._
  layer.myActor = self;
  var nbrSelection:Array[ActorSelection] = null;
  var nbrAddresses:Array[String] = null;
  var blockSums = HashMap.empty[(Long, Int), (Int, Array[Float])];
  var receivers = HashMap.empty[Long, Array[Float] ];
  var blocksSent = HashMap.empty[Long, Int];
  var blocksRecvd = HashMap.empty[Long, Int];
  var src:Array[Float] = null;
  var dest:Array[Float] = null;
  var nnbrs = -1;
  var myPosInGroup = -1;
  var recvArray:Array[Float] = null;
  var msgSize = 1000000;
  var chunk = 512;
  
  def addArray(in:Array[Float], out:Array[Float]) = {
    if (in.length != out.length) throw new RuntimeException("addArray lengths not equal %d %d" format (in.length, out.length));
    var i = 0;
    while (i < in.length) {
      out(i) += in(i);
      i += 1;
    }
  }
  
  def receive = {

    case s:SetAddresses => {
      nbrAddresses = s.alist;
      nnbrs = nbrAddresses.length;
      nbrSelection = nbrAddresses.map((x)=> context.actorSelection(x));
      val me = context.actorSelection(self.path);
      myPosInGroup = nbrSelection.indexOf(me);
      context.parent ! ImDone;  
    }
    
    case a:AllReduce => {
      val len = src.length;
      if (receivers.get(a.guid).isEmpty) {
        receivers += (a.guid -> new Array[Float](len));
      }
      var iblocksSent = 0;
      blocksRecvd(a.guid) = 0;
      for (i0 <- 0 until nnbrs) {
        val i = (i0 + myPosInGroup) % nnbrs;
        val istart = math.round(1f * i * len / nnbrs);
        val iend = math.round(1f * (i + 1) * len / nnbrs);
        val ngroups = math.ceil((iend - istart) / msgSize).toInt;
        for (j <- 0 until ngroups) {
          val jstart = math.round(1f * j * (iend - istart) / ngroups);
          val jend = math.round(1f * (j + 1) * (iend - istart) / ngroups);
          val msg = new Array[Float](iend - istart);
          System.arraycopy(src, istart + jstart, msg, 0, jend - jstart);
          nbrSelection(i) ! ReduceBlock(a.guid, j, istart+jstart, istart+jend, msg);
          iblocksSent += 1;
        }
      }
      blocksSent(a.guid) = iblocksSent;
    }
    
    
    case r:ReduceBlock => {
      val accumOpt = blockSums.get((r.guid, r.part))
      if (accumOpt.isEmpty) {
        blockSums += ((r.guid, r.part) -> (1, r.data));
      } else {
        val pair = accumOpt.get;
        addArray(r.data, pair._2);
        val nnew = pair._1+1;
        blockSums((r.guid, r.part)) = (nnew, pair._2);
        if (nnew == nnbrs) nbrSelection.foreach(_ ! Scatter(r.guid, r.part, r.istart, r.iend, r.data))
        blockSums.remove((r.guid, r.part));
      }
    }
    
    case s:Scatter => {
      val recvr = receivers.get(s.guid).get;
      System.arraycopy(s.data, 0, recvr, s.istart, s.iend - s.istart);
      blocksRecvd(s.guid) = blocksRecvd(s.guid) + 1;
      if (!blocksSent.get(s.guid).isEmpty && blocksSent(s.guid) == blocksRecvd(s.guid)) {
        System.arraycopy(recvr, 0, dest, 0, recvr.length);
        blocksSent.remove(s.guid);
        blocksRecvd.remove(s.guid);
        receivers.remove(s.guid);
        context.parent ! Completed(s.guid);
      }
    }

    case _ => {}
  }
  
  def partition() = {
    
  }
}

object AllReduceActor {
  case class SetAddresses(val alist:Array[String]);
  case class AllReduce(val guid:Long);
  case class ReduceBlock(val guid:Long, val part:Int,  val istart:Int, val iend:Int, val data:Array[Float]);
  case class Scatter(val guid:Long, val part:Int, val istart:Int, val iend:Int, val data:Array[Float]);
  case class Completed(val guid:Long);
  case object ImDone;

  def props(layer: ElasticLayer): Props = Props(new AllReduceActor(layer));
}
