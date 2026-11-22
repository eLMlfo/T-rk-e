// SPDX-License-Identifier: AGPL-3.0-only
// Profil (cihazlar ve tercihler; hesap yok) ve ayarlar (tuval: Telefon-Profil, -Ayarlar).
package com.couchsubs.mobile.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.couchsubs.mobile.BuildConfig
import com.couchsubs.mobile.R
import com.couchsubs.mobile.Services
import com.couchsubs.mobile.core.SubtitleBackgroundShape
import com.couchsubs.mobile.core.SubtitlePlacement
import com.couchsubs.mobile.core.SubtitleTextColor
import com.couchsubs.mobile.core.TvDisplayMode
import com.couchsubs.mobile.remoteplay.LoginPasscode
import com.couchsubs.mobile.service.TranslationService
import com.couchsubs.mobile.settings.AppSettings
import com.couchsubs.mobile.settings.EngineChoice
import com.couchsubs.mobile.settings.SubtitleTarget
import com.couchsubs.mobile.translation.AiModel
import com.couchsubs.mobile.translation.AiRequest
import com.couchsubs.mobile.translation.AiProvider
import com.couchsubs.mobile.translation.KeyCheck
import com.couchsubs.mobile.translation.OnlineKeyState
import com.couchsubs.mobile.translation.aiProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.couchsubs.mobile.tv.FoundTv
import com.couchsubs.mobile.tv.TvDiscovery


/**
 * Profil (tuval Telefon-Profil, hesapsız): cihazlar (PS5, TV'ler), ikinci PSN hesabı, bildirimler, ortak havuz,
 * kullanım verisi ve gizlilik. Uygulamada hesap ya da giriş yoktur.
 */
