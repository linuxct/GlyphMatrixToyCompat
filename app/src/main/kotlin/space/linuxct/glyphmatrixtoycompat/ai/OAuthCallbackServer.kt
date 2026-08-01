package space.linuxct.glyphmatrixtoycompat.ai

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.ServerSocket

private const val CALLBACK_PORT    = 1455
private const val CALLBACK_TIMEOUT = 600_000

suspend fun waitForOAuthCode(expectedState: String): String = withContext(Dispatchers.IO) {
    ServerSocket(CALLBACK_PORT, 1, InetAddress.getByName("127.0.0.1")).use { server ->
        server.soTimeout = CALLBACK_TIMEOUT
        server.accept().use { socket ->
            val request = socket.getInputStream().bufferedReader().readLine() ?: ""
            val path = request.removePrefix("GET ").substringBefore(" HTTP")
            val params = Uri.parse("http://localhost$path")
            val returnedState = params.getQueryParameter("state") ?: ""
            val code = params.getQueryParameter("code")

            val html = "<html><body><p>Authentication complete. You may close this window.</p></body></html>"
            socket.getOutputStream().write(
                "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${html.length}\r\nConnection: close\r\n\r\n$html"
                    .toByteArray()
            )

            if (returnedState != expectedState) error("OAuth state mismatch")
            code ?: error("No authorization code in callback")
        }
    }
}