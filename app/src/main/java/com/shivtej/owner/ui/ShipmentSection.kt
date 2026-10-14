package com.shivtej.owner.ui

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shivtej.owner.App
import com.shivtej.owner.net.Api
import com.shivtej.owner.net.ApiError
import com.shivtej.owner.util.SecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** One Shiprocket courier with its freight charge and delivery days. */
data class CourierOption(val id: String, val name: String, val rate: Double, val etd: String)

// ---------- admin login session (saved encrypted on this phone, same as the AI key) ----------

private fun saveSession(ctx: Context, r: JSONObject): String {
    val access = r.getString("access_token")
    val expiresAt = System.currentTimeMillis() + r.optLong("expires_in", 3600L) * 1000L
    SecretStore.setAdminSession(
        ctx,
        JSONObject()
            .put("access_token", access)
            .put("refresh_token", r.optString("refresh_token"))
            .put("expires_at", expiresAt)
            .toString()
    )
    return access
}

/** Renews the login with the saved refresh token. Null = the owner has to log in again. */
private fun renewSession(ctx: Context): String? {
    val rt = try { JSONObject(SecretStore.adminSession(ctx)).optString("refresh_token") } catch (e: Exception) { "" }
    if (rt.isBlank()) return null
    return try {
        saveSession(ctx, Api.adminRefresh(rt))
    } catch (e: Exception) {
        SecretStore.setAdminSession(ctx, "")
        null
    }
}

/** The login token to use now: the saved one while it is fresh, otherwise a renewed one. Null = log in again. */
private fun freshToken(ctx: Context): String? {
    val s = try { JSONObject(SecretStore.adminSession(ctx)) } catch (e: Exception) { return null }
    val access = s.optString("access_token")
    if (access.isBlank()) return null
    if (System.currentTimeMillis() < s.optLong("expires_at", 0L) - 60_000L) return access
    return renewSession(ctx)
}

/** Runs a Shiprocket action with the saved login. If the server rejects the login, it renews once and tries again. */
private fun shipWithLogin(ctx: Context, body: JSONObject): JSONObject {
    val token = freshToken(ctx) ?: throw ApiError(401, "NOT_LOGGED_IN", "NOT_LOGGED_IN")
    return try {
        Api.shiprocketCall(token, body)
    } catch (e: ApiError) {
        if (e.status != 401) throw e
        val again = renewSession(ctx) ?: throw e
        Api.shiprocketCall(again, body)
    }
}

/** Saves the OTP login token. Kept 6 days here (the server allows 7); after that the owner logs in again. */
private fun saveOtpSession(ctx: Context, token: String) {
    val expiresAt = System.currentTimeMillis() + 6L * 86_400_000L
    SecretStore.setAdminSession(
        ctx,
        JSONObject().put("access_token", token).put("refresh_token", "").put("expires_at", expiresAt).toString()
    )
}

private fun otpMsg(e: Exception): String {
    val c = (e as? ApiError)?.code.orEmpty()
    return when {
        c.contains("NOT_ADMIN") -> "This mobile number is not an admin number."
        c.contains("WRONG_CODE") -> "Wrong code. Check the WhatsApp message and try again."
        c.contains("EXPIRED") -> "The code has expired. Tap Change mobile number and send again."
        c.contains("TOO_MANY") -> "Too many tries. Wait a few minutes and try again."
        c.contains("BAD_MOBILE") -> "Enter a valid 10-digit mobile number."
        else -> "Could not connect. Check internet and try again."
    }
}

private fun shipMsg(e: Exception): String {
    val c = (e as? ApiError)?.code ?: e.message.orEmpty()
    return when {
        c.contains("NOT_LOGGED_IN") -> "Please log in again."
        c.contains("NOT_ADMIN") -> "This login is not an admin account."
        c.contains("NOT_READY") -> "Save the order as Confirmed or Packed first, then try again."
        c.contains("PAYMENT_NOT_CONFIRMED") -> "Payment is not received yet."
        c.contains("PINCODE_MISSING") -> "The customer's pincode is missing."
        c.contains("ADDRESS_INCOMPLETE") -> "The customer's address is incomplete. Fix it in the website admin."
        c.contains("NOT_INDIA_SHIPPING") -> "Shiprocket is only for India addresses."
        c.contains("ORDER_NOT_FOUND") -> "Order not found on the server. Sync and try again."
        c.contains("SHIPROCKET_FAILED") -> "Shiprocket did not accept it now. Try again in a minute."
        // The code in brackets says what went wrong (a server code such as SERVER, or the phone-side error such as SocketTimeoutException).
        else -> "Could not connect. Check internet and try again. [${c.ifBlank { (e as? ApiError)?.status?.toString() ?: e.javaClass.simpleName }}]"
    }
}

/**
 * Shiprocket shipping on the order screen. Log in once with your admin mobile number and the WhatsApp code,
 * load the couriers with their price and delivery days, pick one, then "Create shipment".
 * The server saves the AWB and courier on the order. The manual Courier and Tracking fields stay as they are.
 */