@Composable
fun ProfileScreen(onBack: () -> Unit, onSettings: () -> Unit, onPairing: () -> Unit, onTarget: () -> Unit, onPsn: () -> Unit) {
    val context = LocalContext.current
    val settings = Services.settings
    var tvs by remember { mutableStateOf<List<FoundTv>?>(null) }
    var notifyDisconnect by remember { mutableStateOf(settings.notifyDisconnect) }
    var notifyPacks by remember { mutableStateOf(settings.notifyNewPacks) }
    var pool by remember { mutableStateOf(settings.sharePool) }
    var askGame by remember { mutableStateOf(settings.askGame) }
    var speakerNames by remember { mutableStateOf(settings.showSpeakerNames) }
    var voice by remember { mutableStateOf(settings.voiceSubtitles) }
    var voiceSpeed by remember { mutableStateOf(settings.voiceSpeed) }
    var voiceCharacters by remember { mutableStateOf(settings.voiceCharacters) }
    var voiceEngine by remember { mutableStateOf(settings.voiceEngine) }
    var geminiSaved by remember { mutableStateOf(!settings.apiKey(EngineChoice.GEMINI).isNullOrBlank()) }
    var keyDialog by remember { mutableStateOf(false) }
    var voiceTest by remember { mutableStateOf<String?>(null) }
    remember { com.couchsubs.mobile.service.voice.LocalTts.init(context); 0 }
    val localState by com.couchsubs.mobile.service.voice.LocalTts.state.collectAsState()
    val testScope = rememberCoroutineScope()
    var fastReading by remember { mutableStateOf(settings.fastSubtitleDetection) }
    var usage by remember { mutableStateOf(settings.usageData) }
    LaunchedEffect(Unit) { tvs = withContext(Dispatchers.IO) { runCatching { TvDiscovery.search() }.getOrDefault(emptyList()) } }
    ScreenScaffold(top = { TopBar(stringResource(R.string.profile), onBack = onBack) { SettingsAction(onSettings) } }) {
        TwoColumns(
            { StatusTile(CouchIcons.Gamepad, "PS5", settings.console?.name ?: stringResource(R.string.profile_not_paired), it,
                corner = { if (settings.console != null) StatusLabel(stringResource(R.string.main_paired)) }, chevron = true, onClick = onPairing) },
            { StatusTile(Icons.Filled.Person, stringResource(R.string.profile_psn),
                stringResource(if (settings.accountId != null) R.string.profile_psn_linked_short else R.string.profile_psn_missing), it,
                chevron = true, onClick = onPsn) },
        )
        Tile(Modifier.fillMaxWidth(), onClick = onTarget) {
            TileLabel(stringResource(R.string.profile_tvs))
            Text(if (settings.target == SubtitleTarget.TV) settings.tvName ?: stringResource(R.string.main_target_tv) else stringResource(R.string.main_target_phone),
                style = Tokens.Type.title, color = Tokens.Color.text)
            when (val found = tvs) {
                null -> Text(stringResource(R.string.target_searching), style = Tokens.Type.caption, color = Tokens.Color.secondary)
                else -> Text(if (found.isEmpty()) stringResource(R.string.target_tvs_none) else found.joinToString { "${it.name} (${brandName(it.brand)})" },
                    style = Tokens.Type.caption, color = Tokens.Color.secondary)
            }
        }
        RowList(listOf(
            { RowItem(stringResource(R.string.notify_disconnect)) { Switch(notifyDisconnect, { notifyDisconnect = it; settings.notifyDisconnect = it }) } },
            { RowItem(stringResource(R.string.notify_packs)) { Switch(notifyPacks, { notifyPacks = it; settings.notifyNewPacks = it }) } },
        ))
        RowList(listOf(
            { RowItem(stringResource(R.string.settings_pool), stringResource(R.string.settings_pool_short)) {
                Switch(pool, { pool = it; settings.sharePool = it })
            } },
            { RowItem(stringResource(R.string.settings_ask_game), stringResource(R.string.settings_ask_game_short)) {
                Switch(askGame, { askGame = it; settings.askGame = it })
            } },
            { RowItem(stringResource(R.string.settings_speaker_names), stringResource(R.string.settings_speaker_names_short)) {
                Switch(speakerNames, { speakerNames = it; settings.showSpeakerNames = it })
            } },
            { RowItem(stringResource(R.string.settings_voice), stringResource(R.string.settings_voice_short)) {
                Switch(voice, { voice = it; settings.voiceSubtitles = it })
            } },
            { RowItem(stringResource(R.string.settings_voice_engine), stringResource(when (voiceEngine) {
                    1 -> R.string.settings_voice_engine_ai; 2 -> R.string.settings_voice_engine_ai_fast
                    3 -> R.string.settings_voice_engine_local; else -> R.string.settings_voice_engine_phone
                }), onClick = {
                    // Sıra: telefon sesi → telefon içi yapay zekâ → Gemini hızlı → Gemini kaliteli.
                    voiceEngine = when (voiceEngine) { 0 -> 3; 3 -> 1; 1 -> 2; else -> 0 }
                    settings.voiceEngine = voiceEngine
                }) },
            { RowItem(stringResource(R.string.settings_voice_local_model), com.couchsubs.mobile.service.voice.LocalTts.describe(localState),
                onClick = {
                    val tts = com.couchsubs.mobile.service.voice.LocalTts
                    when {
                        localState is com.couchsubs.mobile.service.voice.LocalTts.State.Unsupported -> Unit
                        !tts.modelReady() -> { Thread({ tts.download() }, "model download").start()
                            if (voiceEngine != 3) { voiceEngine = 3; settings.voiceEngine = 3 } }
                        else -> {
                            voiceTest = "Telefon içi ses deneniyor… (ilk açılışta model belleğe yüklenir, biraz sürebilir)"
                            testScope.launch {
                                voiceTest = withContext(Dispatchers.IO) { localTest() }
                            }
                        }
                    }
                }) },
            { RowItem(stringResource(R.string.settings_voice_key),
                stringResource(if (geminiSaved) R.string.settings_voice_key_saved else R.string.settings_voice_key_missing),
                onClick = { keyDialog = true }) },
            { RowItem("Yapay zekâ sesini test et", "Bir cümle üretir, ne kadar sürdüğünü gösterir ve çalar",
                onClick = {
                    val key = settings.apiKey(EngineChoice.GEMINI)
                    if (key.isNullOrBlank()) { voiceTest = "Önce Gemini anahtarını gir."; return@RowItem }
                    voiceTest = "Test ediliyor… (iki model denenecek)"
                    testScope.launch {
                        val report = withContext(Dispatchers.IO) {
                            listOf(com.couchsubs.mobile.service.voice.GeminiSpeech.MODEL_FAST to "Hızlı model",
                                com.couchsubs.mobile.service.voice.GeminiSpeech.MODEL to "Kaliteli model").joinToString("\n\n") { (model, label) ->
                                val r = com.couchsubs.mobile.service.voice.GeminiSpeech.test(key, model)
                                if (r.ok) com.couchsubs.mobile.service.voice.PcmPlayer.play(r.pcm)
                                if (r.ok) "$label ✅ ilk ses ${r.firstMs} ms, toplam ${r.totalMs} ms. ${r.message}"
                                else "$label ❌ (${r.totalMs} ms): ${r.message}"
                            }
                        }
                        voiceTest = report
                    }
                }) },
            { RowItem(stringResource(R.string.settings_voice_speed), stringResource(when (voiceSpeed) {
                    1 -> R.string.settings_voice_speed_fast; 2 -> R.string.settings_voice_speed_faster; else -> R.string.settings_voice_speed_normal
                }), onClick = { voiceSpeed = (voiceSpeed + 1) % 3; settings.voiceSpeed = voiceSpeed }) },
            { RowItem(stringResource(R.string.settings_voice_characters), stringResource(R.string.settings_voice_characters_short)) {
                Switch(voiceCharacters, { voiceCharacters = it; settings.voiceCharacters = it })
            } },
            { RowItem(stringResource(R.string.settings_fast_reading), stringResource(R.string.settings_fast_reading_short)) {
                Switch(fastReading, { fastReading = it; settings.fastSubtitleDetection = it })
            } },
            { RowItem(stringResource(R.string.profile_usage), stringResource(R.string.profile_usage_note)) {
                Switch(usage, { usage = it; Services.setUsageData(it) })
            } },
            { RowItem(stringResource(R.string.profile_privacy), onClick = { open(context, context.getString(R.string.url_privacy)) }) },
            { RowItem(stringResource(R.string.profile_help), onClick = { open(context, context.getString(R.string.url_help)) }) },
        ))
        Text(stringResource(R.string.profile_no_account), style = Tokens.Type.caption, color = Tokens.Color.secondary)
        DeveloperSignature()
    }
    voiceTest?.let { text ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { voiceTest = null },
            title = { Text("Yapay zekâ sesi testi") },
            text = { Text(text) },
            confirmButton = { androidx.compose.material3.TextButton({ voiceTest = null }) { Text("Tamam") } },
        )
    }
    if (keyDialog) {
        var entered by remember { mutableStateOf("") }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { keyDialog = false },
            title = { Text(stringResource(R.string.settings_voice_key)) },
            text = {
                Column {
                    Text(stringResource(R.string.settings_voice_key_help), style = Tokens.Type.caption)
                    Spacer(Modifier.size(8.dp))
                    androidx.compose.material3.OutlinedTextField(entered, { entered = it.trim() }, singleLine = true,
                        placeholder = { Text("AIza...") })
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton({
                    if (entered.isNotBlank()) {
                        settings.setApiKey(EngineChoice.GEMINI, entered)
                        geminiSaved = true
                        if (voiceEngine == 0) { voiceEngine = 1; settings.voiceEngine = 1 }
                    }
                    keyDialog = false
                }) { Text(stringResource(R.string.settings_voice_key_save)) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton({ open(context, "https://aistudio.google.com/apikey") }) {
                    Text(stringResource(R.string.settings_voice_key_get))
                }
            },
        )
    }
}

