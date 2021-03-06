package wom.graph

import cats.instances.list._
import cats.kernel.Monoid
import cats.syntax.foldable._
import cats.syntax.traverse._
import common.validation.ErrorOr.ErrorOr
import shapeless.{:+:, CNil, Coproduct}
import wom.callable.Callable._
import wom.callable.TaskDefinition.OutputFunctionResponse
import wom.callable.{Callable, TaskDefinition, WorkflowDefinition}
import wom.expression.{IoFunctionSet, WomExpression}
import wom.graph.CallNode._
import wom.graph.GraphNode.GeneratedNodeAndNewNodes
import wom.graph.GraphNodePort._
import wom.graph.expression.ExpressionNode
import wom.values.WomValue

import scala.concurrent.ExecutionContext

sealed abstract class CallNode extends GraphNode {
  def callable: Callable
  def callType: String

  def inputDefinitionMappings: InputDefinitionMappings
}

final case class TaskCallNode private(override val identifier: WomIdentifier,
                                      callable: TaskDefinition,
                                      override val inputPorts: Set[GraphNodePort.InputPort],
                                      inputDefinitionMappings: InputDefinitionMappings) extends CallNode {
  val callType: String = "task"
  lazy val expressionBasedOutputPorts: List[ExpressionBasedOutputPort] = {
    callable.outputs.map(o => ExpressionBasedOutputPort(o.localName, o.womType, this, o.expression))
  }

  override lazy val outputPorts: Set[OutputPort] = expressionBasedOutputPorts.toSet[OutputPort]

  /**
    * Evaluate outputs using the custom evaluation function of the task definition.
    * An empty return value means the engine should fall back to its default evaluation method.
    */
  def customOutputEvaluation(inputs: Map[String, WomValue], ioFunctionSet: IoFunctionSet, executionContext: ExecutionContext): OutputFunctionResponse = {
    callable.customizedOutputEvaluation(outputPorts, inputs, ioFunctionSet, executionContext)
  }
}

final case class WorkflowCallNode private(override val identifier: WomIdentifier,
                                          callable: WorkflowDefinition,
                                          override val inputPorts: Set[GraphNodePort.InputPort],
                                          inputDefinitionMappings: InputDefinitionMappings) extends CallNode {
  val callType: String = "workflow"
  val subworkflowCallOutputPorts: Set[SubworkflowCallOutputPort] = {
    callable.innerGraph.nodes.collect { case gon: GraphOutputNode => SubworkflowCallOutputPort(gon, this) }
  }
  override val outputPorts: Set[OutputPort] = subworkflowCallOutputPorts.toSet[OutputPort]
}

object TaskCall {
  def graphFromDefinition(taskDefinition: TaskDefinition): ErrorOr[Graph] = {
    val taskDefinitionLocalName = LocalName(taskDefinition.name)
    
    /* Creates an identifier for an input or an output
     * The localName is the name of the input or output
     */
    
    // The FQN combines the name of the task to the name of the input or output
    def identifier(name: LocalName) = WomIdentifier(name, taskDefinitionLocalName.combineToFullyQualifiedName(name))

    def linkOutput(call: GraphNode)(output: OutputDefinition): ErrorOr[GraphNode] = call.outputByName(output.name).map(out => PortBasedGraphOutputNode(
      identifier(output.localName), output.womType, out
    ))
    import common.validation.ErrorOr.ShortCircuitingFlatMap

    val callNodeBuilder = new CallNodeBuilder()
    
    val inputDefinitionFold = taskDefinition.inputs.foldMap({ inputDef =>
    {
      val newNode = inputDef match {
        case RequiredInputDefinition(name, womType) => RequiredGraphInputNode(identifier(name), womType, name.value)
        case InputDefinitionWithDefault(name, womType, default) => OptionalGraphInputNodeWithDefault(identifier(name), womType, default, name.value)
        case OptionalInputDefinition(name, womType) => OptionalGraphInputNode(identifier(name), womType, name.value)
      }

      InputDefinitionFold(
        mappings = List(inputDef -> Coproduct[InputDefinitionPointer](newNode.singleOutputPort: OutputPort)),
        newGraphInputNodes = Set(newNode),
        callInputPorts = Set(callNodeBuilder.makeInputPort(inputDef, newNode.singleOutputPort))
      )
    }
    })(inputDefinitionFoldMonoid)

    val uniqueIdentifier = WomIdentifier(taskDefinition.name)
    val callWithInputs = callNodeBuilder.build(uniqueIdentifier, taskDefinition, inputDefinitionFold)

    for {
      outputs <- taskDefinition.outputs.traverse(linkOutput(callWithInputs.node) _)
      callSet = Set[GraphNode](callWithInputs.node)
      inputsSet = callWithInputs.newInputs.toSet[GraphNode]
      outputsSet = outputs.toSet[GraphNode]
      graph <- Graph.validateAndConstruct(callSet ++ inputsSet ++ outputsSet)
    } yield graph
  }
}

