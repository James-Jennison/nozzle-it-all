package net.jamesjennison.klippercompanion

import org.junit.Assert.*
import org.junit.Test

class SettingsBackupTest {
    private val printers = listOf(
        PrinterProfile("http://192.168.1.110/", "U1", true, "cam1", "moonraker-key", PrinterKind.SNAPMAKER_U1_PAXX, "", SlicingPrinterModel.SNAPMAKER_U1, "1.6.0"),
        PrinterProfile("192.168.1.77", "P1S", false, "", "ACCESS12", PrinterKind.BAMBU_LAB, "01S00A000000123", SlicingPrinterModel.BAMBU_GENERIC, ""),
    )

    @Test fun aBackupRoundTripsEveryPrinterAndItsSecrets() {
        val file = SettingsBackup.encode(printers, "correct horse".toCharArray())
        assertEquals(printers, SettingsBackup.decode(file, "correct horse".toCharArray()))
    }
    @Test fun theFileNeverContainsTheSecretsInTheClear() {
        val text = String(SettingsBackup.encode(printers, "correct horse".toCharArray()))
        assertFalse(text.contains("moonraker-key")); assertFalse(text.contains("ACCESS12")); assertFalse(text.contains("192.168.1.110"))
    }
    @Test fun aWrongPassphraseIsRefused() {
        val file = SettingsBackup.encode(printers, "correct horse".toCharArray())
        assertThrows(BackupException::class.java) { SettingsBackup.decode(file, "wrong horse!".toCharArray()) }
    }
    @Test fun aTamperedFileIsRefused() {
        val file = String(SettingsBackup.encode(printers, "correct horse".toCharArray())).replace(Regex("\"data\":\"(.)"), "\"data\":\"A")
        val text = if (file == String(SettingsBackup.encode(printers, "correct horse".toCharArray()))) file.replace("\"v\":1", "\"v\":1 ") else file
        assertThrows(BackupException::class.java) { SettingsBackup.decode(text.toByteArray().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }, "correct horse".toCharArray()) }
    }
    @Test fun aShortPassphraseIsRefusedAtCreation() { assertThrows(BackupException::class.java) { SettingsBackup.encode(printers, "short".toCharArray()) } }
    @Test fun nonBackupFilesAreRejectedPolitely() {
        assertThrows(BackupException::class.java) { SettingsBackup.decode("hello".toByteArray(), "correct horse".toCharArray()) }
        assertThrows(BackupException::class.java) { SettingsBackup.decode("{\"app\":\"other\"}".toByteArray(), "correct horse".toCharArray()) }
    }
    @Test fun eachBackupUsesAFreshSaltAndIv() = assertNotEquals(String(SettingsBackup.encode(printers, "correct horse".toCharArray())), String(SettingsBackup.encode(printers, "correct horse".toCharArray())))
}