/** Ayarlar (tuval Telefon-Ayarlar): motor ve anahtar, altyazı görünümü, paketler, hedef, PS5, hakkında. */
@Composable
fun SettingsScreen(onBack: () -> Unit, onPacks: () -> Unit, onTarget: () -> Unit, onPairing: () -> Unit, onTvInstall: () -> Unit = {},
                   onPlatform: () -> Unit = {}, onDemo: () -> Unit = {}) {
    val context = LocalContext.current
    val settings = Services.settings
    var engine by remember { mutableStateOf(settings.engine) }
    // Anahtar kaydedilmeden önce denenir (iPhone gibi): yalnız kabul edilen anahtar gizli depoya yazılır.
    var key by remember(engine) { mutableStateOf("") }
    var saved by remember(engine) { mutableStateOf(!settings.apiKey(engine).isNullOrBlank()) }
    var checking by remember { mutableStateOf(false) }
    var keyRejected by remember(engine) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var scale by remember { mutableStateOf(settings.subtitleScale.toFloat()) }
    var color by remember { mutableStateOf(settings.textColor) }
    var background by remember { mutableStateOf(settings.tvBackground.toFloat()) }
    var shape by remember { mutableStateOf(settings.tvBackgroundShape) }
    var screenOn by remember { mutableStateOf(settings.keepScreenOn) }
    var stackPrevious by remember { mutableStateOf(settings.stackPrevious) }
    var displayMode by remember { mutableStateOf(settings.tvDisplayMode) }
    var placement by remember { mutableStateOf(settings.tvPlacement) }
    var bottomPercent by remember { mutableStateOf(settings.tvBottomPercent.toFloat()) }
    var passcode by remember { mutableStateOf(settings.loginPasscode ?: "") }
    // Tuvaldeki dört seçenek: Ücretsiz, DeepL, Gemini, Diğer (OpenAI / Claude).
    val segment = when (engine) {
        EngineChoice.FREE -> 0
        EngineChoice.DEEPL -> 1
        EngineChoice.GEMINI -> 2
        else -> 3
    }
    fun choose(choice: EngineChoice) {
        engine = choice
        settings.engine = choice
    }
    ScreenScaffold(top = { TopBar(stringResource(R.string.settings), onBack = onBack) }) {
        Tile(Modifier.fillMaxWidth()) {
            TileLabel(stringResource(R.string.settings_engine))
            Segmented(listOf(stringResource(R.string.engine_free_short), "DeepL", "Gemini", stringResource(R.string.engine_other)), segment) { index ->
                choose(when (index) {
                    0 -> EngineChoice.FREE
                    1 -> EngineChoice.DEEPL
                    2 -> EngineChoice.GEMINI
                    else -> if (engine == EngineChoice.CLAUDE) EngineChoice.CLAUDE else EngineChoice.OPENAI
                })
            }
            if (segment == 3) Segmented(listOf("OpenAI", "Claude"), if (engine == EngineChoice.CLAUDE) 1 else 0) {
                choose(if (it == 1) EngineChoice.CLAUDE else EngineChoice.OPENAI)
            }
            if (engine == EngineChoice.GEMINI) GeminiModelChoice(settings)
            if (engine != EngineChoice.FREE) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TileLabel(stringResource(R.string.settings_key_for, engineName(context)))
                    Spacer(Modifier.weight(1f))
                    AnimatedVisibility(saved, enter = fadeIn(), exit = fadeOut()) { StatusLabel(stringResource(R.string.settings_key_saved)) }
                }
                if (saved) {
                    GhostButton(stringResource(R.string.settings_key_remove)) {
                        settings.setApiKey(engine, null)
                        saved = false
                    }
                } else {
                    Field(key, { key = it; keyRejected = false }, stringResource(R.string.settings_key), secret = true)
                    SecondaryButton(stringResource(if (checking) R.string.settings_key_checking else R.string.settings_key_save),
                        enabled = !checking && key.isNotBlank()) {
                        val choice = engine
                        val typed = key.trim()
                        checking = true
                        keyRejected = false
                        scope.launch {
                            val state = withContext(Dispatchers.IO) {
                                val provider = choice.aiProvider
                                if (provider == null) KeyCheck.deepL(typed)
                                else {
                                    val model = AiModel.selected(AiModel.load(context)[provider.id].orEmpty(), settings.aiModel(provider))
                                    if (model == null) OnlineKeyState.UNREACHABLE else KeyCheck.ai(provider, typed, model)
                                }
                            }
                            checking = false
                            if (KeyCheck.accepted(state)) {
                                settings.setApiKey(choice, typed)
                                if (engine == choice) {
                                    saved = true
                                    key = ""
                                }
                            } else if (engine == choice) keyRejected = true
                        }
                    }
                    AnimatedNote(if (keyRejected) stringResource(R.string.settings_key_rejected) else null)
                }
            }
            // Ek talimat (#306) yalnız yapay zekâ motorlarında; DeepL'de yoktur (iPhone ile aynı).
            if (engine.aiProvider != null) AiInstructionField(settings)
            Text(stringResource(R.string.settings_engine_note), style = Tokens.Type.caption, color = Tokens.Color.secondary)
        }
        // Altyazı görünümü üç karoya bölünür (yazı, arka plan, TV'de); her karonun headline başlığı var.
        Tile(Modifier.fillMaxWidth()) {
            SectionTitle(stringResource(R.string.settings_text_size))
            SliderHeader(stringResource(R.string.settings_size), "%${Math.round(scale)}")
            // %10 adımlarla %40–160 (iPhone ile aynı): 13 değer, aradaki 11 durak.
            Slider(scale, { scale = it; settings.subtitleScale = Math.round(it) },
                valueRange = AppSettings.MIN_SUBTITLE_SCALE.toFloat()..AppSettings.MAX_SUBTITLE_SCALE.toFloat(),
                steps = (AppSettings.MAX_SUBTITLE_SCALE - AppSettings.MIN_SUBTITLE_SCALE) / 10 - 1, colors = couchSliderColors())
            // Önizleme altyazı içeriğidir: boyu ölçeğe göre değişir.
            Text(stringResource(R.string.settings_preview), style = Tokens.Type.subtitleCurrent.copy(fontSize = Tokens.Type.subtitleCurrent.fontSize * (scale / 100f)),
                color = if (color == SubtitleTextColor.YELLOW) Tokens.Color.accentText else Tokens.Color.text)
            Row(verticalAlignment = Alignment.CenterVertically) {
                TileLabel(stringResource(R.string.settings_color))
                Spacer(Modifier.weight(1f))
                Row(Modifier.selectableGroup()) {
                    SubtitleTextColor.entries.forEach { option ->
                        ColorSwatch(option, selected = color == option) {
                            color = option
                            settings.textColor = option
                        }
                    }
                }
            }
            RowItem(stringResource(R.string.settings_stack_previous)) { Switch(stackPrevious, { stackPrevious = it; settings.stackPrevious = it }) }
            Text(stringResource(R.string.settings_stack_previous_note), style = Tokens.Type.caption, color = Tokens.Color.secondary)
        }
        // TV'deki kutu: %100 tamamen opak olur ve oyundaki İngilizce yazıyı kapatır; bant ekranın iki kenarına uzanır.
        Tile(Modifier.fillMaxWidth()) {
            SectionTitle(stringResource(R.string.settings_section_background))
            SliderHeader(stringResource(R.string.settings_background), "%${background.toInt()}")
            Slider(background, { background = it; settings.tvBackground = it.toInt() }, valueRange = 0f..100f, steps = 19,
                colors = couchSliderColors())
            TileLabel(stringResource(R.string.settings_background_area))
            Segmented(listOf(stringResource(R.string.settings_background_fit), stringResource(R.string.settings_background_band)),
                if (shape == SubtitleBackgroundShape.BAND) 1 else 0) {
                shape = if (it == 1) SubtitleBackgroundShape.BAND else SubtitleBackgroundShape.FIT
                settings.tvBackgroundShape = shape
            }
            Text(stringResource(R.string.settings_background_note), style = Tokens.Type.caption, color = Tokens.Color.secondary)
        }
        if (settings.target == SubtitleTarget.TV) Tile(Modifier.fillMaxWidth()) {
            SectionTitle(stringResource(R.string.settings_section_tv))
            // TV'de çevirinin yeri (Mac ve Windows'taki Yeri seçimi, iPhone ile aynı): üstünde/altında seçilince oyundaki
            // İngilizce altyazı da görünür; sabit yerde yükseklik kaydırıcıyla ayarlanır, bırakınca TV'de örnek altyazı çıkar.
            val placements = listOf(SubtitlePlacement.COVER, SubtitlePlacement.ABOVE, SubtitlePlacement.BELOW, SubtitlePlacement.FIXED)
            TileLabel(stringResource(R.string.settings_placement))
            Segmented(listOf(stringResource(R.string.settings_placement_cover), stringResource(R.string.settings_placement_above),
                stringResource(R.string.settings_placement_below), stringResource(R.string.settings_placement_fixed)),
                placements.indexOf(placement).coerceAtLeast(0)) {
                placement = placements[it]
                settings.tvPlacement = placement
            }
            if (placement == SubtitlePlacement.FIXED) {
                SliderHeader(stringResource(R.string.settings_position_height), "%${bottomPercent.toInt()}")
                Slider(bottomPercent, { bottomPercent = it; settings.tvBottomPercent = it.toInt() }, valueRange = 0f..85f,
                    onValueChangeFinished = { TranslationService.sendTestSubtitle(context.getString(R.string.test_subtitle_text)) },
                    colors = couchSliderColors())
                Text(stringResource(R.string.settings_placement_fixed_note), style = Tokens.Type.caption, color = Tokens.Color.secondary)
            } else {
                Text(stringResource(R.string.settings_placement_note), style = Tokens.Type.caption, color = Tokens.Color.secondary)
            }
            // Yalnız Android TV uygulaması kullanır: bazı TV'lerde (ör. TCL) uygulama içindeki PS5 görüntüsü siyah kalıyor.
            // Katman'da TV, PS5'i kendi HDMI girişinde gösterir (Mac ve Windows'taki Görüntü modu seçimi, iPhone ile aynı).
            TileLabel(stringResource(R.string.settings_display_mode))
            Segmented(listOf(stringResource(R.string.settings_display_mode_in_app), stringResource(R.string.settings_display_mode_overlay)),
                if (displayMode == TvDisplayMode.OVERLAY) 1 else 0) {
                displayMode = if (it == 1) TvDisplayMode.OVERLAY else TvDisplayMode.IN_APP
                settings.tvDisplayMode = displayMode
            }
            Text(stringResource(R.string.settings_display_mode_note), style = Tokens.Type.caption, color = Tokens.Color.secondary)
        }
        RowList(listOf({ RowItem(stringResource(R.string.settings_screen_on)) { Switch(screenOn, { screenOn = it; settings.keepScreenOn = it }) } }))
        // Altyazı alanı ve ek alanlar (iPhone ile aynı sırada: görünümden sonra).
        AreaProfileTile()
        AreaTile()
        ExtraAreasTile()
        // İkinci hesabın PS5 giriş şifresi: yalnız 4 hane gizli depoya (Keystore) yazılır; silinince kaldırılır.
        Tile(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TileLabel(stringResource(R.string.settings_passcode))
                Spacer(Modifier.weight(1f))
                if (LoginPasscode.normalized(passcode) != null) StatusLabel(stringResource(R.string.settings_key_saved))
            }
            Field(passcode, { value ->
                passcode = value.filter { it in '0'..'9' }.take(LoginPasscode.LENGTH)
                settings.loginPasscode = passcode
            }, stringResource(R.string.settings_passcode_field), secret = true,
                keyboard = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
            Text(stringResource(R.string.settings_passcode_note), style = Tokens.Type.caption, color = Tokens.Color.secondary)
        }
        var xboxAudio by remember { mutableStateOf(settings.xboxAudioOnDevice) }
        RowList(listOfNotNull<@Composable () -> Unit>(
            { RowItem(stringResource(R.string.settings_packs), onClick = onPacks) { Pill(stringResource(if (settings.packsEnabled) R.string.main_packs_on else R.string.main_packs_off)) } },
            { RowItem(stringResource(R.string.settings_target), if (settings.target == SubtitleTarget.TV) settings.tvName ?: stringResource(R.string.main_target_tv)
                else stringResource(R.string.main_target_phone), onClick = onTarget) },
            { RowItem(stringResource(R.string.platform_setting), stringResource(if (settings.xbox) R.string.platform_xbox else R.string.platform_ps), onClick = onPlatform) {
                if (settings.xbox) Pill(stringResource(R.string.beta)) } },
            if (settings.xbox) ({ RowItem("Xbox", settings.xboxConsole?.name ?: stringResource(R.string.profile_not_paired), onClick = onPairing) })
            else ({ RowItem(stringResource(R.string.settings_ps5), settings.console?.name ?: stringResource(R.string.profile_not_paired), onClick = onPairing) }),
            // Yalnız Xbox: uzaktan oynatmada Xbox sesi istemciye verip TV'yi susturabilir (PS5'te ses TV'de kalır).
            if (settings.xbox) ({ RowItem(stringResource(R.string.settings_xbox_audio), stringResource(R.string.settings_xbox_audio_short)) {
                Switch(xboxAudio, { xboxAudio = it; settings.xboxAudioOnDevice = it })
            } }) else null,
            { RowItem(stringResource(R.string.settings_about), BuildConfig.VERSION_NAME, onClick = { open(context, context.getString(R.string.url_source)) }) },
        ))
        XboxSignOutRow()
        // TV uygulamasını bu telefondan kurmak ve güncellemek (bilgisayardaki gibi; Samsung, LG, Android TV).
        Tile(Modifier.fillMaxWidth()) {
            TileLabel(stringResource(R.string.settings_tv_app))
            RowItem(stringResource(R.string.tvinstall_entry), tvAppSummary(), onClick = onTvInstall)
        }
        // Deneme modu: konsol olmadan örnek görüntülerle (iPhone ve Mac ile aynı; çeviri sürüyorsa önce durur).
        Tile(Modifier.fillMaxWidth()) {
            TileLabel(stringResource(R.string.demo_mode))
            Text(stringResource(R.string.demo_mode_body), style = Tokens.Type.caption, color = Tokens.Color.secondary)
            SecondaryButton(stringResource(R.string.demo_mode_try), onClick = onDemo)
        }
        // Deneme altyazısı: TV'de (ya da telefon ekranında) yazının nasıl göründüğü; iPhone ile aynı.
        var testNote by remember { mutableStateOf<String?>(null) }
        SecondaryButton(stringResource(R.string.settings_test_subtitle)) {
            testNote = if (TranslationService.sendTestSubtitle(context.getString(R.string.test_subtitle_text))) null
                else context.getString(R.string.settings_test_needs_start)
        }
        AnimatedNote(testNote, Tokens.Color.secondary)
        Text(stringResource(R.string.settings_copyright), style = Tokens.Type.caption, color = Tokens.Color.secondary,
            modifier = Modifier.padding(top = Tokens.Space.inline))
        DeveloperSignature()
    }
}

