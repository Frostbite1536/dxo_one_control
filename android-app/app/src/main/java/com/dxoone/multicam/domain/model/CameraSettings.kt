package com.dxoone.multicam.domain.model

/**
 * Camera settings that can be applied to DXO One cameras.
 * Based on the JSON-RPC commands from dxo1usb.js.
 */
data class CameraSettings(
    val imageFormat: ImageFormat = ImageFormat.JPEG_ONLY,
    val shootingMode: ShootingMode = ShootingMode.PROGRAM,
    val iso: IsoSetting = IsoSetting.AUTO,
    val aperture: ApertureSetting = ApertureSetting.F1_8,
    val exposureTime: ExposureTimeSetting = ExposureTimeSetting.AUTO,
    val evBias: EvBiasSetting = EvBiasSetting.ZERO,
    val focusMode: FocusMode = FocusMode.AUTO_FOCUS,
    val afMode: AfMode = AfMode.AF_SINGLE,
    val whiteBalanceIntensity: WhiteBalanceIntensity = WhiteBalanceIntensity.MEDIUM,
    val jpegQuality: JpegQuality = JpegQuality.FINE,
    val selfTimer: SelfTimerSetting = SelfTimerSetting.OFF,
    val liveViewEnabled: Boolean = true
)

/**
 * Image format options.
 * - JPEG_ONLY: Standard JPEG output
 * - RAW_PLUS_JPEG: DNG RAW + JPEG
 * - SUPERRAW_PLUS_JPEG: TNR (Temporal Noise Reduction) SuperRAW + JPEG
 */
enum class ImageFormat(val displayName: String, val rawParam: String, val tnrParam: String) {
    JPEG_ONLY("JPEG Only", "off", "off"),
    RAW_PLUS_JPEG("RAW + JPEG", "on", "off"),
    SUPERRAW_PLUS_JPEG("SuperRAW + JPEG", "on", "on");

    companion object {
        fun fromParams(raw: String, tnr: String): ImageFormat {
            return when {
                raw == "on" && tnr == "on" -> SUPERRAW_PLUS_JPEG
                raw == "on" -> RAW_PLUS_JPEG
                else -> JPEG_ONLY
            }
        }
    }
}

/**
 * Shooting mode presets and priority modes.
 */
enum class ShootingMode(val displayName: String, val param: String) {
    PROGRAM("Program (Auto)", "program"),
    APERTURE_PRIORITY("Aperture Priority", "aperture"),
    SHUTTER_PRIORITY("Shutter Priority", "shutter"),
    MANUAL("Manual", "manual"),
    SPORT("Sport", "sport"),
    PORTRAIT("Portrait", "portrait"),
    LANDSCAPE("Landscape", "landscape"),
    NIGHT("Night", "night")
}

/**
 * ISO sensitivity settings.
 */
enum class IsoSetting(val displayName: String, val param: String) {
    AUTO("Auto", "auto"),
    ISO_100("100", "iso100"),
    ISO_200("200", "iso200"),
    ISO_400("400", "iso400"),
    ISO_800("800", "iso800"),
    ISO_1600("1600", "iso1600"),
    ISO_3200("3200", "iso3200"),
    ISO_6400("6400", "iso6400"),
    ISO_12800("12800", "iso12800"),
    ISO_25600("25600", "iso25600"),
    ISO_51200("51200", "iso51200")
}

/**
 * Aperture settings (f-stops).
 */
enum class ApertureSetting(val displayName: String, val param: String) {
    F1_8("f/1.8", "1.8"),
    F2("f/2", "2"),
    F2_2("f/2.2", "2.2"),
    F2_5("f/2.5", "2.5"),
    F2_8("f/2.8", "2.8"),
    F3_2("f/3.2", "3.2"),
    F3_5("f/3.5", "3.5"),
    F4("f/4", "4"),
    F4_5("f/4.5", "4.5"),
    F5("f/5", "5"),
    F5_6("f/5.6", "5.6"),
    F6_3("f/6.3", "6.3"),
    F7_1("f/7.1", "7.1"),
    F8("f/8", "8"),
    F9("f/9", "9"),
    F10("f/10", "10"),
    F11("f/11", "11")
}

/**
 * Exposure time (shutter speed) settings.
 */
enum class ExposureTimeSetting(val displayName: String, val param: String) {
    AUTO("Auto", "auto"),
    T1_8000("1/8000", "1/8000"),
    T1_4000("1/4000", "1/4000"),
    T1_2000("1/2000", "1/2000"),
    T1_1000("1/1000", "1/1000"),
    T1_500("1/500", "1/500"),
    T1_250("1/250", "1/250"),
    T1_125("1/125", "1/125"),
    T1_60("1/60", "1/60"),
    T1_30("1/30", "1/30"),
    T1_15("1/15", "1/15"),
    T1_8("1/8", "1/8"),
    T1_4("1/4", "1/4"),
    T1_2("1/2", "1/2"),
    T1_1("1s", "1/1"),
    T2_1("2s", "2/1"),
    T4_1("4s", "4/1"),
    T8_1("8s", "8/1"),
    T15_1("15s", "15/1"),
    T30_1("30s", "30/1")
}

/**
 * Exposure compensation (EV bias) settings.
 */
enum class EvBiasSetting(val displayName: String, val param: String) {
    MINUS_3("-3.0", "-3.0"),
    MINUS_2_7("-2.7", "-2.7"),
    MINUS_2_3("-2.3", "-2.3"),
    MINUS_2("-2.0", "-2.0"),
    MINUS_1_7("-1.7", "-1.7"),
    MINUS_1_3("-1.3", "-1.3"),
    MINUS_1("-1.0", "-1.0"),
    MINUS_0_7("-0.7", "-0.7"),
    MINUS_0_3("-0.3", "-0.3"),
    ZERO("0", "0"),
    PLUS_0_3("+0.3", "+0.3"),
    PLUS_0_7("+0.7", "+0.7"),
    PLUS_1("+1.0", "+1.0"),
    PLUS_1_3("+1.3", "+1.3"),
    PLUS_1_7("+1.7", "+1.7"),
    PLUS_2("+2.0", "+2.0"),
    PLUS_2_3("+2.3", "+2.3"),
    PLUS_2_7("+2.7", "+2.7"),
    PLUS_3("+3.0", "+3.0")
}

/**
 * Focus mode settings.
 */
enum class FocusMode(val displayName: String, val param: String) {
    AUTO_FOCUS("Auto Focus", "af"),
    MANUAL_FOCUS("Manual Focus", "mf")
}

/**
 * Auto-focus mode settings.
 */
enum class AfMode(val displayName: String, val param: String) {
    AF_SINGLE("Single Shot (AF-S)", "af-s"),
    AF_CONTINUOUS("Continuous (AF-C)", "af-c"),
    AF_ON_DEMAND("On Demand (AF-OD)", "af-od")
}

/**
 * Auto white balance intensity.
 */
enum class WhiteBalanceIntensity(val displayName: String, val param: String) {
    OFF("Off", "off"),
    SLIGHT("Slight", "slight"),
    MEDIUM("Medium", "medium"),
    STRONG("Strong", "strong")
}

/**
 * JPEG quality settings.
 */
enum class JpegQuality(val displayName: String, val param: String) {
    FINE("Fine (100%)", "100"),
    NORMAL("Normal (95%)", "95"),
    BASIC("Basic (70%)", "70")
}

/**
 * Self-timer settings.
 */
enum class SelfTimerSetting(val displayName: String, val param: String) {
    OFF("Off", "0"),
    TIMER_2S("2 seconds", "2"),
    TIMER_10S("10 seconds", "10")
}
