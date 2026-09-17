package net.jamesjennison.klippercompanion

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class FanDispatchTest {
    private val dispatcher=StandardTestDispatcher()
    @Before fun before(){Dispatchers.setMain(dispatcher)}
    @After fun after(){Dispatchers.resetMain()}
    private class Fake:PrinterService {
        override val address="http://fixture.local/"
        var observed=FanStatus("fan_generic e1_fan","extruder1",true,"standby",0.0,true,true)
        var sent=0
        var beforeRead:(()->Unit)?=null
        override fun snapshot()=PrinterSnapshot(true,"standby",activeExtruder="extruder1")
        override fun catalog()=Catalog(emptyList(),emptyList(),emptyList(),emptyList())
        override fun image(camera:Camera)=byteArrayOf()
        override fun fanStatus(fan:String):FanStatus{beforeRead?.invoke();return observed}
        override fun command(command:PrinterCommand){sent++}
        override fun close(){}
    }
    @Test fun changedCapabilityOrToolAndTamperedScriptNeverDispatch()=runTest(dispatcher) {
        val fake=Fake();val baseline=fake.observed;val request=FanRequest(baseline.fan,"25","extruder1");val command=FanControls.prepare(request,baseline)
        val model=PrinterModel(serviceFactory={fake},clock={100_000},io=dispatcher)
        model.foreground(true);model.connect(fake.address);runCurrent()
        for(changed in listOf(baseline.copy(activeTool="extruder2"),baseline.copy(configured=false),baseline.copy(noMacroOverride=false),baseline.copy(printState="printing"))){
            fake.observed=changed;model.execute(command,model.state.value.generation);runCurrent();assertEquals(0,fake.sent)
        }
        fake.observed=baseline;model.execute(command.copy(arguments=mapOf("script" to "G28")),model.state.value.generation);runCurrent();assertEquals(0,fake.sent)
        model.execute(command,model.state.value.generation);runCurrent();assertEquals(1,fake.sent);model.disconnect()
    }
    @Test fun backgroundDuringPreflightCancelsWithoutDispatch()=runTest(dispatcher) {
        val fake=Fake();val command=FanControls.prepare(FanRequest(fake.observed.fan,"0","extruder1"),fake.observed)
        val model=PrinterModel(serviceFactory={fake},clock={100_000},io=dispatcher)
        model.foreground(true);model.connect(fake.address);runCurrent();fake.beforeRead={model.foreground(false)}
        model.execute(command,model.state.value.generation);runCurrent();assertEquals(0,fake.sent);assertFalse(model.state.value.busy);model.disconnect()
    }
}
