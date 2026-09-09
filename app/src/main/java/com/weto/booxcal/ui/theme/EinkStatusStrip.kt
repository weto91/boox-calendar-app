package com.weto.booxcal.ui.theme

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import androidx.compose.ui.res.stringResource
import com.weto.booxcal.R
import com.weto.booxcal.util.rememberDateFormat
import com.weto.booxcal.util.rememberLocale
import com.weto.booxcal.util.format

private val clock: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

private class BatteryInfo(val percent: Int, val charging: Boolean) {
    companion object {
        fun from(intent: Intent): BatteryInfo? {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level < 0 || scale <= 0) return null
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            return BatteryInfo((level * 100) / scale, charging)
        }
    }
}

/**
 * Barra superior propia: hora, fecha y batería.
 *
 * Existe porque en el Boox la barra de estado de Android puede no verse: el
 * sistema la esconde para la app, o deja su franja en blanco. Es lo que hacen
 * las apps del propio Onyx, que pintan la suya. Se actualiza con los avisos
 * del sistema (cambio de minuto, batería), sin ningún temporizador propio.
 */
@Composable
fun EinkStatusStrip(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    var battery by remember { mutableStateOf<BatteryInfo?>(null) }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_BATTERY_CHANGED) {
                    BatteryInfo.from(intent)?.let { battery = it }
                } else {
                    now = LocalDateTime.now()
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        // Son difusiones del sistema, que no puede mandar nadie más; el
        // receptor se declara exportado para que lleguen en Android 13+.
        val sticky = ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        if (sticky?.action == Intent.ACTION_BATTERY_CHANGED) {
            BatteryInfo.from(sticky)?.let { battery = it }
        }
        now = LocalDateTime.now()
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    Row(
        modifier
            .fillMaxWidth()
            .height(28.dp)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = now.format(clock),
            style = MaterialTheme.typography.labelLarge,
            color = Eink.Black,
        )
        Text(
            text = now.toLocalDate().format(rememberDateFormat(R.string.pattern_date_short), capitalize = true, locale = rememberLocale()),
            style = MaterialTheme.typography.labelMedium,
            color = Eink.Graphite,
            modifier = Modifier.padding(start = 10.dp),
        )
        Spacer(Modifier.weight(1f))
        battery?.let { info ->
            if (info.charging) {
                Text(
                    text = stringResource(R.string.status_charging),
                    style = MaterialTheme.typography.labelMedium,
                    color = Eink.Graphite,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            BatteryGlyph(info.percent)
            Text(
                text = "${info.percent} %",
                style = MaterialTheme.typography.labelLarge,
                color = Eink.Black,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
    EinkDivider(color = Eink.Hairline)
}

/** Pila horizontal: contorno, borne a la derecha y relleno según la carga. */
@Composable
private fun BatteryGlyph(percent: Int) {
    Canvas(Modifier.size(width = 22.dp, height = 11.dp)) {
        val stroke = 1.5.dp.toPx()
        val nubWidth = 2.dp.toPx()
        val bodyWidth = size.width - nubWidth - 1.dp.toPx()
        val corner = CornerRadius(2.dp.toPx())
        drawRoundRect(
            color = Eink.Black,
            topLeft = Offset(stroke / 2f, stroke / 2f),
            size = Size(bodyWidth - stroke, size.height - stroke),
            cornerRadius = corner,
            style = Stroke(stroke),
        )
        drawRect(
            color = Eink.Black,
            topLeft = Offset(bodyWidth + 1.dp.toPx(), size.height * 0.28f),
            size = Size(nubWidth, size.height * 0.44f),
        )
        val inset = stroke + 1.dp.toPx()
        val fillWidth = (bodyWidth - inset * 2f) * (percent.coerceIn(0, 100) / 100f)
        if (fillWidth > 0f) {
            drawRect(
                color = Eink.Black,
                topLeft = Offset(inset, inset),
                size = Size(fillWidth, size.height - inset * 2f),
            )
        }
    }
}
