package net.jamesjennison.klippercompanion

/**
 * Bambu status reports shared by BambuAmsTraysTest and BambuAmsMappingTest. Both are CONSTRUCTED from Bambu Studio's
 * parsing rules (the Bambu AMS research report's fixtures F4 and F5), not captured from a printer.
 */
object BambuAmsFixtures {
    /** F4: an X1C with AMS 0 (PLA red in slot 1), AMS 1 (PLA green in slot 2) and AMS HT 128 (PA-CF), the HT feeding. */
    val F4 = """{"print":{"ams":{"ams_exist_bits":"13","tray_exist_bits":"10021","tray_now":"128","tray_tar":"128",
        "ams":[
         {"id":"0","info":"1001","tray":[{"id":"0","tray_info_idx":"GFA00","tray_type":"PLA","tray_color":"FF0000FF"},{"id":"1"},{"id":"2"},{"id":"3"}]},
         {"id":"1","info":"1001","tray":[{"id":"0"},{"id":"1","tray_info_idx":"GFA00","tray_type":"PLA","tray_color":"00FF00FF"},{"id":"2"},{"id":"3"}]},
         {"id":"128","info":"1004","tray":[{"id":"0","tray_info_idx":"GFN03","tray_type":"PA-CF","tray_color":"000000FF"}]}]},
        "vt_tray":{"id":"254","tray_type":"","tray_color":"00000000"}}}"""

    /**
     * F5: an H2D. AMS 2 Pro id 0 on the right nozzle (extruder 0) with PLA white in slot 1, AMS id 1 on the left
     * (extruder 1) with ABS black in slot 1, PETG blue on the right external holder (255), the left one (254) empty;
     * extruder 0 current, feeding AMS 0 slot 0.
     */
    val F5 = """{"print":{
        "ams":{"ams_exist_bits":"3","tray_exist_bits":"11","ams":[
         {"id":"0","info":"2003","tray":[{"id":"0","tray_info_idx":"GFA00","tray_type":"PLA","tray_color":"FFFFFFFF"},{"id":"1"},{"id":"2"},{"id":"3"}]},
         {"id":"1","info":"2101","tray":[{"id":"0","tray_info_idx":"GFB00","tray_type":"ABS","tray_color":"000000FF"},{"id":"1"},{"id":"2"},{"id":"3"}]}]},
        "vir_slot":[{"id":"255","tray_info_idx":"GFL99","tray_type":"PETG","tray_color":"0000FFFF"},
                    {"id":"254","tray_type":"","tray_color":"00000000"}],
        "device":{"extruder":{"state":2,"info":[
          {"id":0,"info":2,"temp":0,"snow":0,"spre":65535,"star":0,"stat":0},
          {"id":1,"info":0,"temp":0,"snow":65535,"spre":65535,"star":65535,"stat":0}]}}}}"""
}
