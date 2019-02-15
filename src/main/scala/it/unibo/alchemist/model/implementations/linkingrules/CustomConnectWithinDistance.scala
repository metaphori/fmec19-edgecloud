package it.unibo.alchemist.model.implementations.linkingrules;

import java.util.stream.Collectors

import it.unibo.alchemist.model.implementations.molecules.SimpleMolecule
import it.unibo.alchemist.model.implementations.neighborhoods.Neighborhoods
import it.unibo.alchemist.model.interfaces.{Environment, Neighborhood, Node, Position};

class CustomConnectWithinDistance[T, P <: Position[P]](radius: Double)
  extends ConnectWithinDistance[T, P](radius) {

    override def computeNeighborhood(center: Node[T], env: Environment[T, P]): Neighborhood[T] = {
        val nbrs = env.getNodesWithinRange(center, getRange())
        Neighborhoods.make(env, center,
            if(center.getConcentration(new SimpleMolecule("role"))==2) // consumer
                nbrs.stream()
                  .filter(n => n.getId==center.getId || n.getConcentration(new SimpleMolecule("role"))!=2)
                  .collect(Collectors.toList[Node[T]])
            else if(center.getConcentration(new SimpleMolecule("role"))==8) { // candidate manager
                nbrs.addAll(env.getNodes.stream().filter(_.getConcentration(new SimpleMolecule("role")) == 8)
                  .collect(Collectors.toList[Node[T]]))
                nbrs
            } else nbrs
        )
    }
}
