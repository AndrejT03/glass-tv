package com.andrej.glasstv.remote

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.andrej.glasstv.GlassApp
import com.andrej.glasstv.inputs.InputsKeyService
import com.andrej.glasstv.ui.DisplayFont
import com.andrej.glasstv.ui.LocalStrings
import com.andrej.glasstv.ui.Motion
import com.andrej.glasstv.ui.OverlayWindow
import com.andrej.glasstv.ui.Palette
import com.andrej.glasstv.ui.TextFont
import com.andrej.glasstv.ui.stringsFor
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Which phones may use the server: the ones that have shown they can see the TV. A phone asks to
 * pair, the TV shows a four-digit code over whatever is on screen (as Apple TV does for the Remote
 * app), and the phone sends the code back for a token, which it keeps in its keychain and sends with
 * every request after that. The TV keeps only the tokens' hashes. A code lasts two minutes, and five
 * wrong ones end the try.
 */
class Pairing(private val context: Context) {
    private val store = context.getSharedPreferences("remote", Context.MODE_PRIVATE)
    private val random = SecureRandom()
    private val main = Handler(Looper.getMainLooper())
    private val card = PairingCard(context)
    private val expire = Runnable { cancel() }

    // Guarded by this.
    private var code: String? = null
    private var tries = 0

    /** This TV, for a phone to know it again when its address changes. */
    val tvId: String = store.getString(ID, null) ?: hex(8).also { store.edit().putString(ID, it).apply() }

    sealed interface Answer {
        class Paired(val token: String) : Answer
        data object Wrong : Answer
        data object Over : Answer
    }

    /** A phone asks to pair: a new code, on the TV's screen. */
    @Synchronized
    fun start() {
        val next = "%04d".format(random.nextInt(10_000))
        code = next
        tries = 0
        main.removeCallbacks(expire)
        main.postDelayed(expire, CODE_MS)
        main.post { card.show(next) }
    }

    /** The phone sends the code it was shown; [device] names it among the TV's paired phones. */
    @Synchronized
    fun confirm(given: String, device: String): Answer {
        val wanted = code ?: return Answer.Over
        if (!MessageDigest.isEqual(given.trim().toByteArray(), wanted.toByteArray())) {
            tries++
            if (tries < MAX_TRIES) return Answer.Wrong
            cancel()
            return Answer.Over
        }
        cancel()
        val token = hex(32)
        val label = device.replace(Regex("[\t\n]"), " ").take(60)
        val phones = listOf("${sha256(token)}\t$label\t${System.currentTimeMillis()}") + phones()
        store.edit().putString(TOKENS, phones.take(MAX_PHONES).joinToString("\n")).apply()
        return Answer.Paired(token)
    }

    /** No code any more: the phone gave up, it paired, or the time ran out. */
    @Synchronized
    fun cancel() {
        code = null
        main.removeCallbacks(expire)
        main.post { card.hide() }
    }

    fun authorized(token: String?): Boolean {
        if (token.isNullOrEmpty()) return false
        val hash = sha256(token).toByteArray()
        return phones().any { MessageDigest.isEqual(it.substringBefore('\t').toByteArray(), hash) }
    }

    /** A phone unpairs itself. */
    @Synchronized
    fun forget(token: String) {
        val hash = sha256(token)
        store.edit().putString(TOKENS, phones().filterNot { it.startsWith("$hash\t") }.joinToString("\n")).apply()
    }

    private fun phones() = store.getString(TOKENS, "").orEmpty().split('\n').filter { it.isNotBlank() }

    private fun hex(bytes: Int) = ByteArray(bytes).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    private fun sha256(text: String) =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        const val ID = "id"
        const val TOKENS = "tokens"
        const val CODE_MS = 120_000L
        const val MAX_TRIES = 5
        const val MAX_PHONES = 8
    }
}

/**
 * The code on the TV while a phone pairs: a card in the middle of a dimmed screen, over any app, in
 * a window of Glass's accessibility service (as the control center is over other apps). Without the
 * service a toast shows the code instead, again every few seconds. The window is made for each
 * pairing and let go after it: pairing is rare, and a window kept for it would cost memory.
 */
private class PairingCard(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var window: OverlayWindow? = null
    private var code by mutableStateOf("")
    private var shown by mutableStateOf(false)
    private var toasting = false
    private val toastAgain = Runnable { toast() }
    private val drop = Runnable {
        window?.let { runCatching { it.destroy() } }
        window = null
    }

    fun show(code: String) {
        this.code = code
        main.removeCallbacks(drop)
        val service = InputsKeyService.running
        if (service == null) {
            toasting = true
            toast()
            return
        }
        val w = window ?: OverlayWindow(service, 1f, Gravity.START) { PairingCode(this.code, shown) }.also { window = it }
        w.show()
        shown = true
    }

    fun hide() {
        toasting = false
        main.removeCallbacks(toastAgain)
        if (!shown) return
        shown = false
        window?.hide(afterMs = 260)
        main.postDelayed(drop, 1_000)
    }

    private fun toast() {
        if (!toasting) return
        val strings = stringsFor(GlassApp.instance.prefs.language)
        Toast.makeText(context, "${strings.pairTitle}: $code", Toast.LENGTH_LONG).show()
        main.postDelayed(toastAgain, 3_600)
    }
}

/** The card: what it is for, the four digits in boxes as on Apple TV, and where they go. */
@Composable
private fun PairingCode(code: String, shown: Boolean) {
    val strings = LocalStrings.current
    val appear = remember { Animatable(0f) }
    LaunchedEffect(shown) {
        if (shown) appear.animateTo(1f, tween(320, easing = Motion.EaseOut)) else appear.animateTo(0f, tween(200))
    }
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) { drawRect(Shade.copy(alpha = Shade.alpha * appear.value)) }
        Column(
            Modifier
                .align(Alignment.Center)
                .graphicsLayer {
                    val p = appear.value
                    alpha = p
                    scaleX = 0.94f + 0.06f * p
                    scaleY = scaleX
                    // No offscreen buffer: on the TV's tile-based GPU one flushes the whole screen.
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                }
                .background(CardFill, RoundedCornerShape(30.dp))
                .border(1.dp, CardRim, RoundedCornerShape(30.dp))
                .padding(horizontal = 40.dp, vertical = 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BasicText(strings.pairTitle, style = TextStyle(fontFamily = DisplayFont, fontWeight = FontWeight.Bold, fontSize = 24.sp, color = Palette.Label))
            Spacer(Modifier.height(22.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                code.forEach { digit ->
                    Box(Modifier.size(width = 58.dp, height = 72.dp).background(DigitFill, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                        BasicText(
                            digit.toString(),
                            style = TextStyle(fontFamily = DisplayFont, fontWeight = FontWeight.SemiBold, fontSize = 44.sp, color = Palette.Label),
                        )
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            BasicText(
                strings.pairHint,
                style = TextStyle(fontFamily = TextFont, fontWeight = FontWeight.Medium, fontSize = 15.sp, color = Palette.Secondary, textAlign = TextAlign.Center),
            )
        }
    }
}

private val Shade = Color(0x99000000)
private val CardFill = Color(0xFF1F1F23)
private val CardRim = Color(0x24FFFFFF)
private val DigitFill = Color(0x1FFFFFFF)
