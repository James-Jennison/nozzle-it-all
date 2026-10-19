package net.jamesjennison.klippercompanion

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element

class EnvironmentalManifestTest {
    @Test fun optInMonitoringMustNotMakeBluetoothOrLocationMandatory() {
        val manifest = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .map { File(it, "app/src/main/AndroidManifest.xml") }.first { it.isFile }
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val features = factory.newDocumentBuilder().parse(manifest).getElementsByTagName("uses-feature")
        val android = "http://schemas.android.com/apk/res/android"
        for (name in listOf("android.hardware.bluetooth", "android.hardware.bluetooth_le", "android.hardware.location")) {
            val declarations = (0 until features.length).map { features.item(it) as Element }
                .filter { it.getAttributeNS(android, "name") == name }
            assertEquals("one declaration for $name", 1, declarations.size)
            assertEquals("optional feature $name", "false", declarations.single().getAttributeNS(android, "required"))
        }
    }
}
