package net.jamesjennison.klippercompanion

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/** Local simulator only. No PrinterService, model or execution callback is accepted. */
@Composable fun ControlPreviewPanel(close:()->Unit) {
    var action by remember {mutableStateOf(ControlAction.NOZZLE)}
    var value by remember {mutableStateOf(action.initial)}
    var scenario by remember {mutableStateOf("Idle and warm")}
    var preset by remember {mutableStateOf("PLA example")}
    var result by remember {mutableStateOf<ControlPreview?>(null)}
    var error by remember {mutableStateOf("")}
    fun reset() {result=null;error=""}
    AlertDialog(onDismissRequest=close,title={Text("Preview advanced controls")},confirmButton={TextButton(close){Text("Close")}},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("Simulation only. These controls cannot send commands to your printer. Live controls await printer capability checks and idle-printer acceptance.")
            Text("Simulated printer",style=MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                listOf("Idle and warm","Printing","Paused","Print error","Cold nozzle","Unhomed","Offline","Stale","Unsupported").forEach { name ->
                    FilterChip(scenario==name,{scenario=name;reset()},label={Text(name)})
                }
            }
            Text("Action",style=MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                ControlAction.entries.forEach { choice -> FilterChip(action==choice,{action=choice;value=choice.initial;preset="PLA example";reset()},label={Text(choice.label)}) }
            }
            if(action==ControlAction.NOZZLE || action==ControlAction.BED) {
                Text("Example heating presets — verify material requirements before future live use.")
                FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    listOf("PLA example","PETG example","Heater off").forEach { name ->
                        FilterChip(preset==name,{preset=name;value=when(name){"Heater off"->"0";"PETG example"->if(action==ControlAction.NOZZLE)"230" else "80";else->if(action==ControlAction.NOZZLE)"200" else "60"};reset()},label={Text(name)})
                    }
                }
            }
            OutlinedTextField(value,{value=it;preset="";reset()},label={Text("Value (${action.units})")},singleLine=true,modifier=Modifier.fillMaxWidth().testTag("control-value"))
            Text("Example limits: nozzle 260°C; bed 110°C; XYZ 0–250 mm, position 125 mm. Moves ≤10 mm; extrusion/retraction ≤5 mm. These are simulated, not your printer's limits.")
            Button({
                reset()
                val now=10_000L
                val axes=setOf("x","y","z")
                val state=ControlState(connected=scenario!="Offline",ready=true,printState=when(scenario){"Printing"->"printing";"Paused"->"paused";"Print error"->"error";else->"standby"},observedAt=if(scenario=="Stale")0 else now,generation=1,
                    heaterLimits=if(scenario=="Unsupported")emptyMap() else mapOf("extruder" to 260.0,"heater_bed" to 110.0),fan=scenario!="Unsupported",overrides=scenario!="Unsupported",
                    homed=if(scenario=="Unhomed")emptySet() else axes,positions=axes.associateWith{125.0},minimum=axes.associateWith{0.0},maximum=axes.associateWith{250.0},kinematics=if(scenario=="Unsupported")"unknown" else "cartesian",canExtrude=scenario!="Cold nozzle",extruder=if(scenario=="Unsupported")"" else "extruder")
                runCatching {ControlPlanner.preview(action,value,state,now,1)}.onSuccess{result=it}.onFailure{error=it.message?:"Preview unavailable."}
            },modifier=Modifier.testTag("preview-control")){Text("Check and preview")}
            if(error.isNotBlank()) Text(error,color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("control-error"))
            result?.let {
                Text(it.description,style=MaterialTheme.typography.titleSmall)
                Text(it.script,fontFamily=FontFamily.Monospace,modifier=Modifier.testTag("control-script"))
                Text("Preview only — nothing was sent. A multi-command script is not transactional; interrupted execution would need recovery before any future live rollout.")
            }
        }
    })
}
