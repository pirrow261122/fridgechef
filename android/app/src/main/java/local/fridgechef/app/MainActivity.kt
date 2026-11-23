package local.fridgechef.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(applicationContext)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val vm: AppViewModel = viewModel()
                    AppScreen(vm)
                }
            }
        }
    }
}

// ---------------------------------------------------------------
// 全体の画面
// ---------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScreen(vm: AppViewModel) {
    var showSettings by remember { mutableStateOf(false) }

    BackHandler(enabled = showSettings || vm.step != Step.PHOTO) {
        if (showSettings) showSettings = false else if (!vm.isLoading) vm.back()
    }

    if (showSettings) {
        SettingsScreen(onClose = {
            showSettings = false
            vm.refreshUsage()
        })
        return
    }

    val title = when (vm.step) {
        Step.PHOTO -> "冷蔵庫シェフ"
        Step.CONFIRM -> "食材の確認"
        Step.MENUS -> "献立3組"
        Step.DETAIL -> "作り方"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    if (vm.step != Step.PHOTO) {
                        IconButton(onClick = { vm.back() }, enabled = !vm.isLoading) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "設定")
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                vm.errorMessage?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                }
                when (vm.step) {
                    Step.PHOTO -> PhotoScreen(vm)
                    Step.CONFIRM -> ConfirmScreen(vm)
                    Step.MENUS -> MenusScreen(vm)
                    Step.DETAIL -> DetailScreen(vm)
                }
            }
            if (vm.isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f))
                        .clickable(enabled = true, onClick = {}),
                    contentAlignment = Alignment.Center
                ) {
                    Card {
                        Row(
                            modifier = Modifier.padding(20.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(28.dp))
                            Spacer(Modifier.width(12.dp))
                            Text("考えています…")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Caption(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = color)
}

// ---------------------------------------------------------------
// 使用額の目安
// ---------------------------------------------------------------

@Composable
fun UsageBar(vm: AppViewModel) {
    val rate = Store.yenPerDollar
    val deposit = Store.depositUSD
    fun yen(usd: Double) = "約¥${(usd * rate).roundToInt()}"
    Column(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(10.dp)
    ) {
        Caption("今月の使用額（目安）：${yen(vm.monthUSD)}（$${String.format(Locale.US, "%.3f", vm.monthUSD)}）")
        if (deposit > 0) {
            val remaining = deposit - vm.totalUSD
            val warn = remaining < 1
            Caption(
                "残高（目安）：$${String.format(Locale.US, "%.2f", remaining)}（${yen(remaining)}）",
                if (warn) Color(0xFFFF8A3D) else MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (warn) Caption("残りが1ドルを切りました。Claude Console で実際の残高を確認してください。", Color(0xFFFF8A3D))
        } else {
            Caption("設定で入金額を入れると、残高の目安も表示します。")
        }
    }
}

// ---------------------------------------------------------------
// 1. 写真の画面
// ---------------------------------------------------------------

private fun newPhotoUri(context: Context): Uri {
    val dir = File(context.cacheDir, "photos").apply { mkdirs() }
    val file = File.createTempFile("shot_", ".jpg", dir)
    return FileProvider.getUriForFile(context, "local.fridgechef.app.fileprovider", file)
}

@Composable
fun PhotoScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingUri by remember { mutableStateOf<Uri?>(null) }
    val remaining = AppViewModel.MAX_PHOTOS - vm.images.size

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = pendingUri
        if (ok && uri != null) {
            scope.launch {
                val bmp = withContext(Dispatchers.IO) { ImageUtil.load(context, uri) }
                if (bmp != null) vm.addImage(bmp)
            }
        }
    }
    val pickLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(AppViewModel.MAX_PHOTOS)
    ) { uris ->
        scope.launch {
            val room = AppViewModel.MAX_PHOTOS - vm.images.size
            uris.take(room).forEach { uri ->
                val bmp = withContext(Dispatchers.IO) { ImageUtil.load(context, uri) }
                if (bmp != null) vm.addImage(bmp)
            }
            if (uris.size > room) vm.errorMessage = "写真は${AppViewModel.MAX_PHOTOS}枚までです。超えた分は入れていません。"
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        UsageBar(vm)

        if (vm.images.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().height(160.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) { Caption("冷蔵庫の写真がまだありません") }
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(vm.images) { index, bmp ->
                    Box {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "写真${index + 1}",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.width(140.dp).height(190.dp).clip(RoundedCornerShape(12.dp))
                        )
                        IconButton(
                            onClick = { vm.images.removeAt(index) },
                            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(32.dp)
                                .clip(CircleShape).background(Color.Black.copy(alpha = 0.55f))
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = "この写真を外す", tint = Color.White)
                        }
                    }
                }
            }
        }

        Caption("写真 ${vm.images.size} / ${AppViewModel.MAX_PHOTOS} 枚（冷蔵室・野菜室・冷凍室・扉のポケットなどを分けて撮れます）")

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = {
                    val uri = newPhotoUri(context)
                    pendingUri = uri
                    cameraLauncher.launch(uri)
                },
                enabled = remaining > 0,
                modifier = Modifier.weight(1f)
            ) { Text(if (vm.images.isEmpty()) "撮影する" else "もう1枚撮る") }

            OutlinedButton(
                onClick = {
                    pickLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                enabled = remaining > 0,
                modifier = Modifier.weight(1f)
            ) { Text("写真を選ぶ") }
        }

        OutlinedTextField(
            value = vm.memo,
            onValueChange = { vm.memo = it },
            label = { Text("メモ（例：今日は6人分、魚は避けたい）") },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 4
        )

        Button(
            onClick = { vm.readIngredients(thenMenus = false) },
            enabled = vm.images.isNotEmpty() && !vm.isLoading,
            modifier = Modifier.fillMaxWidth()
        ) { Text("食材を読み取って確認する") }

        OutlinedButton(
            onClick = { vm.readIngredients(thenMenus = true) },
            enabled = vm.images.isNotEmpty() && !vm.isLoading,
            modifier = Modifier.fillMaxWidth()
        ) { Text("確認せずにそのまま献立へ") }

        TextButton(
            onClick = { vm.startManual() },
            enabled = !vm.isLoading,
            modifier = Modifier.fillMaxWidth()
        ) { Text("写真なしで食材を手入力する") }

        Caption("※写真は Claude を作っている会社（Anthropic）に送られます。郵便物や書類が写り込まないように撮ってください。")
    }
}

