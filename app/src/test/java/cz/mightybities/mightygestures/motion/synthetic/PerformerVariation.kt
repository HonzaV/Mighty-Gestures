package cz.mightybities.mightygestures.motion.synthetic

/**
 * Per-seed "performer" variability (ADR 0008 "Synthetic trace corpus and calibration"): the same
 * nominal gesture performed slightly differently each time, so a template built from one seed and
 * matched against another exercises the matcher's tolerance rather than a bit-identical replay.
 *
 * Ranges follow spec 0001's "Performer variability": tempo/amplitude ±30 %, grip tilt ±15°,
 * physiological tremor (8–12 Hz, small amplitude), a 0–300 ms lead-in/tail of handling. These
 * ranges are *assumed* (not cited to a specific measurement), as spec 0001 allows for parameters
 * without a published source.
 */
data class PerformerVariation(
    val tempoScale: Float,
    val amplitudeScale: Float,
    val gripTiltDegreesX: Float,
    val gripTiltDegreesY: Float,
    val tremorAmplitudeRadPerSecond: Float,
    val tremorFrequencyHz: Float,
    val leadInSeconds: Float,
    val tailSeconds: Float,
) {
    companion object {
        fun sample(noise: NoiseSource) =
            PerformerVariation(
                tempoScale = noise.uniform(0.7f, 1.3f),
                amplitudeScale = noise.uniform(0.7f, 1.3f),
                gripTiltDegreesX = noise.uniform(-15f, 15f),
                gripTiltDegreesY = noise.uniform(-15f, 15f),
                tremorAmplitudeRadPerSecond = noise.uniform(0.02f, 0.08f),
                tremorFrequencyHz = noise.uniform(8f, 12f),
                leadInSeconds = noise.uniform(0f, 0.3f),
                tailSeconds = noise.uniform(0f, 0.3f),
            )

        /** No variation: the reference performance used when recording "the" template itself. */
        val NONE =
            PerformerVariation(
                tempoScale = 1f,
                amplitudeScale = 1f,
                gripTiltDegreesX = 0f,
                gripTiltDegreesY = 0f,
                tremorAmplitudeRadPerSecond = 0f,
                tremorFrequencyHz = 10f,
                leadInSeconds = 0f,
                tailSeconds = 0f,
            )
    }
}
