package local.fridgechef.app

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.math.max
import kotlin.math.roundToInt

// ---------------------------------------------------------------
// 設定の保存場所
// ---------------------------------------------------------------

object Store {
    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        if (!::sp.isInitialized) {
            sp = context.getSharedPreferences("fridgechef", Context.MODE_PRIVATE)
        }
    }

    var model: String
        get() = sp.getString("model", "claude-haiku-5-5") ?: "claude-haiku-5-5"
        set(v) = sp.edit().putString("model", v).apply()

    var timeLimit: Int
        get() = sp.getInt("timeLimit", 0)
        set(v) = sp.edit().putInt("timeLimit", v).apply()

    var depositUSD: Double
        get() = sp.getFloat("depositUSD", 0f).toDouble()
        set(v) = sp.edit().putFloat("depositUSD", v.toFloat()).apply()

    var yenPerDollar: Double
        get() = sp.getFloat("yenPerDollar", 150f).toDouble()
        set(v) = sp.edit().putFloat("yenPerDollar", v.toFloat()).apply()

    // 「いつもある物」
    val defaultStaples = listOf(
        "ご飯", "パン",
        "砂糖", "塩", "酢", "しょうゆ", "みそ",
        "油", "みりん", "酒", "だしの素", "こしょう"
    )

    var staples: List<String>
        get() {
            val raw = sp.getString("staples", null) ?: return defaultStaples
            return runCatching {
                val arr = JSONArray(raw)
                List(arr.length()) { arr.getString(it) }
            }.getOrDefault(defaultStaples)
        }
        set(v) = sp.edit().putString("staples", JSONArray(v).toString()).apply()

    internal fun prefs(): SharedPreferences = sp
}

// ---------------------------------------------------------------
// APIキーは Android の安全な保管庫（Keystore）の鍵で暗号化して保存する
// ---------------------------------------------------------------

