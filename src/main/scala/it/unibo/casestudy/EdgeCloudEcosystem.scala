package it.unibo.casestudy

import it.unibo.alchemist.model.implementations.positions.LatLongPosition
import it.unibo.alchemist.model.scafi.ScafiIncarnationForAlchemist._
import Builtins._
import it.unibo.alchemist.implementation.nodes.SimpleNodeManager
import it.unibo.alchemist.model.implementations.molecules.SimpleMolecule
import it.unibo.scafi.space.Point3D

import scala.util.{Random, Try}

case class LocalResources(numCPUs: Int = 0, mHz: Int = 0, numCPUsInUse: Int = 0)
object LocalResources {
  def random(initRandom: Double) = LocalResources(
    numCPUs = if(initRandom>0.8) 8 else if(initRandom>0.6) 4 else if(initRandom > 0.4) 2 else 1,
    mHz = (1 + Math.round(initRandom * 1).toInt)*1000,
    numCPUsInUse = 0)
}
case class WorkerData(id: ID, pos: LatLongPosition,
                      localResources: LocalResources,
                      supportedServices: Set[String],
                      favouriteServices: Set[String],
                      ongoingRequests: Set[Request],
                      stability: Double,
                      time: Long)
case class Request(from: ID, time: Long)(val service: String){
  override def toString: String = s"Request(from=$from; t=$time)(service=$service)"
}
case class ConsumerData(id: ID, pos: LatLongPosition,
                        requests: Set[Request],
                        time: Long)
case class AggregateStats(cpus: Int = 0, cpusInUse: Long = 0) {
  def aggregate(ls: LocalResources) = AggregateStats(cpus = this.cpus + ls.numCPUs, cpusInUse = this.cpusInUse + ls.numCPUsInUse)
}
object AggregateStats {
  implicit val default = new Defaultable[AggregateStats]{
    override def default: AggregateStats = AggregateStats()
  }
}
case class AreaStats(leader: Int, val numWorkers: Int, val numActiveWorkers: Int, val numConsumers: Int, val numActiveConsumers: Int){
  lazy val potentialLoad = numConsumers.toDouble / (1.0+numWorkers)
  lazy val load = numActiveConsumers.toDouble / (1.0+numWorkers)
}
case class AreaAggregateStats(leader: Int)(val stats: AggregateStats){ override def toString: String = s"($leader, $stats)" }
case class SpatialData(leader: Int, leaderPosition: LatLongPosition, meanDensity: Double)

trait Event {
  val time: Long
}
case class HeartBeat(id: ID)(val time: Long) extends Event {
  override def toString: String = s"HeartBeat($id)($time)"
}
case class CompletionEvent(request: Request)(val from: ID, val time: Long) extends Event
case class CompletionNotificationEvent(request: Request)(val time: Long) extends Event
case class RejectionEvent(request: Request)(val time: Long, val reason: String) extends Event
object RejectionReasons {
  val NA = "NotAvailable"
  val Failure = "Failure"
}
case class FailureEvent(request: Request)(val time: Long, val reason: String) extends Event
object FailureReasons {
  val ghostWorker = "ghostWorker"
}
case class ReceptionConfirmationEvent(request: Request)(val time: Long) extends Event

case class UpstreamData(workerData: Map[ID,WorkerData],
                        consumerData: Map[ID,ConsumerData],
                        areaStats: AreaStats,
                        events: Set[Event])
object UpstreamData {
  def empty = UpstreamData(Map.empty, Map.empty, AreaStats(-1,0,0,0,0), Set.empty)
  implicit val dud = new Defaultable[UpstreamData] { override def default: UpstreamData = empty }
}

case class DownstreamData(myLeader: Option[ID], allocation: Allocation, events: Set[Event])
object DownstreamData {
  def empty = DownstreamData(None, Allocation(),Set.empty)
  implicit val ddd = new Defaultable[DownstreamData] { override def default: DownstreamData = empty }
}

