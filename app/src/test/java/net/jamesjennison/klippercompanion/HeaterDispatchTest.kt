package net.jamesjennison.klippercompanion

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class HeaterDispatchTest {
    private val dispatcher=StandardTestDispatcher()
    @Before fun before(){Dispatchers.setMain(dispatcher)}
    @After fun after(){Dispatchers.resetMain()}
    private class Fake:PrinterService {
        override val address="http://fixture.local/"
        var heater=HeaterStatus("extruder1","extruder1",true,"complete",34.0,0.0,0.0,300.0,true)
        var sent=0
        var beforeHeaterRead:(()->Unit)?=null
        override fun snapshot()=PrinterSnapshot(true,"complete",activeExtruder="extruder1")
        override fun catalog()=Catalog(emptyList(),emptyList(),emptyList(),emptyList())
        override fun image(camera:Camera)=byteArrayOf()
        override fun heaterStatus(heater:String):HeaterStatus {beforeHeaterRead?.invoke();return this.heater}
        override fun command(command:PrinterCommand){sent++}
        override fun close(){}
    }
    @Test fun freshToolChangeRejectsPreviouslyConfirmedHeater()=runTest(dispatcher) {
        val fake=Fake();val command=HeaterControls.prepare(HeaterRequest("extruder1","200"),fake.heater)
        val model=PrinterModel(serviceFactory={fake},clock={100_000},io=dispatcher)
        model.foreground(true);model.connect(fake.address);runCurrent()
        fake.heater=fake.heater.copy(activeTool="extruder2")
        model.execute(command,model.state.value.generation);runCurrent()
        assertEquals(0,fake.sent);assertFalse(model.state.value.busy);model.disconnect()
    }
    @Test fun loweredLimitAndTamperedCommandRejectButFreshConfirmedCommandSendsOnce()=runTest(dispatcher) {
        val fake=Fake();val command=HeaterControls.prepare(HeaterRequest("extruder1","200"),fake.heater)
        val model=PrinterModel(serviceFactory={fake},clock={100_000},io=dispatcher)
        model.foreground(true);model.connect(fake.address);runCurrent()
        fake.heater=fake.heater.copy(maximum=150.0)
        model.execute(command,model.state.value.generation);runCurrent();assertEquals(0,fake.sent)
        fake.heater=fake.heater.copy(maximum=300.0)
        model.execute(command.copy(arguments=mapOf("script" to "G28")),model.state.value.generation);runCurrent();assertEquals(0,fake.sent)
        model.execute(command,model.state.value.generation);runCurrent();assertEquals(1,fake.sent)
        model.disconnect()
    }
    @Test fun backgroundDuringHeaterPreflightNeverDispatches()=runTest(dispatcher) {
        val fake=Fake();val command=HeaterControls.prepare(HeaterRequest("extruder1","40"),fake.heater)
        val model=PrinterModel(serviceFactory={fake},clock={100_000},io=dispatcher)
        model.foreground(true);model.connect(fake.address);runCurrent()
        fake.beforeHeaterRead={model.foreground(false)}
        model.execute(command,model.state.value.generation);runCurrent()
        assertEquals(0,fake.sent);assertFalse(model.state.value.busy);model.disconnect()
    }
}