object SecretStore {
    private const val ALIAS = "fridgechef_api_key"
    private const val PREF = "apiKeyEnc"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    fun save(value: String) {
        val sp = Store.prefs()
        if (value.isBlank()) {
            sp.edit().remove(PREF).apply()
            return
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val enc = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val packed = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(enc, Base64.NO_WRAP)
        sp.edit().putString(PREF, packed).apply()
    }

    fun load(): String {
        val packed = Store.prefs().getString(PREF, null) ?: return ""
        return runCatching {
            val (ivB64, encB64) = packed.split(":", limit = 2)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE, key(),
                GCMParameterSpec(128, Base64.decode(ivB64, Base64.NO_WRAP))
            )
            String(cipher.doFinal(Base64.decode(encB64, Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrDefault("")
    }
}

// ---------------------------------------------------------------
// 使用額の目安（返事に含まれる使用量から計算する）
// ---------------------------------------------------------------

object UsageTracker {
    /** 100万トークンあたりの料金（米ドル）。料金が変わったらここを直す */
    private val prices = mapOf(
        "claude-haiku-5-5" to Pair(0.10, 0.50),
        "claude-sonnet-5-5" to Pair(2.00, 10.00)
    )

    private fun currentMonth(): String = SimpleDateFormat("yyyy-MM", Locale.US).format(Date())

    private fun rollOver() {
        val sp = Store.prefs()
        if (sp.getString("usageMonth", null) != currentMonth()) {
            sp.edit()
                .putString("usageMonth", currentMonth())
                .putFloat("usageMonthUSD", 0f)
                .apply()
        }
    }

    fun record(model: String, inputTokens: Int, outputTokens: Int) {
        rollOver()
        val price = prices[model] ?: prices.getValue("claude-sonnet-5-5")
        val cost = inputTokens / 1_000_000.0 * price.first + outputTokens / 1_000_000.0 * price.second
        val sp = Store.prefs()
        sp.edit()
            .putFloat("usageMonthUSD", (sp.getFloat("usageMonthUSD", 0f) + cost).toFloat())
            .putFloat("usageTotalUSD", (sp.getFloat("usageTotalUSD", 0f) + cost).toFloat())
            .apply()
    }

    val monthUSD: Double
        get() {
            rollOver(); return Store.prefs().getFloat("usageMonthUSD", 0f).toDouble()
        }

    val totalUSD: Double
        get() = Store.prefs().getFloat("usageTotalUSD", 0f).toDouble()

    fun resetTotal() {
        Store.prefs().edit().putFloat("usageTotalUSD", 0f).apply()
    }
}

// ---------------------------------------------------------------
// データの形
// ---------------------------------------------------------------

data class Ingredient(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val amount: String = "",
    val uncertain: Boolean = false,
    val have: Boolean = true
)

data class Dish(val role: String, val name: String, val uses: List<String>)

data class MenuSet(
    val title: String,
    val dishes: List<Dish>,
    val minutes: Int?,
    val effort: String?
)

object JsonHelper {
    /** 返事の中から最初の「{」と最後の「}」の間を取り出す。崩れていたら null */
    private fun extract(text: String): JSONObject? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { JSONObject(text.substring(start, end + 1)) }.getOrNull()
    }

    private fun strings(arr: JSONArray?): List<String> =
        if (arr == null) emptyList() else List(arr.length()) { arr.optString(it) }.filter { it.isNotBlank() }

    fun ingredients(text: String): List<Ingredient>? {
        val arr = extract(text)?.optJSONArray("ingredients") ?: return null
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            Ingredient(
                name = o.optString("name"),
                amount = o.optString("amount", ""),
                uncertain = o.optBoolean("uncertain", false)
            )
        }.filter { it.name.isNotBlank() }
    }

    fun menus(text: String): List<MenuSet>? {
        val arr = extract(text)?.optJSONArray("sets") ?: return null
        val result = mutableListOf<MenuSet>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val dishesArr = o.optJSONArray("dishes") ?: continue
            val dishes = (0 until dishesArr.length()).mapNotNull { j ->
                val d = dishesArr.optJSONObject(j) ?: return@mapNotNull null
                val name = d.optString("name")
                if (name.isBlank()) null else Dish(d.optString("role"), name, strings(d.optJSONArray("uses")))
            }
            // 「40」でも「"40分"」でも読めるようにする
            val minutes = when (val m = o.opt("minutes")) {
                is Number -> m.toInt()
                is String -> m.filter { it.isDigit() }.toIntOrNull()
                else -> null
            }
            result += MenuSet(
                title = o.optString("title", "献立").ifBlank { "献立" },
                dishes = dishes,
                minutes = minutes,
                effort = o.optString("effort").ifBlank { null }
            )
        }
        return result.ifEmpty { null }
    }
}

// ---------------------------------------------------------------
// Claude を呼び出す部分
// ---------------------------------------------------------------

class ClaudeException(message: String) : Exception(message)

data class Reply(val text: String, val truncated: Boolean)

class ClaudeClient(private val apiKey: String, private val model: String) {

    companion object {
        fun imageBlock(jpeg: ByteArray): JSONObject = JSONObject()
            .put("type", "image")
            .put(
                "source", JSONObject()
                    .put("type", "base64")
                    .put("media_type", "image/jpeg")
                    .put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP))
            )

        fun textBlock(text: String): JSONObject = JSONObject().put("type", "text").put("text", text)
    }

    suspend fun send(content: List<JSONObject>, maxTokens: Int): Reply = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) throw ClaudeException("APIキーが未設定です。右上の歯車から設定してください。")

        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", maxTokens)
            .put(
                "messages", JSONArray().put(
                    JSONObject().put("role", "user").put("content", JSONArray(content))
                )
            )

        val conn = (URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 30_000
            readTimeout = 120_000
            doOutput = true
            setRequestProperty("x-api-key", apiKey)
            setRequestProperty("anthropic-version", "2023-06-01")
            setRequestProperty("content-type", "application/json")
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""

            if (code !in 200..299) {
                val message = runCatching {
                    JSONObject(raw).getJSONObject("error").getString("message")
                }.getOrNull() ?: "通信エラー（コード $code）"
                throw ClaudeException("Claudeからエラーが返りました：$message")
            }

            val json = JSONObject(raw)
            json.optJSONObject("usage")?.let {
                UsageTracker.record(model, it.optInt("input_tokens"), it.optInt("output_tokens"))
            }
            val blocks = json.optJSONArray("content") ?: JSONArray()
            val text = (0 until blocks.length())
                .mapNotNull { blocks.optJSONObject(it)?.optString("text")?.ifBlank { null } }
                .joinToString("\n")
            if (text.isBlank()) throw ClaudeException("返事が空でした。もう一度お試しください。")
            Reply(text, json.optString("stop_reason") == "max_tokens")
        } finally {
            conn.disconnect()
        }
    }
}