@Composable
fun ShipmentSection(
    app: App,
    orderId: String,
    orderStatus: String,
    savedAwb: String,
    onShipped: (awb: String, courier: String, pickup: Boolean) -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var loggedIn by remember { mutableStateOf(SecretStore.adminSession(ctx).isNotBlank()) }
    var mobile by remember { mutableStateOf("") }
    var otp by remember { mutableStateOf("") }
    var otpSent by remember { mutableStateOf(false) }
    var couriers by remember(orderId) { mutableStateOf<List<CourierOption>>(emptyList()) }
    var picked by remember(orderId) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var note by remember(orderId) { mutableStateOf("") }

    Column(Modifier.fillMaxWidth()) {
        if (savedAwb.isNotBlank()) {
            Text("Shipment created. AWB: $savedAwb", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }

        if (!loggedIn) {
            Text(
                "To ship with Shiprocket, log in with your admin mobile number. A 6-digit code comes on WhatsApp. It is saved on this phone only.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!otpSent) {
                OutlinedTextField(
                    value = mobile, onValueChange = { mobile = it.filter { c -> c.isDigit() }.take(10) },
                    label = { Text("Admin mobile number") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                Button(
                    onClick = {
                        scope.launch {
                            busy = true
                            note = ""
                            try {
                                withContext(Dispatchers.IO) { Api.adminOtpSend(mobile) }
                                otpSent = true
                                note = "Code sent on WhatsApp."
                            } catch (e: Exception) {
                                note = otpMsg(e)
                            }
                            busy = false
                        }
                    },
                    enabled = !busy && mobile.length == 10,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                ) { Text(if (busy) "Sending..." else "Send OTP on WhatsApp") }
            } else {
                OutlinedTextField(
                    value = otp, onValueChange = { otp = it.filter { c -> c.isDigit() }.take(6) },
                    label = { Text("6-digit code") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                Button(
                    onClick = {
                        scope.launch {
                            busy = true
                            note = ""
                            try {
                                val token = withContext(Dispatchers.IO) { Api.adminOtpVerify(mobile, otp) }
                                saveOtpSession(ctx, token)
                                loggedIn = SecretStore.adminSession(ctx).isNotBlank()
                                if (loggedIn) {
                                    otp = ""
                                    otpSent = false
                                } else {
                                    note = "Could not save the login on this phone."
                                }
                            } catch (e: Exception) {
                                note = otpMsg(e)
                            }
                            busy = false
                        }
                    },
                    enabled = !busy && otp.length == 6,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                ) { Text(if (busy) "Checking..." else "Log in for shipping") }
                TextButton(onClick = { otpSent = false; otp = ""; note = "" }) { Text("Change mobile number") }
            }
        } else {
            OutlinedButton(
                onClick = {
                    scope.launch {
                        busy = true
                        note = ""
                        try {
                            val r = withContext(Dispatchers.IO) {
                                shipWithLogin(ctx, JSONObject().put("action", "couriers").put("order_id", orderId))
                            }
                            val arr = r.optJSONArray("couriers")
                            val list = mutableListOf<CourierOption>()
                            if (arr != null) {
                                for (i in 0 until arr.length()) {
                                    arr.optJSONObject(i)?.let { c ->
                                        list += CourierOption(
                                            c.optString("courier_id"), c.optString("name"),
                                            c.optDouble("rate", 0.0), c.optString("etd")
                                        )
                                    }
                                }
                            }
                            couriers = list
                            picked = ""
                            if (list.isEmpty()) note = "No courier can deliver to this pincode right now."
                        } catch (e: Exception) {
                            note = shipMsg(e)
                        }
                        busy = false
                    }
                },
                enabled = !busy && savedAwb.isBlank(),
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
            ) { Text(if (busy) "Please wait..." else "Load courier prices") }

            couriers.forEach { c ->
                Row(
                    Modifier.fillMaxWidth().clickable { picked = c.id }.padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = picked == c.id, onClick = { picked = c.id })
                    Column(Modifier.weight(1f)) {
                        Text(c.name, fontWeight = FontWeight.SemiBold)
                        val days = if (c.etd.isBlank()) "" else "${c.etd} days · "
                        Text(days + money(app, c.rate), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            if (couriers.isNotEmpty()) {
                Button(
                    onClick = {
                        scope.launch {
                            busy = true
                            note = ""
                            try {
                                val r = withContext(Dispatchers.IO) {
                                    shipWithLogin(ctx, JSONObject().put("order_id", orderId).put("courier_id", picked))
                                }
                                val awb = r.optString("awb")
                                val courier = r.optString("courier")
                                val pickup = r.optBoolean("pickup", true)
                                onShipped(awb, courier, pickup)
                                note = if (pickup) "Shipment created. AWB $awb" else "AWB $awb saved, but the pickup request failed. Schedule the pickup in Shiprocket."
                                toast(ctx, "Shipment created")
                            } catch (e: Exception) {
                                note = shipMsg(e)
                            }
                            busy = false
                        }
                    },
                    enabled = !busy && picked.isNotBlank() && savedAwb.isBlank(),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                ) { Text("Create shipment") }
            }

            TextButton(onClick = {
                SecretStore.setAdminSession(ctx, "")
                loggedIn = false
                couriers = emptyList()
                picked = ""
            }) { Text("Log out of shipping login") }
        }

        if (note.isNotBlank()) {
            Text(note, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        }
    }
}