/**
 * Gemini modeli (Mac ve Windows'taki Model seçimi, iPhone ile aynı): seçenekler ortak ai-models.json'dan gelir, ilki
 * varsayılandır. Kayıt "geminiModel" adıyla tutulur; dakikalık istek hakkı seçilen modelin sınırıdır.
 */
@Composable
private fun GeminiModelChoice(settings: AppSettings) {
    val context = LocalContext.current
    val models = remember { AiModel.load(context)[AiProvider.GEMINI.id].orEmpty() }
    if (models.isEmpty()) return
    var selected by remember { mutableStateOf(AiModel.selected(models, settings.aiModel(AiProvider.GEMINI))!!.id) }
    TileLabel(stringResource(R.string.settings_model))
    Segmented(models.map { modelLabel(it) }, models.indexOfFirst { it.id == selected }.coerceAtLeast(0)) { index ->
        selected = models[index].id
        settings.setAiModel(AiProvider.GEMINI, selected)
    }
    Text(selected, style = Tokens.Type.caption, color = Tokens.Color.secondary)
}

/**
 * Ek talimat (#306; iPhone, Mac ve Windows ile aynı): yapay zekâ çevirmeninin talimatının sonuna eklenir, boşsa hiçbir
 * şey değişmez. Yazıldıkça kaydedilir; çeviri sürerken de sonraki istekten itibaren yeni talimat kullanılır.
 */