// ---------------------------------------------------------------
// 写真の読み込みと縮小（送る量を減らして料金を抑える）
// ---------------------------------------------------------------

object ImageUtil {
    fun load(context: Context, uri: Uri): Bitmap? = runCatching {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longest = max(info.size.width, info.size.height)
            if (longest > 2000) {
                val ratio = 2000.0 / longest
                decoder.setTargetSize(
                    (info.size.width * ratio).roundToInt(),
                    (info.size.height * ratio).roundToInt()
                )
            }
        }
    }.getOrNull()

    fun jpegForUpload(bitmap: Bitmap, maxSide: Int = 1568, quality: Int = 70): ByteArray {
        val longest = max(bitmap.width, bitmap.height)
        val scaled = if (longest > maxSide) {
            val ratio = maxSide.toDouble() / longest
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * ratio).roundToInt(),
                (bitmap.height * ratio).roundToInt(),
                true
            )
        } else bitmap
        return ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
            out.toByteArray()
        }
    }
}

// ---------------------------------------------------------------
// 指示文（プロンプト）。iPhone 版と同じ内容
// ---------------------------------------------------------------

sealed class ConsultKind {
    object ThreeSets : ConsultKind()
    object Feast : ConsultKind()
    data class Upgrade(val set: MenuSet) : ConsultKind()
    object Centerpiece : ConsultKind()
    data class Genre(val name: String) : ConsultKind()

    companion object {
        val genres = listOf("和食", "洋食", "中華", "エスニック")
    }
}

object Prompts {
    fun read(photoCount: Int): String {
        val head = if (photoCount > 1)
            "添付は冷蔵庫の中の写真（${photoCount}枚）です。すべての写真に写っている食材を読み取ってください。同じ食材が複数の写真に写っていたら、1つにまとめてください。"
        else
            "添付は冷蔵庫の中の写真です。写っている食材を読み取ってください。"
        return head + "\n" + """
次の形式（JSON）だけを返してください。説明文や ``` などの記号は付けないでください。
{"ingredients":[{"name":"食材名","amount":"おおよその量（分からなければ空文字）","uncertain":false}]}
ルール：
- 自信がない食材は uncertain を true にする。
- 読み取れないものを推測で補わない。
- 調味料のボトル類は含めなくてよい。
- 名前は日本の家庭で使う一般的な呼び方にする（例：「豚こま肉」「木綿豆腐」）。
""".trim()
    }

    fun fridgeList(items: List<Ingredient>): String {
        if (items.isEmpty()) return "（なし）"
        return items.joinToString("、") { item ->
            var line = item.name
            if (item.amount.isNotBlank()) line += "（${item.amount}）"
            if (item.uncertain) line += "※たぶんある"
            line
        }
    }

    private fun memoLine(memo: String): String {
        val t = memo.trim()
        return if (t.isEmpty()) "" else "\n【メモ（家族の事情など。人数の指定があればこちらを優先）】$t"
    }

    fun menus(ingredients: List<Ingredient>, staples: List<String>, memo: String,
              timeLimit: Int, exclude: List<String>): String {
        val timeRule = if (timeLimit > 0)
            "- 調理時間は3品を並行して作って、全体で${timeLimit}分以内にする。"
        else
            "- 調理時間の制限はない。ただし目安は必ず書く。"
        val excludeRule = if (exclude.isEmpty()) "" else "\n- 次の料理とは別の献立にする：${exclude.joinToString("、")}"
        return """
家庭料理に詳しい手助け役として、4人家族の夕食の献立を考えてください。

【冷蔵庫にある食材】${fridgeList(ingredients)}
【いつもある物（自由に使ってよい）】${staples.joinToString("、")}${memoLine(memo)}

ルール：
- 「主菜・副菜・汁物」を1組として、3組考える。
- 主菜がシチュー、カレー、鍋物、具だくさんのスープなど汁物を兼ねる場合は、汁物を入れない。
- 副菜は簡単なもの（おひたし、あえ物、浅漬け、冷ややっこなど）でよい。
- 冷蔵庫の食材をできるだけ使い切り、買い足しはなるべく少なくする。
- 3組は和・洋・中などの系統が偏らないようにする。
- 主食（ご飯・パン）は献立に含めない。
$timeRule$excludeRule

次の形式（JSON）だけを返してください。説明文や ``` などの記号は付けないでください。
{"sets":[{"title":"献立の短い呼び名","dishes":[{"role":"主菜","name":"料理名","uses":["使う食材"]},{"role":"副菜","name":"料理名","uses":["使う食材"]},{"role":"汁物","name":"料理名","uses":["使う食材"]}],"minutes":40,"effort":"かんたん"}]}
minutes は3品全体の調理時間の目安（分、数字のみ）。effort は「かんたん」「ふつう」「手間あり」のどれか。
""".trim()
    }

