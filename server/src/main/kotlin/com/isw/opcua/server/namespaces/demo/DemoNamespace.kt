package com.isw.opcua.server.namespaces.demo

import com.google.common.collect.Maps
import com.isw.opcua.milo.extensions.defaultValue
import com.isw.opcua.milo.extensions.inverseReferenceTo
import com.isw.opcua.milo.extensions.resolve
import com.isw.opcua.server.sampling.SampledDataItem
import com.isw.opcua.server.sampling.TickManager
import com.isw.opcua.server.util.AbstractLifecycle
import kotlinx.coroutines.CoroutineScope
import org.eclipse.milo.opcua.sdk.core.AccessLevel
import org.eclipse.milo.opcua.sdk.core.Reference
import org.eclipse.milo.opcua.sdk.server.OpcUaServer
import org.eclipse.milo.opcua.sdk.server.UaNodeManager
import org.eclipse.milo.opcua.sdk.server.api.AttributeServices.ReadContext
import org.eclipse.milo.opcua.sdk.server.api.AttributeServices.WriteContext
import org.eclipse.milo.opcua.sdk.server.api.DataItem
import org.eclipse.milo.opcua.sdk.server.api.MonitoredItem
import org.eclipse.milo.opcua.sdk.server.api.Namespace
import org.eclipse.milo.opcua.sdk.server.api.NodeManager
import org.eclipse.milo.opcua.sdk.server.api.ViewServices.BrowseContext
import org.eclipse.milo.opcua.sdk.server.model.nodes.objects.ServerNode
import org.eclipse.milo.opcua.sdk.server.nodes.*
import org.eclipse.milo.opcua.stack.core.*
import org.eclipse.milo.opcua.stack.core.types.builtin.*
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UInteger
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UShort
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.ubyte
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.ushort
import org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn
import org.eclipse.milo.opcua.stack.core.types.structured.ReadValueId
import org.eclipse.milo.opcua.stack.core.types.structured.ViewDescription
import org.eclipse.milo.opcua.stack.core.types.structured.WriteValue
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.TimeUnit
import java.util.function.BiConsumer

