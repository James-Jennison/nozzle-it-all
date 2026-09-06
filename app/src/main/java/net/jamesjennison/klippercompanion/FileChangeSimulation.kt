package net.jamesjennison.klippercompanion

/** In-memory test server; intentionally has no URL, filesystem, or PrinterService. */
class FileChangeSimulation {
    enum class Operation { UPLOAD, RENAME, DELETE }
    enum class Fault { NONE, INTERRUPTED, LOST_ACK }
    data class Draft(val id:Long,val operation:Operation,val source:String,val destination:String,val bytes:Int)
    data class Listing(val revision:Long,val names:List<String>)
    private data class Pending(val draft:Draft,val revision:Long,val payload:ByteArray?)
    private val files=linkedMapOf("example.gcode" to "G90\nG1 X1\n".toByteArray())
    private var revision=0L
    private var sequence=0L
    private var pending:Pending?=null
    private var closed=false
    @Synchronized fun list():Listing {check(!closed);return Listing(revision,files.keys.sorted())}
    @Synchronized fun contents(name:String):ByteArray? {check(!closed);return files[name]?.copyOf()}
    @Synchronized fun prepare(operation:Operation,source:String,destination:String,payload:ByteArray?=null):Draft {
        check(!closed);pending=null
        if(operation!=Operation.UPLOAD) {FileTransfer.validate(source);require(files.containsKey(source)){"Source file no longer exists."}}
        if(operation!=Operation.DELETE) {FileTransfer.validate(destination);require(!files.containsKey(destination)){"Destination already exists. Choose a different name."}}
        if(operation==Operation.UPLOAD) {
            require(payload!=null && payload.size in 1..MAX_BYTES){"Simulation upload must contain 1 byte to 1 MiB."}
            require(files.size<100 && files.values.sumOf{it.size.toLong()}+payload.size<=16L*MAX_BYTES){"Simulation storage limit reached."}
        }
        val draft=Draft(++sequence,operation,source,destination,if(operation==Operation.UPLOAD)payload!!.size else 0)
        pending=Pending(draft,revision,if(operation==Operation.UPLOAD)payload!!.copyOf()else null)
        return draft
    }
    /** One-shot confirmation. This simulator's revision check is NOT a Moonraker guarantee. */
    @Synchronized fun confirm(id:Long,fault:Fault=Fault.NONE):String {
        check(!closed)
        val item=pending
        require(item!=null && item.draft.id==id){"Confirmation expired. Review the operation again."}
        pending=null
        require(item.revision==revision){"File list changed. Refresh and review again."}
        if(fault==Fault.INTERRUPTED) throw IllegalStateException("Transfer interrupted before commit. No simulated files changed.")
        val d=item.draft
        when(d.operation) {
            Operation.UPLOAD -> {check(d.destination !in files);files[d.destination]=item.payload!!}
            Operation.RENAME -> {check(d.destination !in files);files[d.destination]=files.remove(d.source)?:error("Source missing.")}
            Operation.DELETE -> check(files.remove(d.source)!=null)
        }
        revision++
        if(fault==Fault.LOST_ACK) throw IllegalStateException("Outcome unknown: acknowledgement lost. Refresh files before reviewing any further operation. No retry sent.")
        return "Simulated ${d.operation.name.lowercase()} completed."
    }
    @Synchronized fun cancel() {pending=null}
    @Synchronized fun simulateOtherClient() {
        check(!closed)
        val name="other-client.gcode";val data="G90\n".toByteArray()
        require(name in files || files.size<100){"Simulation storage limit reached."}
        require(files.values.sumOf{it.size.toLong()}-(files[name]?.size?:0)+data.size<=16L*MAX_BYTES){"Simulation storage limit reached."}
        files[name]=data;revision++
    }
    @Synchronized fun close() {pending=null;files.clear();closed=true}
    companion object {const val MAX_BYTES=1024*1024}
}
