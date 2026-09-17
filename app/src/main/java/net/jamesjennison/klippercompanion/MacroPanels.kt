package net.jamesjennison.klippercompanion

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable fun MacroEditor(name:String,options:MacroOptions,close:()->Unit,save:(MacroOptions)->Unit) {
    var group by remember {mutableStateOf(options.group)};var definitions by remember {mutableStateOf(options.parameters)};var error by remember {mutableStateOf("")}
    AlertDialog(onDismissRequest=close,title={Text("Organize $name")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(group,{group=it.take(40)},label={Text("Group")},singleLine=true)
        Text("Define numeric parameters from your macro documentation. Nothing is inferred or executed.")
        Text("One per line: NAME=min,max,default")
        OutlinedTextField(definitions,{definitions=it.take(4000)},label={Text("Parameter definitions")})
        if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error)
    }},confirmButton={TextButton({runCatching {MacroTools.definitions(definitions)}.fold({save(options.copy(group=group.trim(),parameters=definitions));close()},{error=it.message?:"Invalid definitions"})}){Text("Save")}},dismissButton={TextButton(close){Text("Cancel")}})
}
@Composable fun MacroForm(name:String,options:MacroOptions,close:()->Unit,prepare:(PrinterCommand)->Unit) {
    val parsed=remember(name,options.parameters) {runCatching {MacroTools.definitions(options.parameters)}}
    val definitions=parsed.getOrDefault(emptyList())
    var values by remember(name,options.parameters) {mutableStateOf(definitions.associate {it.name to it.default.stripTrailingZeros().toPlainString()})}
    var error by remember {mutableStateOf(parsed.exceptionOrNull()?.message?:"")}
    AlertDialog(onDismissRequest=close,title={Text("Prepare $name")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        definitions.forEach { p -> OutlinedTextField(values[p.name]?:"",{values=values+(p.name to it.take(32))},label={Text(p.name)},supportingText={Text("${p.minimum.toPlainString()} to ${p.maximum.toPlainString()}")},singleLine=true) }
        if(definitions.isEmpty())Text("No parameters defined. The next step shows the command confirmation.")
        if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error)
    }},confirmButton={TextButton({runCatching {MacroTools.command(name,definitions,values)}.fold({prepare(it);close()},{error=it.message?:"Invalid values"})},enabled=parsed.isSuccess){Text("Review command")}},dismissButton={TextButton(close){Text("Cancel")}})
}