case class GlobalStructure(leaders: Boolean, potential: Double)
case class Data(myLeader: ID)
object Data{
  def empty = Data(-1)
  implicit val dd = new Defaultable[Data] { override def default: Data = empty }
}

case class ControlData(allocation: Allocation)
object ControlData{
  def empty = ControlData(Allocation())
  implicit val dcd = new Defaultable[ControlData] { override def default: ControlData = empty }
}

case class Allocation(requests: Map[Request,ID] = Map.empty){ // map from requests to workers
  def byClients: Map[ID,Request] = requests.map(tp => tp._1.from -> tp._1)
  def byWorkers: Map[ID,Request] = requests.map(_.swap)
}
case class RequestHistory(received: Set[Request] = Set.empty,
                          unhandled: Set[Request] = Set.empty,
                          handled: Set[Request] = Set.empty,
                          completed: Set[Request] = Set.empty,
                          declined: Set[Request] = Set.empty,
                          garbage: Set[Request] = Set.empty,
                          failed: Set[Request] = Set.empty)

object Roles {
  val Worker = 1
  val Consumer = 2
  val Relay = 4
  val CandidateManager = 8
  val Fog = 16
  val Cloud = 32
}

class EdgeCloudEcosystem extends AggregateProgram
  with StandardSensors with ScafiAlchemistSupport with BlockG with BlockC with BlockS with FieldUtils { self =>
  override type MainResult = Any

  implicit val defUnit = new Defaultable[Unit] { override def default: Unit = () }

  lazy val grain = node.get[Double]("grain")
  lazy val lowDensity = node.get[Double]("lowDensityThreshold")
  lazy val highDensity = node.get[Double]("highDensityThreshold")
  lazy val lowLoad = node.get[Double]("lowLoad")
  lazy val highLoad = node.get[Double]("highLoad")
  lazy val mobilityEnabled = node.get[Double]("mobilityEnabled").toInt==1
  lazy val requestProbabilitiesAlongTime = node.get[List[Double]]("requestProbabilitiesAlongTime")
  lazy val gcTime = Prop[Int]("gcTime").default(-1)

  lazy val role = Prop[Int]("role")
  def isWorker: Boolean = (role & Roles.Worker) > 0
  def isConsumer: Boolean = (role & Roles.Consumer) > 0
  def isRelay: Boolean = (role & Roles.Relay) > 0
  def isEligibleAsManager: Boolean = (role & Roles.CandidateManager) > 0
  def isCloud: Boolean = (role & Roles.Cloud) > 0

  val serviceSet = Set("a","b","c","d")

  lazy val isLeader = Prop[Boolean]("leader").default(false).onChange{ case (prev,curr) => {
    if(!prev.getOrElse(false) && curr){ // i.e., become leader
      areaStats.write(AreaStats(-1,0,0,0,0))
      requestHistory.write(RequestHistory())
    } else if(prev.getOrElse(true) && !curr){ // i.e., unbecome leader
      areaLoad.delete
      areaUtilization.delete
      areaStats.delete
      requestHistory.delete
      statActiveConsumers.delete
      statActiveWorkers.delete
    }}}
  lazy val leaderFailureProbsAlongTime = Prop[List[Double]]("leaderFailureProbsAlongTime")
  lazy val supportedServices = Prop[Set[String]]("supportedServices") // services supported by workers
  lazy val favouriteServices = Prop[Set[String]]("favouriteServices").default(Set.empty, iff=isWorker)
  lazy val ongoingRequests = Prop[Set[Request]]("ongoingRequests").default(Set.empty, iff=isWorker) // services currently offered by workers to clients
    .onChange { case(_,r) =>
    localWorkerData.write(localWorkerData.get.copy(ongoingRequests = r, time = timestamp()))
  }
  lazy val completedRequestsAtWorker = Prop[Set[Request]]("completedRequestsAtWorker").default(Set.empty, iff=isWorker)
  lazy val workerFailureProbsAlongTime = Prop[List[Double]]("workerFailureProbsAlongTime")
  lazy val localResources = Prop[LocalResources]("workerResources")
  lazy val localWorkerData = Prop[WorkerData]("workerProfile") // worker profile
  lazy val requests = Prop[Set[Request]]("localRequests") // services requested by consumers
    .default(Set.empty, iff = isConsumer)
    .onChange{ case (_,r) => {
      localConsumerData.write(ConsumerData(mid, getPosition(), r, timestamp()))
      activeRequestCount.write(r.size)
    } }
  lazy val requestsCompleted = Prop[Int](name="requestsCompleted").default(0, iff=isConsumer)
  lazy val localConsumerData = Prop[ConsumerData]("consumerProfile").default(ConsumerData(mid,getPosition(),Set.empty,0), iff=isConsumer) // worker profile
  lazy val moveTo = Prop[LatLongPosition]("move_to")
  lazy val allocations = Prop[Allocation]("allocations").default(Allocation())
  lazy val areaStats = Prop[AreaStats]("areaStats")
  lazy val round = Prop[Int]("round")
  lazy val requestHistory = Prop[RequestHistory]("requestHistory").default(RequestHistory())
  lazy val activeRequestCount = Prop[Int]("activeRequestCount").default(0, iff=isConsumer)
  lazy val localEvents = Prop[Set[Event]]("localEvents").default(Set.empty)
  lazy val areaLoad = Prop[Double]("areaLoad")
  lazy val areaUtilization = Prop[Double]("areaUtilization")
  lazy val statReceivedRequests = Prop[Int]("requestsReceived").default(0)
  lazy val statHandledRequests = Prop[Int]("requestsHandled").default(0)
  lazy val statUnhandledRequests = Prop[Int]("requestsUnhandled").default(0)
  lazy val statCompletedRequests = Prop[Int]("requestsCompletedAtLeader").default(0)
  lazy val statDeclinedRequests = Prop[Int]("requestsDeclined").default(0)
  lazy val statGarbageRequests = Prop[Int]("requestsGarbage").default(0)
  lazy val statAllocatedRequests = Prop[Int]("statAllocatedRequests").default(0)
  lazy val statFailedRequests = Prop[Int]("statFailedRequests").default(0)
  lazy val statActiveConsumers = Prop[Int]("statActiveConsumers").default(0)
  lazy val statActiveWorkers = Prop[Int]("statActiveWorkers").default(0)
  lazy val relayCommunicationLoad = Prop[Long]("relayCommunicationLoad").default(0)
  lazy val totalWaitingTime = Prop[Double]("totalWaitingTime").default(Double.NaN, iff=isConsumer)
    .onChange{ case (_,d) => meanWaitingTime.write(d / requestsCompleted.get) }
  lazy val meanWaitingTime = Prop[Double]("meanWaitingTime").default(0, iff=isConsumer)
  lazy val smartAllocation = Prop.prop[Boolean,Double]("allocationStrategy", _>0, if(_) 1.0 else 0.0)
  lazy val numLeaderFailures = Prop[Int]("numLeaderFailures").default(0)
  lazy val numWorkerFailures = Prop[Int]("numWorkerFailures").default(0)
  lazy val liveWorkerPool = Prop[Map[ID,Long]]("liveWorkerPool").default(Map.empty)

  lazy val oneTimeInitialization: Unit = {
    // Compute local resources for each devices
    node.put("worker", if(isWorker) 1 else 0)
    node.put("consumer", if(isConsumer) 1 else 0)
    node.put("relay", if(isRelay) 1 else 0)
    node.put("candidate", if(isEligibleAsManager) 1 else 0)
    moveTo.write(getPosition())

    // Set defaults for properties
    if(isWorker) {
      // Sets a random set of "supported services"
      val kservs = 1+randomGenerator().nextInt(3)
      supportedServices.write(serviceSet.randomSubset(kservs,randomGenerator(), s => s!="d" || kservs==3 && randomGenerator().nextDouble()>0.9))
      if(kservs>2) favouriteServices.write(supportedServices.get.randomSubset(if(randomGenerator().nextInt(10)>7) 1 else 0, randomGenerator()))
      // Local resources
      localResources.write(LocalResources.random(nextRandom()))
      // Ongoing/completed services
      // Sets a profile collecting all the other properties
      localWorkerData.write(WorkerData(mid, getPosition(), localResources, supportedServices, favouriteServices, ongoingRequests, 1.0, 0))
    }

    // Just get to initialise
    requestsCompleted
    activeRequestCount

    if(isEligibleAsManager){
      node.put("smart_alloc", smartAllocation.get)
    }
  }

  def roundInitialization: Unit = {
    // Take count of round
    round.write(rep(0)(_+1))
    //node.put("thePosition1", currentPosition())
    node.put("thePosition2", getPosition())

    branchOn[Unit](isConsumer && requests.isEmpty){
      requestProbabilitiesAlongTime.grouped(2).map(l => (l.head, l.tail.head)).collectFirst {
        case (t,v) if timestamp()<t => if(randomGenerator().nextGaussian()>v) requests.write(Set(Request(mid,timestamp())(serviceSet.random(randomGenerator()))))
      }
    }

    branchOn(isLeader){
      leaderFailureProbsAlongTime.grouped(2).map(l => (l.head, l.tail.head)).collectFirst {
        case (t,v) if timestamp()<t => if(randomGenerator().nextGaussian()>v) {
          role.write(0)
          isLeader.write(false)
          numLeaderFailures.write(numLeaderFailures.get+1)
        }
      }; ()
    }

    branchOn(isWorker){
      workerFailureProbsAlongTime.grouped(2).map(l => (l.head, l.tail.head)).collectFirst {
        case (t,v) if timestamp()<t => if(randomGenerator().nextGaussian()>v) {
          role.write(0)
          numWorkerFailures.write(numWorkerFailures.get+1)
        }
      }; ()
    }

    // Refresh data structures
    relayCommunicationLoad.write(0)

    // Garbage collection
    localEvents.write(localEvents.get.filter(ev => gcTime.get==(-1) || timestamp()-ev.time<gcTime.get.toLong))
  }

  def FOG = isEligibleAsManager

  def EDGE = !CLOUD && !FOG && !OUT

  def CLOUD = false

  def OUT = role.get==0

  def RELAY = isRelay

  override def main(): Any = {
    oneTimeInitialization
    roundInitialization

    // Elect managers among powerful "fog" nodes
    val leaders = branch(FOG){ S(grain, metric = nbrRange) }{ false }
    isLeader.write(leaders)

    branchOn(EDGE || FOG) {
      // Build the potential field to leaders for information down-/up-streaming
      val potential = branch(leaders || RELAY) { distanceTo(leaders) } { +∞ }
      val g = GlobalStructure(leaders, potential)
//      node.put("debug_min", minHood((nbr{potential}+nbrRange(), nbr{mid})))
//      node.put("debug_my_parent", findParent(potential))
//      node.put("debug_Iam_parent_of", foldhood(Set.empty[(ID,Double)])(_++_){ mux(nbr(findParent(potential)) == mid){ Set(nbr((mid,potential)))}{Set.empty} })
//      node.put("debug_nbrs", foldhood(Set.empty[ID])(_++_)(Set(nbr(mid))))

      val (d,uf,cd,df) = rep((Data.empty, UpstreamData.empty, ControlData.empty, DownstreamData.empty)){ case (d,uf,cd,df) =>
        val data = branchOn(isWorker || isConsumer){ execute(df) }
        val uFlow = align(df.myLeader.getOrElse(-1)){ _ => dataUpstreaming(g, data) }
        /* NOTE: C (findParent) minimizes on potential only, whereas broadcast consider potential + nbrRange.
        So, a node near the boundary may upstream to a leader but receive the downstream from another.
        To fix this issue, we can branch/align on the leader received by the downstream.
        */
        val controlData = branchOn(leaders){ processData(uFlow) }
        val dFlow = dataDownstreaming(g, controlData)

        (data, uFlow, controlData, dFlow)
      }

      node.put("myLeader", df.myLeader.getOrElse(-1))
      if(isConsumer || isWorker) {
        node.put("downstream_data", df)
        node.put("area", new scala.util.Random(df.myLeader.getOrElse(-1)).nextInt(1000000))
      }
      if(isLeader) node.put("upstream_data", uf)
      if (isRelay) node.put("potential", potential)
      if (isLeader) node.put("load", uf.areaStats)
      if(isConsumer && mobilityEnabled) {
        val (deltax,deltay) = ((randomGenerator().nextGaussian())/100.0, (randomGenerator().nextGaussian())/100.0)
        moveTo.write(getPosition().map((x,y) => (x+deltax, y+deltay)))
      }
    }

    /*
    branchOn(FOG || CLOUD){

    }

val allResourcesInArea = broadcast(potential, metric, aggregatedResourcesPerArea)
val nbrAreas = excludingSelf.unionHood(nbr{AreaStats(myLeader)(allResourcesInArea)})

val numNodesInArea = C[Double,Int](potential, _+_, 1, 0)
val numNbrs = excludingSelf.sumHood(1)
val numNbrsInArea = C[Double,Double](potential, _+_, numNbrs, 0)
val spatialDataForArea = broadcast(potential, metric, SpatialData(mid, getPosition(), numNbrsInArea/numNodesInArea))
val meanDensity = spatialDataForArea.meanDensity

node.put("more_devices_allowed", s"${meanDensity} < ${highDensity} = $moreDevicesAllowed")
node.put("numNodes", if(leaders) numNodesInArea else 0)
node.put("aggregateResourcesPerArea", aggregatedResourcesPerArea)
node.put("allResourcesPerArea", allResourcesInArea)

    node.put("debug_context",this.vm.asInstanceOf[RoundVMImpl].context)
    node.put("debug_export",this.vm.asInstanceOf[RoundVMImpl].export)
*/
    77
  }

  def dataUpstreaming(g: GlobalStructure, d: Data): UpstreamData = {
    // Collect worker information in managers
    val workerData: Map[ID,WorkerData] = collectAll(localWorkerData, along = g.potential, when = isWorker)

    // Collect consumer requests
    val consumerData: Map[ID,ConsumerData] = collectAll(localConsumerData, along = g.potential, when = isConsumer)

    val events: Set[Event] = collectSet(localEvents, along = g.potential, when = isConsumer || isWorker)

    val numOfConsumersInArea = collectCount(predicate = isConsumer, along = g.potential)
    val numOfWorkersInArea = collectCount(predicate = isWorker, along = g.potential)
    val numOfActiveConsumersInArea = collectCount(predicate = isConsumer && !requests.isEmpty, along = g.potential)
    val numOfActiveWorkersInArea = collectCount(predicate = isWorker && !ongoingRequests.isEmpty, along = g.potential)

    //val nearbyConsumers = excludingSelf.sumHood(if (nbr(isConsumer)) 1 else 0)
    //val meanNumOfConsumersInArea = branch(!isConsumer) { collectMean(nearbyConsumers, along = potential) } { 0 }

    UpstreamData(workerData, consumerData, AreaStats(mid, numOfWorkersInArea, numOfActiveWorkersInArea, numOfConsumersInArea, numOfActiveConsumersInArea), events).let(ud => if(isRelay){
      relayCommunicationLoad.write(relayCommunicationLoad.get + ud.events.size + ud.consumerData.size + ud.workerData.size)
      node.put("debug_upstream_data", ud)
    })
  }

  def dataDownstreaming(g: GlobalStructure, cd: ControlData): DownstreamData = {
    val myLeader = broadcast(mid, along = g.potential, metric = nbrRange)
    val allocation = broadcast(cd.allocation, along = g.potential, metric = nbrRange)
    val events = broadcast(localEvents, along = g.potential, metric = nbrRange)

    DownstreamData(Some(myLeader), allocation, events).let(dd => if(isRelay){
      relayCommunicationLoad.write(relayCommunicationLoad.get + dd.events.size + dd.allocation.requests.size)
    })
  }

  def processData(ud: UpstreamData): ControlData = {
    // Each leader locally calculates the aggregate resources of its area
    val aggregatedResourcesPerArea: AggregateStats =
      ud.workerData.values.foldLeft(AggregateStats())((acc, p) => acc.aggregate(p.localResources))

    // The leader processes incoming events and updates its events to be propagated
    localEvents.merge(ud.events.collect {
      case cne@CompletionNotificationEvent(r) => cne
    })

    // The leader decides how to allocate requests to workers
    val allocations = allocate(ud)

    // Register statistics for exporting simulation data
    areaLoad.write(ud.areaStats.load)
    areaUtilization.write(100.0 * ud.areaStats.numActiveWorkers / ud.areaStats.numWorkers.toDouble)
    statActiveConsumers.write(ud.areaStats.numActiveConsumers)
    statActiveWorkers.write(ud.areaStats.numActiveWorkers)

    ControlData(allocations)
  }

  def execute(ddata: DownstreamData): Data = {
    val allocs = ddata.allocation
    branchOn(isWorker){
      val myReqs = allocs.byWorkers.get(mid)
      localEvents.replace(HeartBeat(mid)(timestamp()))

      myReqs.foreach{ req =>
        if(!completedRequestsAtWorker.contains(req) && !ongoingRequests.contains(req)) {
          localEvents.add(ReceptionConfirmationEvent(req)(timestamp()))
          ongoingRequests.add(req)
        }
      }

      ongoingRequests.subtract(
        ongoingRequests.flatMap(req => if(timestamp()-req.time>=
        (if(favouriteServices.contains(req.service)) 15 else 25) && randomGenerator().nextDouble()>0.9) {
        completedRequestsAtWorker.add(req)
        localEvents.add(CompletionNotificationEvent(req)(timestamp()))
        Set(req)
        } else Set.empty[Request])
      )
    }

    branchOn(isConsumer){
      //val myRequests = allocs.requests.filterKeys(_.from==mid)
      //node.put("NOTE", s"Got my ${myRequests.keySet} handled by ${myRequests.values.mkString(",")}")
      val myId = mid
      ddata.events.collect {
        case CompletionNotificationEvent(r@Request(`myId`,_)) if requests.contains(r) => {
          requestsCompleted.write(requestsCompleted.get+1)
          requests.remove(r)
          localEvents.add(CompletionEvent(r)(from=mid, timestamp()))
          totalWaitingTime.write((if(totalWaitingTime.get.isNaN) 0 else totalWaitingTime.get)+(timestamp()-r.time))
        }
        case RejectionEvent(r@Request(`myId`,_)) if requests.contains(r) => {
          requests.remove(r)
        }
        case FailureEvent(r@Request(`myId`,_)) if requests.contains(r) => {
          requests.remove(r)
        }
      }
      ()
    }

    Data(ddata.myLeader.getOrElse(-1))
  }

  def allocate(ud: UpstreamData): Allocation = {
    val alreadyHandledRequests = allocations.get.byClients
    // Extract new requests (ASSUMPTION: max one request per consumer)
    val newRequests: Set[Request] = ud.consumerData.filter{ case (id,cp) => !alreadyHandledRequests.contains(id) && !cp.requests.isEmpty }.flatMap(_._2.requests).toSet
    // Consider only available workers (ASSUMPTION: one worker can only serve one request/consumer at a time)
    var availableWorkers = ud.workerData.filterKeys(!allocations.byWorkers.contains(_))
    // Retrieve completed requests
    val completedRequests = ud.events.collect{  case e@CompletionEvent(req) if ud.workerData.contains(e.from) => req }
    // New allocations for new requests
    val newAllocs: Map[Request,ID] = (newRequests++requestHistory.unhandled).flatMap(req => {
      var choiceSet: Seq[(ID,WorkerData)] = availableWorkers.toSeq
      choiceSet = choiceSet.sortBy(!_._2.favouriteServices.contains(req.service) || !smartAllocation)
      choiceSet.collectFirst {
        case (id, WorkerData(_, _, _, support, _, _, _, _)) if support.contains(req.service) =>
          availableWorkers -= id
          Set(req -> id)
      }.getOrElse(Set.empty)
    }).toMap
    // If an area has no workers with provided services, an option is to reject them
    val newRejected = (requestHistory.unhandled--newAllocs.keySet).filter(r => !ud.workerData.exists(_._2.supportedServices.contains(r.service)))
    localEvents.merge(newRejected.map(rejReq => RejectionEvent(rejReq)(timestamp(), RejectionReasons.NA)))
    // If I receive a request from a consumer that is not upstreaming data, I put it into garbage
    val garbage = requestHistory.unhandled.filter(r => !ud.consumerData.contains(r.from) && timestamp()-r.time>gcTime)
    // I need to take into account worker failure; options: (1) automatic reschedule; (2) notification to user, which may retry
    ud.events.collect { case ev@HeartBeat(id) => { liveWorkerPool.add(id -> ev.time) } } // First, update worker pool based on heartbeat
    liveWorkerPool.write(liveWorkerPool.filter{ case (wid,lastHeartbeat) => timestamp()-lastHeartbeat > gcTime }) // Then, remove stale workers
    val failedRequests: Set[Request] = allocations.byWorkers.collect {
      case (wid, req) if !liveWorkerPool.get.contains(wid) => req
    }.toSet
    localEvents.merge(failedRequests.map(FailureEvent(_)(timestamp(),FailureReasons.ghostWorker)))

    allocations.write(Allocation(allocations.requests--completedRequests--failedRequests++newAllocs))

    requestHistory.write(requestHistory.get.copy(
      received = requestHistory.received ++ newRequests,
      unhandled = requestHistory.unhandled ++ newRequests -- newAllocs.keySet -- newRejected -- garbage,
      handled = requestHistory.handled ++ newAllocs.keySet ++ newRejected,
      completed = requestHistory.completed ++ completedRequests,
      declined = requestHistory.declined ++ newRejected,
      garbage = requestHistory.garbage ++ garbage,
      failed = requestHistory.failed ++ failedRequests,
    ))
    statReceivedRequests.write(requestHistory.received.size)
    statHandledRequests.write(requestHistory.handled.size)
    statUnhandledRequests.write(requestHistory.unhandled.size)
    statCompletedRequests.write(requestHistory.completed.size)
    statDeclinedRequests.write(requestHistory.declined.size)
    statGarbageRequests.write(requestHistory.garbage.size)
    statAllocatedRequests.write(allocations.requests.size)
    statFailedRequests.write(requestHistory.failed.size)

    allocations
  }

  def collectCount(predicate: Boolean, along: Double) =
    C[Double,Int](along, _+_, if(predicate) 1 else 0, 0)

  def branchOn[T](c: => Boolean)(th: => T)(implicit d: Defaultable[T]) = branch[T](c)(th){d.default}

  def collectMean(value: Double, along: Double): Double = {
    val numNodesInArea = C[Double,Double](along, _+_, 1, 1)
    val collectedValue = C[Double,Double](along, _+_, value, 0)
    collectedValue/numNodesInArea
  }

  /** THIS OVERRIDE IS NEEDED BECAUSE OF A BUG IN "S" */
  override def breakUsingUids(uid: (Double, ID),
                     grain: Double,
                     metric: () => Double): Boolean =
    uid == rep(uid) { lead: (Double, ID) =>
      distanceCompetition(classicGradient(uid == lead, metric), lead, uid, grain, metric)
    }

  def priorityS(grain: Double, metric: Metric, prioField: Double) = {
    val uid = rep((nextRandom()*prioField, mid())) { v => (v._1, mid()) }
    uid == rep(uid){ lead: (Double, ID) =>
      val d = classicGradient(uid==lead, metric)
      val inf: (Double, ID) = (Double.PositiveInfinity, uid._2)
      mux(d > grain & prioField<0) {
        uid
      } {
        mux(d >= (0.5 * grain)) {
          inf
        } {
          minHood {
            mux(nbr(d) + metric() >= 0.5 * grain) { nbr(inf) } { nbr(lead) }
          } } } } }

  def broadcast[T](value: T, along: Double, metric: Metric = nbrRange) =
    G_along[T](along, metric, value, (v:T) => v)

  def collectAll[T](value: => T, along: Double, when: Boolean = true): Map[ID,T] =
    C[Double,Map[ID,T]](along, _++_, branch(when){ Map(mid -> value) }{ Map.empty }, Map.empty)

  def collectSet[T](value: => Set[T], along: Double, when: Boolean = true): Set[T] =
    C[Double,Set[T]](along, _++_, branch(when){ value }{ Set.empty }, Set.empty)

  private def log(label: String, text: Any) = node.put(label, text.toString)

  def getPosition(): LatLongPosition = sense[LatLongPosition](LSNS_POSITION)
  implicit def fromLatLogToPoint(ll: LatLongPosition): Point3D =
    Point3D(ll.getLongitude, ll.getLatitude, 0)

  implicit class RichLatLong(ll: LatLongPosition){
    def newInstance(lat: Double, long: Double): LatLongPosition =
      new LatLongPosition(lat, long)
    def map(f: (Double,Double)=>(Double,Double)) =
      (newInstance _).tupled(f(ll.getLatitude, ll.getLongitude))
  }

  private val +∞ = Double.PositiveInfinity

  implicit class MyRichSet[T](val set: Set[T]){
    def randomSubset(k: Int, rg: Random, pred: T => Boolean = _ => true): Set[T] = {
      var n = k
      var s = set.toSeq
      var res = Set.empty[T]
      while(s.size>0 && n>0) {
        val e = s(rg.nextInt(s.size))
        if (pred(e)) {
          s = s.diff(Seq(e))
          n = n-1
          res = res + e
        }
      }
      res
    }

    def random(rg: Random): T = set.toSeq(rg.nextInt(set.size))
  }

  case class Prop[T](name: String){
    var onChangeFunction: Option[(Option[T],T)=>Unit] = None

    def molecule = new SimpleMolecule(name)
    def get: T = _get
    def write(v: T) = {
      val prev = Try(get).toOption
      _put(v)
      onChangeFunction.foreach(_(prev,v))
    }
    def default(v: T, iff: => Boolean = true): Prop[T] = { if(iff) write(v); this }
    def delete = {
      val n = node.asInstanceOf[SimpleNodeManager].node
      if(n.contains(molecule)) n.removeConcentration(molecule)
    }
    def onChange(action: (Option[T],T)=>Unit): Prop[T] = { onChangeFunction = Some(action); this }

    protected def _get: T = node.get[T](name)
    protected def _put(v: T): Unit = node.put[T](name, v)
  }
  implicit class RichProp[T](pset: Prop[Set[T]]){
    def add(v: T) = pset.write(pset.get+v)
    def replace(v: T) = pset.write(pset.get-v+v)
    def remove(v: T) = pset.write(pset.get-v)
    def merge(s: Set[T]) = pset.write(pset.get++s)
    def subtract(s: Set[T]) = pset.write(pset.get--s)
  }
  implicit class MapProp[K,V](pset: Prop[Map[K,V]]){
    def add(v: (K,V)) = pset.write(pset.get+v)
    def remove(v: K) = pset.write(pset.get-v)
    def merge(s: Map[K,V]) = pset.write(pset.get++s)
    def subtract(s: Set[K]) = pset.write(pset.get--s)
  }
  object Prop {
    implicit def conv[T](prop: Prop[T]): T = prop.get

    def prop[V,Underlying](name: String, from:Underlying=>V, to:V=>Underlying): Prop[V] = new Prop[V](name){
      override def _get: V = from(node.get[Underlying](name))
      override def _put(v: V) = node.put[Underlying](name, to(v))
    }
  }

  implicit class RichObject[T](value: T){
    def let(action: T=>Unit): T = {
      action(value)
      value
    }
  }
}