// ---------------------------------------------------------------
// 2. 食材の確認画面
// ---------------------------------------------------------------

@Composable
fun ConfirmScreen(vm: AppViewModel) {
    var newName by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        vm.rawText?.let { raw ->
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("うまく一覧にできませんでした", fontWeight = FontWeight.Bold)
                    SelectionContainer { Caption(raw) }
                    Caption("上の文を見ながら、下の欄で食材を手で追加してください。")
                    HorizontalDivider()
                }
            }
        }

        item { Text("冷蔵庫にある食材", fontWeight = FontWeight.Bold) }

        items(vm.ingredients, key = { it.id }) { item ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = item.have, onCheckedChange = { vm.updateIngredient(item.copy(have = it)) })
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    OutlinedTextField(
                        value = item.name,
                        onValueChange = { vm.updateIngredient(item.copy(name = it)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = item.amount,
                        onValueChange = { vm.updateIngredient(item.copy(amount = it)) },
                        placeholder = { Text("量（任意。例：4個、半分）") },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (item.uncertain) {
                    TextButton(onClick = { vm.updateIngredient(item.copy(uncertain = false)) }) {
                        Text("要確認", color = Color(0xFFFF8A3D), fontWeight = FontWeight.Bold)
                    }
                }
                IconButton(onClick = { vm.removeIngredient(item) }) {
                    Icon(Icons.Filled.Close, contentDescription = "削除")
                }
            }
        }

        item {
          Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    placeholder = { Text("食材を追加（例：卵）") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = { vm.addIngredient(newName); newName = "" }) { Text("追加") }
            }
            Caption("スイッチを切ると「ない」扱いになります。オレンジの「要確認」は、写真から自信をもって読み取れなかった物です。確かめたら押して消してください。")
          }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("いつもある物（自動で加えます）", fontWeight = FontWeight.Bold)
                Caption(Store.staples.joinToString("、"))
            }
        }

        item {
            Button(
                onClick = { vm.makeMenus(regenerate = false) },
                enabled = !vm.isLoading,
                modifier = Modifier.fillMaxWidth()
            ) { Text("献立を3組出す") }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ConsultButton(vm, ConsultMode.CONFIRM)
                Caption("確認した食材で、AI のアプリに献立3組を考えてもらう依頼文を作ります。このアプリの料金はかかりません。")
            }
        }
    }
}

// ---------------------------------------------------------------
// 「AI本体で相談する」
// ---------------------------------------------------------------

enum class ConsultMode { CONFIRM, MENUS, DETAIL }

private val letters = listOf("A", "B", "C", "D", "E")

