package com.farside.workspace

import androidx.compose.runtime.Composable
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource

@Composable
internal fun MacConnectionQualityPanel(q: MacConnectionQuality) {
    val state = when (q.state) {
        MacConnectionQuality.State.STARTING -> R.string.mac_quality_starting
        MacConnectionQuality.State.RECEIVING -> R.string.mac_quality_receiving
        MacConnectionQuality.State.DEGRADED -> R.string.mac_quality_degraded
        MacConnectionQuality.State.STALLED -> R.string.mac_quality_stalled
        MacConnectionQuality.State.FALLBACK -> R.string.mac_quality_fallback
    }
    val color = when (q.state) {
        MacConnectionQuality.State.RECEIVING -> Color(0xff9bdea7)
        MacConnectionQuality.State.FALLBACK -> Color(0xffffa09b)
        else -> Color(0xffffd185)
    }
    ConnectionInfoRow(stringResource(R.string.mac_quality_health), stringResource(state), color)
    ConnectionInfoRow(stringResource(R.string.mac_quality_video), "${q.videoPackets} / ${q.frames}")
    ConnectionInfoRow(stringResource(R.string.mac_quality_missing), "${q.missing} / ${q.gaps}")
    ConnectionInfoRow(stringResource(R.string.mac_quality_reordered), "${q.reordered} / ${q.duplicates}")
    ConnectionInfoRow(stringResource(R.string.mac_quality_drops), "${q.localDrops} / ${q.decoderDrops}")
    ConnectionInfoRow(stringResource(R.string.mac_quality_skipped), "${q.skipped}")
    ConnectionInfoRow(stringResource(R.string.mac_quality_recovery), "${q.recoveries}")
    ConnectionInfoRow(stringResource(R.string.mac_quality_age), q.frameAgeMs?.let { stringResource(R.string.mac_quality_age_value, it / 1000.0) } ?: "—")
    q.rtcp?.let { r ->
        ConnectionInfoRow(stringResource(R.string.mac_quality_rtcp_rx), "${r.control} / ${r.video}")
        ConnectionInfoRow(stringResource(R.string.mac_quality_rtcp_auth), "${r.authenticated} / ${r.unverified}")
        ConnectionInfoRow(stringResource(R.string.mac_quality_rtcp_reports), "${r.sr} / ${r.rr} / ${r.feedback}")
        ConnectionInfoRow(stringResource(R.string.mac_quality_rtcp_rejected), "${r.malformed} / ${r.duplicates}")
    }
    Text(stringResource(R.string.mac_quality_totals),
        color = Color.White.copy(alpha = .65f),
        style = MaterialTheme.typography.labelSmall
    )
}
