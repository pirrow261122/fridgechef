package local.fridgechef.app

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Step { PHOTO, CONFIRM, MENUS, DETAIL }

/** 画面の流れと処理をまとめる部分（iPhone 版の AppModel と同じ役割） */
class AppViewModel : ViewModel() {

    companion object {
        const val MAX_PHOTOS = 5
        const val TRUNCATED_NOTE = "返事が長くなりすぎて途中で切れたため、一覧にできませんでした。もう一度ボタンを押してください。"
    }

    var step by mutableStateOf(Step.PHOTO)
    val images = mutableStateListOf<Bitmap>()
    var memo by mutableStateOf("")
    val ingredients = mutableStateListOf<Ingredient>()
    var sets by mutableStateOf<List<MenuSet>>(emptyList())
    var selected by mutableStateOf<MenuSet?>(null)
    var detailText by mutableStateOf("")
    var rawText by mutableStateOf<String?>(null)
    var isLoading by mutableStateOf(false)
    var errorMessage by mutableStateOf<String?>(null)
    var monthUSD by mutableDoubleStateOf(0.0)
    var totalUSD by mutableDoubleStateOf(0.0)

    init {
        refreshUsage()
    }

    fun refreshUsage() {
        monthUSD = UsageTracker.monthUSD
        totalUSD = UsageTracker.totalUSD
    }

    private fun client() = ClaudeClient(SecretStore.load(), Store.model)

    private val usableIngredients: List<Ingredient>
        get() = ingredients.filter { it.have && it.name.isNotBlank() }

    fun addImage(bitmap: Bitmap) {
        if (images.size < MAX_PHOTOS) images.add(bitmap)
    }

    private fun launchWork(work: suspend () -> Unit) {
        errorMessage = null
        isLoading = true
        viewModelScope.launch {
            try {
                work()
            } catch (e: Exception) {
                errorMessage = e.message ?: "エラーが起きました。"
            } finally {
                isLoading = false
                refreshUsage()
            }
        }
    }

    /** 1段階目：写真から食材を読み取る。thenMenus が true なら確認を飛ばして献立まで進む */
    fun readIngredients(thenMenus: Boolean) {
        if (images.isEmpty()) return
        val photos = images.toList()
        launchWork {
            val jpegs = withContext(Dispatchers.Default) { photos.map { ImageUtil.jpegForUpload(it) } }
            val content = jpegs.map { ClaudeClient.imageBlock(it) } +
                ClaudeClient.textBlock(Prompts.read(jpegs.size))
            val reply = client().send(content, 4000)
            val parsed = JsonHelper.ingredients(reply.text)
            if (parsed == null) {
                ingredients.clear()
                rawText = if (reply.truncated) TRUNCATED_NOTE else reply.text
                step = Step.CONFIRM
                return@launchWork
            }
            rawText = null
            ingredients.clear()
            ingredients.addAll(parsed)
            if (thenMenus) fetchMenus(emptyList()) else step = Step.CONFIRM
        }
    }

    /** 2段階目：確定した食材で献立3組を出す。regenerate が true なら今の3組とは別の案にする */
    fun makeMenus(regenerate: Boolean) {
        val exclude = if (regenerate) sets.flatMap { s -> s.dishes.map { it.name } } else emptyList()
        launchWork { fetchMenus(exclude) }
    }

    private suspend fun fetchMenus(exclude: List<String>) {
        val prompt = Prompts.menus(usableIngredients, Store.staples, memo, Store.timeLimit, exclude)
        val reply = client().send(listOf(ClaudeClient.textBlock(prompt)), 6000)
        val parsed = JsonHelper.menus(reply.text)
        if (parsed != null) {
            sets = parsed
            rawText = null
        } else {
            sets = emptyList()
            rawText = if (reply.truncated) TRUNCATED_NOTE else reply.text
        }
        step = Step.MENUS
    }

    /** 選んだ1組だけ、作り方と買い足しリストを詳しく出す */
    fun makeDetail(set: MenuSet) {
        selected = set
        launchWork {
            val prompt = Prompts.detail(set, usableIngredients, Store.staples, memo)
            val reply = client().send(listOf(ClaudeClient.textBlock(prompt)), 8000)
            detailText = if (reply.truncated)
                reply.text + "\n\n（返事が長すぎて途中で切れました。もう一度この組を選び直してください）"
            else reply.text
            step = Step.DETAIL
        }
    }

    /** 「AI本体で相談する」用の依頼文（Claude の鍵は使わない） */
    fun consultPrompt(kind: ConsultKind): String =
        Prompts.consult(kind, usableIngredients, Store.staples, memo)

    fun addIngredient(name: String) {
        val t = name.trim()
        if (t.isNotEmpty()) ingredients.add(Ingredient(name = t))
    }

    fun updateIngredient(updated: Ingredient) {
        val i = ingredients.indexOfFirst { it.id == updated.id }
        if (i >= 0) ingredients[i] = updated
    }

    fun removeIngredient(item: Ingredient) {
        ingredients.removeAll { it.id == item.id }
    }

    fun startManual() {
        ingredients.clear()
        rawText = null
        step = Step.CONFIRM
    }

    fun back() {
        errorMessage = null
        step = when (step) {
            Step.PHOTO -> Step.PHOTO
            Step.CONFIRM -> Step.PHOTO
            Step.MENUS -> Step.CONFIRM
            Step.DETAIL -> Step.MENUS
        }
    }

    fun reset() {
        step = Step.PHOTO
        images.clear()
        memo = ""
        ingredients.clear()
        sets = emptyList()
        selected = null
        detailText = ""
        rawText = null
        errorMessage = null
    }
}