    fun detail(set: MenuSet, ingredients: List<Ingredient>, staples: List<String>, memo: String): String {
        val dishes = set.dishes.joinToString("、") { "${it.role}：${it.name}" }
        return """
4人家族の夕食として、次の献立の作り方を教えてください。

【献立】$dishes
【冷蔵庫にある食材】${fridgeList(ingredients)}
【いつもある物】${staples.joinToString("、")}${memoLine(memo)}

次の見出しをそのまま使い、日本語の平易な言葉で書いてください。
【買い足しリスト】冷蔵庫の食材といつもある物に無いものだけ。無ければ「なし」。
【材料（4人分）】料理ごとに。
【段取り】3品を並行して作る順番を、時間の流れに沿って。
【作り方】料理ごとに、番号付きの手順で。
【コツ・注意】あれば短く。
""".trim()
    }

    fun consult(kind: ConsultKind, ingredients: List<Ingredient>, staples: List<String>, memo: String): String {
        val common = "【冷蔵庫にある食材】${fridgeList(ingredients)}\n【いつもある物】${staples.joinToString("、")}\n【人数】4人家族${memoLine(memo)}"
        val request = when (kind) {
            is ConsultKind.ThreeSets -> """
冷蔵庫の食材を使って、4人家族の夕食の献立を3組考えてください。
- 「主菜・副菜・汁物」を1組とする。主菜がシチュー、カレー、鍋物など汁物を兼ねる場合は汁物は不要。
- 副菜は簡単なもの（おひたし、あえ物、浅漬けなど）でよい。
- 冷蔵庫の食材をできるだけ使い切り、買い足しはなるべく少なくする。
- 3組は和・洋・中などの系統が偏らないようにする。
各組について、料理名、使う食材、調理時間の目安、買い足しリストを書いてください。気に入った組があれば、続けて作り方を聞きます。
""".trim()
            is ConsultKind.Feast -> """
週末に時間と手間をかけて作る、お店のような本格的なごちそう献立（主菜・副菜・汁物）を考えてください。
冷蔵庫の食材をできるだけ生かし、足りない物は買い足しリストにまとめてください。
下ごしらえから仕上げまでの段取りを時間の流れで示し、各料理の材料（4人分）と作り方を書いてください。
""".trim()
            is ConsultKind.Upgrade -> {
                val dishes = kind.set.dishes.joinToString("、") { "${it.role}：${it.name}" }
                """
次の献立を、家庭で一段おいしく、見た目も華やかにする方法を教えてください。
【献立】$dishes
料理ごとに、隠し味、火加減や下ごしらえのコツ、盛り付けの工夫を具体的に書いてください。
追加で必要な食材があれば、買い足しリストにまとめてください。
""".trim()
            }
            is ConsultKind.Centerpiece -> """
冷蔵庫の食材を生かして、食卓の主役になる手の込んだ一品を考えてください。
それに合う、手間の少ない副菜も1〜2品添えてください。
材料（4人分）、作り方、うまく作るコツ、買い足しリストを書いてください。
""".trim()
            is ConsultKind.Genre -> """
${kind.name}で、少し凝った献立（主菜・副菜・汁物）を考えてください。主菜が汁物を兼ねる場合は汁物は不要です。
冷蔵庫の食材をできるだけ生かし、材料（4人分）、作り方、段取り、買い足しリストを書いてください。
""".trim()
        }
        return request + "\n\n" + common +
            "\n\n主食（ご飯・パン）は献立に含めなくてかまいません。日本の家庭で手に入る食材・道具で作れる内容にしてください。"
    }
}
