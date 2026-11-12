package com.charles.livecaptionn.compatibility

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.StatFs
import com.charles.livecaptionn.models.OfflineModelCatalog

enum class CompatibilityTier(val label: String) {
    PASSED("Fully Supported"),
    BORDERLINE("Limited Resources (Warning)"),
    UNSUPPORTED("Unsupported for On-Device")
}

data class DeviceSpecs(
    val totalRamMb: Long,
    val cpuCores: Int,
    val is64Bit: Boolean,
    val freeStorageMb: Long,
    val deviceModel: String,
    val androidVersion: String,
    val tier: CompatibilityTier,
    val reasons: List<String>
) {
    val canRunOnDevice: Boolean
        get() = tier != CompatibilityTier.UNSUPPORTED

    val requiresWarning: Boolean
        get() = tier == CompatibilityTier.BORDERLINE
}

object DeviceCompatibilityChecker {
    // Thresholds:
    // Minimum RAM required for Vosk + ML Kit combined: 2.5 GB (2560 MB)
    // Recommended RAM for smooth streaming + large models: 4 GB (3840 MB)
    const val MIN_RAM_MB = 2560L
    const val REC_RAM_MB = 3840L

    // Derived from the actual pinned 2.12 GB offline speech+translation catalog,
    // plus the same 16 MiB reserve enforced by OfflineModelStore.
    const val DOWNLOAD_RESERVE_BYTES = 16L * 1024 * 1024
    val MIN_STORAGE_MB: Long = (OfflineModelCatalog.files.sumOf { it.sizeBytes } +
        DOWNLOAD_RESERVE_BYTES + 999_999L) / 1_000_000L
    val REC_STORAGE_MB: Long = MIN_STORAGE_MB + 512L

    // CPU Cores: 4 minimum for background decoding + Compose UI thread
    const val MIN_CPU_CORES = 4
    const val REC_CPU_CORES = 6

    fun inspect(context: Context, simulatedTier: CompatibilityTier? = null, modelsAlreadyVerified: Boolean = false): DeviceSpecs {
        val totalRamMb = getSystemTotalRamMb(context)
        val cpuCores = Runtime.getRuntime().availableProcessors()
        val is64Bit = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Process.is64Bit()
        } else {
            Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()
        }
        val freeStorageMb = getFreeStorageMb(context)
        val deviceModel = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"
        val androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

        val (detectedTier, reasons) = evaluateTier(
            totalRamMb = totalRamMb,
            cpuCores = cpuCores,
            is64Bit = is64Bit,
            freeStorageMb = freeStorageMb,
            abi = Build.SUPPORTED_ABIS.firstOrNull() ?: if (is64Bit) "64-bit" else "32-bit",
            modelsAlreadyVerified = modelsAlreadyVerified
        )

        val effectiveTier = simulatedTier ?: detectedTier

        val finalReasons = if (simulatedTier != null && simulatedTier != detectedTier) {
            listOf("Simulated tier: ${simulatedTier.label} (Hardware detected: ${detectedTier.label})") + reasons
        } else {
            reasons
        }

        return DeviceSpecs(
            totalRamMb = totalRamMb,
            cpuCores = cpuCores,
            is64Bit = is64Bit,
            freeStorageMb = freeStorageMb,
            deviceModel = deviceModel,
            androidVersion = androidVersion,
            tier = effectiveTier,
            reasons = finalReasons
        )
    }

    fun evaluateTier(
        totalRamMb: Long,
        cpuCores: Int,
        is64Bit: Boolean,
        freeStorageMb: Long,
        abi: String = if (is64Bit) "arm64-v8a" else "armeabi-v7a",
        modelsAlreadyVerified: Boolean = false
    ): Pair<CompatibilityTier, List<String>> {
        val reasons = mutableListOf<String>()
        var isUnsupported = false
        var isBorderline = false

        if (totalRamMb < MIN_RAM_MB) {
            isUnsupported = true
            reasons.add("Total RAM ($totalRamMb MB) is below the 2.5 GB minimum required for on-device AI.")
        } else if (totalRamMb < REC_RAM_MB) {
            isBorderline = true
            reasons.add("RAM ($totalRamMb MB) meets minimum reqs, but 4 GB+ is recommended for optimal latency.")
        }

        if (cpuCores < MIN_CPU_CORES) {
            isUnsupported = true
            reasons.add("CPU has $cpuCores core(s); at least 4 cores are required for real-time transcription.")
        } else if (cpuCores == MIN_CPU_CORES) {
            isBorderline = true
            reasons.add("CPU has 4 cores. Multitasking or large models may experience audio delays.")
        }

        if (!is64Bit) {
            isBorderline = true
            reasons.add("Device runs 32-bit architecture ($abi). High-capacity models are restricted.")
        }

        if (!modelsAlreadyVerified) {
            if (freeStorageMb < 0L) {
                isBorderline = true
                reasons.add("Free internal storage could not be measured; verify space before downloading.")
            } else if (freeStorageMb < MIN_STORAGE_MB) {
                isUnsupported = true
                reasons.add("Free internal storage ($freeStorageMb MB) is below the $MIN_STORAGE_MB MB required for the current offline model files and download reserve.")
            } else if (freeStorageMb < REC_STORAGE_MB) {
                isBorderline = true
                reasons.add("Storage space ($freeStorageMb MB) meets the download minimum but has limited headroom.")
            }
        }

        val detectedTier = when {
            isUnsupported -> CompatibilityTier.UNSUPPORTED
            isBorderline -> CompatibilityTier.BORDERLINE
            else -> CompatibilityTier.PASSED
        }

        return Pair(detectedTier, reasons)
    }

    private fun getSystemTotalRamMb(context: Context): Long {
        val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return 0L
        val memInfo = ActivityManager.MemoryInfo()
        actManager.getMemoryInfo(memInfo)
        return memInfo.totalMem / (1024 * 1024)
    }

    private fun getFreeStorageMb(context: Context): Long {
        return try {
            val path = context.filesDir
            val stat = StatFs(path.path)
            (stat.availableBlocksLong * stat.blockSizeLong) / (1024 * 1024)
        } catch (_: Exception) {
            -1L
        }
    }
}
