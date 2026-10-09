package com.shivtej.owner.util

/** Turns what the owner types in Shop settings into a proper web address (used for the QR codes on the bill). */
object Links {
    fun website(raw: String): String {
        val t = raw.trim()
        if (t.isEmpty()) return ""
        return if (t.startsWith("http://", true) || t.startsWith("https://", true)) t else "https://$t"
    }

    /** "@shivtej", "shivtej", "instagram.com/shivtej" or a full link all give the same Instagram address. */
    fun instagram(raw: String): String {
        var t = raw.trim()
        if (t.isEmpty()) return ""
        if (t.startsWith("http://", true) || t.startsWith("https://", true)) return t
        if (t.contains("instagram.com", true)) return "https://" + t.removePrefix("www.")
        t = t.removePrefix("@").trim()
        return if (t.isEmpty() || t.contains(" ")) "" else "https://instagram.com/$t"
    }

    /** Opens WhatsApp chat with this number (India +91 added when only 10 digits are given). */
    fun whatsapp(rawMobile: String): String {
        val d = rawMobile.filter { it in '0'..'9' }
        if (d.length < 10) return ""
        val full = if (d.length == 10) "91$d" else d
        return "https://wa.me/$full"
    }

    /** Text shown under a QR / in the contact block: the address without "https://". */
    fun shown(url: String): String = url.removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('/')
}