class DemoNamespace(
    private val namespaceIndex: UShort,
    private val coroutineScope: CoroutineScope,
    internal val server: OpcUaServer
) : AbstractLifecycle(), Namespace {

    companion object {
        const val NAMESPACE_URI = "urn:industrialsoftworks:opcua:server:demo"
    }

    private val logger: Logger = LoggerFactory.getLogger(DemoNamespace::class.java)

    private val tickManager = TickManager(coroutineScope)

    private val nodeManager = UaNodeManager()

    private val sampledNodes: ConcurrentMap<DataItem, SampledNode> = Maps.newConcurrentMap()
    private val subscribedNodes: ConcurrentMap<DataItem, SubscribedNode> = Maps.newConcurrentMap()

    override fun onStartup() {
        addCttNodes()
        addMassNodes()
        addTurtleNodes()
        addFileNodes()
        addMethodNodes()
        addDynamicNodes()
        addNullValueNodes()

        // Set the EventNotifier bit on Server Node for Events.
        val serverNode = server.addressSpaceManager.getManagedNode(Identifiers.Server).orElse(null)

        if (serverNode is ServerNode) {
            serverNode.eventNotifier = ubyte(1)

            // Post a bogus Event every couple seconds
            server.scheduledExecutorService.scheduleAtFixedRate({
                try {
                    val eventNode = server.eventFactory.createEvent(
                        NodeId(1, UUID.randomUUID()),
                        Identifiers.BaseEventType
                    )

                    eventNode.browseName = QualifiedName(1, "foo")
                    eventNode.displayName = LocalizedText.english("foo")

                    eventNode.eventId = ByteString.of(byteArrayOf(0, 1, 2, 3))
                    eventNode.eventType = Identifiers.BaseEventType
                    eventNode.sourceNode = serverNode.getNodeId()
                    eventNode.sourceName = serverNode.getDisplayName().text
                    eventNode.time = DateTime.now()
                    eventNode.receiveTime = DateTime.NULL_VALUE
                    eventNode.message = LocalizedText.english("event message!")
                    eventNode.severity = ushort(2)

                    server.eventBus.post(eventNode)
                } catch (e: Throwable) {
                    logger.error("Error creating EventNode: {}", e.message, e)
                }
            }, 0, 2, TimeUnit.SECONDS)
        }
    }

    override fun onShutdown() {
        sampledNodes.values.forEach { it.shutdown() }
    }

    override fun getNamespaceUri(): String = NAMESPACE_URI

    override fun getNamespaceIndex(): UShort = this@DemoNamespace.namespaceIndex

    override fun getNodeManager(): Optional<NodeManager<UaNode>> {
        return Optional.of(nodeManager)
    }

    override fun browse(context: BrowseContext, viewDescription: ViewDescription, nodeId: NodeId) {
        val node: UaNode? = nodeManager[nodeId]

        val references: List<Reference>? = node?.references ?: maybeTurtleReferences(nodeId)

        if (references != null) {
            context.success(references)
        } else {
            context.failure(StatusCodes.Bad_NodeIdUnknown)
        }
    }

    override fun getReferences(
        context: BrowseContext,
        viewDescription: ViewDescription,
        sourceNodeId: NodeId
    ) {

        val references: List<Reference> =
            nodeManager.getReferences(sourceNodeId) +
                (maybeTurtleReferences(sourceNodeId) ?: emptyList())

        context.success(references)
    }

    override fun read(
        context: ReadContext,
        maxAge: Double,
        timestamps: TimestampsToReturn,
        readValueIds: List<ReadValueId>
    ) {

        val values = readValueIds.map { readValueId ->
            val node: UaNode? = nodeManager[readValueId.nodeId] ?: maybeTurtleNode(readValueId.nodeId)

            val value: DataValue? = node?.readAttribute(
                AttributeContext(context),
                readValueId.attributeId,
                timestamps,
                readValueId.indexRange,
                readValueId.dataEncoding
            )

            value ?: DataValue(StatusCodes.Bad_NodeIdUnknown)
        }

        context.complete(values)
    }

    override fun write(context: WriteContext, writeValues: List<WriteValue>) {
        val results: List<StatusCode> = writeValues.map { writeValue ->
            val node: UaNode? = nodeManager[writeValue.nodeId]

            val status: StatusCode? = node?.run {
                try {
                    writeAttribute(
                        AttributeContext(context),
                        writeValue.attributeId,
                        writeValue.value,
                        writeValue.indexRange
                    )
                    StatusCode.GOOD
                } catch (e: UaException) {
                    e.statusCode
                }
            }

            status ?: StatusCode(StatusCodes.Bad_NodeIdUnknown)
        }

        context.complete(results)
    }

    override fun onCreateDataItem(
        itemToMonitor: ReadValueId,
        requestedSamplingInterval: Double,
        requestedQueueSize: UInteger,
        revisionCallback: BiConsumer<Double, UInteger>
    ) {
        if (itemToMonitor.nodeId.identifier.toString().startsWith("Mass")) {
            revisionCallback.accept(0.0, requestedQueueSize)
        } else {
            super.onCreateDataItem(itemToMonitor, requestedSamplingInterval, requestedQueueSize, revisionCallback)
        }
    }

    override fun onModifyDataItem(
        itemToModify: ReadValueId,
        requestedSamplingInterval: Double,
        requestedQueueSize: UInteger,
        revisionCallback: BiConsumer<Double, UInteger>
    ) {
        if (itemToModify.nodeId.identifier.toString().startsWith("Mass")) {
            revisionCallback.accept(0.0, requestedQueueSize)
        } else {
            super.onModifyDataItem(itemToModify, requestedSamplingInterval, requestedQueueSize, revisionCallback)
        }
    }

    override fun onDataItemsCreated(items: List<DataItem>) {
        items.forEach { item ->
            val nodeId: NodeId = item.readValueId.nodeId
            val node: UaNode? = nodeManager.get(nodeId)

            if (node != null) {
                if (nodeId.identifier.toString().startsWith("Mass")) {
                    val subscribedNode = SubscribedNode(item, node)
                    subscribedNodes[item] = subscribedNode
                    subscribedNode.startup()
                } else {
                    val sampledNode = SampledNode(item, coroutineScope, node)
                    sampledNodes[item] = sampledNode
                    sampledNode.startup()
                }
            }
        }
    }

    override fun onDataItemsModified(items: List<DataItem>) {
        items.forEach { item ->
            sampledNodes[item]?.modifyRate(item.samplingInterval)
        }
    }

    override fun onDataItemsDeleted(items: List<DataItem>) {
        items.forEach { item ->
            sampledNodes.remove(item)?.shutdown()
            subscribedNodes.remove(item)?.shutdown()
        }
    }

    override fun onMonitoringModeChanged(items: List<MonitoredItem>) {
        items.forEach {
            sampledNodes[it]?.samplingEnabled = it.isSamplingEnabled
            subscribedNodes[it]?.samplingEnabled = it.isSamplingEnabled
        }
    }

    inner class SampledNode(
        item: DataItem,
        scope: CoroutineScope,
        private val node: UaNode
    ) : SampledDataItem(item, scope, tickManager) {

        override suspend fun sampleCurrentValue(currentTime: Long): DataValue {
            return node.readAttribute(
                AttributeContext(server),
                item.readValueId.attributeId,
                TimestampsToReturn.Both,
                item.readValueId.indexRange,
                item.readValueId.dataEncoding
            )
        }

    }

    inner class SubscribedNode(
        private val item: DataItem,
        private val node: UaNode
    ) : AbstractLifecycle() {

        @Volatile
        var samplingEnabled: Boolean = true

        private val targetAttributeId = AttributeId.from(item.readValueId.attributeId).orElseThrow()

        private val attributeObserver = AttributeObserver { _, attributeId, value ->
            if (samplingEnabled && attributeId == targetAttributeId) {
                item.setValue(value as DataValue)
            }
        }

        override fun onStartup() {
            item.setValue(node.getAttribute(AttributeContext(server), targetAttributeId))

            node.addAttributeObserver(attributeObserver)
        }

        override fun onShutdown() {
            node.removeAttributeObserver(attributeObserver)
        }

    }

}

