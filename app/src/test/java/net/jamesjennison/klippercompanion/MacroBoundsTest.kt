package net.jamesjennison.klippercompanion

import java.math.BigDecimal
import org.junit.Assert.*
import org.junit.Test

class MacroBoundsTest {
    private fun command(definition:String,value:String)=MacroTools.command("TEST",MacroTools.definitions(definition),mapOf("A" to value)).arguments.getValue("script")
    @Test fun rejectsValuesThatRoundOntoEitherBoundary() {
        for(value in listOf("300.00000000000000000000000001","-0.00000000000000000000000001")) {
            assertThrows(IllegalArgumentException::class.java){command("A=0,300,200",value)}
        }
        assertEquals("TEST A=300",command("A=0,300,200","300.000"))
        assertEquals("TEST A=0",command("A=0,300,200","-0.0"))
    }
    @Test fun retainsExactDefinitionBoundsAndRejectsRoundedDefaults() {
        val low="0.10000000000000000000000001"
        assertThrows(IllegalArgumentException::class.java){command("A=$low,1,$low","0.1")}
        assertEquals("TEST A=$low",command("A=$low,1,$low",low))
        assertThrows(IllegalArgumentException::class.java){MacroTools.definitions("A=$low,1,0.1")}
        assertThrows(IllegalArgumentException::class.java){MacroTools.definitions("A=0,1000000.00000000000000001,0")}
    }
    @Test fun scientificDefinitionsProduceUsablePlainDefaultsWithoutExpansionAbuse() {
        val p=MacroTools.definitions("A=0,1e2,1e-7").single()
        val raw=p.default.stripTrailingZeros().toPlainString()
        assertEquals("0.0000001",raw)
        assertEquals("TEST A=0.0000001",command("A=0,1e2,1e-7",raw))
        for(value in listOf("1e-2147483647","1e2147483647","NaN","Infinity")) {
            assertThrows(IllegalArgumentException::class.java){MacroTools.definitions("A=0,1,$value")}
        }
    }
    @Test fun constructedParametersCannotInjectNamesOrBypassDefinitionLimits() {
        assertThrows(IllegalArgumentException::class.java){MacroParameter("A\nG28",BigDecimal.ZERO,BigDecimal.ONE,BigDecimal.ZERO)}
        assertThrows(IllegalArgumentException::class.java){MacroParameter("A",BigDecimal.ONE,BigDecimal.ZERO,BigDecimal.ZERO)}
        val p=MacroTools.definitions("A=0,1,0").single()
        assertThrows(IllegalArgumentException::class.java){MacroTools.command("TEST",listOf(p,p),mapOf("A" to "0"))}
    }
}