@Composable
fun ConsultButton(vm: AppViewModel, mode: ConsultMode) {
    var showChoices by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current

    fun open(kind: ConsultKind) {
        val text = vm.consultPrompt(kind)
        clipboard.setText(AnnotatedString(text))
        showChoices = false
        draft = text
    }

    OutlinedButton(
        onClick = { if (mode == ConsultMode.CONFIRM) open(ConsultKind.ThreeSets) else showChoices = true },
        enabled = !vm.isLoading,
        modifier = Modifier.fillMaxWidth()
    ) { Text("AI本体で相談する") }

    if (showChoices) {
        AlertDialog(
            onDismissRequest = { showChoices = false },
            title = { Text("どんな相談をしますか？") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Caption("依頼文を作ってコピーします。Claude、ChatGPT、Gemini などのアプリに貼り付けて使えます。このアプリの料金はかかりません。")
                    Spacer(Modifier.height(8.dp))
                    if (mode == ConsultMode.MENUS) {
                        vm.sets.forEachIndexed { index, set ->
                            val letter = letters.getOrElse(index) { (index + 1).toString() }
                            TextButton(onClick = { open(ConsultKind.Upgrade(set)) }) {
                                Text("${letter}案「${set.title}」を格上げ")
                            }
                        }
                    }
                    if (mode == ConsultMode.DETAIL) {
                        vm.selected?.let { set ->
                            TextButton(onClick = { open(ConsultKind.Upgrade(set)) }) { Text("選んだ献立を格上げ") }
                        }
                    }
                    TextButton(onClick = { open(ConsultKind.Feast) }) { Text("週末のごちそう") }
                    TextButton(onClick = { open(ConsultKind.Centerpiece) }) { Text("主役の一品") }
                    ConsultKind.genres.forEach { g ->
                        TextButton(onClick = { open(ConsultKind.Genre(g)) }) { Text("ジャンル指定：$g") }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showChoices = false }) { Text("やめる") } }
        )
    }

    draft?.let { text ->
        ConsultDialog(initial = text, onClose = { draft = null })
    }
}

@Composable
fun ConsultDialog(initial: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var text by remember { mutableStateOf(initial) }
    var copied by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("AI本体で相談する") },
        text = {
            Column {
                Caption(
                    if (copied) "コピーしました。「共有」からAIのアプリを選ぶか、アプリを開いて貼り付けてください。文は直してから送ることもできます。"
                    else "文を直したら、もう一度コピーしてください。"
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; copied = false },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 360.dp),
                    textStyle = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { clipboard.setText(AnnotatedString(text)); copied = true },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (copied) "コピー済み" else "コピーする") }
            }
        },
        confirmButton = {
            Button(onClick = {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                }
                context.startActivity(Intent.createChooser(send, "AIのアプリを選ぶ"))
            }) { Text("共有") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("閉じる") } }
    )
}

// ---------------------------------------------------------------
// 3. 献立3組の画面
// ---------------------------------------------------------------

@Composable
fun MenusScreen(vm: AppViewModel) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        vm.rawText?.let { raw ->
            Caption("一覧の形にできなかったため、返事をそのまま表示します。")
            SelectionContainer { Text(raw) }
        }

        vm.sets.forEachIndexed { index, set ->
            MenuCard(index, set, enabled = !vm.isLoading) { vm.makeDetail(set) }
        }

        if (vm.sets.isNotEmpty()) Caption("気に入った組を押すと、作り方と買い足しリストが出ます。")

        OutlinedButton(
            onClick = { vm.makeMenus(regenerate = true) },
            enabled = !vm.isLoading,
            modifier = Modifier.fillMaxWidth()
        ) { Text("別の3組を出し直す") }

        ConsultButton(vm, ConsultMode.MENUS)
    }
}

@Composable
fun MenuCard(index: Int, set: MenuSet, enabled: Boolean, onClick: () -> Unit) {
    val letter = letters.getOrElse(index) { (index + 1).toString() }
    Column(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline), RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("${letter}案：${set.title}", fontWeight = FontWeight.Bold)
        set.dishes.forEach { dish ->
            Text("${dish.role}　${dish.name}")
            if (dish.uses.isNotEmpty()) Caption("使う食材：" + dish.uses.joinToString("、"))
        }
        val info = listOfNotNull(set.minutes?.let { "約${it}分" }, set.effort).joinToString("　")
        if (info.isNotEmpty()) Caption(info)
    }
}

// ---------------------------------------------------------------
// 4. 作り方の画面
// ---------------------------------------------------------------

@Composable
fun DetailScreen(vm: AppViewModel) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        vm.selected?.let { set ->
            Text(set.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Caption(set.dishes.joinToString("\n") { "${it.role}：${it.name}" })
            HorizontalDivider()
        }

        SelectionContainer { Text(vm.detailText) }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = { clipboard.setText(AnnotatedString(vm.detailText)); copied = true },
                modifier = Modifier.weight(1f)
            ) { Text(if (copied) "コピーしました" else "全文コピー") }
            OutlinedButton(onClick = { vm.reset() }, modifier = Modifier.weight(1f)) { Text("最初からやり直す") }
        }

        ConsultButton(vm, ConsultMode.DETAIL)

        Caption("※食材の読み取りや作り方は間違うことがあります。傷み具合や火の通りは、最後にご自身の目で確かめてください。")
    }
}

