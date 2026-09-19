package net.jamesjennison.klippercompanion

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable fun RemoteAccessHelpPanel(close: () -> Unit) {
    AlertDialog(onDismissRequest = close, title = { Text("Connecting away from home") }, confirmButton = { TextButton(close) { Text("Close") } }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Your printer's local address only works on the same network. To reach it from elsewhere, put it behind one of these, then enter that address and (if Moonraker requires one) an API key above.")
            Text("Tailscale (recommended)", fontWeight = FontWeight.Bold)
            Text("Install Tailscale on the machine running Moonraker and on this phone, then use the 100.x.x.x address or *.ts.net name it assigns. Zero router configuration, WireGuard-encrypted, and this app accepts that address over plain http:// since it never leaves your private tailnet.", style = MaterialTheme.typography.bodySmall)
            Text("Cloudflare Tunnel", fontWeight = FontWeight.Bold)
            Text("A free Quick Tunnel gives Moonraker a public https:// address without opening any port on your router. Use that address here with https://.", style = MaterialTheme.typography.bodySmall)
            Text("Port forwarding", fontWeight = FontWeight.Bold)
            Text("Forward a port on your router to Moonraker and use a dynamic DNS name if your ISP doesn't give you a fixed IP. This exposes the printer directly to the internet, so set an API key and use https:// if at all possible.", style = MaterialTheme.typography.bodySmall)
            Text("OctoEverywhere", fontWeight = FontWeight.Bold)
            Text("A third-party cloud relay with a free tier; no router setup, but your traffic depends on their service staying up and trustworthy. Follow their setup, then use the address they give you.", style = MaterialTheme.typography.bodySmall)
            Text("Whichever you choose, an API key (set above) is the only thing standing between your printer and anyone who reaches that address — set one for anything other than Tailscale.", style = MaterialTheme.typography.bodySmall)
        }
    })
}
