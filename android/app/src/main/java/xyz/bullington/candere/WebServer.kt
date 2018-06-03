package xyz.bullington.candere

import android.content.Context
import android.database.sqlite.SQLiteException
import android.os.Handler
import android.widget.Toast

import com.rvirin.onvif.onvifcamera.OnvifDevice

import fi.iki.elonen.NanoHTTPD

import org.json.JSONArray
import org.json.JSONObject

import java.util.*

fun serializeDevice(device: Device): JSONObject {
    val obj = JSONObject()

    obj.put("id", device.getId())
    obj.put("nickname", device.getNickname())
    obj.put("address", device.getAddress())
    obj.put("username", device.getUsername())
    obj.put("password", device.getPassword())
    obj.put("manufacturer", device.getManufacturer())
    obj.put("snapshotUrl", device.getSnapshotUrl())
    obj.put("rtspUrl", device.getRtspUrl())

    return obj
}

fun deserializeDevice(obj: JSONObject): Device {
    // generate id if it doesn't exist
    var id: Int = obj.getInt("id")
    if (id == 0) {
        obj.put("timestamp", Calendar.getInstance()?.timeInMillis)
        id = obj.toString().hashCode()
        obj.remove("timestamp")
    }

    val nickname: String = obj.getString("nickname")
    val address: String = obj.getString("address")
    val username: String = obj.getString("username")
    val password: String = obj.getString("password")
    val manufacturer: String = obj.getString("manufacturer")
    val snapshotUrl: String = obj.getString("snapshotUrl")
    val rtspUrl: String = obj.getString("rtspUrl")

    return Device(id, nickname, address, username, password, manufacturer, snapshotUrl, rtspUrl)
}

fun serializeResponse(response: JSONObject?, data: Array<Pair<String, JSONObject>>): JSONObject {
    val res = JSONObject()

    response?.let { response -> res.put("response", response) }
    data.forEach { (key, value) ->
        res.put(key, value)
    }

    return res
}

fun onvifLogin(
        server: WebServer,
        obj: JSONObject,
        address: String,
        username: String,
        password: String): Boolean {
    val onvif = OnvifDevice(address, username, password)

    // get services
    var onvifRes = onvif.getServices()

    if (!onvifRes.success) {
        return false
    }

    // get device information
    onvifRes = onvif.getDeviceInformation()

    if (!onvifRes.success) {
        return false
    }

    obj.put("manufacturer", onvifRes.parsingUIMessage)

    server.uiHandler.post {
        val toast = Toast.makeText(server.context, "Device information retrieved 👍", Toast.LENGTH_SHORT)
        toast?.show()
    }

    // get device profiles
    onvifRes = onvif.getProfiles()

    if (!onvifRes.success) {
        return false
    }

    val profilesCount = onvif.mediaProfiles.count()
    server.uiHandler.post {
        val toast = Toast.makeText(server.context, "$profilesCount profiles retrieved 😎", Toast.LENGTH_SHORT)
        toast?.show()
    }

    // get snapshot uri
    onvifRes = onvif.getSnapshotURI()

    if (!onvifRes.success) {
        return false
    }

    obj.put("snapshotUrl", onvif.snapshotURI!!)

    server.uiHandler.post {
        val toast = Toast.makeText(server.context, "Snapshot URI retrieved", Toast.LENGTH_SHORT)
        toast?.show()
    }

    // get stream uri
    onvifRes = onvif.getStreamURI()

    if (!onvifRes.success) {
        return false
    }

    obj.put("rtspUrl", onvif.rtspURI!!)

    server.uiHandler.post {
        val toast = Toast.makeText(server.context, "Stream URI retrieved", Toast.LENGTH_SHORT)
        toast?.show()
    }

    return true
}

class WebServer(
        // only use from within ui handler
        internal val context: Context,
        internal val uiHandler: Handler,
        private val db: DeviceDatabase, port: Int
) : NanoHTTPD("127.0.0.1", port) {

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val dbErrorRes = newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                MIME_PLAINTEXT,
                "database error")

        if (uri == "/devices/fetch") {
            try {
                val devices = db.deviceDao().fetch()
                val data = JSONObject()

                data.put("operation", "=")

                val items = JSONArray()

                devices?.forEach { device -> items.put(serializeDevice(device)) }

                data.put("items", items)

                return newFixedLengthResponse(
                        serializeResponse(null, arrayOf(Pair("devices", data))).toString()
                )
            } catch (e: SQLiteException) {
                return dbErrorRes
            }
        }

        if (uri == "/devices/add") {
            val loginFailedRes = newFixedLengthResponse(serializeResponse(
                    JSONObject(mapOf(Pair("success", false))),
                    arrayOf()
            ).toString())

            try {
                val files = HashMap<String, String>()
                session.parseBody(files)

                files.values?.first()?.let { str ->
                    val obj = JSONObject(str)
                    val result = onvifLogin(
                            this,
                            obj,
                            obj.getString("address"),
                            obj.getString("username"),
                            obj.getString("password")
                    )

                    if (!result) return loginFailedRes

                    val device: Device = deserializeDevice(obj)

                    db.deviceDao().add(device)

                    val data = JSONObject()

                    data.put("operation", "+")
                    data.put("item", obj)

                    return newFixedLengthResponse(serializeResponse(
                            JSONObject(mapOf(Pair("success", true))),
                            arrayOf(Pair("devices", data))
                    ).toString())
                }
            } catch (e: SQLiteException) {
                return dbErrorRes
            }
        }

        if (uri == "/devices/refresh") {
            val loginFailedRes = newFixedLengthResponse(serializeResponse(
                    JSONObject(mapOf(Pair("success", false))),
                    arrayOf()
            ).toString())

            try {
                val files = HashMap<String, String>()
                session.parseBody(files)

                files.values?.first()?.let { str ->
                    val obj = JSONObject(str)
                    val result = onvifLogin(
                            this,
                            obj,
                            obj.getString("address"),
                            obj.getString("username"),
                            obj.getString("password")
                    )

                    if (!result) return loginFailedRes

                    val device: Device = deserializeDevice(obj)

                    db.deviceDao().update(device)

                    val devices = db.deviceDao().fetch()

                    val data = JSONObject()
                    val items = JSONArray()

                    devices?.forEach { device -> items.put(serializeDevice(device)) }

                    data.put("operation", "=")
                    data.put("items", items)

                    return newFixedLengthResponse(serializeResponse(
                            JSONObject(mapOf(Pair("success", true))),
                            arrayOf(Pair("devices", data))
                    ).toString())
                }
            } catch (e: SQLiteException) {
                return dbErrorRes
            }
        }

        if (uri == "/devices/remove") {
            try {
                val files = HashMap<String, String>()
                session.parseBody(files)

                files.values?.first().let { str ->
                    val obj = JSONObject(str)
                    val device: Device = deserializeDevice(obj)

                    db.deviceDao().remove(device)

                    val data = JSONObject()

                    data.put("operation", "-")
                    data.put("item", obj)

                    return newFixedLengthResponse(serializeResponse(
                            JSONObject(mapOf(Pair("success", true))),
                            arrayOf(Pair("devices", data))
                    ).toString())
                }
            } catch (e: SQLiteException) {
                return dbErrorRes
            }
        }

        return newFixedLengthResponse(
                Response.Status.NOT_FOUND,
                MIME_PLAINTEXT,
                "not found $uri")
    }
}