// ---------------------------------------------------------------
// 設定画面
// ---------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onClose: () -> Unit) {
    val staples = remember { mutableStateListOf<String>().apply { addAll(Store.staples) } }
    var newStaple by remember { mutableStateOf("") }
    var timeLimit by remember { mutableStateOf(Store.timeLimit) }
    var model by remember { mutableStateOf(Store.model) }
    var deposit by remember { mutableStateOf(if (Store.depositUSD > 0) Store.depositUSD.toString() else "") }
    var yen by remember { mutableStateOf(Store.yenPerDollar.toString()) }
    var apiKey by remember { mutableStateOf(SecretStore.load()) }
    var total by remember { mutableStateOf(UsageTracker.totalUSD) }
    var confirmReset by remember { mutableStateOf(false) }

    fun save() {
        Store.staples = staples.toList()
        Store.timeLimit = timeLimit
        Store.model = model
        Store.depositUSD = deposit.toDoubleOrNull() ?: 0.0
        Store.yenPerDollar = yen.toDoubleOrNull()?.takeIf { it > 0 } ?: 150.0
        SecretStore.save(apiKey.trim())
        onClose()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("設定") },
                navigationIcon = { TextButton(onClick = onClose) { Text("閉じる") } },
                actions = { TextButton(onClick = { save() }) { Text("保存") } }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("いつもある物", fontWeight = FontWeight.Bold)
            staples.toList().forEach { s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s, modifier = Modifier.weight(1f))
                    IconButton(onClick = { staples.remove(s) }) { Icon(Icons.Filled.Close, contentDescription = "削除") }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newStaple, onValueChange = { newStaple = it },
                    placeholder = { Text("追加する物") }, singleLine = true, modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    val t = newStaple.trim()
                    if (t.isNotEmpty() && t !in staples) staples.add(t)
                    newStaple = ""
                }) { Text("追加") }
            }
            TextButton(onClick = { staples.clear(); staples.addAll(Store.defaultStaples) }) { Text("初期の内容に戻す") }
            Caption("ここに入れた物は毎回「ある」として扱い、買い足しリストに出ません。")

            HorizontalDivider()
            Text("調理時間", fontWeight = FontWeight.Bold)
            listOf(0 to "指定なし", 30 to "30分以内", 60 to "60分以内", 90 to "90分以内").forEach { (v, label) ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { timeLimit = v }) {
                    RadioButton(selected = timeLimit == v, onClick = { timeLimit = v })
                    Text(label)
                }
            }
            Caption("3品を並行して作ったときの全体の時間です。献立は4人分で考えます。")

            HorizontalDivider()
            Text("使うモデル", fontWeight = FontWeight.Bold)
            listOf("claude-haiku-5-5" to "Haiku（安い・速い）", "claude-sonnet-5-5" to "Sonnet（読み取りが丁寧）").forEach { (v, label) ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { model = v }) {
                    RadioButton(selected = model == v, onClick = { model = v })
                    Text(label)
                }
            }
            Caption("まずは Haiku で試し、食材の読み違いが多ければ Sonnet に切り替えてください。")

            HorizontalDivider()
            Text("使用額の目安", fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = deposit, onValueChange = { deposit = it },
                label = { Text("入金した合計（ドル）") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = yen, onValueChange = { yen = it },
                label = { Text("1ドルあたりの円") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )
            Text("これまでの使用額（目安）：$${String.format(Locale.US, "%.3f", total)}")
            TextButton(onClick = { confirmReset = true }) {
                Text("使用額の累計を0に戻す", color = MaterialTheme.colorScheme.error)
            }
            Caption("Claude からの返事に含まれる使用量を、料金表で換算した目安です。税金や料金改定は反映されません。ずれが大きいときは、Claude Console の残高を「入金した合計」に入れ、累計を0に戻すと合わせられます。")

            HorizontalDivider()
            Text("APIキー", fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = apiKey, onValueChange = { apiKey = it },
                placeholder = { Text("sk-ant-...") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth()
            )
            Caption("キーはこの端末の中に暗号化して保存され、コードや GitHub には残りません。")
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("使用額の累計を0に戻しますか？") },
            confirmButton = {
                TextButton(onClick = {
                    UsageTracker.resetTotal(); total = 0.0; confirmReset = false
                }) { Text("0に戻す", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("やめる") } }
        )
    }
}
