package com.bori.core.ai.local

/** How well one on-device model suits a phone. */
enum class FitRating { RECOMMENDED, SLOW, NOT_ENOUGH_RAM, NOT_ENOUGH_STORAGE }

data class ModelFit(
    val model: LocalModel,
    val rating: FitRating,
    /** Plain-language explanation shown under the model. */
    val reason: String,
    /** The single model the advisor would choose for this phone. */
    val isBestPick: Boolean = false,
)

/**
 * Rates every [LocalModel] against a phone's [DeviceSpecs]. RAM decides whether a model
 * loads and how smoothly it runs (thresholds live on [LocalModel]); storage decides
 * whether it can be downloaded at all.
 */
object ModelAdvisor {

    /** Space to leave free beyond the file itself, so the phone isn't left completely full. */
    private const val STORAGE_HEADROOM_GB = 1.0

    private const val GB = 1024.0 * 1024 * 1024

    fun recommend(specs: DeviceSpecs, alreadyDownloaded: Set<LocalModel> = emptySet()): List<ModelFit> {
        val fits = LocalModel.entries.map { model -> rate(model, specs, model in alreadyDownloaded) }
        // Best pick: the most capable model that runs comfortably; else the smallest that runs at all
        val best = fits.lastOrNull { it.rating == FitRating.RECOMMENDED }
            ?: fits.firstOrNull { it.rating == FitRating.SLOW }
        return fits.map { if (it === best) it.copy(isBestPick = true) else it }
    }

    private fun rate(model: LocalModel, specs: DeviceSpecs, downloaded: Boolean): ModelFit {
        val ram = specs.ramGb
        val ramText = "${formatGb(ram)} GB of RAM"
        if (ram < model.minRamGb) {
            return ModelFit(model, FitRating.NOT_ENOUGH_RAM, "Needs at least ${model.minRamGb} GB of RAM; this phone has $ramText.")
        }
        // A model already on the phone needs no more space
        val neededGb = model.fileBytes / GB + STORAGE_HEADROOM_GB
        if (!downloaded && specs.freeStorageGb < neededGb) {
            val short = neededGb - specs.freeStorageGb
            return ModelFit(
                model,
                FitRating.NOT_ENOUGH_STORAGE,
                "Needs ${model.sizeLabel} free plus some spare room; free up about ${formatGb(short)} GB to download it.",
            )
        }
        return if (ram >= model.goodRamGb && !specs.isLowRam) {
            ModelFit(model, FitRating.RECOMMENDED, "Runs comfortably with $ramText.")
        } else {
            ModelFit(
                model,
                FitRating.SLOW,
                "Will run with $ramText, but expect slow replies and other apps closing in the background " +
                    "(${model.goodRamGb} GB+ is comfortable).",
            )
        }
    }

    private fun formatGb(value: Double): String =
        if (value >= 10 || value % 1.0 == 0.0) "%.0f".format(value) else "%.1f".format(value)
}
