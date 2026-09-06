package net.jamesjennison.klippercompanion

import java.math.BigDecimal

/** Deliberately not a PrinterCommand: this development preview cannot be dispatched. */
data class ControlPreview(val description:String, val script:String)
enum class ControlAction(val label:String, val units:String, val initial:String) {
    NOZZLE("Nozzle target","°C","200"), BED("Bed target","°C","60"), FAN("Part cooling fan","%","50"),
    SPEED("Print speed","%","100"), FLOW("Extrusion flow","%","100"),
    X("Move X","mm, signed","1"), Y("Move Y","mm, signed","1"), Z("Move Z","mm, signed","1"),
    EXTRUDE("Extrude / retract","mm, signed","5")
}
data class ControlState(
    val connected:Boolean=false, val ready:Boolean=false, val printState:String="unknown",
    val observedAt:Long=0, val generation:Int=0,
    val heaterLimits:Map<String,Double> = emptyMap(), val fan:Boolean=false, val overrides:Boolean=false,
    val homed:Set<String> = emptySet(), val positions:Map<String,Double> = emptyMap(),
    val minimum:Map<String,Double> = emptyMap(), val maximum:Map<String,Double> = emptyMap(),
    val kinematics:String="unknown", val canExtrude:Boolean=false, val extruder:String="",
)
object ControlPlanner {
    private val idle=setOf("standby","complete","cancelled")
    fun preview(action:ControlAction, raw:String, state:ControlState, now:Long, generation:Int):ControlPreview {
        require(state.connected && state.ready) {"Connect to a ready printer."}
        require(generation==state.generation && state.observedAt>=0 && now>=state.observedAt && now-state.observedAt<=5000) {"Printer state is stale or belongs to another connection."}
        require(state.printState in idle) {"Controls are blocked while printing, paused, in error, or state is unknown."}
        require(raw.length in 1..24 && Regex("-?[0-9]+(?:\\.[0-9]+)?").matches(raw)) {"Enter a plain number without commands or units."}
        val value=raw.toDouble();require(value.isFinite()) {"Enter a finite number."}
        val decimal=BigDecimal(raw)
        val number=decimal.stripTrailingZeros().toPlainString()
        fun range(min:Double,max:Double) {require(decimal >= BigDecimal.valueOf(min) && decimal <= BigDecimal.valueOf(max)) {"Value must be between $min and $max ${action.units}."}}
        fun scoped(move:String):String {
            require(state.overrides) {"Speed/flow capability required for state-preserving motion."}
            return "SAVE_GCODE_STATE NAME=COMPANION_PREVIEW\nM220 S100\nM221 S100\n$move\nRESTORE_GCODE_STATE NAME=COMPANION_PREVIEW"
        }
        val script=when(action) {
            ControlAction.NOZZLE,ControlAction.BED -> {
                val heater=if(action==ControlAction.NOZZLE) "extruder" else "heater_bed"
                val limit=state.heaterLimits[heater]
                require(limit!=null && limit.isFinite() && limit>0 && limit<=500) {"Verified heater limit unavailable."}
                range(0.0,limit)
                "SET_HEATER_TEMPERATURE HEATER=$heater TARGET=$number"
            }
            ControlAction.FAN -> {require(state.fan) {"Part cooling fan unavailable."};range(0.0,100.0);"M106 S${decimal.multiply(BigDecimal("2.55")).stripTrailingZeros().toPlainString()}"}
            ControlAction.SPEED,ControlAction.FLOW -> {require(state.overrides) {"Speed/flow capability unavailable."};if(action==ControlAction.SPEED)range(10.0,200.0) else range(50.0,150.0);"${if(action==ControlAction.SPEED) "M220" else "M221"} S$number"}
            ControlAction.X,ControlAction.Y,ControlAction.Z -> {
                require(state.kinematics in setOf("cartesian","corexy")) {"Movement preview requires supported, verified kinematics."}
                require(state.homed.containsAll(setOf("x","y","z"))) {"Home all axes before movement."}
                range(-10.0,10.0);require(value!=0.0) {"Choose a nonzero distance."}
                val axis=action.name.lowercase();val position=state.positions[axis];val min=state.minimum[axis];val max=state.maximum[axis]
                require(position!=null && min!=null && max!=null && listOf(position,min,max).all{it.isFinite()} && min<max && position in min..max) {"Axis position or travel limits unavailable."}
                require(BigDecimal.valueOf(position).add(decimal) in BigDecimal.valueOf(min)..BigDecimal.valueOf(max)) {"Movement would exceed the axis travel limits."}
                scoped("G91\nG1 ${action.name}$number F${if(action==ControlAction.Z)300 else 1200}")
            }
            ControlAction.EXTRUDE -> {
                require(state.extruder=="extruder") {"Supported active extruder unavailable."}
                require(state.canExtrude) {"Extruder is too cold or temperature permission is unknown."}
                range(-5.0,5.0);require(value!=0.0) {"Choose a nonzero distance."}
                scoped("M83\nG1 E$number F120")
            }
        }
        return ControlPreview("${action.label}: $number ${action.units}",script)
    }
}