@Composable
private fun AiInstructionField(settings: AppSettings) {
    var text by remember { mutableStateOf(settings.aiInstruction) }
    Field(text, { value ->
        text = value.take(AiRequest.MAX_INSTRUCTION_LENGTH)
        settings.aiInstruction = text
    }, stringResource(R.string.settings_instruction), lines = 3, placeholder = stringResource(R.string.settings_instruction_example))
    Text(stringResource(R.string.settings_instruction_note), style = Tokens.Type.caption, color = Tokens.Color.secondary)
}

/** Modelin kısa açıklaması; listedeki açıklama (label) tanınmazsa olduğu gibi gösterilir. */
@Composable
private fun modelLabel(model: AiModel): String = when (model.label) {
    "Fast and cheap (recommended)" -> stringResource(R.string.settings_model_fast)
    "Better quality, a little slower" -> stringResource(R.string.settings_model_quality)
    else -> model.label
}


/** Xbox seçiliyken ve giriş yapılmışken Microsoft hesabından çıkış (jeton Keystore'dan silinir). */
@Composable
private fun XboxSignOutRow() {
    val settings = Services.settings
    var signedIn by remember { mutableStateOf(Services.xboxAuth.signedIn) }
    if (!settings.xbox || !signedIn) return
    Tile(Modifier.fillMaxWidth()) {
        RowItem(stringResource(R.string.xbox_sign_out), onClick = {
            Services.xboxAuth.signOut()
            signedIn = false
        })
    }
}

