package net.jamesjennison.klippercompanion

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.*

@Composable fun FanPanel(state:ScreenState,execute:(PrinterCommand,Int)->Unit,close:()->Unit,
    factory:(String)->FanReader={ a -> state.moonrakerFor(a) },clock:()->Long={System.nanoTime()/1_000_000}) {
    val scope=rememberCoroutineScope()
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    val context=LocalContext.current
    val fanPrefs=remember {context.getSharedPreferences("fan-options",0)}
    val fanKey=remember(state.address) {java.security.MessageDigest.getInstance("SHA-256").digest(state.address.toByteArray()).joinToString(""){"%02x".format(it)}}
    var hiddenFans by remember(fanKey) {mutableStateOf(FanControls.decodeHidden(runCatching {fanPrefs.getString(fanKey,"[]")}.getOrNull()?:"[]"))}
    fun setHidden(name:String,hide:Boolean) {hiddenFans=if(hide)hiddenFans+name else hiddenFans-name;fanPrefs.edit().putString(fanKey,FanControls.encodeHidden(hiddenFans)).apply()}
    var showHiddenFans by remember {mutableStateOf(false)}
    val reader=remember(state.address,state.generation){factory(state.address)}
    var foreground by remember {mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))}
    var fans by remember {mutableStateOf<List<String>>(emptyList())}
    var fan by remember {mutableStateOf("")}
    var value by remember {mutableStateOf("0")}
    var notice by remember {mutableStateOf("")}
    var pending by remember {mutableStateOf<PrinterCommand?>(null)}
    var preparedAt by remember {mutableLongStateOf(0)}
    var busy by remember {mutableStateOf(false)}
    var job by remember {mutableStateOf<Job?>(null)}
    var epoch by remember {mutableIntStateOf(0)}
    fun invalidate(){epoch++;pending=null;notice=""}
    fun reset(){invalidate();job?.cancel();reader.close();busy=false;fans=emptyList();fan=""}
    DisposableEffect(reader,lifecycle) {
        val observer=LifecycleEventObserver {_,event ->
            foreground=lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if(event==Lifecycle.Event.ON_STOP)reset()
        }
        lifecycle.addObserver(observer)
        onDispose {lifecycle.removeObserver(observer);job?.cancel();reader.close()}
    }
    LaunchedEffect(state.connected,state.generation,state.snapshot?.activeExtruder,state.snapshot?.state){reset()}
    val enabled=foreground && state.connected && state.snapshot?.ready==true && state.snapshot.state in FanControls.idleStates && !state.busy && !busy
    AlertDialog(onDismissRequest=close,title={Text("Fan controls")},confirmButton={TextButton(close){Text("Close")}},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("Printer: ${state.address}")
            Text("Select the exact configured fan. Fan names are not mapped to the active nozzle. Automatic heater fans are excluded.")
            Button({
                invalidate();fan="";fans=emptyList();busy=true;val ticket=epoch
                job=scope.launch {
                    try {
                        val found=withContext(Dispatchers.IO){reader.fans()}
                        ensureActive();if(ticket!=epoch)return@launch
                        require(found.size<=64 && found.all(FanControls::validFan)){"Unsupported fan list."}
                        fans=found.distinct();notice=if(fans.isEmpty())"No supported manual fans found." else "Select a fan before reviewing a command."
                    }catch(e:CancellationException){throw e}catch(e:Exception){if(ticket==epoch)notice=e.message?:"Cannot load fans."}
                    finally{if(ticket==epoch)busy=false}
                }
            },enabled=enabled,modifier=Modifier.testTag("load-fans")){Text("Load manual fans")}
            if(fans.isNotEmpty())FilterChip(showHiddenFans,{showHiddenFans=!showHiddenFans},label={Text("Show hidden")},modifier=Modifier.testTag("show-hidden-fans"))
            fans.filter{showHiddenFans || it !in hiddenFans}.forEach {name ->
                FlowRow(verticalArrangement=Arrangement.spacedBy(4.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    FilterChip(fan==name,{fan=name;invalidate()},enabled=!busy,label={Text(name)},modifier=Modifier.testTag("fan-option-$name"))
                    if(name in hiddenFans)Text("Hidden",style=MaterialTheme.typography.bodySmall)
                    TextButton({setHidden(name,name !in hiddenFans)},modifier=Modifier.testTag("hide-fan-$name")){Text(if(name in hiddenFans)"Unhide" else "Hide")}
                }
            }
            if(fan.isNotEmpty())Text("Selected: $fan")
            OutlinedTextField(value,{value=it;invalidate()},enabled=!busy,label={Text("Requested speed % (0 turns off)")},singleLine=true,modifier=Modifier.testTag("fan-value"))
            TextButton({value="0";invalidate()},enabled=!busy){Text("Off")}
            Button({
                invalidate();busy=true;val ticket=epoch
                val request=FanRequest(fan,value,state.snapshot?.activeExtruder.orEmpty())
                job=scope.launch {
                    try {
                        val observed=withContext(Dispatchers.IO){reader.fanStatus(request.fan)}
                        ensureActive();if(ticket!=epoch)return@launch
                        pending=FanControls.prepare(request,observed);preparedAt=clock()
                        notice="Reported output: ${observed.speed?.times(100)}%. This is not an RPM measurement."
                    }catch(e:CancellationException){throw e}catch(e:Exception){if(ticket==epoch)notice=e.message?:"Cannot verify fan."}
                    finally{if(ticket==epoch)busy=false}
                }
            },enabled=enabled && fan in fans,modifier=Modifier.testTag("review-fan")){Text(if(busy)"Checking…" else "Review fan command")}
            if(notice.isNotEmpty())Text(notice,modifier=Modifier.testTag("fan-notice"))
            pending?.let {command ->
                Text(command.title);Text(command.arguments.getValue("script"),modifier=Modifier.testTag("fan-script"))
                Text("Fan and printer state will be checked again before sending. Stay with the printer.")
                Button({
                    pending=null
                    if(clock()-preparedAt !in 0..5000)notice="Review the command again; this confirmation expired."
                    else execute(command,state.generation)
                },enabled=enabled,modifier=Modifier.testTag("confirm-fan")){Text("Confirm fan command")}
            }
        }
    })
}
