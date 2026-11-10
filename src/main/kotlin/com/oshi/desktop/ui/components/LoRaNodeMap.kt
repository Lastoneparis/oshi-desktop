package com.oshi.desktop.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oshi.desktop.lora.LoRaNodeRoster
import com.oshi.desktop.lora.NodeMapGeometry
import com.oshi.desktop.ui.OshiTheme
import kotlin.math.min

/**
 * __DESKTOP_NODE_PERIMETER_2026_11_07__
 *
 * The node perimeter, drawn.
 *
 * I had closed this as impossible here, on the grounds that the desktop client
 * has no map. That was circular reasoning: there is no map because nobody wrote
 * one, and what was asked for does not need one. Meshtastic's circle says HOW
 * FAR A NODE REACHES; that is a radius around a point, and the only inputs are
 * relative position and metres. Tiles and roads would be pleasant, and are not
 * the feature.
 *
 * So this is a RANGE DIAGRAM, labelled as one rather than dressed up as a map:
 * our radio at the centre, each node placed by true bearing and distance, and
 * each node's reach drawn as the circle that was asked for. No basemap, because
 * drawing nodes over blank space is honest and drawing them over a fake street
 * grid would not be.
 *
 * A relayed node gets a dot and NO circle — its signal strength was measured by
 * whoever relayed it, so a perimeter drawn from it would be centred on the
 * wrong radio. That rule lives in `LoRaCoverage` and is shared with both phones.
 */
@Composable
fun LoRaNodeMap(
    nodes: List<LoRaNodeRoster.Node>,
    myLat: Double?,
    myLon: Double?,
    modifier: Modifier = Modifier,
) {
    val plan = NodeMapGeometry.plan(myLat, myLon, nodes)
    val density = LocalDensity.current
    val brand = OshiTheme.brand
    val ringColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.30f)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

    if (plan == null) {
        // Said plainly rather than drawn empty: a blank diagram looks like "no
        // nodes", and the actual reason is almost always that WE have no fix.
        Text(
            if (myLat == null || myLon == null)
                "Range needs this radio's own position — it has no GPS fix yet."
            else
                "No node has reported a position yet.",
            style = OshiTheme.typography.bodySmall,
            color = labelColor,
            modifier = modifier.padding(OshiTheme.sm),
        )
        return
    }

    Column(modifier) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(260.dp)) {
            val wPx = with(density) { maxWidth.toPx() }
            val hPx = with(density) { maxHeight.toPx() }
            val cx = wPx / 2f
            val cy = hPx / 2f
            // Usable radius, leaving room for labels at the edge.
            val viewR = min(cx, cy) - 18f
            val pxPerM = viewR / plan.spanM.toFloat()
            val step = NodeMapGeometry.scaleStepM(plan.spanM)

            Canvas(Modifier.fillMaxWidth().height(260.dp)) {
                // Scale rings first, so node circles read on top of them.
                var r = step
                while (r <= plan.spanM) {
                    drawCircle(
                        color = ringColor,
                        radius = (r * pxPerM).toFloat(),
                        center = Offset(cx, cy),
                        style = Stroke(width = 1f),
                    )
                    r += step
                }

                for (p in plan.placed) {
                    val x = cx + (p.eastM * pxPerM).toFloat()
                    // North is UP: screen y grows downward, so this subtracts.
                    val y = cy - (p.northM * pxPerM).toFloat()

                    p.radiusM?.let { rad ->
                        drawCircle(
                            color = brand.copy(alpha = 0.13f),
                            radius = (rad * pxPerM).toFloat(),
                            center = Offset(x, y),
                        )
                        drawCircle(
                            color = brand.copy(alpha = 0.55f),
                            radius = (rad * pxPerM).toFloat(),
                            center = Offset(x, y),
                            style = Stroke(width = 1.5f),
                        )
                    }
                    drawCircle(color = brand, radius = 3.5f, center = Offset(x, y))
                }

                // Us, last and distinct: a filled dot with a halo, so it is
                // never mistaken for one of the peers.
                drawCircle(Color.White, radius = 5.5f, center = Offset(cx, cy))
                drawCircle(brand, radius = 3.5f, center = Offset(cx, cy))
            }

            // Labels as real Text rather than painted into the canvas: they
            // stay selectable, they scale with the user's font settings, and a
            // screen reader can reach them.
            for (p in plan.placed) {
                val x = cx + (p.eastM * pxPerM).toFloat()
                val y = cy - (p.northM * pxPerM).toFloat()
                Text(
                    p.name.ifBlank { "node ${p.num}" },
                    style = OshiTheme.typography.bodySmall,
                    color = labelColor,
                    modifier = Modifier.offset(
                        x = with(density) { (x + 6f).toDp() },
                        y = with(density) { (y - 7f).toDp() },
                    ),
                )
            }
            // One ring labelled, not all of them — the step is constant and a
            // diagram crowded with numbers stops being readable.
            Text(
                NodeMapGeometry.formatDistance(step),
                style = OshiTheme.typography.bodySmall,
                color = labelColor,
                modifier = Modifier.offset(
                    x = with(density) { (cx + 3f).toDp() },
                    y = with(density) { (cy - step.toFloat() * pxPerM - 13f).toDp() },
                ),
            )
        }
        Spacer(Modifier.height(OshiTheme.xxs))
        Text(
            "Range diagram \u2014 distance and bearing from this radio. " +
                "Circles are estimated reach; relayed nodes get none.",
            style = OshiTheme.typography.bodySmall,
            color = labelColor,
        )
    }
}
