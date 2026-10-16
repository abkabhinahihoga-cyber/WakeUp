package com.aditya.wakeup

import androidx.annotation.RawRes

data class RingtoneItem(
    val id: String,
    val title: String,
    val description: String,
    val category: String,
    @get:RawRes val resId: Int
)

object Ringtones {
    val items: List<RingtoneItem> = listOf(
        RingtoneItem("dawn", "Soft Dawn", "Gentle ambient morning chimes", "Gentle", R.raw.dawn),
        RingtoneItem("morning_rise", "Morning Rise", "Intensifying harmonic melodic chimes", "Gentle", R.raw.morning_rise),
        RingtoneItem("orbit", "Orbit Bells", "Bright resonant bells with gentle rhythm", "Gentle", R.raw.orbit),
        RingtoneItem("pulse", "Bright Pulse", "Uplifting melodic pulse cadence", "Gentle", R.raw.pulse),
        RingtoneItem("metal_bell", "Metal Bell", "Acoustic bell chime with resonant overtones", "Classic", R.raw.metal_bell),
        RingtoneItem("mechanical_clock", "Mechanical Clock", "Crisp mechanical clockwork chime", "Classic", R.raw.mechanical_clock),
        RingtoneItem("classic_alarm", "Classic Alarm", "Retro twin-bell clock alarm cadence", "Classic", R.raw.classic_alarm),
        RingtoneItem("double_beep", "Double Beep", "Sharp electronic dual-beep pattern", "Modern", R.raw.double_beep),
        RingtoneItem("digital_alert", "Digital Alert", "Crisp multi-frequency electronic trill", "Modern", R.raw.digital_alert),
        RingtoneItem("radar_sweep", "Radar Sweep", "Recurrent frequency sweep with ping decay", "Modern", R.raw.radar_sweep),
        RingtoneItem("rapid_wake", "Rapid Wake", "Fast staccato high-energy rhythm", "Urgent", R.raw.rapid_wake),
        RingtoneItem("emergency_pulse", "Emergency Pulse", "Urgent alternating dual-tone pulse", "Urgent", R.raw.emergency_pulse),
        RingtoneItem("rising_siren", "Rising Siren", "Escalating attention-grabbing siren sweep", "Urgent", R.raw.rising_siren),
        RingtoneItem("deep_buzz", "Deep Buzz", "Rich forceful harmonic buzzer tone", "Urgent", R.raw.deep_buzz),
        RingtoneItem("high_alert", "High Alert", "Penetrating high-frequency alert pattern", "Urgent", R.raw.high_alert)
    )

    private val idMap: Map<String, RingtoneItem> = items.associateBy { it.id }

    fun get(id: String): RingtoneItem? = idMap[id]

    fun isBundled(id: String): Boolean = id in idMap

    fun titleOf(id: String, fallback: String = "Custom sound"): String = idMap[id]?.title ?: fallback

    val categories: List<String> = listOf("All", "Gentle", "Classic", "Modern", "Urgent")
}