object CallNode {
  /* A monoid can't be derived automatically for this class because it contains a Map[InputDefinition, InputDefinitionPointer],
   * and there's no monoid defined over InputDefinitionPointer
   */
  implicit val inputDefinitionFoldMonoid = new Monoid[InputDefinitionFold] {
    override def empty: InputDefinitionFold = InputDefinitionFold()
    override def combine(x: InputDefinitionFold, y: InputDefinitionFold): InputDefinitionFold = {
      InputDefinitionFold(
        mappings = x.mappings ++ y.mappings,
        callInputPorts = x.callInputPorts ++ y.callInputPorts,
        newGraphInputNodes = x.newGraphInputNodes ++ y.newGraphInputNodes,
        newExpressionNodes = x.newExpressionNodes ++ y.newExpressionNodes,
        usedOuterGraphInputNodes = x.usedOuterGraphInputNodes ++ y.usedOuterGraphInputNodes
      )
    }
  }

  final case class InputDefinitionFold(mappings: InputDefinitionMappings = List.empty,
                                       callInputPorts: Set[InputPort] = Set.empty,
                                       newGraphInputNodes: Set[ExternalGraphInputNode] = Set.empty,
                                       newExpressionNodes: Set[ExpressionNode] = Set.empty,
                                       usedOuterGraphInputNodes: Set[OuterGraphInputNode] = Set.empty)

  type InputDefinitionPointer = OutputPort :+: WomExpression :+: WomValue :+: CNil
  // This is a List rather than Map because the order of 'InputDefinition's is important:
  type InputDefinitionMappings = List[(InputDefinition, InputDefinitionPointer)]

  final case class CallNodeAndNewNodes(node: CallNode, newInputs: Set[ExternalGraphInputNode], newExpressions: Set[ExpressionNode], override val usedOuterGraphInputNodes: Set[OuterGraphInputNode]) extends GeneratedNodeAndNewNodes {
    def nodes: Set[GraphNode] = Set(node) ++ newInputs ++ newExpressions ++ usedOuterGraphInputNodes
  }

  /**
    * Don't use this directly; go via callWithInputs to make sure everything's in order when constructing a CallNode.
    */
  private[graph] def apply(nodeIdentifier: WomIdentifier,
                           callable: Callable,
                           inputPorts: Set[GraphNodePort.InputPort],
                           inputDefinitionMappings: InputDefinitionMappings): CallNode = callable match {
    case t: TaskDefinition => TaskCallNode(nodeIdentifier, t, inputPorts, inputDefinitionMappings)
    case w: WorkflowDefinition => WorkflowCallNode(nodeIdentifier, w, inputPorts, inputDefinitionMappings)
  }

  /**
    * Helper class to build call nodes.
    * Helps making input ports and building the node while making sure node references are set properly.
    */
  class CallNodeBuilder {
    private val graphNodeSetter = new GraphNode.GraphNodeSetter[CallNode]()

    /**
      * Makes an input port for this call.
      * Ensures that the port will contain the reference to the node when it gets created.
      */
    def makeInputPort(inputDefinition: InputDefinition, outputPort: OutputPort) = {
      ConnectedInputPort(inputDefinition.name, inputDefinition.womType, outputPort, graphNodeSetter.get)
    }

    def build(nodeIdentifier: WomIdentifier,
              callable: Callable,
              inputDefinitionFold: InputDefinitionFold): CallNodeAndNewNodes = {
      val callNode = CallNode(nodeIdentifier, callable, inputDefinitionFold.callInputPorts, inputDefinitionFold.mappings)
      graphNodeSetter._graphNode = callNode
      CallNodeAndNewNodes(callNode, inputDefinitionFold.newGraphInputNodes, inputDefinitionFold.newExpressionNodes, inputDefinitionFold.usedOuterGraphInputNodes)
    }
  }
}