fun Optional<NodeManager<UaNode>>.addNode(node: UaNode) {
    this.ifPresent { it.addNode(node) }
}

fun DemoNamespace.addFolderNode(parentNodeId: NodeId, name: String): UaFolderNode {
    val folderNode = UaFolderNode(
        server,
        parentNodeId.resolve(name),
        QualifiedName(namespaceIndex, name),
        LocalizedText(name)
    )

    nodeManager.addNode(folderNode)

    folderNode.inverseReferenceTo(
        parentNodeId,
        Identifiers.HasComponent
    )

    return folderNode
}

fun DemoNamespace.addVariableNode(
    parentNodeId: NodeId,
    name: String,
    nodeId: NodeId = parentNodeId.resolve(name),
    dataType: BuiltinDataType = BuiltinDataType.Int32
): UaVariableNode {

    val variableNode = UaVariableNode.UaVariableNodeBuilder(server).run {
        setNodeId(nodeId)
        setAccessLevel(Unsigned.ubyte(AccessLevel.getMask(AccessLevel.READ_WRITE)))
        setUserAccessLevel(Unsigned.ubyte(AccessLevel.getMask(AccessLevel.READ_WRITE)))
        setBrowseName(QualifiedName(namespaceIndex, name))
        setDisplayName(LocalizedText.english(name))
        setDataType(dataType.nodeId)
        setTypeDefinition(Identifiers.BaseDataVariableType)
        setMinimumSamplingInterval(100.0)
        setValue(DataValue(Variant(dataType.defaultValue())))

        build()
    }

    nodeManager.addNode(variableNode)

    variableNode.inverseReferenceTo(
        parentNodeId,
        Identifiers.HasComponent
    )

    return variableNode
}
