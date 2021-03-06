package com.digitalpetri.opcua.server.sampling

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.eclipse.milo.opcua.sdk.server.AbstractLifecycle
import org.eclipse.milo.opcua.sdk.server.api.DataItem
import org.eclipse.milo.opcua.stack.core.StatusCodes
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue
import org.slf4j.LoggerFactory

abstract class SampledDataItem(
    protected val item: DataItem,
    private val scope: CoroutineScope
) : AbstractLifecycle() {

    @Volatile
    var samplingEnabled: Boolean = true

    private var tick: TickManager.Tick? = null

    override fun onStartup() {
        scope.launch {
            item.setValue(sampleInitialValue(System.currentTimeMillis()))

            synchronized(this) {
                if (super.isRunning()) {
                    try {
                        tick = TickManager.registerForTick(scope, item.samplingInterval.toLong()) { tick(it) }
                    } catch (t: Throwable) {
                        LoggerFactory.getLogger(javaClass).error(
                            "Error registering tick for item=${item.readValueId} " +
                                "samplingInterval=${item.samplingInterval}",
                            t
                        )

                        throw t
                    }
                }
            }
        }
    }

    override fun onShutdown(): Unit = synchronized(this) {
        tick?.cancel()
    }

    private suspend fun tick(currentTime: Long) {
        if (samplingEnabled) {
            try {
                item.setValue(sampleCurrentValue(currentTime))
            } catch (t: Throwable) {
                LoggerFactory.getLogger(javaClass)
                    .error("Error sampling value for ${item.readValueId}", t)

                item.setValue(DataValue(StatusCodes.Bad_InternalError))
            }
        }
    }

    fun modifyRate(newRate: Double) {
        try {
            tick?.modify(newRate.toLong())
        } catch (t: Throwable) {
            LoggerFactory.getLogger(javaClass).error(
                "Error modifying rate for item=${item.readValueId} " +
                    "samplingInterval=${item.samplingInterval}",
                t
            )
        }
    }

    protected abstract suspend fun sampleCurrentValue(currentTime: Long): DataValue

    protected open suspend fun sampleInitialValue(currentTime: Long): DataValue = sampleCurrentValue(currentTime)

}