/**
 * Yazı rengi seçeneği: 32 dp renk dairesi, 48 dp dokunma alanı. Seçiliyken daireden 4 dp boşlukla ayrılan 2 dp sarı halka;
 * boşluk sayesinde sarı dairenin çevresinde de görünür. Ekran okuyucu rengin adını seçenek düğmesi olarak okur.
 */
@Composable
private fun ColorSwatch(option: SubtitleTextColor, selected: Boolean, onSelect: () -> Unit) {
    val yellow = option == SubtitleTextColor.YELLOW
    val name = stringResource(if (yellow) R.string.settings_color_yellow else R.string.settings_color_white)
    Box(
        Modifier.size(Tokens.Control.touch).clip(CircleShape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .semantics { contentDescription = name },
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Box(Modifier.size(44.dp).border(2.dp, Tokens.Color.accent, CircleShape))
        Box(Modifier.size(32.dp).background(if (yellow) Tokens.Color.accent else Tokens.Color.text, CircleShape)
            .border(1.dp, Tokens.Color.borderStrong, CircleShape))
    }
}


/** Telefon içi sesi dener: motoru açar, bir kızgın bir de sakin cümle okutur, süreleri yazar. */
private fun localTest(): String {
    val tts = com.couchsubs.mobile.service.voice.LocalTts
    val speech = com.couchsubs.mobile.service.voice.TurkishSpeech
    val opened = System.currentTimeMillis()
    if (!tts.start()) return "❌ " + tts.describe(tts.state.value)
    val openMs = System.currentTimeMillis() - opened
    val lines = listOf(
        Triple("male_2", "Get in the car, now! The cops are coming!", "Arabaya bin, hemen! Polisler geliyor!"),
        Triple("female_2", "It's okay, take a breath.", "Tamam, sakin ol. Bir nefes al."),
    )
    val report = StringBuilder("Motor açılışı: $openMs ms\n")
    for ((voice, english, turkish) in lines) {
        val out = java.io.ByteArrayOutputStream()
        val start = System.currentTimeMillis()
        var first = 0L
        try {
            tts.stream(speech.prepare(turkish, speech.guess(english)), voice, { false }) {
                if (first == 0L) first = System.currentTimeMillis() - start
                out.write(it)
            }
        } catch (error: Throwable) {
            report.append("❌ $voice: ${error.message}\n"); continue
        }
        val total = System.currentTimeMillis() - start
        val audioMs = out.size() / 2 * 1000L / com.couchsubs.mobile.service.voice.GeminiSpeech.SAMPLE_RATE
        com.couchsubs.mobile.service.voice.PcmPlayer.play(out.toByteArray())
        report.append("✅ $voice: ilk ses $first ms, ${audioMs} ms ses $total ms'de üretildi (hız ${"%.1f".format(audioMs.toDouble() / maxOf(1, total))}x)\n")
    }
    return report.toString()
}
