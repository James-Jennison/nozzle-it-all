package net.jamesjennison.klippercompanion

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.*

@Composable fun HeaterPanel(state:ScreenState,execute:(PrinterCommand,Int)->Unit,close:()->Unit,
    factory:(String)->HeaterReader={Moonraker(it)}) {
    val scope=rememberCoroutineScope()
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    val reader=remember(state.address,state.generation){factory(state.address)}
    var foreground by remember {mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))}
    var heater by remember {mutableStateOf("heater_bed")}
    var value by remember {mutableStateOf("0")}
    var notice by remember {mutableStateOf("")}
    var pending by remember {mutableStateOf<PrinterCommand?>(null)}
    var preparedAt by remember {mutableLongStateOf(0)}
    var busy by remember {mutableStateOf(false)}
    var job by remember {mutableStateOf<Job?>(null)}
    var readEpoch by remember {mutableIntStateOf(0)}
    fun invalidate(){readEpoch++;pending=null;notice=""}
    DisposableEffect(reader,lifecycle) {
        val observer=LifecycleEventObserver {_,event ->
            foreground=lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if(event==Lifecycle.Event.ON_STOP){readEpoch++;job?.cancel();reader.close();pending=null;notice="";busy=false}
        }
        lifecycle.addObserver(observer)
        onDispose {lifecycle.removeObserver(observer);job?.cancel();reader.close()}
    }
    LaunchedEffect(state.connected,state.generation,state.snapshot?.activeExtruder,state.snapshot?.state) {
        readEpoch++;job?.cancel();pending=null;notice="";busy=false
    }
    val enabled=foreground && state.connected && state.snapshot?.ready==true && state.snapshot.state in HeaterControls.idleStates && !state.busy && !busy
    AlertDialog(onDismissRequest=close,title={Text("Heater controls")},confirmButton={TextButton(close){Text("Close")}},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("Printer: ${state.address}")
            Text("Choose a target, review the exact command, then confirm. Only idle-printer heating is supported here.")
            FilterChip(heater=="heater_bed",{heater="heater_bed";invalidate()},enabled=!busy,label={Text("Bed")})
            val active=state.snapshot?.activeExtruder.orEmpty()
            if(active.isNotEmpty())FilterChip(heater==active,{heater=active;invalidate()},enabled=!busy,label={Text("Active nozzle · $active")})
            OutlinedTextField(value,{value=it;invalidate()},enabled=!busy,label={Text("Target °C (0 turns off)")},singleLine=true,modifier=Modifier.testTag("heater-value"))
            FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                listOf("Off" to "0","PLA example" to if(heater=="heater_bed")"60" else "200","PETG example" to if(heater=="heater_bed")"80" else "230").forEach {(label,target)->
                    TextButton({value=target;invalidate()},enabled=!busy){Text(label)}
                }
            }
            Button({
                invalidate();busy=true
                val ticket=readEpoch
                val request=HeaterRequest(heater,value)
                job=scope.launch {
                    try {
                        val observed=withContext(Dispatchers.IO){reader.heaterStatus(request.heater)}
                        ensureActive()
                        if(ticket!=readEpoch)return@launch
                        pending=HeaterControls.prepare(request,observed)
                        preparedAt=System.nanoTime()/1_000_000
                        notice="Current ${observed.temperature}°C; configured range ${observed.minimum}–${observed.maximum}°C."
                    }catch(e:CancellationException){throw e}catch(e:Exception){if(ticket==readEpoch)notice=e.message?:"Cannot verify heater."}
                    finally {if(ticket==readEpoch)busy=false}
                }
            },enabled=enabled,modifier=Modifier.testTag("review-heater")){Text(if(busy)"Checking…" else "Review heater command")}
            if(notice.isNotEmpty())Text(notice,modifier=Modifier.testTag("heater-notice"))
            pending?.let {command ->
                Text(command.title)
                Text(command.arguments.getValue("script"),modifier=Modifier.testTag("heater-script"))
                Text("The heater and printer state will be checked again before sending. Stay with the printer.")
                Button({
                    pending=null
                    if(System.nanoTime()/1_000_000-preparedAt !in 0..5000)notice="Review the command again; this confirmation expired."
                    else execute(command,state.generation)
                },enabled=enabled,modifier=Modifier.testTag("confirm-heater")){Text("Confirm heater command")}
            }
        }
    })
}
